"""Offline observation contracts: fake clocks/transports only, no model calls."""

import asyncio
import json
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from core.observability.session_telemetry import (
    SessionMetrics, SessionTelemetry, latency_span, observe_incoming_detect,
)
from core.utils.turn_metrics import TurnMetrics
from test_session_observability import _Clock, _Logger


def observer(session_id="session-a"):
    clock = _Clock(100.0)
    logger = _Logger()
    telemetry = SessionTelemetry(session_id, logger, "test", SessionMetrics(_Clock(0), clock))
    return SimpleNamespace(session_telemetry=telemetry), clock, logger


def events(logger):
    return [json.loads(message) for message in logger.messages]


def test_nested_spans_use_monotonic_offsets_not_additive_totals():
    conn, clock, logger = observer()
    with latency_span(conn, "outer"):
        clock.value += 0.2
        with latency_span(conn, "inner"):
            clock.value += 0.3
        clock.value += 0.1
    result = events(logger)
    assert result[-1]["elapsed_ms"] == 600
    assert result[-2]["elapsed_ms"] == 300
    assert result[-1]["offset_ms"] == 600
    assert all(item["session_id"] == "session-a" for item in result)


@pytest.mark.parametrize("error", [RuntimeError("secret"), TimeoutError("secret"), asyncio.CancelledError("secret")])
def test_span_preserves_failure_timeout_and_cancellation_without_payload(error):
    conn, clock, logger = observer()
    with pytest.raises(type(error)):
        with latency_span(conn, "init.test"):
            clock.value += 0.25
            raise error
    assert events(logger)[-1]["status"] == "interrupted"
    assert events(logger)[-1]["elapsed_ms"] == 250
    assert "secret" not in "".join(logger.messages)


def test_logging_failure_does_not_fail_work():
    conn, _, logger = observer()
    def fail(_message):
        raise RuntimeError("broken sink")
    logger.info = fail
    with latency_span(conn, "init.test"):
        pass


def test_detect_ingress_logs_no_text_headers_or_client_identifiers():
    conn, _, logger = observer()
    observe_incoming_detect(conn, json.dumps({
        "type": "listen", "state": "detect", "text": "private utterance",
        "turn_id": "untrusted-id", "Authorization": "secret",
    }))
    for other in (b"audio", "invalid", "[]", '{"type":"hello"}'):
        observe_incoming_detect(conn, other)
    assert len(events(logger)) == 1
    assert events(logger)[0]["stage"] == "detect.received"
    assert not any(value in logger.messages[0] for value in ("private", "secret", "untrusted"))


def test_parallel_config_requests_are_independently_measured(monkeypatch):
    from config import config_loader
    conn, clock, logger = observer()
    started = set()

    async def request(name, result):
        started.add(name)
        await asyncio.sleep(0)
        assert started == {"models", "words"}
        return result

    monkeypatch.setattr(config_loader, "get_agent_models", lambda *args: request("models", {}))
    monkeypatch.setattr(config_loader, "get_correct_words", lambda *args: request("words", ["private"]))
    result = asyncio.run(config_loader.get_private_config_from_api(
        {"selected_module": {}}, "private-device", "private-client", observer=conn,
    ))
    assert result["correct_words"] == ["private"]
    assert {item["stage"] for item in events(logger)} == {
        "config.models.start", "config.models.end",
        "config.correct_words.start", "config.correct_words.end",
    }
    assert "private" not in "".join(logger.messages)


