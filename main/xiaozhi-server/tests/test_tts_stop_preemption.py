"""Exercise concurrent device stop delivery with real reply lifecycle state."""

import asyncio
import json
import threading
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from config import settings

settings.config_file_valid = True

from core.handle import sendAudioHandle
from core.handle.abortHandle import handleAbortMessage
from core.providers.tts.dto.dto import SentenceType
from core.session import ReplyLifecycle, ReplyState


def make_connection():
    messages = []

    async def send(payload):
        messages.append(json.loads(payload))

    lifecycle = ReplyLifecycle()
    logger = Mock()
    logger.bind.return_value = logger
    return SimpleNamespace(
        config={}, session_id="session", sentence_id="reply-1",
        websocket=SimpleNamespace(send=send), messages=messages,
        reply_lifecycle=lifecycle, audio_rate_controller=None,
        logger=logger, client_abort=False, abort_generation=0,
        _abort_lock=threading.Lock(), tts=None,
        clear_queues=Mock(), clearSpeakStatus=lambda: lifecycle.fail(),
    )


async def start(conn):
    assert await sendAudioHandle.send_tts_message(
        conn, "start", protocol_sentence_id=conn.sentence_id,
    )
    return conn.reply_lifecycle.generation


def hold_drain(monkeypatch):
    entered, release = asyncio.Event(), asyncio.Event()

    async def drain(_conn):
        entered.set()
        await release.wait()

    monkeypatch.setattr(sendAudioHandle, "_wait_for_audio_completion", drain)
    return entered, release


def states(conn):
    return [item["state"] for item in conn.messages]


def test_normal_stop_waits_for_tail_and_deduplicates_pending_stop(monkeypatch):
    async def scenario():
        conn = make_connection()
        await start(conn)
        entered, release = hold_drain(monkeypatch)
        stop = asyncio.create_task(sendAudioHandle.send_tts_message(conn, "stop"))
        await entered.wait()
        assert not conn._tts_protocol_stop_sent
        assert conn.reply_lifecycle.state is ReplyState.STOPPING
        assert not await sendAudioHandle.send_tts_message(conn, "stop")
        assert states(conn) == ["start"]
        release.set()
        assert await stop
        assert states(conn) == ["start", "stop"]
        assert conn._tts_protocol_stop_sent
        assert conn.reply_lifecycle.state is ReplyState.COMPLETED

    asyncio.run(scenario())


def test_cancelled_drain_still_allows_real_abort_stop(monkeypatch):
    async def scenario():
        conn = make_connection()
        await start(conn)
        entered, _ = hold_drain(monkeypatch)
        stop = asyncio.create_task(sendAudioHandle.send_tts_message(conn, "stop"))
        await entered.wait()
        stop.cancel()
        with pytest.raises(asyncio.CancelledError):
            await stop
        assert not conn._tts_protocol_stop_sent
        await handleAbortMessage(conn, preserve_user_audio=True)
        assert states(conn) == ["start", "stop"]
        assert conn.messages[-1]["reason"] == "abort"
        assert conn.reply_lifecycle.state is ReplyState.INTERRUPTED

    asyncio.run(scenario())


@pytest.mark.parametrize("next_sentence", ["reply-1", "reply-2"])
def test_abort_preempts_drain_and_resumed_old_stop_cannot_stop_new_reply(monkeypatch, next_sentence):
    async def scenario():
        conn = make_connection()
        old_generation = await start(conn)
        entered, release = hold_drain(monkeypatch)
        stop = asyncio.create_task(sendAudioHandle.send_tts_message(conn, "stop"))
        await entered.wait()
        await asyncio.wait_for(handleAbortMessage(conn, preserve_user_audio=True), 0.5)
        conn.sentence_id = next_sentence
        assert await start(conn) > old_generation
        controller = SimpleNamespace(stop_sending=Mock())
        conn.audio_rate_controller = controller
        release.set()
        assert not await stop
        controller.stop_sending.assert_not_called()
        assert states(conn) == ["start", "stop", "start"]
        assert not conn._tts_protocol_stop_sent
        assert conn.reply_lifecycle.is_active

    asyncio.run(scenario())


def test_cancel_old_attempt_does_not_release_abort_already_sending(monkeypatch):
    async def scenario():
        conn = make_connection()
        await start(conn)
        entered, _ = hold_drain(monkeypatch)
        old_stop = asyncio.create_task(sendAudioHandle.send_tts_message(conn, "stop"))
        await entered.wait()
        sending, release_send = asyncio.Event(), asyncio.Event()

        async def send(payload):
            sending.set()
            await release_send.wait()
            conn.messages.append(json.loads(payload))

        conn.websocket.send = send
        abort = asyncio.create_task(sendAudioHandle.send_tts_message(
            conn, "stop", drain_audio=False, reason="abort",
        ))
        await sending.wait()
        old_stop.cancel()
        with pytest.raises(asyncio.CancelledError):
            await old_stop
        assert not await sendAudioHandle.send_tts_message(
            conn, "stop", drain_audio=False, reason="abort",
        )
        release_send.set()
        assert await abort
        assert states(conn) == ["start", "stop"]

    asyncio.run(scenario())


