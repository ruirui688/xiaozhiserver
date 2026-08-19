import asyncio
import sys
import time
from pathlib import Path

import pytest
from websockets.exceptions import ConnectionClosedOK
from websockets.frames import Close


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings

settings.config_file_valid = True

from core.providers.asr import doubao_stream


class FakeWebSocket:
    def __init__(self, *, recv_delay=1, responses=None):
        self.recv_delay = recv_delay
        self.responses = list(responses or [])
        self.closed = False
        self.sent = []

    async def send(self, payload):
        self.sent.append(payload)

    async def recv(self):
        if self.responses:
            response = self.responses.pop(0)
            if isinstance(response, BaseException):
                raise response
            return response
        await asyncio.sleep(self.recv_delay)
        return b""

    async def close(self):
        self.closed = True


class FinalAfterInternalVadWebSocket(FakeWebSocket):
    def __init__(self):
        super().__init__()
        self.tail_audio_sent = asyncio.Event()
        self.recv_count = 0
        self.audio_frame_count = 0

    async def send(self, payload):
        await super().send(payload)
        if len(payload) >= 2 and payload[1] >> 4 == 0x02:
            if payload[1] & 0x0F == 0:
                self.audio_frame_count += 1
                if self.audio_frame_count >= 2:
                    self.tail_audio_sent.set()

    async def recv(self):
        self.recv_count += 1
        if self.recv_count == 1:
            return b"init"
        await self.tail_audio_sent.wait()
        return b"final"


class FailingSendWebSocket(FakeWebSocket):
    async def send(self, payload):
        raise RuntimeError("simulated send failure")


class FakeConnection:
    def __init__(self):
        self.client_listen_mode = "auto"
        self.asr_audio = []
        self.client_have_voice = False
        self.client_voice_stop = False
        self.stop_event = type("StopEvent", (), {"is_set": lambda self: False})()
        self.reset_count = 0
        self.reset_preserve_voiceprint = []

    def reset_audio_states(self, preserve_voiceprint=False):
        self.reset_count += 1
        self.reset_preserve_voiceprint.append(preserve_voiceprint)
        self.client_have_voice = False
        self.client_voice_stop = False
        self.asr_audio.clear()


def _provider(tmp_path, **overrides):
    config = {
        "api_key": "test-api-key",
        "output_dir": str(tmp_path),
    }
    config.update(overrides)
    return doubao_stream.ASRProvider(config, delete_audio_file=True)


def test_stream_timeout_configuration_defaults_and_overrides(tmp_path):
    provider = _provider(tmp_path)
    assert provider.end_window_size == 200
    assert provider.init_timeout_seconds == 10
    assert provider.response_timeout_seconds == 15
    assert provider.final_response_timeout_seconds == 5

    configured = _provider(
        tmp_path,
        end_window_size="350",
        init_timeout_seconds="0.25",
        response_timeout_seconds="1.5",
        final_response_timeout_seconds="0.75",
    )
    assert configured.end_window_size == 350
    assert configured.init_timeout_seconds == 0.25
    assert configured.response_timeout_seconds == 1.5
    assert configured.final_response_timeout_seconds == 0.75


def test_stream_request_sends_agent_hotword_table_in_corpus(tmp_path):
    provider = _provider(
        tmp_path,
        boosting_table_id="example-hotword-table-id",
        context_text='{"context_type":"dialog_ctx"}',
    )

    request = provider.construct_request("request-id")

    assert request["request"]["corpus"] == {
        "boosting_table_id": "example-hotword-table-id"
    }
    assert request["request"]["end_window_size"] == 200
    assert request["request"]["context"] == '{"context_type":"dialog_ctx"}'


def test_stream_request_omits_empty_hotword_corpus(tmp_path):
    provider = _provider(tmp_path, boosting_table_id="  ")

    assert "corpus" not in provider.construct_request("request-id")["request"]


def test_stream_speech_to_text_preserves_structured_utterances(tmp_path):
    provider = _provider(tmp_path)
    provider.text = "先讨论计划。"
    provider.utterances = [
        {
            "text": "先讨论计划。",
            "speaker": "spk_1",
            "start_time": 0,
            "end_time": 1200,
        }
    ]

    result, _ = asyncio.run(provider.speech_to_text([], "session", "opus"))

    assert result["content"] == "先讨论计划。"
    assert result["utterances"][0]["provider_speaker"] == "spk_1"
    assert result["provider_metadata"]["provider"] == "doubao_stream"


