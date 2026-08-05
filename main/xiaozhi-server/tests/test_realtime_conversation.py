import asyncio
import json
import threading
from types import MethodType, SimpleNamespace

import pytest
import base64

from config import settings

settings.config_file_valid = True

from core.benchmark import runner
from core.connection import ConnectionHandler
from core.conversation.mode import ConversationMode, get_conversation_mode
from core.conversation.router import ConversationRouter
from core.providers.realtime.base import realtime_tools
from core.providers.realtime.registry import realtime_adapter_capabilities
from core.providers.realtime.qwen_omni_realtime import (
    QwenOmniRealtimeSession,
    QwenOmniRealtimeTransport,
    QwenRealtimeTextProbe,
    build_audio_append,
    build_image_append,
    build_instruction_prompt,
    build_manual_turn_events,
    build_session_update,
    validate_realtime_url,
)
from core.utils import modules_initialize
from core.providers.tools.unified_tool_manager import ToolManager
from core.handle.textHandler.abortMessageHandler import AbortTextMessageHandler
from core.handle.textHandler.listenMessageHandler import ListenTextMessageHandler


def _runtime_config(mode="realtime", adapter="qwen_omni_realtime"):
    return {
        "xiaozhi": {
            "type": "hello",
            "audio_params": {
                "format": "opus",
                "sample_rate": 24000,
                "channels": 1,
                "frame_duration": 60,
            },
        },
        "selected_module": {"LLM": "LLM_QWEN_RT"},
        "model_config": {
            "LLM_QWEN_RT": {
                "model_type": "LLM",
                "typed_config": {
                    "type": adapter,
                    "conversation_mode": mode,
                    "base_url": "wss://workspace-a.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime",
                    "model_name": "qwen3.5-omni-flash-realtime",
                    "api_key": "test-key",
                    "input_sample_rate": 16000,
                    "output_sample_rate": 24000,
                },
            }
        },
        "prompt": "你是测试助手",
        "dialogue": {},
    }


class _Logger:
    def bind(self, **_kwargs):
        return self

    def debug(self, *_args, **_kwargs):
        pass

    def info(self, *_args, **_kwargs):
        pass

    def warning(self, *_args, **_kwargs):
        pass

    def error(self, *_args, **_kwargs):
        pass


def test_conversation_mode_uses_selected_llm_typed_config():
    assert get_conversation_mode(_runtime_config()) is ConversationMode.REALTIME
    assert (
        get_conversation_mode(_runtime_config(mode="pipeline"))
        is ConversationMode.PIPELINE
    )


def test_realtime_capabilities_are_adapter_driven():
    qwen = realtime_adapter_capabilities({"type": "qwen-omni-realtime"})
    doubao = realtime_adapter_capabilities({"type": "doubao-realtime"})

    assert qwen.native_function_calling is True
    assert qwen.natural_barge_in is True
    assert doubao.native_function_calling is False
    assert doubao.natural_barge_in is True
    assert doubao.context_reset is True
    assert doubao.session_voice is True


def test_realtime_protocol_events_and_audio_contract():
    config = _runtime_config()["model_config"]["LLM_QWEN_RT"]["typed_config"]
    update = build_session_update(
        config,
        "system prompt",
        [
            {
                "type": "function",
                "function": {
                    "name": "get_weather",
                    "description": "weather",
                    "parameters": {"type": "object"},
                },
            }
        ],
    )
    assert update["session"]["input_audio_format"] == "pcm"
    assert update["session"]["output_audio_format"] == "pcm"
    assert update["session"]["voice"] == "Ethan"
    assert update["session"]["input_audio_transcription"] == {}
    assert update["session"]["tools"] == [
        {
            "type": "function",
            "function": {
                "name": "get_weather",
                "description": "weather",
                "parameters": {"type": "object"},
            },
        }
    ]
    assert build_audio_append(b"pcm")["type"] == "input_audio_buffer.append"
    assert build_image_append(b"\xff\xd8\xffjpg")["type"] == "input_image_buffer.append"
    with pytest.raises(ValueError, match="JPG/JPEG"):
        build_image_append(b"png")

    prompt = build_instruction_prompt("system", "hello")
    assert "<current_user_request>\nhello" in prompt
    assert [item["type"] for item in build_manual_turn_events()] == [
        "input_audio_buffer.clear",
        "input_audio_buffer.append",
        "input_audio_buffer.commit",
        "response.create",
    ]
    assert [item["type"] for item in build_manual_turn_events(b"\xff\xd8\xffjpg")] == [
        "input_audio_buffer.clear",
        "input_audio_buffer.append",
        "input_image_buffer.append",
        "input_audio_buffer.commit",
        "response.create",
    ]

    invalid = dict(config, input_sample_rate=24000)
    with pytest.raises(ValueError, match="输入16kHz"):
        build_session_update(invalid, "", [])


