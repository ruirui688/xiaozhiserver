"""Opt-in DeepSeek bridge for locally assembled, synthetic interaction journeys.

Run the local driver in the project Conda environment. Only this short remote
process reads deployment credentials; no source, configuration or results are
written to the deployment. Tool calls are returned as data and never executed.
The bridge never loads a production role prompt, conversation or memory into a
model request. Callers supply the complete synthetic ``messages`` and ``tools``.
"""

import json
import subprocess
import threading


MAX_CALLS = 20
MODEL_TIMEOUT_SECONDS = 45
MAX_INPUT_CHARS = 512_000


class RemoteDeepSeekError(RuntimeError):
    """A sanitized bridge failure; upstream exception bodies are never exposed."""


# Executed inside the already-deployed container, not the local Python runtime.
# REQUEST is serialized by the local bridge before this program is sent to stdin.
REMOTE_PROGRAM = r'''
import json
import signal
import time
from urllib.parse import urlparse

import requests
import yaml
from core.utils.model_config import apply_legacy_runtime_view

stage = "config"
http_status = None


def deadline(_signum, _frame):
    raise TimeoutError("remote stage deadline")


signal.signal(signal.SIGALRM, deadline)
signal.alarm(20)
try:
    with open("data/.config.yaml", encoding="utf-8") as source:
        manager = yaml.safe_load(source)["manager-api"]
    session = requests.Session()
    # requests' default adapter already has zero retries; state it explicitly.
    session.mount("https://", requests.adapters.HTTPAdapter(max_retries=0))
    session.mount("http://", requests.adapters.HTTPAdapter(max_retries=0))
    response = session.post(
        manager["url"].rstrip("/") + "/config/agent-models",
        headers={"Authorization": "Bearer " + manager["secret"]},
        json={"macAddress": REQUEST["device"],
              "clientId": "prompt-journey-readonly", "selectedModule": {}},
        timeout=(5, 15), allow_redirects=False,
    )
    http_status = response.status_code
    response.raise_for_status()
    body = response.json()
    if body.get("code") != 0:
        raise ValueError("model configuration unavailable")
    config = apply_legacy_runtime_view(body["data"])
    provider = config["LLM"][config["selected_module"]["LLM"]]
    base = str(provider.get("base_url") or provider.get("url") or "").rstrip("/")
    endpoint = urlparse(base)
    model = str(provider.get("model_name") or "")
    if (provider.get("type") != "openai" or endpoint.scheme != "https"
            or endpoint.hostname != "api.deepseek.com"
            or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment
            or not model.lower().startswith("deepseek") or not provider.get("api_key")):
        raise ValueError("expected configured official DeepSeek provider")
    metadata = {"model": model, "provider": "openai", "thinking": "disabled",
                "temperature": 0.3, "max_tokens": 650}
    signal.alarm(0)
    if REQUEST["operation"] == "probe":
        print(json.dumps({"metadata": metadata}), flush=True)
    else:
        stage = "model"
        http_status = None
        payload = REQUEST["payload"]
        params = {"model": model, "messages": payload["messages"],
                  "temperature": 0.3, "max_tokens": 650, "stream": False,
                  "thinking": {"type": "disabled"}}
        if payload.get("tools"):
            params["tools"] = payload["tools"]
            if "tool_choice" in payload:
                params["tool_choice"] = payload["tool_choice"]
            if "parallel_tool_calls" in payload:
                params["parallel_tool_calls"] = bool(payload["parallel_tool_calls"])
        started = time.monotonic()
        signal.alarm(45)
        response = session.post(
            base + "/chat/completions",
            headers={"Authorization": "Bearer " + provider["api_key"]},
            json=params, timeout=(5, 45), allow_redirects=False,
        )
        http_status = response.status_code
        response.raise_for_status()
        result = response.json()
        signal.alarm(0)
        # Return standard completion data, never headers, credentials or hidden reasoning.
        choices = []
        for choice in result["choices"]:
            message = choice["message"]
            clean_message = {"role": message.get("role", "assistant"),
                             "content": message.get("content")}
            if message.get("tool_calls"):
                clean_message["tool_calls"] = message["tool_calls"]
            choices.append({"index": choice.get("index", len(choices)),
                            "message": clean_message,
                            "finish_reason": choice.get("finish_reason")})
        print(json.dumps({"id": result.get("id"), "object": "chat.completion",
                          "created": result.get("created"), "model": model,
                          "choices": choices, "usage": result.get("usage", {}),
                          "bridge": {**metadata, "elapsed_ms": round(
                              (time.monotonic() - started) * 1000, 2)}},
                         ensure_ascii=False), flush=True)
except Exception as exc:
    signal.alarm(0)
    print(json.dumps({"error": {"stage": stage, "type": type(exc).__name__,
                                "http_status": http_status}}), flush=True)
    raise SystemExit(1)
'''


