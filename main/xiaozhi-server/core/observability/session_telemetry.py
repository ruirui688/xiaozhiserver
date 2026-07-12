"""Thread-safe session metrics and a single structured close summary."""

import hashlib
import json
import threading
import time


def _masked_device_id(device_id):
    if not device_id:
        return "unknown"
    digest = hashlib.sha256(str(device_id).encode("utf-8")).hexdigest()
    return f"sha256:{digest[:12]}"


class SessionMetrics:
    """Accumulate low-cost session counters without emitting per-event logs."""

    def __init__(self, wall_clock=time.time, monotonic_clock=time.monotonic):
        self._wall_clock = wall_clock
        self._monotonic_clock = monotonic_clock
        self._lock = threading.Lock()
        self.started_at_epoch_ms = int(wall_clock() * 1000)
        self._started_at_monotonic = monotonic_clock()
        self.received_message_count = 0
        self.received_audio_count = 0
        self.received_audio_bytes = 0
        self.llm_call_count = 0
        self.llm_first_chunk_count = 0
        self.llm_first_chunk_ms_total = 0
        self.llm_first_chunk_ms_max = 0
        self.llm_total_ms = 0
        self.llm_total_ms_max = 0

    def record_message(self, message):
        with self._lock:
            self.received_message_count += 1
            if isinstance(message, bytes):
                self.received_audio_count += 1
                self.received_audio_bytes += len(message)

    def record_llm_started(self):
        with self._lock:
            self.llm_call_count += 1

    def record_llm_first_chunk(self, elapsed_seconds):
        elapsed_ms = max(0, round(elapsed_seconds * 1000))
        with self._lock:
            self.llm_first_chunk_count += 1
            self.llm_first_chunk_ms_total += elapsed_ms
            self.llm_first_chunk_ms_max = max(
                self.llm_first_chunk_ms_max, elapsed_ms
            )

    def record_llm_complete(self, elapsed_seconds):
        elapsed_ms = max(0, round(elapsed_seconds * 1000))
        with self._lock:
            self.llm_total_ms += elapsed_ms
            self.llm_total_ms_max = max(self.llm_total_ms_max, elapsed_ms)

    def snapshot(self):
        with self._lock:
            first_chunk_average = (
                round(self.llm_first_chunk_ms_total / self.llm_first_chunk_count)
                if self.llm_first_chunk_count
                else None
            )
            return {
                "started_at_epoch_ms": self.started_at_epoch_ms,
                "duration_ms": max(
                    0,
                    round(
                        (self._monotonic_clock() - self._started_at_monotonic)
                        * 1000
                    ),
                ),
                "received_message_count": self.received_message_count,
                "received_audio_count": self.received_audio_count,
                "received_audio_bytes": self.received_audio_bytes,
                "llm_call_count": self.llm_call_count,
                "llm_first_chunk_count": self.llm_first_chunk_count,
                "llm_first_chunk_ms_avg": first_chunk_average,
                "llm_first_chunk_ms_max": (
                    self.llm_first_chunk_ms_max
                    if self.llm_first_chunk_count
                    else None
                ),
                "llm_total_ms": self.llm_total_ms,
                "llm_total_ms_max": (
                    self.llm_total_ms_max if self.llm_call_count else None
                ),
            }


class SessionTelemetry:
    """Attach session identity and emit exactly one close summary."""

    def __init__(self, session_id, logger, log_tag, metrics=None):
        self.session_id = session_id
        self.logger = logger
        self.log_tag = log_tag
        self.metrics = metrics or SessionMetrics()
        self._lock = threading.Lock()
        self._device_id = None
        self._close_reason = None
        self._summary_emitted = False

    def set_device_id(self, device_id):
        with self._lock:
            self._device_id = device_id

    def set_close_reason(self, reason):
        if not reason:
            return
        with self._lock:
            if self._close_reason is None:
                self._close_reason = str(reason)

    def emit_close_summary(self, default_reason="server_close"):
        with self._lock:
            if self._summary_emitted:
                return False
            self._summary_emitted = True
            device_id = self._device_id
            close_reason = self._close_reason or default_reason

        summary = {
            "event": "session_summary",
            "session_id": self.session_id,
            "device_id": _masked_device_id(device_id),
            "close_reason": close_reason,
            **self.metrics.snapshot(),
        }
        self.logger.bind(tag=self.log_tag).info(
            json.dumps(summary, ensure_ascii=True, separators=(",", ":"))
        )
        return True
