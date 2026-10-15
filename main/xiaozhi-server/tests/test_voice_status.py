import asyncio
import json
import threading
from concurrent.futures import Future
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
from config import settings

settings.config_file_valid = True

from core.metering.admission import PlatformAdmissionError
from core.providers.asr.base import ASRProviderBase
from core.providers.asr import doubao_stream
from core.session.runtime_readiness import RuntimeReadiness
from core.session.voice_status import VoiceStatus, voice_status, report_asr_failure
from core.session.turn_context import TurnContextBuffer
from core.utils.turn_metrics import TurnMetrics
from core.handle.helloHandle import handleHelloMessage
from core.handle.receiveAudioHandle import handleAudioMessage


def connection():
    conn = SimpleNamespace(
        session_id="voice-test", welcome_msg={}, websocket=SimpleNamespace(send=AsyncMock()),
        closing=False, closed=False, initialization_failed=False, need_bind=False,
        logger=Mock(), config={}, reset_audio_states=Mock(), turn_context=TurnContextBuffer(),
    )
    readiness = RuntimeReadiness(conn)
    conn.runtime_readiness = readiness
    conn.audio_input_ready = readiness.audio_input_ready
    conn.conversation_ready = readiness.conversation_ready
    conn.tts_ready = readiness.tts_ready
    return conn


def messages(conn):
    return [json.loads(call.args[0]) for call in conn.websocket.send.call_args_list]


def test_opt_in_ready_requires_both_actual_capabilities_and_is_event_driven():
    async def run():
        conn = connection()
        status = voice_status(conn)
        status.negotiate({"voice_status": 1})
        await status.start()
        assert conn.welcome_msg["features"]["voice_status"] == 1
        conn.audio_input_ready.set()
        await asyncio.sleep(0)
        assert [m["status"] for m in messages(conn)] == ["initializing"]
        # Actual provider initialization publishes readiness from a worker thread.
        await asyncio.to_thread(conn.conversation_ready.set)
        await asyncio.wait_for(status._monitor, .2)
        assert [m["status"] for m in messages(conn)] == ["initializing", "ready"]
        assert messages(conn)[1]["type"] == "voice_status"
    asyncio.run(run())


def test_real_hello_ack_precedes_voice_status_and_preserves_audio_contract():
    async def run():
        conn = connection()
        conn.welcome_msg = {"type": "hello", "audio_params": {"sample_rate": 24000}}
        conn.sample_rate = 24000
        conn.headers = {}
        conn.audio_input_ready.set()
        conn.conversation_ready.set()
        await handleHelloMessage(conn, {"features": {"voice_status": 1, "aec": True},
                                       "audio_params": {"format": "opus", "sample_rate": 16000}})
        await conn.voice_status._monitor
        payloads = messages(conn)
        assert [m["type"] for m in payloads] == ["hello", "voice_status", "voice_status"]
        assert payloads[0]["features"]["voice_status"] == 1
        assert payloads[0]["audio_params"]["sample_rate"] == 16000
        assert conn.sample_rate == 24000
        assert payloads[-1]["status"] == "ready"
    asyncio.run(run())


@pytest.mark.parametrize("failed", ["initialization", "missing", "timeout"])
def test_failed_initialization_never_publishes_ready(monkeypatch, failed):
    async def run():
        conn = connection()
        status = voice_status(conn)
        status.negotiate({"voice_status": 1})
        status.INITIALIZATION_TIMEOUT = .01
        if failed == "missing":
            conn._components_initialize_future = Future()
            conn._components_initialize_future.set_result(True)
        await status.start()
        if failed == "initialization":
            await asyncio.to_thread(conn.runtime_readiness.mark_initialization_failed, RuntimeError("secret-canary"))
        await asyncio.wait_for(status._monitor, .2)
        payloads = messages(conn)
        assert payloads[-1]["status"] == "error"
        assert "ready" not in [item["status"] for item in payloads]
        assert "secret-canary" not in json.dumps(payloads)
    asyncio.run(run())