class RemoteDeepSeek:
    """One evaluation session, capped at 20 attempted model requests; no retries.

    ``complete`` accepts an OpenAI Chat Completions payload, including tool
    schemas and tool-choice options. Model, token limit, temperature and thinking
    are controlled remotely. ``probe`` performs only a configuration read.
    """

    def __init__(self, ssh="ubuntu@yh.wejoy.link", device=None, max_calls=20, *, live=False):
        if not isinstance(ssh, str) or not ssh or ssh.startswith("-") or any(c.isspace() for c in ssh):
            raise ValueError("ssh must be a single host or user@host")
        if not isinstance(device, str) or not device.strip():
            raise ValueError("an existing device identifier is required for model configuration")
        if type(max_calls) is not int or not 1 <= max_calls <= MAX_CALLS:
            raise ValueError("max_calls must be between 1 and 20")
        self.ssh = ssh
        self.device = device
        self.max_calls = max_calls
        self.live = live is True
        self.calls_used = 0
        self._closed = False
        self._lock = threading.Lock()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()

    def close(self):
        with self._lock:
            self._closed = True

    def probe(self):
        """Return safe metadata only, without requesting a model completion."""
        with self._lock:
            self._check_open()
            return self._run("probe", None)["metadata"]

    def complete(self, payload):
        """Send one complete request and return a standard nonstreaming response."""
        normalized = self._validate_payload(payload)
        with self._lock:
            self._check_open()
            if not self.live:
                raise RemoteDeepSeekError("live=True is required for model requests")
            if self.calls_used >= self.max_calls:
                raise RemoteDeepSeekError("model request limit reached; no request sent")
            # Failed/uncertain attempts consume the budget too; never retry here.
            self.calls_used += 1
            return self._run("complete", normalized)

    def _check_open(self):
        if self._closed:
            raise RemoteDeepSeekError("bridge is closed")

    @staticmethod
    def _validate_payload(payload):
        if not isinstance(payload, dict):
            raise ValueError("completion payload must be a mapping")
        messages = payload.get("messages")
        if not isinstance(messages, list) or not messages:
            raise ValueError("messages must be a nonempty list")
        if any(not isinstance(message, dict) or message.get("role") not in
               {"system", "developer", "user", "assistant", "tool"}
               for message in messages):
            raise ValueError("invalid message roles")
        normalized = {"messages": messages}
        tools = payload.get("tools")
        if tools is not None and not isinstance(tools, list):
            raise ValueError("tools must be a list")
        if tools:
            normalized["tools"] = tools
            for key in ("tool_choice", "parallel_tool_calls"):
                if key in payload:
                    normalized[key] = payload[key]
        if len(json.dumps(normalized, ensure_ascii=False)) > MAX_INPUT_CHARS:
            raise ValueError("synthetic request exceeds bridge size limit")
        return normalized

    def _run(self, operation, payload):
        request = {"operation": operation, "device": self.device, "payload": payload}
        source = "REQUEST = json.loads(" + repr(json.dumps(request, ensure_ascii=False)) + ")\n"
        # json is needed before the serialized request declaration.
        source = "import json\n" + source + REMOTE_PROGRAM
        try:
            process = subprocess.run(
                ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", self.ssh,
                 "sudo -n docker exec -i xiaozhi-esp32-server python -"],
                input=source, text=True, stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, timeout=80, check=False,
            )
        except subprocess.TimeoutExpired:
            raise RemoteDeepSeekError("bridge timeout; no retry") from None
        except OSError:
            raise RemoteDeepSeekError("could not start SSH bridge") from None
        try:
            result = json.loads(process.stdout)
        except (TypeError, ValueError):
            raise RemoteDeepSeekError("remote bridge returned invalid JSON") from None
        if not isinstance(result, dict):
            raise RemoteDeepSeekError("remote bridge returned invalid response")
        if process.returncode or "error" in result:
            error = result.get("error") or {}
            # These fields are created by REMOTE_PROGRAM, not upstream response bodies.
            raise RemoteDeepSeekError(
                "remote request failed: stage=%s type=%s status=%s; no retry"
                % (error.get("stage", "ssh"), error.get("type", "unknown"),
                   error.get("http_status"))
            )
        return result
