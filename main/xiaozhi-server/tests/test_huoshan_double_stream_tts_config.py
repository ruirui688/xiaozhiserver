import asyncio
import concurrent.futures
import sys
import threading
import time
from types import SimpleNamespace
from pathlib import Path

import pytest


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings

settings.config_file_valid = True

from core.providers.tts import huoshan_double_stream
from core.providers.tts.huoshan_double_stream import TTSProvider
from core.providers.tts.dto.dto import (
    ContentType,
    SentenceType,
    TTSMessageDTO,
)


def _base_config(**overrides):
    config = {
        "type": "huoshan_double_stream",
        "api_key": "test-api-key",
        "speaker": "zh_female_sophie_uranus_bigtts",
        "output_dir": "tmp/",
    }
    config.update(overrides)
    return config


def test_doubao_tts_uses_seed_tts_2_default_protocol_defaults():
    provider = TTSProvider(_base_config(ws_url="wss://example.invalid"), True)

    assert provider.resource_id == "seed-tts-2.0"
    assert provider.ws_url == "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
    assert provider.audio_format == "pcm"
    assert provider.enable_ws_reuse is True
    assert provider.stream_prebuffer_ms == 0
    assert provider.stream_prebuffer_frames == 0


def test_doubao_tts_accepts_biggtts_resource_id():
    provider = TTSProvider(_base_config(resource_id="volc.service_type.10029", speaker=""), True)

    assert provider.resource_id == "volc.service_type.10029"
    assert provider.voice == "zh_female_wanwanxiaohe_moon_bigtts"


def test_doubao_tts_rejects_unknown_resource_id():
    with pytest.raises(ValueError, match="不支持的豆包 TTS resource_id"):
        TTSProvider(_base_config(resource_id="legacy-resource"), True)


def test_doubao_tts_merges_flat_audio_fields():
    provider = TTSProvider(
        _base_config(
            speech_rate=20,
            loudness_rate=10,
            pitch=2,
            emotion="happy",
            emotion_scale=4,
        ),
        True,
    )

    assert provider.audio_params["speech_rate"] == 20
    assert provider.audio_params["loudness_rate"] == 10
    assert provider.audio_params["emotion"] == "happy"
    assert provider.audio_params["emotion_scale"] == 4
    assert provider.additions["post_process"]["pitch"] == 2
    assert provider.audio_file_type == "pcm"


def test_doubao_tts_coerces_string_audio_numbers():
    provider = TTSProvider(
        _base_config(
            speech_rate="0",
            loudness_rate="0",
            sample_rate="24000",
            pitch="0",
            emotion_scale="4",
        ),
        True,
    )

    assert provider.audio_params["speech_rate"] == 0
    assert provider.audio_params["loudness_rate"] == 0
    assert provider.audio_params["sample_rate"] == 24000
    assert provider.audio_params["emotion_scale"] == 4
    assert provider.additions["post_process"]["pitch"] == 0


def test_doubao_tts_accepts_json_string_advanced_fields():
    provider = TTSProvider(
        _base_config(
            audio_params='{"speech_rate": 15, "loudness_rate": 8}',
            additions='{"post_process": {"pitch": 3}}',
        ),
        True,
    )

    assert provider.audio_params["speech_rate"] == 15
    assert provider.audio_params["loudness_rate"] == 8
    assert provider.additions["post_process"]["pitch"] == 3


def test_doubao_tts_requires_api_key():
    with pytest.raises(ValueError, match="需要配置 api_key"):
        TTSProvider(_base_config(api_key=""), True)


def test_doubao_tts_rejects_invalid_json_string_advanced_fields():
    with pytest.raises(ValueError, match="audio_params 必须是 JSON 对象字符串"):
        TTSProvider(_base_config(audio_params="not-json"), True)


def test_doubao_tts_flushes_partial_opus_frame_at_session_end(monkeypatch):
    provider = TTSProvider(_base_config(), True)
    provider.opus_encoder = object()
    calls = []

    monkeypatch.setattr(
        provider,
        "wav_to_opus_data_audio_raw_stream",
        lambda raw, is_end=False, callback=None: calls.append(
            (raw, is_end, callback)
        ),
    )

    provider._flush_opus_audio_stream()

    assert len(calls) == 1
    assert calls[0][:2] == (b"", True)
    assert callable(calls[0][2])


