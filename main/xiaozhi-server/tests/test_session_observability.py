import json

from core.observability import SessionMetrics, SessionTelemetry


class _Clock:
    def __init__(self, value):
        self.value = value

    def __call__(self):
        return self.value


class _Logger:
    def __init__(self):
        self.messages = []
        self.bound = []

    def bind(self, **kwargs):
        self.bound.append(kwargs)
        return self

    def info(self, message):
        self.messages.append(message)


def test_session_metrics_counts_messages_audio_and_llm_timings():
    wall_clock = _Clock(100.5)
    monotonic_clock = _Clock(20.0)
    metrics = SessionMetrics(wall_clock, monotonic_clock)

    metrics.record_message("hello")
    metrics.record_message(b"1234")
    metrics.record_llm_started()
    metrics.record_llm_first_chunk(0.125)
    metrics.record_llm_complete(0.75)
    monotonic_clock.value = 21.25

    assert metrics.snapshot() == {
        "started_at_epoch_ms": 100500,
        "duration_ms": 1250,
        "received_message_count": 2,
        "received_audio_count": 1,
        "received_audio_bytes": 4,
        "llm_call_count": 1,
        "llm_first_chunk_count": 1,
        "llm_first_chunk_ms_avg": 125,
        "llm_first_chunk_ms_max": 125,
        "llm_total_ms": 750,
        "llm_total_ms_max": 750,
    }


def test_close_summary_is_structured_masked_and_emitted_once():
    logger = _Logger()
    metrics = SessionMetrics(_Clock(10.0), _Clock(5.0))
    telemetry = SessionTelemetry("session-123", logger, "connection", metrics)
    telemetry.set_device_id("secret-device-id")
    telemetry.set_close_reason("client_disconnect")

    assert telemetry.emit_close_summary() is True
    assert telemetry.emit_close_summary() is False

    assert len(logger.messages) == 1
    summary = json.loads(logger.messages[0])
    assert summary["event"] == "session_summary"
    assert summary["session_id"] == "session-123"
    assert summary["close_reason"] == "client_disconnect"
    assert summary["device_id"].startswith("sha256:")
    assert "secret-device-id" not in logger.messages[0]
    assert logger.bound == [{"tag": "connection"}]


def test_first_close_reason_wins_and_missing_device_is_safe():
    logger = _Logger()
    telemetry = SessionTelemetry(
        "session-456",
        logger,
        "connection",
        SessionMetrics(_Clock(1.0), _Clock(1.0)),
    )

    telemetry.set_close_reason("connection_error")
    telemetry.set_close_reason("server_close")
    telemetry.emit_close_summary()

    summary = json.loads(logger.messages[0])
    assert summary["close_reason"] == "connection_error"
    assert summary["device_id"] == "unknown"
