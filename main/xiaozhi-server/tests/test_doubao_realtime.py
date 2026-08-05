import asyncio
import gzip
import json
import threading
from types import SimpleNamespace

import pytest

from config import settings

settings.config_file_valid = True

from core.conversation.router import ConversationRouter
from core.providers.realtime.doubao_realtime import (
    CHAT_RESPONSE,
    CHAT_TEXT_QUERY,
    CLIENT_INTERRUPT,
    CONNECTION_STARTED,
    DOUBAO_APP_KEY,
    DOUBAO_RESOURCE_ID,
    FINISH_CONNECTION,
    FINISH_SESSION,
    SERIALIZATION_RAW,
    SERVER_ERROR_RESPONSE,
    SERVER_AUDIO_RESPONSE,
    SERVER_FULL_RESPONSE,
    SESSION_STARTED,
    START_CONNECTION,
    START_SESSION,
    TASK_REQUEST,
    TTS_ENDED,
    TTS_RESPONSE,
    TTS_SENTENCE_START,
    DoubaoRealtimeTextProbe,
    DoubaoRealtimeSession,
    DoubaoRealtimeTransport,
    build_client_frame,
    build_start_session,
    parse_server_frame,
    validate_realtime_url,
)
from core.providers.realtime.registry import create_realtime_probe


OFFICIAL_URL = "wss://openspeech.bytedance.com/api/v3/realtime/dialogue"


def _server_frame(event_id, payload, session_id="session-1", audio=False):
    serialization = SERIALIZATION_RAW if audio else 1
    body = bytes(payload) if audio else json.dumps(payload).encode("utf-8")
    body = gzip.compress(body)
    identifier = b"" if event_id == CONNECTION_STARTED else session_id.encode("utf-8")
    frame = bytearray(
        (
            0x11,
            ((SERVER_AUDIO_RESPONSE if audio else SERVER_FULL_RESPONSE) << 4) | 0x4,
            (serialization << 4) | 0x1,
            0x00,
        )
    )
    frame.extend(event_id.to_bytes(4, "big"))
    frame.extend(len(identifier).to_bytes(4, "big"))
    frame.extend(identifier)
    frame.extend(len(body).to_bytes(4, "big"))
    frame.extend(body)
    return bytes(frame)


def test_official_endpoint_is_strictly_allowlisted():
    assert validate_realtime_url(OFFICIAL_URL) == OFFICIAL_URL
    for value in (
        "ws://openspeech.bytedance.com/api/v3/realtime/dialogue",
        "wss://attacker.example/api/v3/realtime/dialogue",
        "wss://openspeech.bytedance.com.evil.test/api/v3/realtime/dialogue",
        "wss://openspeech.bytedance.com/api/v3/realtime/dialogue?key=bad",
        "wss://user:pass@openspeech.bytedance.com/api/v3/realtime/dialogue",
    ):
        with pytest.raises(ValueError):
            validate_realtime_url(value)


def test_start_session_uses_official_audio_contract_and_model_prompt_fields():
    omni = build_start_session(
        {
            "model_name": "1.2.1.1",
            "voice": "zh_female_vv_jupiter_bigtts",
            "input_sample_rate": 16000,
            "output_sample_rate": 24000,
        },
        "你是英语老师",
    )
    assert omni["asr"]["audio_info"] == {
        "format": "pcm",
        "sample_rate": 16000,
        "channel": 1,
    }
    assert omni["tts"]["audio_config"]["format"] == "pcm_s16le"
    assert omni["dialog"]["system_role"] == "你是英语老师"
    assert omni["dialog"]["extra"]["model"] == "1.2.1.1"

    character = build_start_session({"model_name": "2.2.0.0"}, "你是一位船长")
    assert character["dialog"]["character_manifest"] == "你是一位船长"
    assert "system_role" not in character["dialog"]


def test_binary_protocol_round_trip_and_size_limit():
    parsed = parse_server_frame(
        _server_frame(CHAT_RESPONSE, {"content": "你好"}), max_event_bytes=1024
    )
    assert parsed["event"] == CHAT_RESPONSE
    assert parsed["session_id"] == "session-1"
    assert parsed["payload"] == {"content": "你好"}

    audio = parse_server_frame(
        _server_frame(TTS_RESPONSE, b"\x01\x02", audio=True), max_event_bytes=1024
    )
    assert audio["payload"] == b"\x01\x02"

    with pytest.raises(ValueError, match="超过限制"):
        parse_server_frame(_server_frame(TTS_RESPONSE, b"12345", audio=True), 4)