def test_stream_speech_to_text_keeps_plain_text_compatibility(tmp_path):
    provider = _provider(tmp_path)
    provider.text = "普通识别"
    provider.utterances = [{"text": "普通识别", "definite": True}]

    result, _ = asyncio.run(provider.speech_to_text([], "session", "opus"))

    assert result == "普通识别"


@pytest.mark.parametrize(
    "key,value",
    [
        ("init_timeout_seconds", 0),
        ("response_timeout_seconds", -1),
        ("response_timeout_seconds", "invalid"),
        ("final_response_timeout_seconds", 0),
        ("end_window_size", 0),
        ("end_window_size", "invalid"),
    ],
)
def test_stream_timeout_configuration_rejects_invalid_values(
    tmp_path, key, value
):
    with pytest.raises(ValueError, match=key):
        _provider(tmp_path, **{key: value})


def test_initialization_timeout_closes_websocket_and_resets_state(
    monkeypatch, tmp_path
):
    websocket = FakeWebSocket()

    async def fake_connect(*args, **kwargs):
        return websocket

    monkeypatch.setattr(doubao_stream.websockets, "connect", fake_connect)
    provider = _provider(tmp_path, init_timeout_seconds=0.01)
    conn = FakeConnection()
    conn.asr = provider

    asyncio.run(provider.receive_audio(conn, b"invalid-opus", True))

    assert websocket.closed is True
    assert provider.asr_ws is None
    assert provider.is_processing is False
    assert provider._is_stopping is False
    assert conn.reset_count == 1


def test_response_timeout_closes_websocket_and_preserves_audio_state(tmp_path):
    websocket = FakeWebSocket()
    provider = _provider(tmp_path, response_timeout_seconds=0.01)
    provider.asr_ws = websocket
    provider.is_processing = True
    conn = FakeConnection()
    conn.asr_audio = [b"cached-frame"]

    asyncio.run(provider._forward_asr_results(conn))

    assert websocket.closed is True
    assert provider.asr_ws is None
    assert provider.is_processing is False
    assert provider._is_stopping is False
    assert conn.reset_count == 0
    assert conn.asr_audio == [b"cached-frame"]


def test_empty_auto_result_drops_segment_without_handle_voice_stop(tmp_path):
    websocket = FakeWebSocket(responses=[b"empty-result"])
    provider = _provider(tmp_path)
    provider.parse_response = lambda _response: {
        "payload_msg": {
            "audio_info": {"duration": 2400},
            "result": {"text": "", "utterances": []},
        }
    }
    provider.asr_ws = websocket
    provider.is_processing = True
    conn = FakeConnection()
    conn.asr_audio = [b"cached-frame"]
    handled = []

    async def fake_handle_voice_stop(_conn, audio_data):
        handled.append(list(audio_data))

    provider.handle_voice_stop = fake_handle_voice_stop

    asyncio.run(provider._forward_asr_results(conn))

    assert handled == []
    assert websocket.closed is True
    assert provider.asr_ws is None
    assert provider.is_processing is False
    assert conn.reset_count == 1
    assert conn.reset_preserve_voiceprint == [False]
    assert conn.asr_audio == []
    assert conn.client_voice_stop is False


def test_stale_auto_voice_stop_does_not_send_last_for_new_session(
    monkeypatch, tmp_path
):
    websocket = FakeWebSocket(
        responses=[
            b"init",
            ConnectionClosedOK(Close(1000, "server finished"), None, None),
        ]
    )

    async def fake_connect(*args, **kwargs):
        return websocket

    monkeypatch.setattr(doubao_stream.websockets, "connect", fake_connect)
    provider = _provider(tmp_path)
    provider.decoder = type("Decoder", (), {"decode": lambda _self, data, _size: data})()
    provider.parse_response = lambda response: {"code": 1000}
    conn = FakeConnection()
    conn.asr = provider
    conn.client_have_voice = True
    conn.client_voice_stop = True

    async def scenario():
        await provider.receive_audio(conn, b"voice-frame", True)
        await asyncio.wait_for(provider.forward_task, timeout=0.1)

    asyncio.run(scenario())

    audio_frames = [
        payload for payload in websocket.sent if payload[1] >> 4 == 0x02
    ]
    regular_frames = [payload for payload in audio_frames if payload[1] & 0x0F == 0]
    last_frames = [payload for payload in audio_frames if payload[1] & 0x0F == 0x02]

    assert len(regular_frames) == 1
    assert len(last_frames) == 0
    assert conn.client_voice_stop is False
    assert conn.asr_audio == [b"voice-frame"]
    assert conn.reset_count == 0
    assert provider.forward_task is None


