"""Process-local announcement budgets; durable scheduling belongs to Java.

There is no background worker or deferred queue here. Rejected work stays with
the durable scheduler. Receipts are never evicted while valid: a full receipt
cache rejects new work instead of weakening duplicate protection.
"""

from collections import OrderedDict
from dataclasses import dataclass, fields
import math
import threading
import time
import weakref


class NotificationBusy(RuntimeError):
    """A competing reply won before the notification changed any queue/state."""


@dataclass(frozen=True)
class NotificationLimits:
    rate_per_second: float = 4.0
    burst: int = 8
    max_pending: int = 32
    device_cooldown_seconds: float = 2.0
    receipt_ttl_seconds: float = 3600.0
    max_receipts: int = 32768

    @classmethod
    def from_config(cls, config):
        if not isinstance(config, dict):
            raise ValueError("server_notification_limits must be an object")
        bounds = {
            "rate_per_second": (0.1, 100),
            "burst": (1, 100),
            "max_pending": (1, 1024),
            "device_cooldown_seconds": (0.1, 300),
            "receipt_ttl_seconds": (3600, 21600),
            "max_receipts": (1, 100000),
        }
        defaults = cls()
        values = {}
        for field in fields(cls):
            value = config.get(field.name, getattr(defaults, field.name))
            low, high = bounds[field.name]
            if (
                isinstance(value, bool)
                or not isinstance(value, (int, float))
                or not math.isfinite(value)
                or not low <= value <= high
                or (field.type is int and not float(value).is_integer())
            ):
                raise ValueError(f"invalid server_notification_limits.{field.name}")
            values[field.name] = int(value) if field.type is int else float(value)
        return cls(**values)


@dataclass
class _Receipt:
    expires_at: float
    fingerprint: str
    status: str = "delivery_uncertain"


class NotificationAdmission:
    """Serialize only short in-memory checks and nonblocking queue writes.

    Pending connections count until their TTS pipeline is idle. Connection
    references are weak where supported; all registries have a strict bound.
    This limits announcements only, not all interactive model traffic.
    """

    def __init__(self, limits=None, *, clock=time.monotonic):
        self.limits = limits or NotificationLimits()
        self._clock = clock
        self._lock = threading.RLock()
        self._tokens = float(self.limits.burst)
        self._updated_at = clock()
        self._receipts = OrderedDict()
        self._pending = {}
        self._counts = {}

    def configure(self, config):
        limits = NotificationLimits.from_config(config)
        with self._lock:
            self._tokens = min(self._tokens, limits.burst)
            self.limits = limits

    def execute(self, conn, request_key, fingerprint, check, submit, is_busy):
        with self._lock:
            now = self._clock()
            self._expire(now, is_busy)
            receipt = self._receipts.get(request_key) if request_key else None
            if receipt is not None:
                if receipt.fingerprint != fingerprint:
                    return self._result(False, "request_conflict")
                if receipt.status == "queued":
                    return self._result(True, "duplicate")
                return self._result(False, "delivery_uncertain")

            reason = check()
            if reason:
                return self._result(False, reason)
            device_key = self.device_key(conn)
            if device_key in self._pending:
                return self._result(False, "device_busy")
            self._tokens = min(
                self.limits.burst,
                self._tokens + max(0, now - self._updated_at) * self.limits.rate_per_second,
            )
            self._updated_at = now
            if (
                self._tokens < 1
                or len(self._pending) >= self.limits.max_pending
                or (request_key and len(self._receipts) >= self.limits.max_receipts)
            ):
                return self._result(False, "overloaded")

            self._tokens -= 1
            try:
                reference = weakref.ref(conn)
            except TypeError:
                reference = lambda: conn
            self._pending[device_key] = (
                reference, now + self.limits.device_cooldown_seconds
            )
            if request_key:
                receipt = _Receipt(now + self.limits.receipt_ttl_seconds, fingerprint)
                # Reserve before any queue mutation. An ambiguous partial write
                # must not cause another attempt to play the same announcement.
                self._receipts[request_key] = receipt
            try:
                submit()
            except NotificationBusy:
                # No enqueue side effect occurred. Release the reservation so
                # the durable scheduler can retry after the competing reply.
                if request_key:
                    self._receipts.pop(request_key, None)
                self._pending.pop(device_key, None)
                self._tokens = min(self.limits.burst, self._tokens + 1)
                return self._result(False, "device_busy")
            except Exception:
                return self._result(False, "delivery_uncertain")
            if receipt is not None:
                receipt.status = "queued"
            return self._result(True, "queued")

    def _expire(self, now, is_busy):
        # Insertion order is expiry order while config is unchanged. A config
        # change never expires an existing receipt early; at worst it retains
        # a later, shorter-lived receipt until the bounded head expires.
        while self._receipts:
            key, receipt = next(iter(self._receipts.items()))
            if receipt.expires_at > now:
                break
            self._receipts.pop(key)
        for key, (reference, cooldown_until) in list(self._pending.items()):
            conn = reference()
            if (
                conn is None
                or getattr(conn, "closed", False)
                or getattr(conn, "closing", False)
                or (now >= cooldown_until and not is_busy(conn))
            ):
                self._pending.pop(key, None)

    def _result(self, accepted, status):
        self._counts[status] = self._counts.get(status, 0) + 1
        return accepted, status

    def snapshot(self):
        """Bounded aggregate diagnostics: no device IDs or reminder content."""
        with self._lock:
            return {
                "receipts": len(self._receipts),
                "pending": len(self._pending),
                "outcomes": dict(self._counts),
            }

    @staticmethod
    def device_key(conn):
        value = str(getattr(conn, "device_id", "") or "").strip().lower()
        return value.replace("_", ":") or f"connection:{id(conn)}"