@pytest.mark.parametrize(
    "url",
    [
        "ws://dashscope.aliyuncs.com/api-ws/v1/realtime",
        "wss://example.com/api-ws/v1/realtime",
        "wss://dashscope.aliyuncs.com:8443/api-ws/v1/realtime",
        "wss://user:pass@dashscope.aliyuncs.com/api-ws/v1/realtime",
        "wss://dashscope.aliyuncs.com/other",
        "wss://workspace-a.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime?token=bad",
        "wss://workspace-a.us-west-1.maas.aliyuncs.com/api-ws/v1/realtime",
    ],
)
def test_realtime_url_rejects_credential_exfiltration_targets(url):
    with pytest.raises(ValueError):
        validate_realtime_url(url)


@pytest.mark.parametrize(
    "url",
    [
        "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
        "wss://workspace-a.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime",
        "wss://workspace-b.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime",
    ],
)
def test_realtime_url_accepts_only_official_legacy_and_workspace_hosts(url):
    assert validate_realtime_url(url) == url


def test_modules_initialize_skips_legacy_llm_and_tts_for_realtime(monkeypatch):
    config = _runtime_config()
    config["selected_module"]["TTS"] = "TTS_TEST"
    config["model_config"]["TTS_TEST"] = {
        "model_type": "TTS",
        "typed_config": {"type": "would_fail"},
    }
    monkeypatch.setattr(
        modules_initialize.llm,
        "create_instance",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("legacy llm")),
    )
    monkeypatch.setattr(
        modules_initialize,
        "initialize_tts",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("legacy tts")),
    )
    assert modules_initialize.initialize_modules(
        _Logger(), config, init_llm=True, init_tts=True
    ) == {}


class _FakeSocket:
    def __init__(self):
        self.queue = asyncio.Queue()
        self.sent = []
        self.closed = False

    def __aiter__(self):
        return self

    async def __anext__(self):
        item = await self.queue.get()
        if item is StopAsyncIteration:
            raise StopAsyncIteration
        return item

    async def send(self, raw):
        payload = json.loads(raw)
        self.sent.append(payload)
        if payload["type"] == "session.update":
            await self.queue.put(json.dumps({"type": "session.updated"}))

    async def close(self):
        self.closed = True
        await self.queue.put(StopAsyncIteration)


def test_transport_registers_waiters_before_fast_events():
    async def scenario():
        socket = _FakeSocket()

        async def factory(_url, **kwargs):
            assert kwargs["extra_headers"] == {
                "Authorization": "Bearer test-key",
                "X-DashScope-OmniRealtime": "true",
            }
            await socket.queue.put(json.dumps({"type": "session.created"}))
            return socket

        config = _runtime_config()["model_config"]["LLM_QWEN_RT"]["typed_config"]
        transport = QwenOmniRealtimeTransport(
            config, websocket_factory=factory
        )
        await transport.connect()
        await transport.configure("prompt", [])
        assert [item["type"] for item in socket.sent] == ["session.update"]
        await transport.close()
        assert socket.closed is True

    asyncio.run(scenario())


def test_transport_supports_websockets_14_additional_headers_signature():
    async def scenario():
        socket = _FakeSocket()

        async def factory(
            _url, *, additional_headers=None, max_size=None, open_timeout=None
        ):
            assert additional_headers == {
                "Authorization": "Bearer test-key",
                "X-DashScope-OmniRealtime": "true",
            }
            assert max_size == 10 * 1024 * 1024
            await socket.queue.put(json.dumps({"type": "session.created"}))
            return socket

        config = _runtime_config()["model_config"]["LLM_QWEN_RT"]["typed_config"]
        transport = QwenOmniRealtimeTransport(config, websocket_factory=factory)
        await transport.connect()
        await transport.close()

    asyncio.run(scenario())