def test_initialization_half_open_keeps_heartbeat_and_next_connection_starts(
    monkeypatch, tmp_path
):
    first = FakeWebSocket(recv_delay=1)
    second = FakeWebSocket(responses=[b"init", b"final"])
    sockets = iter([first, second])

    async def fake_connect(*args, **kwargs):
        return next(sockets)

    monkeypatch.setattr(doubao_stream.websockets, "connect", fake_connect)
    provider = _provider(
        tmp_path,
        init_timeout_seconds=0.02,
        response_timeout_seconds=0.05,
    )
    provider.parse_response = lambda response: (
        {"code": 1000}
        if response == b"init"
        else {"payload_msg": {"error": "simulated completion"}}
    )
    provider.decoder = type("Decoder", (), {"decode": lambda *args: b""})()
    conn = FakeConnection()
    conn.asr = provider

    async def scenario():
        heartbeat_count = 0

        async def heartbeat():
            nonlocal heartbeat_count
            for _ in range(10):
                await asyncio.sleep(0.005)
                heartbeat_count += 1

        heartbeat_task = asyncio.create_task(heartbeat())
        started = time.monotonic()
        await provider.receive_audio(conn, b"audio", True)
        first_elapsed = time.monotonic() - started
        await provider.receive_audio(conn, b"audio", True)
        await asyncio.wait_for(provider.forward_task, timeout=0.1)
        await heartbeat_task
        return first_elapsed, heartbeat_count

    elapsed, heartbeat_count = asyncio.run(scenario())

    assert elapsed < 0.1
    assert heartbeat_count == 10
    assert first.closed is True
    assert second.closed is True
    assert provider.asr_ws is None
    assert provider.is_processing is False
    assert conn.reset_count == 1


def test_result_half_open_keeps_heartbeat_and_next_forwarder_starts(tmp_path):
    first = FakeWebSocket(recv_delay=1)
    second = FakeWebSocket(responses=[b"final"])
    provider = _provider(tmp_path, response_timeout_seconds=0.02)
    provider.parse_response = lambda response: {
        "payload_msg": {"error": "simulated completion"}
    }
    provider.asr_ws = first
    provider.is_processing = True
    conn = FakeConnection()

    async def scenario():
        heartbeat = asyncio.create_task(asyncio.sleep(0.05, result="alive"))
        started = time.monotonic()
        await provider._forward_asr_results(conn)
        first_elapsed = time.monotonic() - started

        provider.asr_ws = second
        provider.is_processing = True
        await provider._forward_asr_results(conn)
        return first_elapsed, await heartbeat

    elapsed, heartbeat = asyncio.run(scenario())

    assert elapsed < 0.1
    assert heartbeat == "alive"
    assert first.closed is True
    assert second.closed is True
    assert provider.asr_ws is None
    assert provider.is_processing is False
    assert conn.reset_count == 0


