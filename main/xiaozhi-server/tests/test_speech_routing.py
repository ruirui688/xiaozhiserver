import asyncio
import json
import queue
from types import SimpleNamespace

import pytest

from config import settings

settings.config_file_valid = True

from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO
from core.handle.textMessageProcessor import TextMessageProcessor
import core.handle.textHandler.listenMessageHandler as listen_handler_module
from core.handle.textHandler.listenMessageHandler import ListenTextMessageHandler
from core.session.reply_lifecycle import ReplyLifecycle, ReplyState
from core.session.tts_session_options import SessionTTSOptions
from core.session.turn_context import TurnContextBuffer
from core.session.runtime_config_reloader import RuntimeConfigReloader
from core.speech.device_tts import DeviceTTSProvider
from core.speech.routing import SpeechRouteError, resolve_speech_route
from core.speech.runtime import SpeechRuntime


class _Logger:
    def __init__(self):
        self.info_messages = []

    def bind(self, **_kwargs):
        return self

    def debug(self, *_args, **_kwargs):
        pass

    def info(self, message, *_args, **_kwargs):
        self.info_messages.append(str(message))

    def warning(self, *_args, **_kwargs):
        pass

    def error(self, *_args, **_kwargs):
        pass


class _WebSocket:
    def __init__(self):
        self.messages = []

    async def send(self, payload):
        self.messages.append(json.loads(payload))


def _speech_features(*, asr=True, tts=True, ack=True):
    return {
        "speech": {
            "version": 1,
            "input": ["device_asr"] if asr else [],
            "output": ["device_tts"] if tts else [],
            "asr_final_text": asr,
            "tts_playback_ack": ack,
        }
    }


def test_old_device_defaults_to_server_routes():
    route = resolve_speech_route({}, {})
    assert route.input == "server"
    assert route.output == "server"


def test_auto_routes_only_to_declared_device_capabilities():
    route = resolve_speech_route(
        {"speech_routing": {"input": "auto", "output": "auto"}},
        _speech_features(),
    )
    assert route.input == "device"
    assert route.output == "device"

    without_ack = resolve_speech_route(
        {"speech_routing": {"input": "auto", "output": "auto"}},
        _speech_features(ack=False),
    )
    assert without_ack.input == "device"
    assert without_ack.output == "server"


def test_required_device_route_and_realtime_conflicts_fail_closed():
    with pytest.raises(SpeechRouteError, match="required ASR"):
        resolve_speech_route(
            {"speech_routing": {"input": "device", "output": "server"}},
            {},
        )
    with pytest.raises(SpeechRouteError, match="Realtime"):
        resolve_speech_route(
            {"speech_routing": {"input": "auto", "output": "server"}},
            _speech_features(),
            realtime=True,
        )


def test_incompatible_route_is_reported_to_device():
    async def scenario():
        conn = SimpleNamespace(
            websocket=_WebSocket(), session_id="session-1", loop=None
        )
        runtime = SpeechRuntime(conn)
        runtime.record_hello({})
        with pytest.raises(SpeechRouteError):
            await runtime.resolve(
                {"speech_routing": {"input": "device", "output": "server"}}
            )
        assert conn.websocket.messages[-1] == {
            "type": "speech_route",
            "session_id": "session-1",
            "version": 1,
            "status": "error",
            "reason": "incompatible_route",
            "detail": "device does not support required ASR",
        }

    asyncio.run(scenario())
def test_hello_barrier_times_out_to_legacy_server_route():
    async def scenario():
        conn = SimpleNamespace(
            websocket=_WebSocket(), session_id="session-1", loop=None
        )
        runtime = SpeechRuntime(conn)
        route = await runtime.resolve(
            {
                "speech_routing": {
                    "input": "auto",
                    "output": "auto",
                    "hello_timeout_seconds": 0.1,
                }
            }
        )
        assert route.hello_timed_out is True
        assert route.input == "server"
        assert route.output == "server"
        assert conn.websocket.messages[-1]["type"] == "speech_route"

    asyncio.run(scenario())


def test_device_asr_requires_final_turn_id_and_is_idempotent_and_limited():
    conn = SimpleNamespace()
    runtime = SpeechRuntime(conn)
    runtime.route = resolve_speech_route(
        {"speech_routing": {"input": "device", "output": "server"}},
        _speech_features(tts=False),
    )
    accepted = runtime.validate_device_asr(
        {
            "source": "device_asr",
            "final": True,
            "turn_id": "turn-1",
            "text": "打开客厅灯",
        }
    )
    assert accepted.accepted is True
    duplicate = runtime.validate_device_asr(
        {
            "source": "device_asr",
            "final": True,
            "turn_id": "turn-1",
            "text": "打开客厅灯",
        }
    )
    assert duplicate.duplicate is True
    assert runtime.validate_device_asr(
        {"source": "device_asr", "final": False, "turn_id": "turn-2", "text": "x"}
    ).reason == "device_asr_final_required"

    for index in range(2, 9):
        assert runtime.validate_device_asr(
            {
                "source": "device_asr",
                "final": True,
                "turn_id": f"turn-{index}",
                "text": "x",
            }
        ).accepted
    limited = runtime.validate_device_asr(
        {
            "source": "device_asr",
            "final": True,
            "turn_id": "turn-9",
            "text": "x",
        }
    )
    assert limited.reason == "rate_limited"


