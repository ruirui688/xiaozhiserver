import asyncio
import sys
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
from core.providers.tts.dto.dto import SentenceType


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

    assert calls == [(b"", True, provider.handle_opus)]


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

    assert provider.connection_timeout_seconds == 5
    assert provider.session_timeout_seconds == 5
    assert provider.response_timeout_seconds == 5
    assert provider.send_timeout_seconds == 3
    assert provider.audio_send_timeout_seconds == 5

    configured = TTSProvider(
        _base_config(
            connection_timeout_seconds="0.1",
            session_timeout_seconds="0.2",
            response_timeout_seconds="0.3",
            send_timeout_seconds="0.4",
            audio_send_timeout_seconds="0.5",
        ),
        True,
    )

    assert configured.connection_timeout_seconds == 0.1
    assert configured.session_timeout_seconds == 0.2
    assert configured.response_timeout_seconds == 0.3
    assert configured.send_timeout_seconds == 0.4
    assert configured.audio_send_timeout_seconds == 0.5


def test_last_audio_message_wait_budget_includes_queue_drain_timeout():
    provider = TTSProvider(
        _base_config(audio_send_timeout_seconds=5),
        True,
    )
    provider.conn = SimpleNamespace(
        config={"tts_audio_completion_timeout_seconds": 20}
    )

    assert provider._audio_message_timeout(SentenceType.MIDDLE) == 5
    assert provider._audio_message_timeout(SentenceType.LAST) == 25


@pytest.mark.parametrize(
    "key,value",
    [
        ("connection_timeout_seconds", 0),
        ("session_timeout_seconds", -1),
        ("response_timeout_seconds", "invalid"),
        ("send_timeout_seconds", float("inf")),
        ("audio_send_timeout_seconds", None),
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
        "session_timeout_seconds": 0.04,
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

        provider.conn.sentence_id = "session-2"
        await provider.start_session("session-2")
        await provider.finish_session("session-2")
        assert provider.activate_session is False
        await provider.close()

    asyncio.run(scenario())


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