def test_transport_close_rejects_pending_event_waiters():
    async def scenario():
        socket = _FakeSocket()
        config = _runtime_config()["model_config"]["LLM_QWEN_RT"]["typed_config"]
        transport = QwenOmniRealtimeTransport(config)
        transport.websocket = socket
        waiter = asyncio.create_task(transport.wait_for("session.updated", timeout=5))
        await asyncio.sleep(0)
        await transport.close()
        with pytest.raises(ConnectionError, match="已关闭"):
            await waiter

    asyncio.run(scenario())


def test_transport_normal_upstream_close_notifies_session():
    async def scenario():
        socket = _FakeSocket()
        events = []
        config = _runtime_config()["model_config"]["LLM_QWEN_RT"]["typed_config"]
        transport = QwenOmniRealtimeTransport(config)
        transport.websocket = socket
        transport.event_handler = lambda payload: _record_event(events, payload)
        reader = asyncio.create_task(transport._reader())
        await socket.queue.put(StopAsyncIteration)
        await reader
        assert events == ["transport.closed"]

    async def _record_event(events, payload):
        events.append(payload["type"])

    asyncio.run(scenario())


def test_text_probe_uses_manual_audio_commit_instead_of_user_text_item():
    probe_holder = {}

    class Transport:
        def __init__(self):
            self.configured = []
            self.sent = []

        async def configure(self, prompt, tools, config_override=None):
            self.configured.append((prompt, tools, config_override))

        async def send(self, payload):
            self.sent.append(payload)
            if payload["type"] == "response.create":
                probe = probe_holder["probe"]
                await probe._on_event(
                    {"type": "response.text.delta", "delta": "ok"}
                )
                await probe._on_event({"type": "response.done"})

    async def scenario():
        transport = Transport()
        probe = QwenRealtimeTextProbe(
            {"prompt": "system", "response_timeout_seconds": 1},
            transport=transport,
        )
        probe_holder["probe"] = probe
        result = await probe.turn("hello")
        assert result["output"] == "ok"
        assert "<current_user_request>\nhello" in transport.configured[0][0]
        assert transport.configured[0][2] == {"turn_detection_type": "manual"}
        assert [item["type"] for item in transport.sent] == [
            "input_audio_buffer.clear",
            "input_audio_buffer.append",
            "input_audio_buffer.commit",
            "response.create",
        ]
        assert all(
            item.get("item", {}).get("type") != "message"
            for item in transport.sent
        )

    asyncio.run(scenario())


def test_router_only_consumes_messages_in_realtime_mode():
    events = []

    class FakeSession:
        def __init__(self, _conn, _config):
            pass

        async def initialize(self):
            events.append("initialize")

        async def handle_audio(self, audio):
            events.append(("audio", audio))
            return True

        async def handle_text(self, text):
            events.append(("text", text))
            return True

        async def close(self):
            events.append("close")

    async def scenario():
        conn = SimpleNamespace(
            config=_runtime_config(),
            func_handler=SimpleNamespace(get_tool_statistics=lambda: {}),
        )
        router = ConversationRouter(conn, session_factory=FakeSession)
        assert await router.initialize() is True
        assert await router.handle_audio(b"opus") is True
        assert await router.handle_text("hello") is True
        await router.close()

        legacy = ConversationRouter(
            SimpleNamespace(config=_runtime_config(mode="pipeline")),
            session_factory=FakeSession,
        )
        assert await legacy.initialize() is False
        assert await legacy.handle_audio(b"opus") is False

    asyncio.run(scenario())
    assert events == ["initialize", ("audio", b"opus"), ("text", "hello"), "close"]