def test_device_asr_text_is_redacted_from_protocol_log():
    class _Handler:
        async def handle(self, _conn, _message):
            return None

    class _Registry:
        def get_handler(self, _message_type):
            return _Handler()

    logger = _Logger()
    conn = SimpleNamespace(logger=logger, websocket=_WebSocket())
    asyncio.run(
        TextMessageProcessor(_Registry()).process_message(
            conn,
            json.dumps(
                {
                    "type": "listen",
                    "state": "detect",
                    "source": "device_asr",
                    "final": True,
                    "turn_id": "turn-private",
                    "text": "这段识别正文不能进入协议日志",
                },
                ensure_ascii=False,
            ),
        )
    )
    rendered = "\n".join(logger.info_messages)
    assert "这段识别正文不能进入协议日志" not in rendered
    assert "<redacted:" in rendered


def test_device_asr_consumes_listen_start_context(monkeypatch):
    captured = {}

    async def fake_start_to_chat(_conn, text, turn_context=None, turn_id=None):
        captured.update(text=text, context=turn_context, turn_id=turn_id)
        return True

    monkeypatch.setattr(listen_handler_module, "startToChat", fake_start_to_chat)
    conn = SimpleNamespace(
        client_listen_mode="auto",
        conversation_router=None,
        conversation_ready=SimpleNamespace(is_set=lambda: True),
        initialization_failed=False,
        logger=_Logger(),
        client_have_voice=False,
        turn_context=TurnContextBuffer(),
        last_activity_time=0,
        config={"wakeup_words": [], "enable_greeting": False},
        websocket=_WebSocket(),
        session_id="session-1",
        read_config_from_api=False,
        need_bind=False,
        report_asr_enable=False,
        chat_history_conf=0,
        reset_audio_states=lambda **_kwargs: None,
    )
    conn.speech_runtime = SpeechRuntime(conn)
    conn.speech_runtime.route = resolve_speech_route(
        {"speech_routing": {"input": "device", "output": "server"}},
        _speech_features(tts=False),
    )
    asyncio.run(
        ListenTextMessageHandler().handle(
            conn,
            {
                "type": "listen",
                "state": "start",
                "context": {"page": "商品详情", "productId": "123"},
            },
        )
    )
    asyncio.run(
        ListenTextMessageHandler().handle(
            conn,
            {
                "type": "listen",
                "state": "detect",
                "source": "device_asr",
                "final": True,
                "turn_id": "turn-context",
                "text": "介绍一下",
            },
        )
    )
    assert captured == {
        "text": "介绍一下",
        "context": {"page": "商品详情", "productId": "123"},
        "turn_id": "turn-context",
    }
    assert conn.turn_context.take() is None


def test_device_tts_does_not_advertise_server_voice_controls():
    provider = DeviceTTSProvider({})
    assert SessionTTSOptions().capabilities(provider) == ()


def test_device_tts_waits_for_playback_ack_before_reply_completes():
    async def scenario():
        websocket = _WebSocket()
        lifecycle = ReplyLifecycle()
        generation = lifecycle.begin()
        conn = SimpleNamespace(
            loop=asyncio.get_running_loop(),
            websocket=websocket,
            logger=_Logger(),
            session_id="session-1",
            sentence_id="sentence-1",
            reply_lifecycle=lifecycle,
            _tts_protocol_sentence_id=None,
            _tts_protocol_start_sent=True,
            _tts_protocol_stop_sent=False,
            config={"enable_stop_tts_notify": False},
            audio_rate_controller=None,
            calling=False,
            closing=False,
            closed=False,
            read_config_from_api=False,
            need_bind=False,
            report_tts_enable=False,
            chat_history_conf=0,
        )
        conn.speech_runtime = SpeechRuntime(conn)
        conn.speech_runtime.route = resolve_speech_route(
            {"speech_routing": {"input": "server", "output": "device"}},
            _speech_features(asr=False),
        )
        provider = DeviceTTSProvider(
            {"speech_routing": {"device_tts_ack_timeout_seconds": 2}}
        )
        provider.conn = conn
        conn.tts = provider

        await provider._handle_message(
            TTSMessageDTO(
                "sentence-1", SentenceType.FIRST, ContentType.ACTION, generation=generation
            )
        )
        await provider._handle_message(
            TTSMessageDTO(
                "sentence-1",
                SentenceType.MIDDLE,
                ContentType.TEXT,
                content_detail="你好",
                generation=generation,
            )
        )
        finish = asyncio.create_task(
            provider._handle_message(
                TTSMessageDTO(
                    "sentence-1", SentenceType.LAST, ContentType.ACTION, generation=generation
                )
            )
        )
        await asyncio.sleep(0)
        assert lifecycle.state is ReplyState.STREAMING
        assert finish.done() is False
        assert conn.speech_runtime.handle_tts_ack(
            {
                "sentence_id": "sentence-1",
                "generation": generation,
                "event": "played",
            }
        )
        await finish
        assert lifecycle.state is ReplyState.COMPLETED
        assert [item["state"] for item in websocket.messages] == [
            "text",
            "text_end",
            "stop",
        ]

    asyncio.run(scenario())