def test_doubao_tts_resets_opus_encoder_after_aborted_session():
    provider = TTSProvider(_base_config(), True)

    class FakeEncoder:
        def __init__(self):
            self.reset_count = 0

        def reset_state(self):
            self.reset_count += 1

    provider.opus_encoder = FakeEncoder()

    provider._reset_opus_audio_stream()

    assert provider.opus_encoder.reset_count == 1


def test_doubao_tts_timeout_configuration_defaults_and_overrides():
    provider = TTSProvider(_base_config(), True)

    assert provider.connection_timeout_seconds == 10
    assert provider.session_start_timeout_seconds == 10
    assert provider.session_finish_timeout_seconds == 30
    assert provider.session_timeout_seconds == 10
    assert provider.response_timeout_seconds == 15
    assert provider.send_timeout_seconds == 5
    assert provider.audio_send_timeout_seconds == 5

    configured = TTSProvider(
        _base_config(
            connection_timeout_seconds="0.1",
            session_start_timeout_seconds="0.2",
            session_finish_timeout_seconds="0.6",
            response_timeout_seconds="0.3",
            send_timeout_seconds="0.4",
            audio_send_timeout_seconds="0.5",
        ),
        True,
    )

    assert configured.connection_timeout_seconds == 0.1
    assert configured.session_start_timeout_seconds == 0.2
    assert configured.session_finish_timeout_seconds == 0.6
    assert configured.session_timeout_seconds == 0.2
    assert configured.response_timeout_seconds == 0.3
    assert configured.send_timeout_seconds == 0.4
    assert configured.audio_send_timeout_seconds == 0.5


def test_legacy_short_session_timeout_keeps_start_budget_but_not_finish_budget():
    provider = TTSProvider(_base_config(session_timeout_seconds="0.2"), True)

    assert provider.session_start_timeout_seconds == 0.2
    assert provider.session_finish_timeout_seconds == 30


def test_last_audio_message_wait_budget_includes_queue_drain_timeout():
    provider = TTSProvider(
        _base_config(audio_send_timeout_seconds=5),
        True,
    )
    provider.conn = SimpleNamespace(
        config={"tts_audio_completion_grace_seconds": 5},
        tts=provider,
        audio_rate_controller=SimpleNamespace(
            queue=[("audio", b"opus")] * 1000,
            frame_duration=60,
        ),
    )

    assert provider._audio_message_timeout(SentenceType.MIDDLE) == 5
    assert provider._audio_message_timeout(SentenceType.LAST) == 75


@pytest.mark.parametrize(
    "key,value",
    [
        ("connection_timeout_seconds", 0),
        ("session_timeout_seconds", -1),
        ("session_start_timeout_seconds", 0),
        ("session_finish_timeout_seconds", "invalid"),
        ("response_timeout_seconds", "invalid"),
        ("send_timeout_seconds", float("inf")),
        ("audio_send_timeout_seconds", None),
        ("stream_prebuffer_ms", -1),
    ],
)
def test_doubao_tts_rejects_invalid_timeout_configuration(key, value):
    with pytest.raises(ValueError, match=key):
        TTSProvider(_base_config(**{key: value}), True)


class FakeTTSWebSocket:
    def __init__(self, responses, *, hang_on_send_number=None):
        self.responses = list(responses)
        self.hang_on_send_number = hang_on_send_number
        self.send_count = 0
        self.closed = False

    async def send(self, payload):
        self.send_count += 1
        if self.send_count == self.hang_on_send_number:
            await asyncio.Event().wait()

    async def recv(self):
        if self.responses:
            response = self.responses.pop(0)
            if isinstance(response, BaseException):
                raise response
            return response
        await asyncio.Event().wait()

    async def close(self):
        self.closed = True


def _tts_response(event, session_id=None):
    return SimpleNamespace(
        header=SimpleNamespace(message_type=huoshan_double_stream.FULL_SERVER_RESPONSE),
        optional=SimpleNamespace(
            event=event,
            sessionId=session_id,
            response_meta_json=None,
            errorCode=0,
        ),
        payload=b"",
    )