def test_router_initializes_native_realtime_tool_runtime_without_legacy_intent(monkeypatch):
    initialized = []

    class FakeToolHandler:
        def __init__(self, conn):
            self.conn = conn

        async def _initialize(self):
            initialized.append("tools")

        def get_tool_statistics(self):
            return {"mcp_endpoint": 1}

    class FakeSession:
        def __init__(self, _conn, _config):
            pass

        async def initialize(self):
            initialized.append("session")

    monkeypatch.setattr(
        "core.providers.tools.unified_tool_handler.UnifiedToolHandler",
        FakeToolHandler,
    )

    async def scenario():
        conn = SimpleNamespace(config=_runtime_config(), func_handler=None, logger=_Logger())
        router = ConversationRouter(conn, session_factory=FakeSession)
        assert await router.initialize() is True
        assert isinstance(conn.func_handler, FakeToolHandler)

    asyncio.run(scenario())
    assert initialized == ["tools", "session"]


def test_function_call_completion_is_deduplicated():
    class ToolHandler:
        def __init__(self):
            self.calls = 0

        async def handle_llm_function_call(self, _conn, _call):
            self.calls += 1
            return SimpleNamespace(action=None, result="ok", response=None)

    class Transport:
        def __init__(self):
            self.sent = []

        async def send(self, payload):
            self.sent.append(payload)

    async def scenario():
        handler = ToolHandler()
        transport = Transport()
        conn = SimpleNamespace(func_handler=handler)
        session = object.__new__(QwenOmniRealtimeSession)
        session.conn = conn
        session.transport = transport
        session.function_calls = {}
        session.completed_function_calls = set()
        session._tool_followup_pending = False
        session._cancel_generation = 0
        session._response_active = True
        payload = {
            "type": "response.function_call_arguments.done",
            "call_id": "call-1",
            "name": "tool",
            "arguments": "{}",
        }
        await session._handle_function_call(payload)
        await session._handle_function_call(payload)
        second = dict(payload, call_id="call-2", name="other_tool")
        await session._handle_function_call(second)
        assert handler.calls == 0
        assert transport.sent == []
        await session.handle_event(
            {
                "type": "response.done",
                "response": {"output": [{"type": "function_call"}]},
            }
        )
        assert handler.calls == 2
        assert [item["type"] for item in transport.sent] == [
            "conversation.item.create",
            "conversation.item.create",
            "response.create",
        ]

    asyncio.run(scenario())


def test_invalid_opus_frame_is_dropped_without_closing_session():
    class Decoder:
        def decode(self, *_args):
            raise __import__("opuslib_next").OpusError("bad frame")

    async def scenario():
        session = object.__new__(QwenOmniRealtimeSession)
        session.decoder = Decoder()
        session.conn = SimpleNamespace(logger=_Logger())
        assert await session.handle_audio(b"bad-opus") is True

    asyncio.run(scenario())


def test_finish_response_releases_untranscribed_input_audio(monkeypatch):
    async def scenario():
        session = object.__new__(QwenOmniRealtimeSession)
        session.encoder = SimpleNamespace(
            encode_pcm_to_opus_stream=lambda *_args: None
        )
        session.response_text = ""
        session.response_audio = []
        session.input_audio = [b"one", b"two"]
        session._reply_started = False
        session._response_active = True
        session.response_done = asyncio.Event()
        await session._finish_response()
        assert session.input_audio == []
        assert session.response_done.is_set()

    asyncio.run(scenario())


def test_pcm24k_audio_response_updates_complete_subtitle_before_single_stop(monkeypatch):
    protocol = []
    packets = []

    async def fake_tts(_conn, state, text=None, **_kwargs):
        protocol.append((state, text))
        return True

    async def fake_audio(_conn, packet):
        packets.append(packet)

    monkeypatch.setattr(
        "core.providers.realtime.qwen_omni_realtime.send_tts_message", fake_tts
    )
    monkeypatch.setattr(
        "core.providers.realtime.qwen_omni_realtime.sendAudio", fake_audio
    )
    monkeypatch.setattr(
        "core.providers.realtime.qwen_omni_realtime.enqueue_tts_report",
        lambda *_args, **_kwargs: True,
    )

    async def scenario():
        conn = SimpleNamespace(
            logger=_Logger(),
            func_handler=None,
            prompt="",
            config={"prompt": {}, "selected_module": {}, "Memory": {}},
            sentence_id=None,
            dialogue=SimpleNamespace(put=lambda _message: None),
        )
        session = QwenOmniRealtimeSession(conn, {})
        session._response_active = True
        pcm = b"\x00\x00" * 1440  # 24kHz * 60ms mono PCM16
        await session.handle_event(
            {
                "type": "response.audio.delta",
                "delta": base64.b64encode(pcm).decode("ascii"),
            }
        )
        session.response_text = "你好"
        await session.handle_event({"type": "response.done", "response": {}})
        await session.handle_event({"type": "response.done", "response": {}})
        session.encoder.close()

    asyncio.run(scenario())
    assert [state for state, _text in protocol] == [
        "start",
        "sentence_start",
        "stop",
    ]
    assert protocol[1][1] == "你好"
    assert len(packets) == 1


