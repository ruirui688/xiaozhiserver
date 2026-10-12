"""Allowlisted collector observations, separate from billable usage and ownership.

Counters are process-local (startedAt defines their interval). A heartbeat proves
only that this worker can report status, never that a model call reached the ledger.
"""

import hashlib
import logging
import threading
from collections import deque
from datetime import datetime, timezone

logger = logging.getLogger(__name__)
COLLECTOR_VERSION = "2"
# Only adapters with actual request-boundary instrumentation belong here.
SUPPORTED_ADAPTERS = (
    "LLM/openai", "ASR/doubao_stream", "ASR/doubao_recording",
    "TTS/huoshan_double_stream", "REALTIME/doubao_duplex",
    "REALTIME/doubao-duplex", "REALTIME/doubao_realtime",
    "REALTIME/doubao-realtime", "REALTIME/qwen_omni_realtime",
    "REALTIME/qwen-omni-realtime",
)
_observer = None


def _now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def configure(observer):
    global _observer
    _observer = observer


def context_received(config):
    if _observer is not None:
        try:
            _observer.context_received(config)
        except Exception:
            logger.warning("Billing context health observation unavailable")


def call_started(scope):
    if _observer is not None:
        try:
            _observer.call_started(scope)
        except Exception:
            logger.warning("Billing call health observation unavailable")


class CollectorHealth:
    def __init__(self, managed_mode):
        self.managed_mode = bool(managed_mode)
        self.started_at = _now()
        self._lock = threading.RLock()
        self._context_hashes = deque(maxlen=1024)
        self._counts = dict(contextCount=0, missingContextCount=0,
                            missingContextCalls=0, persistenceFailureCount=0)
        self._times = dict(lastContextAt=None, lastCallStartedAt=None, lastDeliveryAt=None)
        self._delivery_error = None

    def context_received(self, config):
        context = config.get("billing_context") if isinstance(config, dict) else None
        valid = (isinstance(context, dict) and context.get("context_id")
                 and context.get("models") and (context.get("context_token") or context.get("token")))
        with self._lock:
            if not valid:
                self._counts["missingContextCount"] += 1
                if self._counts["missingContextCount"] == 1:
                    logger.warning("Billing context missing from managed configuration; collection is incomplete")
                return
            # Keep only bounded opaque digests, never a capability or private config.
            digest = hashlib.sha256(str(context["context_id"]).encode()).digest()
            if digest not in self._context_hashes:
                self._context_hashes.append(digest)
                self._counts["contextCount"] += 1
                self._times["lastContextAt"] = _now()

    def call_started(self, scope):
        with self._lock:
            self._times["lastCallStartedAt"] = _now()
            if scope is None:
                self._counts["missingContextCalls"] += 1
                if self._counts["missingContextCalls"] == 1:
                    logger.warning("Billing call has no ownership context; model execution continues without usage collection")
            else:
                self.context_received({"billing_context": scope.context()})

    def persistence_failed(self):
        with self._lock:
            self._counts["persistenceFailureCount"] += 1

    def delivery_failed(self, code="DELIVERY_FAILED"):
        with self._lock:
            self._delivery_error = code if code in {"DELIVERY_FAILED", "INVALID_ACK", "ACK_INCOMPLETE"} else "DELIVERY_FAILED"

    def delivery_result(self, accepted, incomplete):
        with self._lock:
            if accepted:
                self._times["lastDeliveryAt"] = _now()
            self._delivery_error = "ACK_INCOMPLETE" if incomplete else None

    def payload(self, instance_id, outbox):
        # Queue read failures are unknown, not evidence that the queue is empty.
        try:
            counts = outbox.counts()
            available = True
        except Exception:
            counts = dict(activeCount=0, pendingCount=0, rejectedCount=0)
            available = False
        with self._lock:
            return dict(instanceId=instance_id, collectorVersion=COLLECTOR_VERSION,
                        startedAt=self.started_at, managedMode=self.managed_mode,
                        supportedAdapters=list(SUPPORTED_ADAPTERS), queueAvailable=available,
                        lastDeliveryErrorCode=self._delivery_error,
                        **self._counts, **self._times, **counts)