def _fault_provider(**overrides):
    config = {
        "connection_timeout_seconds": 0.04,
        "session_start_timeout_seconds": 0.04,
        "session_finish_timeout_seconds": 0.04,
        "response_timeout_seconds": 0.04,
        "send_timeout_seconds": 0.04,
        "audio_send_timeout_seconds": 0.04,
        "tts_timeout": 1,
    }
    config.update(overrides)
    provider = TTSProvider(_base_config(**config), True)
    provider.conn = SimpleNamespace(
        stop_event=SimpleNamespace(is_set=lambda: False),
        sentence_id="session-1",
    )
    provider.opus_encoder = SimpleNamespace(reset_state=lambda: None)
    provider.parser_response = lambda response: response
    return provider


def _wait_until(predicate, timeout=0.5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.005)
    return predicate()


def _wait_until_stable(predicate, timeout=0.5, stable_for=0.03):
    deadline = time.monotonic() + timeout
    stable_since = None
    while time.monotonic() < deadline:
        if predicate():
            stable_since = stable_since or time.monotonic()
            if time.monotonic() - stable_since >= stable_for:
                return True
        else:
            stable_since = None
        time.sleep(0.005)
    return False


def _run_tts_text_thread(provider):
    worker = threading.Thread(target=provider.tts_text_priority_thread, daemon=True)
    worker.start()
    return worker


def _stop_tts_text_thread(provider, worker):
    provider.conn.stop_event.set()
    provider.tts_text_queue.put(
        TTSMessageDTO(
            sentence_id="wake-worker",
            sentence_type=SentenceType.LAST,
            content_type=ContentType.ACTION,
        )
    )
    worker.join(timeout=1)
    assert worker.is_alive() is False


def _run_coroutine_immediately(coro, _loop=None, **_kwargs):
    future = concurrent.futures.Future()
    try:
        future.set_result(asyncio.run(coro))
    except BaseException as exc:
        future.set_exception(exc)
    return future


def _lazy_session_provider(monkeypatch):
    provider = TTSProvider(_base_config(tts_timeout=1), True)
    provider.conn = SimpleNamespace(
        stop_event=threading.Event(),
        client_abort=False,
        sentence_id="session-lazy",
        loop=object(),
    )
    monkeypatch.setattr(
        huoshan_double_stream.asyncio,
        "run_coroutine_threadsafe",
        _run_coroutine_immediately,
    )
    return provider


def test_tts_text_thread_lazily_starts_session_on_first_text(monkeypatch):
    provider = _lazy_session_provider(monkeypatch)
    calls = []

    async def fake_start_session(session_id):
        calls.append(("start", session_id))
        provider.activate_session = True

    async def fake_text_to_speak(text, session_id):
        calls.append(("text", session_id, text))

    async def fake_finish_session(session_id):
        calls.append(("finish", session_id))
        provider.activate_session = False

    monkeypatch.setattr(provider, "start_session", fake_start_session)
    monkeypatch.setattr(provider, "text_to_speak", fake_text_to_speak)
    monkeypatch.setattr(provider, "finish_session", fake_finish_session)

    worker = _run_tts_text_thread(provider)
    try:
        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.FIRST,
                content_type=ContentType.ACTION,
            )
        )
        assert _wait_until(
            lambda: getattr(provider, "current_sentence_id", None)
            == "session-lazy"
        )

        # Simulate a slow LLM: FIRST must not consume the upstream idle window.
        time.sleep(0.08)
        assert calls == []
        assert provider.activate_session is False

        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.MIDDLE,
                content_type=ContentType.TEXT,
                content_detail="第一段回复",
            )
        )
        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.MIDDLE,
                content_type=ContentType.TEXT,
                content_detail="第二段回复",
            )
        )
        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.LAST,
                content_type=ContentType.ACTION,
            )
        )

        assert _wait_until(lambda: len(calls) == 4)
        assert calls == [
            ("start", "session-lazy"),
            ("text", "session-lazy", "第一段回复"),
            ("text", "session-lazy", "第二段回复"),
            ("finish", "session-lazy"),
        ]
    finally:
        _stop_tts_text_thread(provider, worker)


