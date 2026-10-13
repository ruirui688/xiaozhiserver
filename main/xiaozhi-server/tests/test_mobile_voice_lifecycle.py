"""Mobile protocol and cleanup against real session state, without paid providers."""

import asyncio
import json
import queue
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
import config.settings

config.settings.config_file_valid = True

from core.auth import AuthManager
from core.connection import ConnectionHandler
from core.device_runtime_registry import get_connection
from core.handle.helloHandle import handleHelloMessage
from core.handle.textHandler.listenMessageHandler import ListenTextMessageHandler
from core.notifications.admission import NotificationAdmission
from core.session.reply_lifecycle import ReplyState
from core.spoken_notifications import enqueue_spoken_notification
from core.websocket_server import WebSocketServer


DEVICE = "mobile-0123456789abcdef01234567"
CLIENT = "mobile-voice-12345678-1234-4234-8234-123456789abc"


class OfflineTTS:
    def __init__(self):
        self.tts_text_queue = queue.Queue()
        self.tts_audio_queue = queue.Queue()
        self.stored = []
        self.exact = []
        self.skip_memory = []
        self.close = AsyncMock()

    def store_tts_text(self, sentence_id, text):
        self.stored.append((sentence_id, text))

    def mark_exact_text(self, sentence_id):
        self.exact.append(sentence_id)

    def mark_skip_memory_event(self, sentence_id):
        self.skip_memory.append(sentence_id)


def _connection():
    conn = ConnectionHandler({
        "selected_module": {},
        "agent_id": "unit-agent",
        "xiaozhi": {"type": "hello", "audio_params": {"sample_rate": 24000}},
    }, None, None, None, None, None)
    conn.logger = Mock()
    conn.device_id = DEVICE
    conn.headers = {"device-id": DEVICE, "client-id": CLIENT}
    conn.websocket = SimpleNamespace(send=AsyncMock(), close=AsyncMock())
    conn.welcome_msg = conn._build_welcome_msg()
    conn.tts = OfflineTTS()
    conn.tts_ready.set()
    # Configuration/transport boundaries are isolated; session state stays real.
    conn.runtime_config_reloader.refresh_if_due = AsyncMock()
    return conn


@pytest.mark.parametrize("aec", [False, True])
def test_mobile_auto_listen_accepts_reminder_only_when_idle(monkeypatch, aec):
    import core.spoken_notifications as spoken
    monkeypatch.setattr(spoken, "_admission", NotificationAdmission())

    async def exercise():
        conn = _connection()
        try:
            await handleHelloMessage(conn, {
                "type": "hello", "features": {"aec": aec},
                "audio_params": {"format": "opus", "sample_rate": 16000,
                                 "channels": 1, "frame_duration": 60},
            })
            route = await conn.speech_runtime.resolve(conn.config)
            await ListenTextMessageHandler().handle(conn, {
                "type": "listen", "state": "start", "mode": "auto",
            })
            assert not route.uses_device_asr and not route.uses_device_tts
            assert conn.client_aec is aec
            assert conn.client_listen_mode == "auto"
            assert conn.calling is False  # App call is not device-to-device calling.
            hello = json.loads(conn.websocket.send.call_args_list[0].args[0])
            assert hello["audio_params"]["sample_rate"] == 16000
            assert conn.sample_rate == 24000

            conn.client_have_voice = True
            assert enqueue_spoken_notification(conn, "日程提醒：开会", request_id="due-1") == (False, "device_busy")
            conn.reset_audio_states()
            generation = conn.reply_lifecycle.begin()
            assert enqueue_spoken_notification(conn, "日程提醒：开会", request_id="due-1") == (False, "device_busy")
            conn.reply_lifecycle.complete(generation)

            assert enqueue_spoken_notification(conn, "日程提醒：开会", request_id="due-1") == (True, "queued")
            assert enqueue_spoken_notification(conn, "日程提醒：开会", request_id="due-1") == (True, "duplicate")
            assert conn.tts.tts_text_queue.qsize() == 3
            assert conn.tts.stored == [(conn.sentence_id, "日程提醒：开会")]
            assert conn.tts.exact == conn.tts.skip_memory == [conn.sentence_id]
            assert conn.reply_lifecycle.snapshot().kind == "notification"
        finally:
            await conn.close()
        assert conn.closed and conn.tts.tts_text_queue.empty()
        assert enqueue_spoken_notification(conn, "新提醒", request_id="after-close") == (False, "connection_closing")

    asyncio.run(exercise())


@pytest.mark.parametrize("deadline", [False, True])
def test_mobile_disconnect_or_deadline_runs_actual_session_cleanup(monkeypatch, deadline):
    async def exercise():
        conn = _connection()
        background_cancelled = asyncio.Event()
        started = asyncio.Event()
        disconnect = asyncio.Event()

        async def background_initialize():
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                background_cancelled.set()

        conn._background_initialize = background_initialize
        conn.asr = SimpleNamespace(close=AsyncMock())
        conn.tts.tts_text_queue.put(object())
        conn.tts.tts_audio_queue.put(object())
        conn.asr_audio_queue.put(b"stale-audio")
        conn.reply_lifecycle.begin()
        monkeypatch.setattr("core.websocket_server.ConnectionHandler", lambda *args: conn)
        monkeypatch.setattr("core.connection.generate_and_save_chat_title", AsyncMock())
        real_wait_for = asyncio.wait_for

        async def bounded_wait_for(awaitable, timeout):
            return await real_wait_for(awaitable, timeout=0.05 if timeout == 1800 else timeout)

        if deadline:
            monkeypatch.setattr("core.websocket_server.asyncio.wait_for", bounded_wait_for)

        server = object.__new__(WebSocketServer)
        server.active_handlers = set()
        server.active_mobile_devices = set()
        server.max_device_connections = 4
        server.logger = Mock()
        server.config = conn.config
        server._vad = server._asr = server._llm = server._memory = server._intent = None
        server.auth_enable = False
        server.allowed_devices = set()
        server.auth = AuthManager("unit-test-only")

        class WebSocket:
            def __init__(self):
                self.request = SimpleNamespace(path="/xiaozhi/v1/", headers={
                    "device-id": DEVICE, "client-id": CLIENT,
                    "authorization": "Bearer " + server.auth.generate_token(CLIENT, DEVICE),
                })
                self.remote_address = ("127.0.0.1", 12345)
                self.close = AsyncMock()

            def __aiter__(self):
                return self

            async def __anext__(self):
                await disconnect.wait()
                raise StopAsyncIteration

        ws = WebSocket()
        task = asyncio.create_task(server._handle_connection(ws))
        await started.wait()
        assert get_connection(DEVICE) is conn
        assert server.active_mobile_devices == {DEVICE}
        if not deadline:
            disconnect.set()
        await task
        assert conn.closed and conn.closing and conn.stop_event.is_set()
        assert background_cancelled.is_set()
        assert conn._background_initialize_task.done()
        assert conn.tts.tts_text_queue.empty() and conn.tts.tts_audio_queue.empty()
        assert conn.asr_audio_queue.empty()
        assert conn.reply_lifecycle.state is ReplyState.INTERRUPTED
        assert conn.executor is None
        conn.tts.close.assert_awaited_once()
        conn.asr.close.assert_awaited_once()
        assert get_connection(DEVICE) is None
        assert not server.active_handlers and not server.active_mobile_devices

    asyncio.run(exercise())