def test_binary_protocol_rejects_decompression_bomb_trailing_bytes_and_parses_error():
    oversized = _server_frame(TTS_RESPONSE, b"x" * 2048, audio=True)
    with pytest.raises(ValueError, match="解压后 payload 超过限制"):
        parse_server_frame(oversized, 1024)

    with pytest.raises(ValueError, match="长度不完整"):
        parse_server_frame(_server_frame(CHAT_RESPONSE, {"content": "OK"}) + b"tail")

    payload = gzip.compress(json.dumps({"message": "denied"}).encode("utf-8"))
    frame = bytearray((0x11, SERVER_ERROR_RESPONSE << 4, 0x11, 0x00))
    frame.extend((45000000).to_bytes(4, "big"))
    frame.extend(len(payload).to_bytes(4, "big"))
    frame.extend(payload)
    parsed = parse_server_frame(bytes(frame))
    assert parsed["type"] == "server.error"
    assert parsed["code"] == 45000000
    assert parsed["payload"] == {"message": "denied"}


def test_client_frames_keep_credentials_out_of_url_and_payload():
    frame = build_client_frame(
        TASK_REQUEST,
        b"pcm",
        session_id="session-1",
        message_type=2,
        serialization=SERIALIZATION_RAW,
    )
    assert frame[0:4] == bytes((0x11, 0x24, 0x01, 0x00))
    assert int.from_bytes(frame[4:8], "big") == TASK_REQUEST
    assert b"api-key" not in frame


def test_transport_uses_required_headers_and_real_start_events():
    class FakeWebSocket:
        def __init__(self):
            self.responses = asyncio.Queue()
            self.sent = []
            self.closed = False

        async def send(self, frame):
            self.sent.append(frame)
            event_id = int.from_bytes(frame[4:8], "big")
            if event_id == START_CONNECTION:
                await self.responses.put(_server_frame(CONNECTION_STARTED, {}))
            elif event_id == START_SESSION:
                await self.responses.put(_server_frame(SESSION_STARTED, {}))

        def __aiter__(self):
            return self

        async def __anext__(self):
            if self.closed:
                raise StopAsyncIteration
            return await self.responses.get()

        async def close(self):
            self.closed = True

    async def scenario():
        websocket = FakeWebSocket()
        captured = {}

        async def factory(url, **kwargs):
            captured["url"] = url
            captured.update(kwargs)
            return websocket

        transport = DoubaoRealtimeTransport(
            {
                "app_id": "app-test",
                "api_key": "access-test",
                "base_url": OFFICIAL_URL,
                "model_name": "1.2.1.1",
            },
            websocket_factory=factory,
        )
        await transport.connect()
        await transport.configure("测试角色")
        headers = captured.get("additional_headers") or captured.get("extra_headers")
        assert captured["url"] == OFFICIAL_URL
        assert headers["X-Api-App-ID"] == "app-test"
        assert headers["X-Api-Access-Key"] == "access-test"
        assert headers["X-Api-Resource-Id"] == DOUBAO_RESOURCE_ID
        assert headers["X-Api-App-Key"] == DOUBAO_APP_KEY
        assert "access-test" not in captured["url"]
        assert [int.from_bytes(item[4:8], "big") for item in websocket.sent[:2]] == [
            START_CONNECTION,
            START_SESSION,
        ]
        await transport.close()
        assert [int.from_bytes(item[4:8], "big") for item in websocket.sent[-2:]] == [
            FINISH_SESSION,
            FINISH_CONNECTION,
        ]
        assert websocket.closed is True

    asyncio.run(scenario())


def test_probe_collects_real_text_audio_and_terminal_event():
    class Transport:
        async def connect(self, handler):
            self.handler = handler

        async def configure(self, *_args, **_kwargs):
            pass

        async def send_event(self, event_id, payload):
            assert event_id == CHAT_TEXT_QUERY
            assert payload == {"content": "你好"}
            await self.handler({"event": CHAT_RESPONSE, "payload": {"content": "您好"}})
            await self.handler({"event": TTS_RESPONSE, "payload": b"pcm"})
            await self.handler({"event": TTS_ENDED, "payload": {}})

        async def close(self):
            pass

    async def scenario():
        probe = DoubaoRealtimeTextProbe({}, transport=Transport())
        await probe.connect()
        result = await probe.turn("你好")
        assert result["output"] == "您好"
        assert result["audio_bytes"] == 3
        assert result["first_audio_ms"] is not None

    asyncio.run(scenario())