def test_tts_text_thread_skips_upstream_session_for_empty_turn(monkeypatch):
    provider = _lazy_session_provider(monkeypatch)
    calls = []

    async def record_call(*args):
        calls.append(args)

    monkeypatch.setattr(provider, "start_session", record_call)
    monkeypatch.setattr(provider, "finish_session", record_call)

    worker = _run_tts_text_thread(provider)
    try:
        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.FIRST,
                content_type=ContentType.ACTION,
            )
        )
        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.LAST,
                content_type=ContentType.ACTION,
            )
        )

        assert _wait_until_stable(
            lambda: provider.tts_text_queue.empty()
            and calls == []
            and provider.tts_audio_queue.empty()
            and provider.activate_session is False
        )
    finally:
        _stop_tts_text_thread(provider, worker)


def test_tts_text_thread_lazily_starts_session_on_first_file(monkeypatch):
    provider = _lazy_session_provider(monkeypatch)
    calls = []

    async def fake_start_session(session_id):
        calls.append(("start", session_id))
        provider.activate_session = True

    async def fake_finish_session(session_id):
        calls.append(("finish", session_id))
        provider.activate_session = False

    def fake_process_file(path, callback):
        calls.append(("file", path))

    monkeypatch.setattr(provider, "start_session", fake_start_session)
    monkeypatch.setattr(provider, "finish_session", fake_finish_session)
    monkeypatch.setattr(provider, "_process_audio_file_stream", fake_process_file)
    monkeypatch.setattr(huoshan_double_stream.os.path, "exists", lambda _path: True)

    worker = _run_tts_text_thread(provider)
    try:
        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.FIRST,
                content_type=ContentType.ACTION,
            )
        )
        assert _wait_until(
            lambda: getattr(provider, "current_sentence_id", None)
            == "session-lazy"
        )
        assert calls == []

        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.MIDDLE,
                content_type=ContentType.FILE,
                content_detail="背景音乐",
                content_file="/fake/music.opus",
            )
        )
        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.LAST,
                content_type=ContentType.ACTION,
            )
        )

        assert _wait_until(lambda: len(calls) == 3)
        assert calls == [
            ("start", "session-lazy"),
            ("file", "/fake/music.opus"),
            ("finish", "session-lazy"),
        ]
    finally:
        _stop_tts_text_thread(provider, worker)


def test_tts_text_thread_abort_before_text_creates_no_upstream_boundary(monkeypatch):
    provider = _lazy_session_provider(monkeypatch)
    calls = []

    async def fake_start_session(session_id):
        calls.append(("start", session_id))

    async def fake_finish_session(session_id):
        calls.append(("finish", session_id))

    async def fake_cancel_session(session_id):
        calls.append(("cancel", session_id))

    monkeypatch.setattr(provider, "start_session", fake_start_session)
    monkeypatch.setattr(provider, "finish_session", fake_finish_session)
    monkeypatch.setattr(provider, "cancel_session", fake_cancel_session)

    worker = _run_tts_text_thread(provider)
    try:
        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.FIRST,
                content_type=ContentType.ACTION,
            )
        )
        assert _wait_until(
            lambda: getattr(provider, "current_sentence_id", None)
            == "session-lazy"
        )

        provider.conn.client_abort = True
        for _ in range(2):
            provider.tts_text_queue.put(
                TTSMessageDTO(
                    sentence_id="session-lazy",
                    sentence_type=SentenceType.LAST,
                    content_type=ContentType.ACTION,
                )
            )

        assert _wait_until_stable(
            lambda: provider.tts_text_queue.empty()
            and calls == []
            and provider.tts_audio_queue.empty()
        )
    finally:
        _stop_tts_text_thread(provider, worker)


def test_tts_text_thread_abort_during_lazy_start_cancels_once(monkeypatch):
    provider = _lazy_session_provider(monkeypatch)
    calls = []

    async def fake_start_session(session_id):
        calls.append(("start", session_id))
        provider.activate_session = True
        provider.conn.client_abort = True

    async def fake_text_to_speak(text, session_id):
        calls.append(("text", session_id, text))

    async def fake_cancel_session(session_id):
        calls.append(("cancel", session_id))

    monkeypatch.setattr(provider, "start_session", fake_start_session)
    monkeypatch.setattr(provider, "text_to_speak", fake_text_to_speak)
    monkeypatch.setattr(provider, "cancel_session", fake_cancel_session)

    worker = _run_tts_text_thread(provider)
    try:
        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.FIRST,
                content_type=ContentType.ACTION,
            )
        )
        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.MIDDLE,
                content_type=ContentType.TEXT,
                content_detail="不应在打断后发送",
            )
        )
        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.LAST,
                content_type=ContentType.ACTION,
            )
        )

        assert _wait_until_stable(
            lambda: provider.tts_text_queue.empty()
            and calls
            == [
                ("start", "session-lazy"),
                ("cancel", "session-lazy"),
            ]
        )
        assert provider.tts_audio_queue.empty()
    finally:
        _stop_tts_text_thread(provider, worker)