def test_close_cancels_readiness_wait_without_late_status():
    async def run():
        conn = connection()
        status = voice_status(conn)
        status.negotiate({"voice_status": 1})
        await status.start()
        conn.closing = True
        status.close()
        await asyncio.gather(status._monitor, return_exceptions=True)
        conn.audio_input_ready.set()
        conn.conversation_ready.set()
        await asyncio.sleep(0)
        assert [m["status"] for m in messages(conn)] == ["initializing"]
    asyncio.run(run())


def test_initialization_failure_after_ready_is_reported_from_worker():
    async def run():
        conn = connection()
        conn.audio_input_ready.set()
        conn.conversation_ready.set()
        status = voice_status(conn)
        status.negotiate({"voice_status": 1})
        await status.start()
        await status._monitor
        await asyncio.to_thread(conn.runtime_readiness.mark_initialization_failed, RuntimeError("private"))
        await asyncio.sleep(0)
        await status._invalidation_task
        assert [m["status"] for m in messages(conn)] == ["initializing", "ready", "error"]
        assert messages(conn)[-1]["code"] == "INITIALIZATION_FAILED"
        assert not status.allows_asr()
    asyncio.run(run())


async def ready_connection():
    conn = connection()
    conn.audio_input_ready.set()
    conn.conversation_ready.set()
    status = voice_status(conn)
    status.negotiate({"voice_status": 1})
    await status.start()
    await status._monitor
    return conn, status


@pytest.mark.parametrize("field", ["audio_input_ready", "conversation_ready"])
@pytest.mark.parametrize("restore_immediately", [False, True])
def test_ready_invalidation_ends_call_even_if_clear_set_precedes_callbacks(field, restore_immediately):
    async def run():
        conn, status = await ready_connection()
        event = getattr(conn, field)
        event.clear()
        if restore_immediately:
            event.set()  # No yield: both changes finish before queued callbacks.
        await asyncio.sleep(0)
        await status._invalidation_task
        assert [m["status"] for m in messages(conn)] == ["initializing", "ready", "error"]
        assert messages(conn)[-1]["code"] == "VOICE_SESSION_INVALIDATED"
        assert not status.allows_asr()
        event.set()
        await asyncio.sleep(0)
        assert len(messages(conn)) == 3  # Restoration must not silently reopen capture.
    asyncio.run(run())


def test_initial_clear_set_and_queued_set_callbacks_do_not_invalidate_first_ready():
    async def run():
        conn = connection()
        status = voice_status(conn)
        status.negotiate({"voice_status": 1})
        await status.start()
        conn.audio_input_ready.set()
        conn.conversation_ready.set()
        conn.conversation_ready.clear()
        conn.conversation_ready.set()
        await status._monitor
        await asyncio.sleep(0)
        assert [m["status"] for m in messages(conn)] == ["initializing", "ready"]
        assert status._invalidation_task is None and status.allows_asr()
    asyncio.run(run())


def test_invalidation_while_ready_frame_is_sending_still_sends_terminal_error():
    async def run():
        conn = connection()
        sending = asyncio.Event()
        release = asyncio.Event()
        async def send(payload):
            if json.loads(payload)["status"] == "ready":
                sending.set()
                await release.wait()
        conn.websocket.send.side_effect = send
        conn.audio_input_ready.set()
        conn.conversation_ready.set()
        status = voice_status(conn)
        status.negotiate({"voice_status": 1})
        await status.start()
        await sending.wait()
        conn.conversation_ready.clear()
        conn.conversation_ready.set()
        await asyncio.sleep(0)
        assert not status.allows_asr()
        release.set()
        await status._monitor
        await status._invalidation_task
        assert [m["status"] for m in messages(conn)] == ["initializing", "ready", "error"]
    asyncio.run(run())


def test_real_hot_reload_deactivation_invalidates_negotiated_call():
    from core.session.runtime_config_reloader import RuntimeConfigReloader
    async def run():
        conn, status = await ready_connection()
        conn._modules_ready = threading.Event()
        await RuntimeConfigReloader(conn)._deactivate_pipeline()
        await status._invalidation_task
        assert messages(conn)[-1]["code"] == "VOICE_SESSION_INVALIDATED"
        assert not status.allows_asr()
    asyncio.run(run())


