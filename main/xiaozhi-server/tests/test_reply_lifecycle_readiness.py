import asyncio
import json
import queue
import threading
import time
from types import MethodType, SimpleNamespace

from config import settings

settings.config_file_valid = True

import core.handle.receiveAudioHandle as receive_handle
from core.connection import ConnectionHandler
from core.external_tts import ExternalTTSCoordinator
from core.handle.receiveAudioHandle import startToChat
from core.handle.sendAudioHandle import send_tts_message, terminate_pending_reply
from core.handle.textHandler.listenMessageHandler import ListenTextMessageHandler
from core.session import ReplyLifecycle, ReplyState, RuntimeReadiness


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


class _WebSocket:
    def __init__(self):
        self.messages = []

    async def send(self, payload):
        self.messages.append(json.loads(payload))


def _deferred_conn():
    conn = SimpleNamespace(
        closing=False,
        closed=False,
        initialization_failed=False,
        initialization_error="",
        logger=_Logger(),
    )
    conn.runtime_readiness = RuntimeReadiness(conn)
    conn.conversation_ready = conn.runtime_readiness.conversation_ready
    conn.deferred_listen_detects = (
        conn.runtime_readiness.deferred_listen_detects
    )
    conn._deferred_listen_detect_ttl_seconds = (
        conn.runtime_readiness.detect_ttl_seconds
    )
    conn.defer_listen_detect = MethodType(
        ConnectionHandler.defer_listen_detect, conn
    )
    conn.flush_deferred_listen_detects = MethodType(
        ConnectionHandler.flush_deferred_listen_detects, conn
    )
    conn._clear_deferred_listen_detects = MethodType(
        ConnectionHandler._clear_deferred_listen_detects, conn
    )
    return conn


def test_listen_detect_is_deferred_deduplicated_and_replayed(monkeypatch):
    conn = _deferred_conn()
    replayed = []

    async def fake_handle_text(_conn, payload):
        replayed.append(json.loads(payload))

    monkeypatch.setattr(
        "core.handle.textHandle.handleTextMessage", fake_handle_text
    )
    message = {"type": "listen", "state": "detect", "text": "欢迎光临"}

    asyncio.run(ListenTextMessageHandler().handle(conn, message))
    asyncio.run(ListenTextMessageHandler().handle(conn, message))

    assert len(conn.deferred_listen_detects) == 1
    conn.conversation_ready.set()
    assert asyncio.run(conn.flush_deferred_listen_detects()) == 1
    assert replayed == [message]
    assert list(conn.deferred_listen_detects) == []


def test_expired_or_closed_deferred_detect_is_not_replayed(monkeypatch):
    conn = _deferred_conn()
    replayed = []

    async def fake_handle_text(_conn, payload):
        replayed.append(payload)

    monkeypatch.setattr(
        "core.handle.textHandle.handleTextMessage", fake_handle_text
    )
    conn.defer_listen_detect(
        {"type": "listen", "state": "detect", "text": "过期消息"},
        ttl_seconds=0.1,
    )
    conn.deferred_listen_detects[0]["expires_at"] = time.monotonic() - 1
    conn.conversation_ready.set()

    assert asyncio.run(conn.flush_deferred_listen_detects()) == 0
    assert replayed == []


def test_missing_llm_never_sends_tts_start(monkeypatch):
    async def no_intent(_conn, _text, **_kwargs):
        return False

    monkeypatch.setattr(receive_handle, "handle_user_intent", no_intent)
    conn = SimpleNamespace(
        voiceprint_enrollment=None,
        correct_word_replacements={},
        current_speaker=None,
        current_speaker_id=None,
        meeting_minutes=None,
        need_bind=False,
        max_output_size=0,
        client_is_speaking=False,
        client_listen_mode="auto",
        semantic_center=None,
        llm=None,
        tts=object(),
        reply_lifecycle=ReplyLifecycle(),
        conversation_ready=threading.Event(),
        client_abort=False,
        _abort_lock=threading.Lock(),
        logger=_Logger(),
        config={"correct_words": None},
        websocket=_WebSocket(),
        session_id="session-1",
    )

    assert asyncio.run(startToChat(conn, "欢迎光临")) is True
    assert conn.websocket.messages == []
    assert conn.reply_lifecycle.is_active is False