def test_device_tts_ack_timeout_fails_reply():
    async def scenario():
        lifecycle = ReplyLifecycle()
        generation = lifecycle.begin()
        conn = SimpleNamespace(
            loop=asyncio.get_running_loop(),
            websocket=_WebSocket(),
            logger=_Logger(),
            session_id="session-1",
            sentence_id="sentence-1",
            reply_lifecycle=lifecycle,
            _tts_protocol_sentence_id=None,
            _tts_protocol_start_sent=True,
            _tts_protocol_stop_sent=False,
            config={"enable_stop_tts_notify": False},
            audio_rate_controller=None,
            calling=False,
            closing=False,
            closed=False,
            read_config_from_api=False,
            need_bind=False,
            report_tts_enable=False,
            chat_history_conf=0,
        )
        conn.speech_runtime = SpeechRuntime(conn)
        provider = DeviceTTSProvider(
            {"speech_routing": {"device_tts_ack_timeout_seconds": 1}}
        )
        provider.conn = conn
        conn.tts = provider
        await provider._handle_message(
            TTSMessageDTO(
                "sentence-1", SentenceType.FIRST, ContentType.ACTION, generation=generation
            )
        )
        await provider._handle_message(
            TTSMessageDTO(
                "sentence-1", SentenceType.LAST, ContentType.ACTION, generation=generation
            )
        )
        assert lifecycle.state is ReplyState.FAILED
        assert lifecycle.snapshot().terminal_reason == "device_tts_ack_timeout"

    asyncio.run(scenario())


def test_device_tts_close_releases_ack_waiter_and_worker():
    async def scenario():
        lifecycle = ReplyLifecycle()
        generation = lifecycle.begin()
        conn = SimpleNamespace(
            loop=asyncio.get_running_loop(),
            websocket=_WebSocket(),
            logger=_Logger(),
            session_id="session-close",
            sentence_id="sentence-close",
            reply_lifecycle=lifecycle,
            _tts_protocol_sentence_id=None,
            _tts_protocol_start_sent=False,
            _tts_protocol_stop_sent=False,
            config={"enable_stop_tts_notify": False},
            audio_rate_controller=None,
            calling=False,
            closing=False,
            closed=False,
            read_config_from_api=False,
            need_bind=False,
            report_tts_enable=False,
            chat_history_conf=0,
        )
        conn.speech_runtime = SpeechRuntime(conn)
        provider = DeviceTTSProvider(
            {"speech_routing": {"device_tts_ack_timeout_seconds": 30}}
        )
        conn.tts = provider
        await provider.open_audio_channels(conn)
        provider.tts_text_queue.put(
            TTSMessageDTO(
                "sentence-close", SentenceType.FIRST, ContentType.ACTION, generation=generation
            )
        )
        provider.tts_text_queue.put(
            TTSMessageDTO(
                "sentence-close", SentenceType.LAST, ContentType.ACTION, generation=generation
            )
        )
        for _ in range(100):
            if any(
                item.get("state") == "text_end"
                for item in conn.websocket.messages
            ):
                break
            await asyncio.sleep(0.01)
        await provider.close()
        await asyncio.to_thread(provider.tts_priority_thread.join, 1.0)
        assert provider.tts_priority_thread.is_alive() is False
        assert lifecycle.state is ReplyState.INTERRUPTED

    asyncio.run(scenario())