def test_qwen_sends_stream_preview_then_complete_subtitle_before_stop(monkeypatch):
    protocol = []

    async def fake_tts(_conn, state, text=None, **_kwargs):
        protocol.append((state, text))
        return True

    async def fake_audio(_conn, _packet):
        return None

    monkeypatch.setattr(
        "core.providers.realtime.qwen_omni_realtime.send_tts_message", fake_tts
    )
    monkeypatch.setattr(
        "core.providers.realtime.qwen_omni_realtime.sendAudio", fake_audio
    )
    monkeypatch.setattr(
        "core.providers.realtime.qwen_omni_realtime.enqueue_tts_report",
        lambda *_args, **_kwargs: True,
    )

    async def scenario():
        conn = SimpleNamespace(
            logger=_Logger(),
            func_handler=None,
            prompt="",
            config={"prompt": {}, "selected_module": {}, "Memory": {}},
            sentence_id=None,
            dialogue=SimpleNamespace(put=lambda _message: None),
        )
        session = QwenOmniRealtimeSession(conn, {})
        session._response_active = True
        await session.handle_event(
            {"type": "response.audio_transcript.delta", "delta": "您"}
        )
        await session.handle_event(
            {
                "type": "response.audio.delta",
                "delta": base64.b64encode(b"\x00\x00" * 1440).decode("ascii"),
            }
        )
        await session.handle_event(
            {
                "type": "response.audio_transcript.done",
                "transcript": "您好，这是完整回答",
            }
        )
        await session.handle_event({"type": "response.done", "response": {}})
        session.encoder.close()

    asyncio.run(scenario())
    assert protocol == [
        ("start", None),
        ("sentence_start", "您"),
        ("sentence_start", "您好，这是完整回答"),
        ("stop", None),
    ]


def test_late_audio_after_response_done_is_ignored(monkeypatch):
    sent = []

    async def fake_audio(_conn, packet):
        sent.append(packet)

    monkeypatch.setattr(
        "core.providers.realtime.qwen_omni_realtime.sendAudio", fake_audio
    )

    async def scenario():
        conn = SimpleNamespace(logger=_Logger())
        session = object.__new__(QwenOmniRealtimeSession)
        session.conn = conn
        session._response_active = False
        await session.handle_event(
            {
                "type": "response.audio.delta",
                "delta": base64.b64encode(b"\x00\x00" * 1440).decode("ascii"),
            }
        )

    asyncio.run(scenario())
    assert sent == []


def test_connection_realtime_initialization_sets_all_readiness_after_session():
    initialized = []

    class FakeSession:
        def __init__(self, _conn, _config):
            pass

        async def initialize(self):
            initialized.append("upstream_ready")

        async def close(self):
            pass

    async def scenario():
        conn = ConnectionHandler(_runtime_config(), None, None, None, None, None)
        conn.loop = asyncio.get_running_loop()
        conn.need_bind = False
        conn.conversation_router = ConversationRouter(conn, session_factory=FakeSession)
        conn._init_report_threads = MethodType(lambda self: None, conn)
        conn._init_voiceprint_enrollment_from_config = MethodType(lambda self: None, conn)
        conn._init_prompt_enhancement = MethodType(lambda self: None, conn)
        conn._inject_tool_call_fewshot = MethodType(lambda self: None, conn)

        async def flush(self):
            initialized.append("detects_replayed")
            return 0

        conn.flush_deferred_listen_detects = MethodType(flush, conn)
        assert await asyncio.to_thread(conn._initialize_components) is True
        await asyncio.sleep(0)
        assert initialized == ["upstream_ready", "detects_replayed"]
        assert conn.audio_input_ready.is_set()
        assert conn.tts_ready.is_set()
        assert conn.conversation_ready.is_set()
        assert conn.tts is None and conn.asr is None and conn.llm is None

    asyncio.run(scenario())