def test_tts_failure_finalizer_enqueues_last_exactly_once_after_first():
    provider = _fault_provider()
    provider._prepare_session_audio_state("session-1")
    assert provider._enqueue_session_first_once("session-1", "长回复") is True
    provider.before_stop_play_files.append((b"file-opus", "背景音乐"))

    assert provider._finalize_session_audio_once(
        "session-1", normal=False, reason="test_timeout"
    ) is True
    assert provider._finalize_session_audio_once(
        "session-1", normal=True, reason="late_session_finished"
    ) is False

    items = [provider.tts_audio_queue.get_nowait() for _ in range(3)]
    assert [item[0] for item in items] == [
        SentenceType.FIRST,
        SentenceType.MIDDLE,
        SentenceType.LAST,
    ]
    assert all(item[3] == "session-1" for item in items)
    assert items[-1][6] == "tts_error:test_timeout"
    assert provider.tts_audio_queue.empty()


def test_tts_failure_finalizer_publishes_error_last_without_audio_start():
    provider = _fault_provider()
    provider._prepare_session_audio_state("session-1")

    assert provider._finalize_session_audio_once(
        "session-1", normal=False, reason="start_failed"
    ) is True
    item = provider.tts_audio_queue.get_nowait()
    assert item[0] is SentenceType.LAST
    assert item[3] == "session-1"
    assert item[6] == "tts_error:start_failed"


def test_tts_file_still_publishes_boundaries_when_upstream_start_fails(
    monkeypatch,
):
    provider = _lazy_session_provider(monkeypatch)

    async def fail_start_session(_session_id):
        raise RuntimeError("simulated upstream start failure")

    def fake_process_file(_path, callback):
        callback(b"file-opus")

    monkeypatch.setattr(provider, "start_session", fail_start_session)
    monkeypatch.setattr(provider, "_process_audio_file_stream", fake_process_file)
    monkeypatch.setattr(huoshan_double_stream.os.path, "exists", lambda _path: True)

    worker = _run_tts_text_thread(provider)
    try:
        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.FIRST,
                content_type=ContentType.ACTION,
            )
        )
        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.MIDDLE,
                content_type=ContentType.FILE,
                content_detail="背景音乐",
                content_file="/fake/music.opus",
            )
        )
        provider.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id="session-lazy",
                sentence_type=SentenceType.LAST,
                content_type=ContentType.ACTION,
            )
        )

        assert _wait_until(lambda: provider.tts_audio_queue.qsize() == 3)
        items = [provider.tts_audio_queue.get_nowait() for _ in range(3)]
        assert [item[0] for item in items] == [
            SentenceType.FIRST,
            SentenceType.MIDDLE,
            SentenceType.LAST,
        ]
        assert items[1][1] == b"file-opus"
        assert all(item[3] == "session-lazy" for item in items)
    finally:
        _stop_tts_text_thread(provider, worker)


def test_tts_zero_audio_session_finish_raises_and_closes_connection():
    provider = _fault_provider(response_timeout_seconds=1)
    websocket = FakeTTSWebSocket(
        [_tts_response(huoshan_double_stream.EVENT_SessionFinished, "session-1")]
    )
    reset_calls = []
    provider.ws = websocket
    provider.activate_session = True
    provider._active_session_id = "session-1"
    provider._upstream_session_id = "session-1"
    provider._prepare_session_audio_state("session-1")
    provider._mark_session_content_requested("session-1")
    provider._reset_session_waiters()
    provider.opus_encoder = SimpleNamespace(
        reset_state=lambda: reset_calls.append("reset")
    )

    async def scenario():
        provider._monitor_task = asyncio.create_task(
            provider._start_monitor_tts_response()
        )
        with pytest.raises(RuntimeError, match="未返回任何音频"):
            await provider.finish_session("session-1")

        assert websocket.closed is True
        assert provider.ws is None
        assert provider.activate_session is False
        assert provider._active_session_id is None
        assert reset_calls == ["reset"]
        terminal = provider.tts_audio_queue.get_nowait()
        assert terminal[0] is SentenceType.LAST
        assert terminal[6] == "tts_error:zero_audio"
        assert provider.tts_audio_queue.empty()

    asyncio.run(scenario())