def test_real_realtime_rebuild_invalidates_but_ordinary_audio_does_not():
    from core.conversation.router import ConversationRouter
    async def run():
        conn, status = await ready_connection()
        def session(*args):
            return SimpleNamespace(close=AsyncMock(), initialize=AsyncMock(), handle_audio=AsyncMock(return_value=True))
        router = ConversationRouter(conn, session_factory=session)
        router.session = session()
        for _ in range(5):
            assert await router.handle_audio(b"test")
        assert status._invalidation_task is None
        await router.rebuild_for_memory()
        await asyncio.sleep(0)
        await status._invalidation_task
        assert conn.audio_input_ready.is_set() and conn.conversation_ready.is_set()
        assert messages(conn)[-1]["code"] == "VOICE_SESSION_INVALIDATED"
        assert not status.allows_asr()
    asyncio.run(run())


def test_legacy_clients_ignore_invalidation_and_events_skip_noop_notifications():
    conn = connection()
    changed = Mock()
    conn.voice_status = SimpleNamespace(notify_readiness_changed=changed)
    conn.audio_input_ready.set()
    conn.audio_input_ready.set()
    conn.audio_input_ready.clear()
    conn.audio_input_ready.clear()
    assert changed.call_count == 2
    assert conn.audio_input_ready.invalidation_count == 1

    async def run():
        conn = connection()
        status = voice_status(conn)
        await status.start()
        conn.audio_input_ready.set()
        conn.conversation_ready.set()
        conn.conversation_ready.clear()
        conn.conversation_ready.set()
        await asyncio.sleep(0)
        assert not messages(conn) and status.allows_asr()
    asyncio.run(run())


def test_close_cancels_pending_invalidation_without_late_error():
    async def run():
        conn, status = await ready_connection()
        await status._send_lock.acquire()
        conn.conversation_ready.clear()
        await asyncio.sleep(0)
        assert status._invalidation_task is not None
        conn.closing = True
        status.close()
        await asyncio.gather(status._invalidation_task, return_exceptions=True)
        status._send_lock.release()
        assert [m["status"] for m in messages(conn)] == ["initializing", "ready"]
    asyncio.run(run())


def test_old_devices_receive_no_readiness_extension_and_sanitized_generic_error():
    async def run():
        conn = connection()
        status = voice_status(conn)
        status.negotiate({"aec": True})
        await status.start()
        assert status._monitor is None and not messages(conn)
        await report_asr_failure(conn, RuntimeError("Authorization secret-canary"))
        await report_asr_failure(conn, RuntimeError("other secret-canary"))
        assert len(messages(conn)) == 1
        assert messages(conn)[0]["type"] == "error"
        assert messages(conn)[0]["code"] == "ASR_UNAVAILABLE"
        assert messages(conn)[0]["retry_after_ms"] == 5000
        assert "secret-canary" not in json.dumps(messages(conn))
        assert not status.allows_asr()
        status._asr_blocked_until = 0
        assert status.allows_asr()
    asyncio.run(run())


class FailingRecording(ASRProviderBase):
    def __init__(self, directory):
        super().__init__()
        self.output_dir = str(directory)
        self.delete_audio_file = True
        self.calls = 0

    async def speech_to_text(self, *args, **kwargs):
        self.calls += 1
        raise PlatformAdmissionError("ALLOWANCE_EXHAUSTED")


def test_recording_denial_survives_wrapper_and_stops_subsequent_paid_attempts(tmp_path):
    async def run():
        conn = connection()
        conn.audio_format = "pcm"
        conn.voiceprint_provider = None
        conn.turn_metrics = TurnMetrics()
        conn.asr = FailingRecording(tmp_path)
        voice_status(conn).negotiate({"voice_status": 1})
        for _ in range(10):
            await conn.asr.handle_voice_stop(conn, [b"\x00\x00" * 960] * 20)
        assert conn.asr.calls == 1
        assert len(messages(conn)) == 1
        assert messages(conn)[0]["code"] == "ALLOWANCE_EXHAUSTED"
        assert messages(conn)[0]["retryable"] is False
        assert not voice_status(conn).allows_asr()
    asyncio.run(run())