def test_concurrent_sessions_and_old_sentences_do_not_mix():
    def run(session):
        conn, clock, logger = observer(session)
        metrics = TurnMetrics(conn.session_telemetry.mark_latency)
        metrics.ensure_text_turn("old")
        metrics.bind_sentence("old-sentence", "old")
        metrics.ensure_text_turn("new")
        metrics.bind_sentence("new-sentence", "new")
        for _ in range(10):
            metrics.record_latency_milestone("tts.first_upstream_audio", "old-sentence")
            metrics.record_latency_milestone("tts.first_upstream_audio", "new-sentence")
        metrics.record_latency_milestone("tts.first_upstream_audio", "unknown")
        metrics.discard_sentence("old-sentence")
        metrics.record_latency_milestone("tts.first_upstream_audio", "old-sentence")
        return events(logger)

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(run, ["session-a", "session-b"]))
    for session, result in zip(["session-a", "session-b"], results):
        assert len(result) == 2
        assert all(item["session_id"] == session for item in result)
        assert [(item["turn_id"], item["sentence_id"]) for item in result] == [
            ("old", "old-sentence"), ("new", "new-sentence"),
        ]


@pytest.mark.parametrize("fails", [False, True])
def test_first_send_is_recorded_only_after_successful_websocket_send(fails):
    from core.handle.sendAudioHandle import _do_send_audio
    conn, _, logger = observer()
    conn.turn_metrics = TurnMetrics(conn.session_telemetry.mark_latency)
    conn.turn_metrics.ensure_text_turn("turn")
    conn.turn_metrics.bind_sentence("sentence", "turn")
    conn.conn_from_mqtt_gateway = False

    async def send(packet):
        assert events(logger) == []
        if fails:
            raise TimeoutError("private transport error")

    conn.websocket = SimpleNamespace(send=send)
    flow = {"sentence_id": "sentence"}
    if fails:
        with pytest.raises(TimeoutError):
            asyncio.run(_do_send_audio(conn, b"opus", flow))
        assert events(logger) == []
    else:
        asyncio.run(_do_send_audio(conn, b"opus", flow))
        assert events(logger)[0]["stage"] == "audio.first_send_completed"
        assert events(logger)[0]["turn_id"] == "turn"


def test_deferred_expiry_failure_and_close_keep_observation_isolated(monkeypatch):
    from test_reply_lifecycle_readiness import _deferred_conn
    from core.session import runtime_readiness
    conn = _deferred_conn()
    observed, clock, logger = observer()
    conn.session_telemetry = observed.session_telemetry
    monkeypatch.setattr(runtime_readiness.time, "monotonic", clock)
    handler = AsyncMock()
    monkeypatch.setattr("core.handle.textHandle.handleTextMessage", handler)
    message = {"type": "listen", "state": "detect", "text": "private"}
    conn.defer_listen_detect(message, ttl_seconds=1)
    clock.value += 2
    conn.conversation_ready.set()
    assert asyncio.run(conn.flush_deferred_listen_detects()) == 0
    assert events(logger)[-1]["stage"] == "detect.expired"
    assert events(logger)[-1]["elapsed_ms"] == 2000
    conn.defer_listen_detect(message)
    conn.runtime_readiness.mark_initialization_failed(RuntimeError("private"))
    assert not conn.conversation_ready.is_set()
    conn.closed = True
    assert not conn.defer_listen_detect(message)
    assert asyncio.run(conn.flush_deferred_listen_detects()) == 0
    handler.assert_not_awaited()
    assert "private" not in "".join(logger.messages)


def test_tts_text_submit_does_not_claim_audio_arrival():
    from core.providers.tts.huoshan_double_stream import TTSProvider
    conn, _, logger = observer()
    conn.turn_metrics = TurnMetrics(conn.session_telemetry.mark_latency)
    conn.turn_metrics.ensure_text_turn("turn")
    conn.turn_metrics.bind_sentence("sentence", "turn")
    provider = object.__new__(TTSProvider)
    provider.conn = conn
    provider.ws = object()
    provider.get_payload_bytes = lambda **kwargs: b"private-text"
    provider.send_event = AsyncMock()

    async def run():
        await provider.send_text("private-speaker", "private-text", "sentence")
        await provider.send_text("private-speaker", "more-text", "sentence")

    asyncio.run(run())
    assert provider.send_event.await_count == 2
    assert [item["stage"] for item in events(logger)] == ["tts.first_text_submit"]
    assert "private" not in "".join(logger.messages)