def test_tts_late_old_session_event_does_not_reset_new_session():
    provider = _fault_provider(response_timeout_seconds=1)
    provider.ws = FakeTTSWebSocket(
        [
            _tts_response(
                huoshan_double_stream.EVENT_SessionFinished,
                "session-old",
            ),
            _tts_response(
                huoshan_double_stream.EVENT_SessionStarted,
                "session-new",
            ),
        ]
    )
    provider.activate_session = True
    provider._active_session_id = "session-new"
    provider._reset_session_waiters()

    async def scenario():
        monitor_task = asyncio.create_task(provider._start_monitor_tts_response())
        provider._monitor_task = monitor_task
        await asyncio.wait_for(provider._session_started_event.wait(), timeout=0.2)

        assert provider.activate_session is True
        assert provider._active_session_id == "session-new"
        assert provider._session_error is None

        monitor_task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await monitor_task

    asyncio.run(scenario())


async def _heartbeat(duration=0.08, interval=0.005):
    count = 0
    deadline = asyncio.get_running_loop().time() + duration
    while asyncio.get_running_loop().time() < deadline:
        await asyncio.sleep(interval)
        count += 1
    return count


def test_tts_session_start_half_open_is_bounded_and_next_session_recovers(
    monkeypatch,
):
    first = FakeTTSWebSocket(
        [_tts_response(huoshan_double_stream.EVENT_ConnectionStarted)]
    )
    second = FakeTTSWebSocket(
        [
            _tts_response(huoshan_double_stream.EVENT_ConnectionStarted),
            _tts_response(huoshan_double_stream.EVENT_SessionStarted, "session-2"),
            _tts_response(huoshan_double_stream.EVENT_SessionFinished, "session-2"),
        ]
    )
    sockets = iter([first, second])

    async def fake_connect(*args, **kwargs):
        return next(sockets)

    monkeypatch.setattr(huoshan_double_stream.websockets, "connect", fake_connect)
    provider = _fault_provider()

    async def scenario():
        heartbeat_task = asyncio.create_task(_heartbeat())
        started = time.monotonic()
        with pytest.raises((TimeoutError, RuntimeError)):
            await provider.start_session("session-1")
        elapsed = time.monotonic() - started
        heartbeat_count = await heartbeat_task

        assert elapsed < 0.2
        assert heartbeat_count >= 8
        assert first.closed is True
        assert provider.ws is None
        assert provider.activate_session is False

        provider.conn.sentence_id = "session-2"
        await provider.start_session("session-2")
        await provider.finish_session("session-2")
        assert provider.activate_session is False
        assert second.closed is False
        await provider.close()

    asyncio.run(scenario())