def test_benchmark_routes_realtime_without_legacy_llm(monkeypatch):
    instances = []

    class FakeProbe:
        def __init__(self, _config):
            self.turns = []
            instances.append(self)

        async def connect(self):
            return 5

        async def turn(self, text):
            self.turns.append(text)
            return {
                "output": f"reply:{text}",
                "connect_ms": 5,
                "ttft_ms": 8,
                "first_audio_ms": 10,
                "audio_bytes": 4800,
                "latency_ms": 20,
            }

        async def close(self):
            pass

    def fake_judge(inputs, *_args):
        return [
            {
                "success": True,
                "measured": True,
                "latency_ms": item["latency_ms"],
                "quality_score": 1,
                "output_detail": {},
            }
            for item in inputs
        ]

    monkeypatch.setattr(runner, "create_realtime_probe", lambda *_args: FakeProbe({}))
    monkeypatch.setattr(runner, "evaluate_llm_case_batch", fake_judge)
    monkeypatch.setattr(
        runner,
        "create_llm_instance",
        lambda *_args: (_ for _ in ()).throw(AssertionError("legacy llm initialized")),
    )
    result = asyncio.run(
        runner._run_llm(
            {"id": "rt", "model_type": "LLM"},
            "qwen_omni_realtime",
            {"conversation_mode": "realtime"},
            1,
            benchmark_cases=[{"scenario": "test", "turns": ["hello", "again"]}],
        )
    )
    assert result["benchmark_level"] == "REAL_INFERENCE"
    assert result["success_count"] == 1
    sample = result["samples"][0]
    assert sample["first_audio_ms"] == 10
    assert sample["output_detail"]["audio_bytes"] == 9600
    assert len(instances) == 1
    assert instances[0].turns == ["hello", "again"]


def test_realtime_benchmark_sample_redacts_provider_credentials(monkeypatch):
    class ErrorProbe:
        def __init__(self, _config):
            pass

        async def connect(self):
            raise RuntimeError(
                "api_key='sk-secret' Authorization: Bearer bearer-secret"
            )

        async def close(self):
            pass

    monkeypatch.setattr(runner, "create_realtime_probe", lambda *_args: ErrorProbe({}))
    monkeypatch.setattr(
        runner,
        "evaluate_llm_case_batch",
        lambda inputs, *_args: [
            {
                "success": False,
                "measured": False,
                "latency_ms": item["latency_ms"],
                "output_detail": {},
            }
            for item in inputs
        ],
    )
    result = asyncio.run(
        runner._run_realtime_llm(
            {"id": "rt", "model_type": "LLM"},
            "qwen_omni_realtime",
            {"conversation_mode": "realtime"},
            1,
            benchmark_cases=[{"scenario": "test", "turns": ["hello"]}],
        )
    )
    serialized = json.dumps(result, ensure_ascii=False)
    assert "sk-secret" not in serialized
    assert "bearer-secret" not in serialized
    assert "***" in serialized


def test_realtime_image_rejects_an_active_response():
    async def scenario():
        session = object.__new__(QwenOmniRealtimeSession)
        session._turn_lock = asyncio.Lock()
        session._response_active = True
        with pytest.raises(RuntimeError, match="不能并发提交图片"):
            await session.handle_image("question", b"image")

    asyncio.run(scenario())


def test_realtime_abort_runs_upstream_and_common_state_convergence(monkeypatch):
    calls = []

    class Router:
        is_realtime = True

        async def handle_abort(self):
            calls.append("upstream_cancel")

    async def common_abort(conn):
        conn.client_abort = True
        conn.abort_generation += 1
        calls.append("common_abort")

    monkeypatch.setattr(
        "core.handle.textHandler.abortMessageHandler.handleAbortMessage", common_abort
    )
    conn = SimpleNamespace(
        conversation_router=Router(), client_abort=False, abort_generation=4
    )
    asyncio.run(AbortTextMessageHandler().handle(conn, {"type": "abort"}))
    assert calls == ["upstream_cancel", "common_abort"]
    assert conn.client_abort is True
    assert conn.abort_generation == 5


