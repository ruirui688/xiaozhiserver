"""Request-local usage; absence of supplier usage is never represented as zero."""

import re
import uuid
import time
import asyncio
from decimal import Decimal, InvalidOperation

from .context import current_scope
from .outbox import utc_now
from . import health
from . import admission

_outbox = None
_NAME = re.compile(r"^[a-z][a-z0-9_]{0,63}$")


def configure(outbox):
    global _outbox
    _outbox = outbox


class UsageCall:
    def __init__(self, outbox, event):
        self.outbox, self.event = outbox, event
        self.finished = False
        self.status = "UNKNOWN"
        self.permit = None
        self._watchdog = None
        self._last_observed_checkpoint = 0
        self.outbox.save(self.event, active=True)

    def request_id(self, value):
        if value and not self.finished:
            # Only caller-supplied request identifiers, never arbitrary headers.
            value = str(value)[:255]
            if self.event.get("providerRequestId") == value:
                return
            self.event["providerRequestId"] = value
            self.outbox.save(self.event, active=True)

    def usage(self, metric, quantity, unit="token", source="PROVIDER", *, complete=False, add=False):
        if self.finished or not _NAME.fullmatch(metric) or not _NAME.fullmatch(unit) or len(unit) > 32:
            return
        try:
            value = Decimal(str(quantity))
        except (InvalidOperation, ValueError):
            return
        if not value.is_finite() or value < 0 or source not in {"PROVIDER", "OBSERVED"}:
            return
        if value >= Decimal("1e20") or value.as_tuple().exponent < -9:
            return
        values = self.event["usage"]
        item = next((v for v in values if (v["metric"], v["source"], v["unit"]) == (metric, source, unit)), None)
        if item:
            value = value + Decimal(item["quantity"]) if add else value
            item["quantity"] = str(value)
        else:
            values.append(dict(metric=metric, quantity=str(value), unit=unit, source=source))
        self.event["usageComplete"] |= complete
        # Audio arrives at frame cadence. Persist the initial observation and
        # periodic checkpoints; finish() always saves the latest accumulated value.
        now = time.time()
        if source == "OBSERVED" and item is not None and now - self._last_observed_checkpoint < 1:
            return
        if source == "OBSERVED":
            self._last_observed_checkpoint = now
        self.outbox.save(self.event, active=True)

    def finish(self, status=None):
        if self.finished:
            return
        self.event.update(status=status or self.status, endedAt=utc_now())
        self.outbox.save(self.event, active=False)
        self.finished = True
        if self.permit:
            self.permit.erase_secrets()
        if self._watchdog is not None and not self._watchdog.done():
            self._watchdog.get_loop().call_soon_threadsafe(self._watchdog.cancel)

    def start(self):
        if self.permit and not self.permit.started:
            # A crashed/ambiguous start must be held for reconciliation, never
            # mistaken for a reservation that certainly never reached execution.
            self.event["platformStarted"] = True
            self.outbox.save(self.event, active=True)
            try:
                self.permit.start()
            except admission.PlatformAdmissionError as exc:
                if exc.code == "PLATFORM_START_REJECTED":
                    self.event["platformStarted"] = False
                    self.outbox.save(self.event, active=True)
                raise

    def watch(self, transport):
        if self.permit:
            self._watchdog = asyncio.create_task(admission.watchdog(self, transport))


class _NoUsageCall:
    status = "UNKNOWN"
    permit = None
    def request_id(self, *args, **kwargs): pass
    def usage(self, *args, **kwargs): pass
    def finish(self, *args, **kwargs): pass
    def watch(self, *args, **kwargs): pass
    def start(self): pass


def begin_call(provider, service_type, *, scope=None, logical_call_id=None, request_id=None, max_usage=None):
    scope = scope or current_scope.get() or getattr(provider, "_billing_scope", None)
    health.call_started(scope)
    if scope is None:
        if admission.managed(provider):
            raise admission.PlatformAdmissionError("BILLING_CONTEXT_REQUIRED")
        return _NoUsageCall()
    model = scope.model(service_type, provider)
    if model is None:
        raise RuntimeError("Billing context does not authorize the selected model")
    if _outbox is None:
        raise RuntimeError("Billing outbox is not initialized")
    context = scope.context()
    event_id = str(uuid.uuid4())
    event = dict(
        eventId=event_id, contextId=context["context_id"], contextToken=context.get("context_token") or context.get("token"),
        modelId=model["model_id"], provider=model["provider"], serviceType=service_type,
        purpose=scope.purpose, sessionId=scope.session_id or None,
        turnId=scope.turn_id or None, sentenceId=scope.sentence_id or None,
        logicalCallId=logical_call_id or event_id, attemptId=event_id,
        startedAt=utc_now(), endedAt=None, status="UNKNOWN", usageComplete=False, usage=[],
    )
    call = UsageCall(_outbox, event)
    call.request_id(request_id)
    if admission.managed(provider, scope):
        try:
            call.permit = admission.reserve(event, max_usage)
            event["platformSettlement"] = call.permit.reference()
            event["platformStarted"] = False
            # Durable identity precedes start and every upstream transmission.
            _outbox.save(event, active=True)
        except BaseException:
            call.finish("UNKNOWN")
            raise
    return call


async def begin_call_async(provider, service_type, **kwargs):
    return await asyncio.to_thread(begin_call, provider, service_type, **kwargs)