def test_doubao_sends_preview_then_complete_subtitle_before_stop(monkeypatch):
    protocol = []

    async def fake_tts(_conn, state, text=None, **_kwargs):
        protocol.append((state, text))
        return True

    async def fake_audio(_conn, _packet):
        return None

    monkeypatch.setattr(
        "core.providers.realtime.doubao_realtime.send_tts_message", fake_tts
    )
    monkeypatch.setattr(
        "core.providers.realtime.doubao_realtime.sendAudio", fake_audio
    )
    monkeypatch.setattr(
        "core.providers.realtime.doubao_realtime.enqueue_tts_report",
        lambda *_args, **_kwargs: True,
    )

    class Encoder:
        def encode_pcm_to_opus_stream(self, data, is_end, callback):
            if data and not is_end:
                callback(b"opus")

    async def scenario():
        conn = SimpleNamespace(
            sentence_id=None,
            dialogue=SimpleNamespace(put=lambda _message: None),
        )
        session = object.__new__(DoubaoRealtimeSession)
        session.conn = conn
        session.encoder = Encoder()
        session.response_audio = []
        session.response_text = ""
        session.last_response_text = ""
        session._subtitle_text_sent = ""
        session._reply_started = False
        session._response_active = True
        session._context_reset_pending = False
        session._pending_voice = None
        session._session_maintenance_task = None
        session.response_done = asyncio.Event()
        await session.handle_event(
            {"event": TTS_SENTENCE_START, "payload": {"text": "您"}}
        )
        await session.handle_event({"event": TTS_RESPONSE, "payload": b"pcm"})
        session.response_text = "您好，这是完整回答"
        await session.handle_event({"event": TTS_ENDED, "payload": {}})

    asyncio.run(scenario())
    assert protocol == [
        ("start", None),
        ("sentence_start", "您"),
        ("sentence_start", "您好，这是完整回答"),
        ("stop", None),
    ]


def test_registry_and_router_select_doubao_without_changing_model_id_contract():
    assert isinstance(
        create_realtime_probe({"type": "doubao_realtime"}),
        DoubaoRealtimeTextProbe,
    )

    created = []

    class Session:
        def __init__(self, _conn, config):
            created.append(config["type"])

        async def initialize(self):
            pass

    class Conn:
        config = {
            "selected_module": {"LLM": "LLM_DoubaoRealtime"},
            "model_config": {
                "LLM_DoubaoRealtime": {
                    "model_type": "LLM",
                    "typed_config": {
                        "type": "doubao_realtime",
                        "conversation_mode": "realtime",
                    },
                }
            },
        }

    async def scenario():
        router = ConversationRouter(Conn(), session_factory=Session)
        assert await router.initialize() is True

    asyncio.run(scenario())
    assert created == ["doubao_realtime"]


def test_doubao_natural_barge_in_uses_interrupt_and_preserves_new_audio(monkeypatch):
    sent = []
    convergence = []

    class Transport:
        async def send_event(self, event_id, payload):
            sent.append((event_id, payload))

    class Encoder:
        def reset_state(self):
            convergence.append("encoder_reset")

    class Logger:
        def bind(self, **_kwargs):
            return self

        def warning(self, *_args, **_kwargs):
            pass

    async def fake_abort(_conn, preserve_user_audio=False):
        convergence.append(("local_abort", preserve_user_audio))

    monkeypatch.setattr(
        "core.providers.realtime.doubao_realtime.handleAbortMessage", fake_abort
    )

    async def scenario():
        session = object.__new__(DoubaoRealtimeSession)
        session.conn = SimpleNamespace(logger=Logger())
        session.transport = Transport()
        session.encoder = Encoder()
        session.response_text = "partial"
        session.response_audio = [b"reply"]
        session.input_audio = [b"new-user-audio"]
        session._barge_in_active = False
        session._reply_started = True
        session._response_active = True
        session.response_done = asyncio.Event()

        await session._handle_natural_barge_in()

        assert sent == [(CLIENT_INTERRUPT, {})]
        assert session.input_audio == [b"new-user-audio"]
        assert session._response_active is False
        assert session.response_done.is_set()
        assert convergence == ["encoder_reset", ("local_abort", True)]

    asyncio.run(scenario())


def test_doubao_context_reset_and_voice_change_rebuild_upstream_session():
    transports = []

    class Logger:
        def bind(self, **_kwargs):
            return self

        def warning(self, *_args, **_kwargs):
            pass

    class Transport:
        def __init__(self, label):
            self.label = label
            self.closed = False
            self.configured = []
            transports.append(self)

        async def connect(self, handler):
            self.handler = handler

        async def configure(self, prompt, tools=None, config_override=None):
            self.configured.append((prompt, tools, config_override))

        async def close(self):
            self.closed = True

    async def scenario():
        old = Transport("old")
        conn = SimpleNamespace(
            logger=Logger(),
            prompt="system prompt",
            prompt_manager=None,
            config={"selected_module": {}},
            func_handler=None,
            audio_input_ready=threading.Event(),
            conversation_ready=threading.Event(),
        )
        conn.audio_input_ready.set()
        conn.conversation_ready.set()
        session = DoubaoRealtimeSession(
            conn,
            {"voice": "old-voice"},
            transport=old,
            transport_factory=lambda: Transport("new"),
        )
        session._response_active = True
        assert session.request_voice_change("account-enabled-voice") is True
        assert session.request_context_reset() is True
        session._response_active = False

        assert await session.reset_context_now() is True

        assert old.closed is True
        assert session.transport.label == "new"
        assert session.transport.configured[0][0] == "system prompt"
        assert session.current_voice() == "account-enabled-voice"
        assert conn.audio_input_ready.is_set()
        assert conn.conversation_ready.is_set()
        await session.close()

    asyncio.run(scenario())