@pytest.mark.parametrize("recognized_text", ["是", "不是", "对", "退下", "停止"])
def test_internal_vad_definite_accepts_short_final_text_without_last(
    monkeypatch, tmp_path, recognized_text
):
    websocket = FinalAfterInternalVadWebSocket()

    async def fake_connect(*args, **kwargs):
        return websocket

    monkeypatch.setattr(doubao_stream.websockets, "connect", fake_connect)
    provider = _provider(tmp_path, final_response_timeout_seconds=0.1)
    provider.decoder = type("Decoder", (), {"decode": lambda _self, data, _size: data})()
    provider.parse_response = lambda response: (
        {"code": 1000}
        if response == b"init"
        else {
            "payload_msg": {
                "result": {
                    "text": recognized_text,
                    "utterances": [
                        {"text": recognized_text, "definite": True}
                    ],
                }
            }
        }
    )
    conn = FakeConnection()
    conn.asr = provider
    handled = []

    async def fake_handle_voice_stop(_conn, audio_data):
        text, _ = await provider.speech_to_text(
            audio_data, "session", "opus"
        )
        handled.append((text, list(audio_data)))
        provider.stop_ws_connection()

    provider.handle_voice_stop = fake_handle_voice_stop

    async def scenario():
        await provider.receive_audio(conn, b"voice-frame", True)
        forward_task = provider.forward_task
        conn.client_have_voice = True
        conn.client_voice_stop = True
        await provider.receive_audio(conn, b"silence-frame", False)
        await asyncio.wait_for(forward_task, timeout=0.2)

    asyncio.run(scenario())

    audio_frames = [
        payload for payload in websocket.sent if payload[1] >> 4 == 0x02
    ]
    regular_frames = [payload for payload in audio_frames if payload[1] & 0x0F == 0]
    last_frames = [payload for payload in audio_frames if payload[1] & 0x0F == 0x02]

    assert len(regular_frames) == 2
    assert len(last_frames) == 0
    assert handled == [(recognized_text, [b"voice-frame", b"silence-frame"])]
    assert websocket.closed is True
    assert conn.reset_count == 1
    assert conn.reset_preserve_voiceprint == [True]
    assert conn.client_voice_stop is False
    assert conn.asr_audio == []
    assert provider.forward_task is None


def test_stop_request_is_idempotent(tmp_path):
    websocket = FakeWebSocket()
    provider = _provider(tmp_path)
    provider.asr_ws = websocket
    provider.is_processing = True

    async def scenario():
        return await asyncio.gather(
            provider._send_stop_request(),
            provider._send_stop_request(),
        )

    first, second = asyncio.run(scenario())

    assert first is True
    assert second is False
    assert len(websocket.sent) == 1
    assert websocket.sent[0][1] == 0x22


def test_stop_request_send_failure_allows_retry(tmp_path):
    provider = _provider(tmp_path)
    provider.asr_ws = FailingSendWebSocket()
    provider.is_processing = True

    sent = asyncio.run(provider._send_stop_request())

    assert sent is False
    assert provider._is_stopping is False
    assert provider._stop_sent_at is None
    assert provider._stop_sent_event.is_set() is False


def test_final_result_timeout_uses_final_budget_and_cleans_state(tmp_path):
    websocket = FakeWebSocket(recv_delay=1)
    provider = _provider(
        tmp_path,
        response_timeout_seconds=1,
        final_response_timeout_seconds=0.01,
    )
    provider.asr_ws = websocket
    provider.is_processing = True
    provider._is_stopping = True
    provider._stop_sent_at = time.monotonic()
    provider._stop_sent_event.set()
    provider.text = "stale partial text"
    conn = FakeConnection()

    started = time.monotonic()
    asyncio.run(provider._forward_asr_results(conn))
    elapsed = time.monotonic() - started

    assert elapsed < 0.1
    assert websocket.closed is True
    assert provider.text == ""
    assert provider.asr_ws is None
    assert provider.is_processing is False
    assert conn.reset_count == 1


def test_old_forwarder_does_not_close_or_reset_new_session(tmp_path):
    old_websocket = FakeWebSocket(responses=[b"old-error"])
    new_websocket = FakeWebSocket()
    provider = _provider(tmp_path)
    provider.parse_response = lambda _response: {
        "payload_msg": {"error": "old session finished"}
    }
    provider.asr_ws = old_websocket
    provider.is_processing = True
    old_stop_event = asyncio.Event()
    conn = FakeConnection()

    async def scenario():
        task = asyncio.create_task(
            provider._forward_asr_results(conn, old_websocket, old_stop_event)
        )
        provider.asr_ws = new_websocket
        provider.is_processing = True
        await task

    asyncio.run(scenario())

    assert old_websocket.closed is True
    assert new_websocket.closed is False
    assert provider.asr_ws is new_websocket
    assert provider.is_processing is True
    assert conn.reset_count == 0