def test_device_tts_chat_history_contains_no_fabricated_audio():
    async def scenario():
        lifecycle = ReplyLifecycle()
        generation = lifecycle.begin()
        lifecycle.bind_sentence(generation, "sentence-report")
        reports = queue.Queue()
        conn = SimpleNamespace(
            loop=asyncio.get_running_loop(),
            websocket=_WebSocket(),
            logger=_Logger(),
            session_id="session-report",
            sentence_id="sentence-report",
            device_id="device-1",
            reply_lifecycle=lifecycle,
            _tts_protocol_sentence_id="sentence-report",
            _tts_protocol_start_sent=True,
            _tts_protocol_stop_sent=False,
            config={"enable_stop_tts_notify": False},
            audio_rate_controller=None,
            calling=False,
            closing=False,
            closed=False,
            read_config_from_api=True,
            need_bind=False,
            report_tts_enable=True,
            chat_history_conf=2,
            report_queue=reports,
            turn_metrics=None,
        )
        conn.speech_runtime = SpeechRuntime(conn)
        provider = DeviceTTSProvider({})
        provider.conn = conn
        conn.tts = provider
        provider.store_tts_text("sentence-report", "设备合成的回复")
        await provider._finish("sentence-report", generation, "played")
        report = reports.get_nowait()
        assert report[1] == "设备合成的回复"
        assert report[2] == []
        assert lifecycle.state is ReplyState.COMPLETED

    asyncio.run(scenario())


def test_hot_reload_switches_server_to_device_and_rolls_back_on_failure(monkeypatch):
    async def success_scenario():
        conn = SimpleNamespace(
            config={"speech_routing": {"input": "server", "output": "server"}},
            speech_runtime=None,
            closing=False,
            closed=False,
        )
        conn.speech_runtime = SpeechRuntime(conn)
        conn.speech_runtime.record_hello(_speech_features())
        conn.speech_runtime.route = resolve_speech_route(conn.config, {})
        reloader = RuntimeConfigReloader(conn)
        activated = []

        async def fake_build(_config, route):
            return {"route": route}

        async def fake_deactivate():
            return None

        async def fake_activate(_config, _modules, route):
            activated.append(route)

        monkeypatch.setattr(reloader, "_build_modules", fake_build)
        monkeypatch.setattr(reloader, "_deactivate_pipeline", fake_deactivate)
        monkeypatch.setattr(reloader, "_activate_pipeline", fake_activate)
        await reloader._replace_pipeline(
            {"speech_routing": {"input": "device", "output": "device"}}
        )
        assert activated[-1].input == "device"
        assert activated[-1].output == "device"

    asyncio.run(success_scenario())

    async def rollback_scenario():
        conn = SimpleNamespace(
            config={"speech_routing": {"input": "server", "output": "server"}},
            speech_runtime=None,
            closing=False,
            closed=False,
            logger=_Logger(),
        )
        conn.speech_runtime = SpeechRuntime(conn)
        conn.speech_runtime.record_hello(_speech_features())
        conn.speech_runtime.route = resolve_speech_route(conn.config, {})
        reloader = RuntimeConfigReloader(conn)
        activated = []

        async def fake_build(_config, route):
            return {"route": route}

        async def fake_deactivate():
            return None

        async def fake_activate(_config, _modules, route):
            activated.append(route)
            if route.uses_device_tts:
                raise RuntimeError("candidate failed")

        monkeypatch.setattr(reloader, "_build_modules", fake_build)
        monkeypatch.setattr(reloader, "_deactivate_pipeline", fake_deactivate)
        monkeypatch.setattr(reloader, "_activate_pipeline", fake_activate)
        with pytest.raises(RuntimeError, match="candidate failed"):
            await reloader._replace_pipeline(
                {"speech_routing": {"input": "device", "output": "device"}}
            )
        assert [(route.input, route.output) for route in activated] == [
            ("device", "device"),
            ("server", "server"),
        ]

    asyncio.run(rollback_scenario())


def test_device_device_module_build_skips_vad_asr_and_server_tts(monkeypatch):
    captured = {}

    def fake_initialize_modules(
        _logger,
        _config,
        init_vad=False,
        init_asr=False,
        init_llm=False,
        init_tts=False,
        init_memory=False,
        init_intent=False,
    ):
        captured.update(
            vad=init_vad,
            asr=init_asr,
            llm=init_llm,
            tts=init_tts,
            memory=init_memory,
            intent=init_intent,
        )
        return {"llm": object()}

    monkeypatch.setattr(
        "core.session.runtime_config_reloader.initialize_modules",
        fake_initialize_modules,
    )
    conn = SimpleNamespace(logger=_Logger())
    reloader = RuntimeConfigReloader(conn)
    route = resolve_speech_route(
        {"speech_routing": {"input": "device", "output": "device"}},
        _speech_features(),
    )
    config = {
        "speech_routing": {"input": "device", "output": "device"},
        "selected_module": {"VAD": "v", "ASR": "a", "LLM": "l", "TTS": "t"},
    }
    asyncio.run(reloader._build_modules(config, route))
    assert captured == {
        "vad": False,
        "asr": False,
        "llm": True,
        "tts": False,
        "memory": True,
        "intent": True,
    }