def test_obsolete_drain_timeout_cannot_reset_reused_controller():
    async def scenario():
        conn = make_connection()
        conn.config.update({
            "tts_audio_send_timeout_seconds": 0.01,
            "tts_audio_completion_grace_seconds": 0.01,
        })
        controller = SimpleNamespace(
            queue=[], frame_duration=60, last_send_error=None,
            queue_empty_event=asyncio.Event(), reset=Mock(), stop_sending=Mock(),
        )
        conn.audio_rate_controller = controller
        await start(conn)
        stop = asyncio.create_task(sendAudioHandle.send_tts_message(conn, "stop"))
        await asyncio.sleep(0)
        await handleAbortMessage(conn, preserve_user_audio=True)
        conn.sentence_id = "reply-2"
        await start(conn)
        assert not await asyncio.wait_for(stop, 0.5)
        controller.reset.assert_not_called()
        controller.stop_sending.assert_called_once()
        assert conn.reply_lifecycle.is_active

    asyncio.run(scenario())


def test_queued_current_subtitle_survives_drain_but_not_abort_or_new_generation(monkeypatch):
    async def scenario():
        conn = make_connection()
        await start(conn)
        callbacks = []
        conn.audio_rate_controller = SimpleNamespace(
            add_message=callbacks.append, stop_sending=Mock(),
        )
        conn.audio_flow_control = {"sentence_id": conn.sentence_id}
        conn.tts = SimpleNamespace(tts_audio_first_sentence=False)
        conn.calling = False
        monkeypatch.setattr(sendAudioHandle, "sendAudio", AsyncMock())
        await sendAudioHandle.sendAudioMessage(
            conn, SentenceType.FIRST, [], "current subtitle",
            sentence_id=conn.sentence_id,
        )
        assert len(callbacks) == 1
        entered, release = hold_drain(monkeypatch)
        stop = asyncio.create_task(sendAudioHandle.send_tts_message(conn, "stop"))
        await entered.wait()
        assert await callbacks[0]()
        await handleAbortMessage(conn, preserve_user_audio=True)
        assert not await callbacks[0]()
        await start(conn)  # Reusing a sentence id still advances the reply generation.
        assert not await callbacks[0]()
        release.set()
        assert not await stop
        assert states(conn) == ["start", "sentence_start", "stop", "start"]

    asyncio.run(scenario())


def test_abort_does_not_wait_for_normal_stop_notification(monkeypatch):
    async def scenario():
        conn = make_connection()
        conn.config["enable_stop_tts_notify"] = True
        await start(conn)
        entered, release = asyncio.Event(), asyncio.Event()

        async def load_notification(*_args, **_kwargs):
            entered.set()
            await release.wait()
            return [b"notification"]

        monkeypatch.setattr(sendAudioHandle, "audio_to_data", load_notification)
        audio = AsyncMock()
        monkeypatch.setattr(sendAudioHandle, "sendAudio", audio)
        stop = asyncio.create_task(sendAudioHandle.send_tts_message(conn, "stop"))
        await entered.wait()
        await asyncio.wait_for(handleAbortMessage(conn, preserve_user_audio=True), 0.5)
        release.set()
        assert not await stop
        audio.assert_not_awaited()
        assert states(conn) == ["start", "stop"]

    asyncio.run(scenario())


def test_normal_stop_keeps_notification_before_drain_and_stop(monkeypatch):
    async def scenario():
        conn = make_connection()
        conn.config["enable_stop_tts_notify"] = True
        await start(conn)
        events = []

        async def audio(_conn, packet):
            events.append(packet)

        async def drain(_conn):
            events.append("drain")

        async def send(payload):
            events.append(json.loads(payload)["state"])

        conn.websocket.send = send
        monkeypatch.setattr(sendAudioHandle, "audio_to_data", AsyncMock(return_value=[b"one", b"two"]))
        monkeypatch.setattr(sendAudioHandle, "sendAudio", audio)
        monkeypatch.setattr(sendAudioHandle, "_wait_for_audio_completion", drain)
        assert await sendAudioHandle.send_tts_message(conn, "stop")
        assert events == [b"one", b"two", "drain", "stop"]

    asyncio.run(scenario())


@pytest.mark.parametrize("failure_stage", ["drain", "send"])
def test_stop_failure_keeps_terminal_convergence_and_allows_unsent_retry(monkeypatch, failure_stage):
    async def scenario():
        conn = make_connection()
        await start(conn)
        error = RuntimeError("audio failure")
        if failure_stage == "drain":
            monkeypatch.setattr(sendAudioHandle, "_wait_for_audio_completion", AsyncMock(side_effect=error))
        else:
            conn.websocket.send = AsyncMock(side_effect=error)
        with pytest.raises(RuntimeError, match="audio failure"):
            await sendAudioHandle.send_tts_message(conn, "stop", drain_audio=failure_stage == "drain")
        assert not conn.reply_lifecycle.is_active
        if failure_stage == "drain":
            assert states(conn) == ["start", "stop"]
            assert conn._tts_protocol_stop_sent
        else:
            assert not conn._tts_protocol_stop_sent
            conn.websocket.send = AsyncMock()
            assert await sendAudioHandle.send_tts_message(conn, "stop", drain_audio=False, reason="abort")
            conn.websocket.send.assert_awaited_once()

    asyncio.run(scenario())