def test_realtime_abort_still_converges_when_upstream_cancel_fails(monkeypatch):
    calls = []

    class Router:
        is_realtime = True

        async def handle_abort(self):
            raise ConnectionError("upstream closed")

    async def common_abort(conn):
        conn.client_abort = True
        calls.append("common_abort")

    monkeypatch.setattr(
        "core.handle.textHandler.abortMessageHandler.handleAbortMessage", common_abort
    )
    conn = SimpleNamespace(
        conversation_router=Router(), client_abort=False, logger=_Logger()
    )
    asyncio.run(AbortTextMessageHandler().handle(conn, {"type": "abort"}))
    assert calls == ["common_abort"]
    assert conn.client_abort is True


def test_realtime_session_abort_clears_local_state_when_transport_fails():
    class Transport:
        async def send(self, _payload):
            raise ConnectionError("closed")

    async def scenario():
        session = object.__new__(QwenOmniRealtimeSession)
        session.transport = Transport()
        session._cancel_generation = 0
        session.function_calls = {"call": {"name": "tool", "arguments": "{}"}}
        session._tool_followup_pending = True
        session.response_text = "partial"
        session.response_audio = [b"audio"]
        session.input_audio = [b"input"]
        session._reply_started = True
        session._response_active = True
        session.response_done = asyncio.Event()
        with pytest.raises(ConnectionError):
            await session.handle_abort()
        assert session._cancel_generation == 1
        assert session.function_calls == {}
        assert session.response_text == ""
        assert session.response_audio == []
        assert session.input_audio == []
        assert session._response_active is False
        assert session.response_done.is_set()

    asyncio.run(scenario())


def test_qwen_server_vad_speech_start_cancels_reply_without_clearing_new_audio(
    monkeypatch,
):
    sent = []
    aborted = []

    class Transport:
        async def send(self, payload):
            sent.append(payload)

    class Encoder:
        def reset_state(self):
            aborted.append("encoder_reset")

    async def fake_abort(_conn, preserve_user_audio=False):
        aborted.append(("local_abort", preserve_user_audio))

    monkeypatch.setattr(
        "core.providers.realtime.qwen_omni_realtime.handleAbortMessage", fake_abort
    )

    async def scenario():
        session = object.__new__(QwenOmniRealtimeSession)
        session.conn = SimpleNamespace(logger=_Logger())
        session.transport = Transport()
        session.encoder = Encoder()
        session.response_text = "partial"
        session.response_audio = [b"reply"]
        session.input_audio = [b"new-user-audio"]
        session.function_calls = {}
        session._tool_followup_pending = False
        session._cancel_generation = 2
        session._barge_in_active = False
        session._reply_started = True
        session._response_active = True
        session.response_done = asyncio.Event()

        await session.handle_event({"type": "input_audio_buffer.speech_started"})

        assert [item["type"] for item in sent] == ["response.cancel"]
        assert session.input_audio == [b"new-user-audio"]
        assert session._response_active is False
        assert session.response_done.is_set()
        assert aborted == ["encoder_reset", ("local_abort", True)]

    asyncio.run(scenario())


def test_qwen_context_reset_reconnects_with_current_prompt_tools_and_voice():
    transports = []

    class Transport:
        def __init__(self, label):
            self.label = label
            self.closed = False
            self.configured = []
            transports.append(self)

        async def connect(self, handler):
            self.handler = handler

        async def configure(self, prompt, tools, config_override=None):
            self.configured.append((prompt, tools, config_override))

        async def close(self):
            self.closed = True

    async def scenario():
        old = Transport("old")
        conn = SimpleNamespace(
            logger=_Logger(),
            prompt="system prompt",
            config={"selected_module": {}, "Memory": {}},
            func_handler=SimpleNamespace(get_functions=lambda: [{"type": "function"}]),
            audio_input_ready=threading.Event(),
            conversation_ready=threading.Event(),
        )
        conn.audio_input_ready.set()
        conn.conversation_ready.set()
        session = QwenOmniRealtimeSession(
            conn,
            {"voice": "Serena"},
            transport=old,
            transport_factory=lambda: Transport("new"),
        )
        assert await session.reset_context_now() is True

        assert old.closed is True
        assert session.transport.label == "new"
        assert session.transport.configured[0][0] == "system prompt"
        assert conn.audio_input_ready.is_set()
        assert conn.conversation_ready.is_set()
        assert session.current_voice() == "Serena"
        await session.close()

    asyncio.run(scenario())