def test_recording_failure_cancels_parallel_voiceprint_task(tmp_path):
    async def run():
        conn = connection()
        cancelled = asyncio.Event()
        async def identify(*args):
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()
        conn.audio_format = "pcm"
        conn.voiceprint_provider = SimpleNamespace(identify_speaker=identify)
        conn.turn_metrics = TurnMetrics()
        conn.asr = FailingRecording(tmp_path)
        await asyncio.wait_for(conn.asr.handle_voice_stop(conn, [b"\x00\x00" * 960] * 20), .5)
        assert cancelled.is_set()
        assert messages(conn)[0]["code"] == "ALLOWANCE_EXHAUSTED"
    asyncio.run(run())


def test_shared_audio_input_error_is_sanitized_and_not_retried_per_frame():
    async def run():
        conn = connection()
        conn.vad = SimpleNamespace(is_vad=Mock(side_effect=RuntimeError("private-provider-detail")))
        conn.asr = object()
        for _ in range(100):
            await handleAudioMessage(conn, b"invalid")
        assert conn.vad.is_vad.call_count == 1
        assert len(messages(conn)) == 1
        assert messages(conn)[0]["code"] == "ASR_UNAVAILABLE"
        assert "private-provider-detail" not in json.dumps(messages(conn))
    asyncio.run(run())


def test_stream_admission_denial_does_not_retry_per_frame_or_open_upstream(monkeypatch, tmp_path):
    async def run():
        conn = connection()
        provider = doubao_stream.ASRProvider({"api_key": "unit-only", "output_dir": str(tmp_path)}, True)
        conn.asr = provider
        conn.client_listen_mode = "auto"
        conn.asr_audio = []
        conn.client_have_voice = True
        conn.client_voice_stop = False
        denied = AsyncMock(side_effect=PlatformAdmissionError("ALLOWANCE_EXHAUSTED"))
        connect = AsyncMock()
        monkeypatch.setattr(doubao_stream, "begin_call_async", denied)
        monkeypatch.setattr(doubao_stream.websockets, "connect", connect)
        voice_status(conn).negotiate({"voice_status": 1})
        for _ in range(100):
            await provider.receive_audio(conn, b"unused-opus", True)
        assert denied.await_count == 1
        connect.assert_not_called()
        assert messages(conn)[0]["code"] == "ALLOWANCE_EXHAUSTED"
        assert len(messages(conn)) == 1
        await provider.close()
    asyncio.run(run())


def test_stale_provider_failure_cannot_block_replacement_or_closed_session():
    async def run():
        conn = connection()
        conn.asr = object()
        await report_asr_failure(conn, PlatformAdmissionError("ALLOWANCE_EXHAUSTED"), provider=object())
        assert voice_status(conn).allows_asr() and not messages(conn)
        conn.closing = True
        await report_asr_failure(conn, RuntimeError("failure"), provider=conn.asr)
        assert not messages(conn)
    asyncio.run(run())


def test_denial_cannot_be_downgraded_by_late_transport_error_or_init_timeout():
    async def run():
        conn = connection()
        status = voice_status(conn)
        status.negotiate({"voice_status": 1})
        await status.start()
        await asyncio.sleep(0)
        await report_asr_failure(conn, PlatformAdmissionError("ALLOWANCE_EXHAUSTED"))
        await report_asr_failure(conn, RuntimeError("late disconnect"))
        await asyncio.wait_for(status._monitor, .2)
        assert [m.get("code") for m in messages(conn) if m["status"] == "error"] == ["ALLOWANCE_EXHAUSTED"]
        assert status._asr_blocked_until == float("inf")
    asyncio.run(run())