def test_tts_finish_half_open_cleans_state_and_next_session_recovers(monkeypatch):
    first = FakeTTSWebSocket(
        [
            _tts_response(huoshan_double_stream.EVENT_ConnectionStarted),
            _tts_response(huoshan_double_stream.EVENT_SessionStarted, "session-1"),
            SimpleNamespace(
                header=SimpleNamespace(
                    message_type=huoshan_double_stream.AUDIO_ONLY_RESPONSE
                ),
                optional=SimpleNamespace(
                    event=huoshan_double_stream.EVENT_TTSResponse,
                    sessionId="session-1",
                    response_meta_json=None,
                    errorCode=0,
                ),
                payload=b"pcm",
            ),
        ]
    )
    second = FakeTTSWebSocket(
        [
            _tts_response(huoshan_double_stream.EVENT_ConnectionStarted),
            _tts_response(huoshan_double_stream.EVENT_SessionStarted, "session-2"),
            _tts_response(huoshan_double_stream.EVENT_SessionFinished, "session-2"),
        ]
    )
    sockets = iter([first, second])

    async def fake_connect(*args, **kwargs):
        return next(sockets)

    monkeypatch.setattr(huoshan_double_stream.websockets, "connect", fake_connect)
    provider = _fault_provider()
    provider.store_tts_text("session-1", "这是一段很长的回复")
    provider.wav_to_opus_data_audio_raw_stream = (
        lambda _payload, is_end=False, callback=None: callback(b"opus")
        if callback and not is_end
        else None
    )

    async def scenario():
        await provider.start_session("session-1")
        heartbeat_task = asyncio.create_task(_heartbeat())
        started = time.monotonic()
        with pytest.raises((TimeoutError, RuntimeError)):
            await provider.finish_session("session-1")
        elapsed = time.monotonic() - started
        heartbeat_count = await heartbeat_task

        assert elapsed < 0.2
        assert heartbeat_count >= 8
        assert first.closed is True
        assert provider.ws is None
        assert provider.activate_session is False
        audio_items = [
            provider.tts_audio_queue.get_nowait()
            for _ in range(provider.tts_audio_queue.qsize())
        ]
        assert [item[0] for item in audio_items] == [
            SentenceType.FIRST,
            SentenceType.MIDDLE,
            SentenceType.LAST,
        ]
        assert audio_items[-1][6] == "tts_error:response_timeout"

        provider.conn.sentence_id = "session-2"
        await provider.start_session("session-2")
        await provider.finish_session("session-2")
        assert provider.activate_session is False
        await provider.close()

    asyncio.run(scenario())


def test_tts_prebuffer_requires_non_empty_first_text():
    provider = _fault_provider(stream_prebuffer_ms=120)
    provider._prepare_session_audio_state("session-1", generation=3)

    assert provider._enqueue_session_first_once("session-1", None) is False
    assert provider._enqueue_session_first_once("session-1", "   ") is False
    assert provider.tts_audio_queue.empty()


def test_tts_default_streams_audio_until_complete_subtitle_is_available():
    provider = _fault_provider()
    provider._prepare_session_audio_state("session-1", generation=3)
    provider.store_tts_preview("session-1", "您")
    provider._mark_session_content_requested("session-1", generation=3)

    assert provider._handle_session_opus("session-1", b"opus-0") is True
    first_item = provider.tts_audio_queue.get_nowait()
    assert first_item[0] is SentenceType.MIDDLE
    assert first_item[1] == b"opus-0"

    provider.store_tts_text("session-1", "您好，这是完整回答。")
    assert provider._handle_session_opus("session-1", b"opus-1") is True

    items = [provider.tts_audio_queue.get_nowait() for _ in range(2)]
    assert [item[0] for item in items] == [
        SentenceType.FIRST,
        SentenceType.MIDDLE,
    ]
    assert items[0][2] == "您好，这是完整回答。"
    assert items[1][1] == b"opus-1"
    assert all(item[3] == "session-1" for item in items)
    assert all(item[5] == 3 for item in items)


def test_tts_normal_finish_publishes_complete_subtitle_without_more_audio(
    monkeypatch,
):
    provider = _fault_provider()
    provider._prepare_session_audio_state("session-1", generation=5)
    provider.store_tts_preview("session-1", "我")
    provider._mark_session_content_requested("session-1", generation=5)

    assert provider._handle_session_opus("session-1", b"opus-0") is True
    streamed_audio = provider.tts_audio_queue.get_nowait()
    assert streamed_audio[0] is SentenceType.MIDDLE
    assert streamed_audio[1] == b"opus-0"

    provider.store_tts_text("session-1", "我是完整回答。")
    monkeypatch.setattr(provider, "_flush_opus_audio_stream", lambda _sid: None)

    assert provider._finalize_session_audio_once(
        "session-1", normal=True, reason="session_finished"
    ) is True
    items = [provider.tts_audio_queue.get_nowait() for _ in range(2)]
    assert [item[0] for item in items] == [
        SentenceType.FIRST,
        SentenceType.LAST,
    ]
    assert items[0][2] == "我是完整回答。"
    assert all(item[3] == "session-1" for item in items)
    assert all(item[5] == 5 for item in items)