def test_qwen_runtime_voice_change_updates_next_upstream_session_config():
    class Transport:
        def __init__(self):
            self.config = {"voice": "Ethan"}
            self.configured = []

        async def configure(self, prompt, tools, config_override=None):
            self.configured.append((prompt, tools, config_override))

    async def scenario():
        transport = Transport()
        conn = SimpleNamespace(
            logger=_Logger(),
            prompt="system prompt",
            config={"selected_module": {}, "Memory": {}},
            func_handler=None,
        )
        session = QwenOmniRealtimeSession(conn, {}, transport=transport)
        session._response_active = True

        assert session.request_voice_change("Serena") is True
        await session._apply_pending_session_controls()

        assert transport.configured[-1][2] == {"voice": "Serena"}
        assert transport.config["voice"] == "Serena"
        session.encoder.close()

    asyncio.run(scenario())


def test_transport_disconnect_clears_readiness_and_releases_active_turn():
    async def scenario():
        conn = SimpleNamespace(
            audio_input_ready=threading.Event(),
            conversation_ready=threading.Event(),
        )
        conn.audio_input_ready.set()
        conn.conversation_ready.set()
        session = object.__new__(QwenOmniRealtimeSession)
        session.conn = conn
        session._response_active = True
        session.response_done = asyncio.Event()
        finished = []

        async def finish_response(self, reason=None):
            finished.append(reason)
            self._response_active = False
            self.response_done.set()

        session._finish_response = MethodType(finish_response, session)
        await session.handle_event(
            {"type": "transport.closed", "error": {"code": "ConnectionError"}}
        )
        assert not conn.audio_input_ready.is_set()
        assert not conn.conversation_ready.is_set()
        assert session.response_done.is_set()
        assert finished == ["realtime_error"]

    asyncio.run(scenario())


def test_realtime_listen_start_refreshes_before_routing():
    calls = []

    class Reloader:
        async def refresh_if_due(self, reason):
            calls.append(("refresh", reason))

    class Router:
        is_realtime = True

        async def handle_listen(self, message):
            calls.append(("route", message["state"]))

    conn = SimpleNamespace(
        client_listen_mode="auto",
        logger=_Logger(),
        runtime_config_reloader=Reloader(),
        conversation_router=Router(),
        conversation_ready=threading.Event(),
    )
    conn.conversation_ready.set()
    asyncio.run(
        ListenTextMessageHandler().handle(
            conn, {"type": "listen", "state": "start", "mode": "auto"}
        )
    )
    assert calls == [("refresh", "listen_start"), ("route", "start")]


def test_realtime_session_prompt_includes_bounded_summary_memory():
    session = object.__new__(QwenOmniRealtimeSession)
    session.conn = SimpleNamespace(
        prompt="base prompt",
        config={"summaryMemory": "x" * 5000},
    )
    prompt = session._session_prompt()
    assert prompt.startswith("base prompt\n\n<realtime_summary_memory>")
    assert "[上下文已按预算截断]" in prompt
    assert len(prompt) < 4200


def test_tool_cache_refresh_schedules_realtime_session_update():
    calls = []

    class Router:
        is_realtime = True
        session = object()

        async def refresh_tools(self):
            calls.append("refresh")

    async def scenario():
        conn = SimpleNamespace(
            conversation_router=Router(), loop=asyncio.get_running_loop()
        )
        manager = ToolManager(conn)
        manager.refresh_tools()
        await asyncio.sleep(0)

    asyncio.run(scenario())
    assert calls == ["refresh"]


def test_realtime_tools_ignores_invalid_schemas():
    assert realtime_tools([{}, {"type": "function"}]) == []