def test_tts_protocol_and_reply_generation_have_one_terminal_state():
    lifecycle = ReplyLifecycle()
    conn = SimpleNamespace(
        reply_lifecycle=lifecycle,
        _tts_protocol_sentence_id=None,
        _tts_protocol_start_sent=False,
        _tts_protocol_stop_sent=False,
        sentence_id="sentence-1",
        session_id="session-1",
        config={"enable_stop_tts_notify": False},
        websocket=_WebSocket(),
        audio_rate_controller=None,
    )

    assert asyncio.run(send_tts_message(conn, "start")) is True
    generation = lifecycle.generation
    lifecycle.bind_sentence(generation, conn.sentence_id)
    conn._tts_protocol_sentence_id = conn.sentence_id
    assert asyncio.run(
        send_tts_message(
            conn, "stop", protocol_sentence_id=conn.sentence_id, drain_audio=False
        )
    ) is True
    assert asyncio.run(
        send_tts_message(
            conn, "stop", protocol_sentence_id=conn.sentence_id, drain_audio=False
        )
    ) is False

    assert [item["state"] for item in conn.websocket.messages] == ["start", "stop"]
    assert lifecycle.state is ReplyState.COMPLETED
    assert lifecycle.is_active is False


def test_terminate_pending_reply_fails_only_current_generation():
    lifecycle = ReplyLifecycle()
    generation = lifecycle.begin()
    lifecycle.bind_sentence(generation, "sentence-1")
    cleared = []
    conn = SimpleNamespace(
        reply_lifecycle=lifecycle,
        _tts_protocol_sentence_id="sentence-1",
        _tts_protocol_start_sent=True,
        _tts_protocol_stop_sent=False,
        sentence_id="sentence-1",
        session_id="session-1",
        config={"enable_stop_tts_notify": False},
        websocket=_WebSocket(),
        audio_rate_controller=None,
        clear_queues=lambda: cleared.append(True),
    )

    assert asyncio.run(
        terminate_pending_reply(
            conn, "llm_task_failed", generation=generation
        )
    ) is True
    assert lifecycle.state is ReplyState.FAILED
    assert lifecycle.snapshot().terminal_reason == "llm_task_failed"
    assert cleared == [True]


def test_external_tts_busy_reason_exposes_reply_generation_state():
    lifecycle = ReplyLifecycle()
    lifecycle.begin()
    conn = SimpleNamespace(
        reply_lifecycle=lifecycle,
        client_have_voice=False,
        tts=SimpleNamespace(
            tts_text_queue=queue.Queue(), tts_audio_queue=queue.Queue()
        ),
        device_id="device-1",
    )

    assert ExternalTTSCoordinator()._busy_reason(conn) == "assistant_reply_active"


def test_tts_channel_open_failure_does_not_publish_any_ready_state():
    class _FailingTTS:
        async def open_audio_channels(self, _conn):
            raise RuntimeError("open failed")

    async def scenario():
        conn = ConnectionHandler.__new__(ConnectionHandler)
        conn.closing = False
        conn.closed = False
        conn.tts = _FailingTTS()
        conn.loop = asyncio.get_running_loop()
        conn.need_bind = False
        conn.logger = _Logger()
        conn.tts_ready = threading.Event()
        conn.conversation_ready = threading.Event()
        conn.audio_input_ready = threading.Event()
        conn._modules_ready = threading.Event()
        conn.initialization_failed = False
        conn.initialization_error = ""
        conn.runtime_readiness = RuntimeReadiness(conn)
        conn.tts_ready = conn.runtime_readiness.tts_ready
        conn.conversation_ready = conn.runtime_readiness.conversation_ready
        conn.audio_input_ready = conn.runtime_readiness.audio_input_ready
        conn.deferred_listen_detects = (
            conn.runtime_readiness.deferred_listen_detects
        )
        result = await asyncio.to_thread(conn._initialize_components)
        return conn, result

    conn, result = asyncio.run(scenario())
    assert result is False
    assert conn.initialization_failed is True
    assert "open failed" in conn.initialization_error
    assert conn.tts_ready.is_set() is False
    assert conn.conversation_ready.is_set() is False
    assert conn.audio_input_ready.is_set() is False
    assert conn._modules_ready.is_set() is False