def test_tts_prebuffer_releases_first_and_audio_after_threshold():
    provider = _fault_provider(stream_prebuffer_ms=600)
    provider._prepare_session_audio_state("session-1", generation=3)
    provider.store_tts_preview("session-1", "这")
    provider._mark_session_content_requested("session-1", generation=3)
    provider.store_tts_text("session-1", "这是完整的设备字幕。")

    for index in range(9):
        assert provider._handle_session_opus(
            "session-1", f"opus-{index}".encode()
        ) is True
    first_item = provider.tts_audio_queue.get_nowait()
    assert first_item[0] is SentenceType.FIRST
    assert first_item[2] == "这是完整的设备字幕。"
    assert provider.tts_audio_queue.empty()

    assert provider._handle_session_opus("session-1", b"opus-9") is True
    items = [provider.tts_audio_queue.get_nowait() for _ in range(10)]
    assert [item[0] for item in items] == [SentenceType.MIDDLE] * 10
    assert [item[1] for item in items] == [
        f"opus-{index}".encode() for index in range(10)
    ]
    assert all(item[3] == "session-1" for item in items)
    assert all(item[5] == 3 for item in items)


def test_short_tts_session_releases_prebuffer_before_normal_last(monkeypatch):
    provider = _fault_provider(stream_prebuffer_ms=600)
    provider._prepare_session_audio_state("session-1", generation=4)
    provider.store_tts_preview("session-1", "短")
    provider._mark_session_content_requested("session-1", generation=4)
    provider.store_tts_text("session-1", "短回复也必须连续播放。")
    monkeypatch.setattr(provider, "_flush_opus_audio_stream", lambda _sid: None)
    for index in range(3):
        provider._handle_session_opus(
            "session-1", f"short-{index}".encode()
        )

    assert provider._finalize_session_audio_once(
        "session-1", normal=True, reason="session_finished"
    ) is True
    items = [provider.tts_audio_queue.get_nowait() for _ in range(5)]
    assert [item[0] for item in items] == [
        SentenceType.FIRST,
        SentenceType.MIDDLE,
        SentenceType.MIDDLE,
        SentenceType.MIDDLE,
        SentenceType.LAST,
    ]
    assert all(item[5] == 4 for item in items)
    assert items[-1][6] is None


@pytest.mark.parametrize("hang_on_send_number", [2, 3])
def test_tts_hung_session_send_is_bounded_and_cleans_connection(
    monkeypatch, hang_on_send_number
):
    responses = [
        _tts_response(huoshan_double_stream.EVENT_ConnectionStarted),
        _tts_response(huoshan_double_stream.EVENT_SessionStarted, "session-1"),
    ]
    websocket = FakeTTSWebSocket(
        responses,
        hang_on_send_number=hang_on_send_number,
    )

    async def fake_connect(*args, **kwargs):
        return websocket

    monkeypatch.setattr(huoshan_double_stream.websockets, "connect", fake_connect)
    provider = _fault_provider()

    async def scenario():
        started = time.monotonic()
        if hang_on_send_number == 2:
            with pytest.raises(TimeoutError):
                await provider.start_session("session-1")
        else:
            await provider.start_session("session-1")
            with pytest.raises(TimeoutError):
                await provider.finish_session("session-1")
        assert time.monotonic() - started < 0.2
        assert websocket.closed is True
        assert provider.ws is None
        assert provider.activate_session is False

    asyncio.run(scenario())


def test_tts_websocket_failure_unblocks_waiter_and_allows_reconnect(monkeypatch):
    first = FakeTTSWebSocket(
        [
            _tts_response(huoshan_double_stream.EVENT_ConnectionStarted),
            RuntimeError("simulated disconnect"),
        ]
    )
    second = FakeTTSWebSocket(
        [
            _tts_response(huoshan_double_stream.EVENT_ConnectionStarted),
            _tts_response(huoshan_double_stream.EVENT_SessionStarted, "session-2"),
            _tts_response(huoshan_double_stream.EVENT_SessionFinished, "session-2"),
        ]
    )
    sockets = iter([first, second])

    async def fake_connect(*args, **kwargs):
        return next(sockets)

    monkeypatch.setattr(huoshan_double_stream.websockets, "connect", fake_connect)
    provider = _fault_provider()

    async def scenario():
        with pytest.raises(RuntimeError, match="TTS下行响应异常"):
            await provider.start_session("session-1")
        assert first.closed is True
        assert provider.ws is None

        provider.conn.sentence_id = "session-2"
        await provider.start_session("session-2")
        await provider.finish_session("session-2")
        await provider.close()

    asyncio.run(scenario())
