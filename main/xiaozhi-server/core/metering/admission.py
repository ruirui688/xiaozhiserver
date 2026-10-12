"""Bounded platform execution permits; upstream secrets stay in call-local memory."""

import asyncio
import json
import math
import time
from datetime import datetime, timezone
from decimal import Decimal
from urllib.parse import urlsplit

import httpx


class PlatformAdmissionError(RuntimeError):
    def __init__(self, code="PLATFORM_SERVICE_UNAVAILABLE"):
        self.code = code
        super().__init__(code)


# Business refusals the Java admission service returns; kept verbatim so callers can
# explain the real reason instead of a single generic failure. This adds no retry,
# no fallback credential and no money movement.
DENIAL_CODES = frozenset({
    "ALLOWANCE_EXHAUSTED", "WORKSPACE_ALLOWANCE_LIMIT", "TEAM_ALLOWANCE_LIMIT", "PAYER_FROZEN", "WORKSPACE_DISABLED",
    "PENDING_CREDENTIAL", "CREDENTIAL_SERVICE_NOT_READY", "CREDENTIAL_CHANGED",
    "MODEL_NOT_ADMITTED", "PLATFORM_SERVICE_BUDGET_REQUIRED", "PLATFORM_CALL_DENIED",
    "BOUNDED_USAGE_REQUIRED",
})


SUPPORTED = {
    "LLM": {"openai"},
    "ASR": {"doubao_stream", "doubao_recording"},
    "TTS": {"huoshan_double_stream"},
}
LOCAL_ASR = {"fun_local", "vosk", "sherpa_onnx_local"}


def managed(provider=None, scope=None):
    if getattr(provider, "_platform_managed", False):
        return True
    context = scope.context() if scope else {}
    return context.get("_platform_managed") is True or context.get("platform_managed") is True


def validate_adapter(service, adapter, config):
    # These implementations infer on local models and never send audio to a
    # supplier. Remote FunASR and cloud adapters are intentionally excluded.
    if service == "ASR" and adapter in LOCAL_ASR:
        return
    if config.get("_platform_managed") and adapter not in SUPPORTED.get(service, set()):
        raise PlatformAdmissionError("MODEL_NOT_ADMITTED")


def configure_provider(provider, config):
    provider._platform_managed = config.get("_platform_managed") is True
    provider._billing_model_id = config.get("_platform_model_id") or config.get("model_config_id")


def _request(action, payload):
    from config.manage_api_client import ManageApiClient
    manager = ManageApiClient._instance
    config = getattr(manager, "config", None)
    secret = getattr(manager, "_secret", None)
    if not config or not secret:
        raise PlatformAdmissionError()
    url = config["url"].rstrip("/") + "/config/platform/" + action
    try:
        # Do not retry a start or follow a redirect carrying the service identity.
        with httpx.Client(timeout=8, follow_redirects=False) as client:
            response = client.post(url, json=payload, headers={"Authorization": "Bearer " + secret})
            response.raise_for_status()
            result = response.json()
        if not isinstance(result, dict) or result.get("code") != 0 or not isinstance(result.get("data"), dict):
            raise PlatformAdmissionError(_denial_code(result))
        return result["data"]
    except PlatformAdmissionError:
        raise
    except Exception:
        # Never include URLs, headers or server error payloads in exceptions.
        raise PlatformAdmissionError() from None


def _denial_code(result):
    """Extract a known business refusal code; anything else stays a generic failure."""
    if not isinstance(result, dict):
        return "PLATFORM_SERVICE_UNAVAILABLE"
    message = result.get("msg")
    if not isinstance(message, str):
        return "PLATFORM_CALL_DENIED"
    for code in DENIAL_CODES:
        if message.startswith(code):
            return code
    return "PLATFORM_CALL_DENIED"


def _deadline(value):
    try:
        expiry = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        remaining = (expiry - datetime.now(timezone.utc)).total_seconds()
        if not math.isfinite(remaining) or remaining <= 0:
            raise ValueError()
        return time.monotonic() + min(remaining, 300)
    except (ValueError, TypeError):
        raise PlatformAdmissionError("INVALID_PLATFORM_PERMIT") from None


def _amounts(values):
    result = {}
    if not isinstance(values, dict) or not values:
        raise PlatformAdmissionError("BOUNDED_USAGE_REQUIRED")
    for metric, quantity in values.items():
        value = Decimal(str(quantity))
        if not value.is_finite() or value < 0 or value > Decimal("1000000000"):
            raise PlatformAdmissionError("INVALID_PLATFORM_USAGE")
        result[metric] = str(value)
    return result


class ExecutionPermit:
    def __init__(self, grant):
        self.reservation_id = grant.get("reservationId")
        self.token = grant.get("permitToken")
        if not self.reservation_id or not self.token:
            raise PlatformAdmissionError("INVALID_PLATFORM_PERMIT")
        self.deadline = _deadline(grant.get("hardExpiresAt") or grant.get("expiresAt"))
        self.limits = _amounts(grant.get("limits"))
        self.credentials = grant.get("credentials") or {}
        self.runtime = grant.get("runtimeConfig") or {}
        self.started = False
        self.used = {}

    def reference(self):
        return {"reservationId": self.reservation_id, "permitToken": self.token}

    def start(self):
        self.check()
        result = _request("start", self.reference())
        if result.get("started") is False:
            raise PlatformAdmissionError("PLATFORM_START_REJECTED")
        if result.get("started") is not True:
            raise PlatformAdmissionError("PLATFORM_START_DENIED")
        self.started = True

    def check(self):
        if time.monotonic() >= self.deadline:
            raise PlatformAdmissionError("PLATFORM_PERMIT_EXPIRED")

    def secret(self, name="api_key"):
        value = self.credentials.get(name)
        if not isinstance(value, str) or not value.strip():
            raise PlatformAdmissionError("CREDENTIAL_NOT_READY")
        return value

    def endpoint(self, *names):
        value = next((self.runtime.get(name) for name in names if self.runtime.get(name)), None)
        parsed = urlsplit(str(value or ""))
        host = (parsed.hostname or "").lower()
        allowed = host in {"api.deepseek.com", "openspeech.bytedance.com", "ark.cn-beijing.volces.com", "ark.cn-shanghai.volces.com"}
        if not allowed or parsed.scheme not in {"https", "wss"} or parsed.port not in {None, 443} or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise PlatformAdmissionError("UNAPPROVED_MODEL_ENDPOINT")
        return value

    def consume(self, metric, quantity):
        self.check()
        next_value = self.used.get(metric, Decimal(0)) + Decimal(str(quantity))
        if not next_value.is_finite() or next_value < 0:
            raise PlatformAdmissionError("INVALID_PLATFORM_USAGE")
        limit = Decimal(self.limits.get(metric, "0"))
        if next_value > limit:
            # Reserve the next bounded segment before transmitting paid data.
            requested = dict(self.limits)
            segment = Decimal("5000") if metric == "audio_duration" else Decimal("256")
            requested[metric] = str(max(next_value, limit + segment))
            grant = _request("extend", {**self.reference(), "maxUsage": requested})
            self.limits = _amounts(grant.get("limits"))
            if next_value > Decimal(self.limits.get(metric, "0")):
                raise PlatformAdmissionError("PLATFORM_LIMIT_EXCEEDED")
        self.used[metric] = next_value

    def erase_secrets(self):
        self.credentials.clear()


def reserve(event, maximum):
    grant = _request("admit", {
        key: event[key] for key in ("contextId", "contextToken", "modelId", "serviceType", "purpose", "logicalCallId", "attemptId")
    } | {"maxUsage": _amounts(maximum)})
    return ExecutionPermit(grant)


def llm_bounds(params):
    # SDK extra_body merges after named parameters; do not let it replace the
    # reserved model/input/token bound or transport controls.
    if set(params.get("extra_body") or {}) - {"thinking", "reasoning_effort", "enable_thinking"}:
        raise PlatformAdmissionError("UNBOUNDED_MODEL_OPTIONS")
    if params.get("n", 1) != 1:
        raise PlatformAdmissionError("UNBOUNDED_MODEL_OPTIONS")
    # UTF-8 bytes plus generous chat/tool framing bound tokenizer input without
    # persisting prompts or pretending this reservation is supplier actual usage.
    payload = {key: params[key] for key in ("messages", "tools", "functions", "response_format") if key in params}
    if any(part in json.dumps(payload, ensure_ascii=False) for part in ('"image_url"', '"input_audio"')):
        raise PlatformAdmissionError("MODEL_NOT_ADMITTED")
    maximum = params.get("max_completion_tokens") or params.get("max_tokens") or 1024
    if isinstance(maximum, bool) or not isinstance(maximum, int) or not 1 <= maximum <= 32768:
        raise PlatformAdmissionError("BOUNDED_USAGE_REQUIRED")
    input_bound = len(json.dumps(payload, ensure_ascii=False).encode("utf-8")) + 4096
    return {"input_tokens": str(input_bound), "output_tokens": str(maximum)}


def settlement(event):
    reference = event.get("platformSettlement")
    if not reference:
        return None
    quantities = {entry["metric"]: entry["quantity"] for entry in event.get("usage", []) if entry.get("source") == "PROVIDER"}
    return {**reference, "status": event["status"], "usageComplete": event["usageComplete"],
            "usage": quantities, "providerRequestId": event.get("providerRequestId")}


async def watchdog(call, transport):
    permit = getattr(call, "permit", None)
    if permit is None:
        return
    try:
        await asyncio.sleep(max(0, permit.deadline - time.monotonic()))
        await transport.close()
    finally:
        if not call.finished:
            call.finish("UNKNOWN")
