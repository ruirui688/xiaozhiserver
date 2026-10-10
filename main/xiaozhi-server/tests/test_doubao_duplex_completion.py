"""Exercise realtime completion through the actual upstream reader and device output."""

import asyncio
import json
from types import MethodType
from unittest.mock import Mock

import pytest

from test_doubao_duplex import Socket, make_session
from core.connection import ConnectionHandler
from core.handle import sendAudioHandle
from core.providers.realtime.doubao_duplex_transport import DoubaoDuplexTransport, OFFICIAL_URL
from core.session.reply_lifecycle import ReplyLifecycle, ReplyState


PCM_FRAME = b"\x01\x00" * 1440  # 60 ms of mono PCM16 at 24 kHz.
AUDIO_DONE = "response.output_audio.done"
SPEECH_STARTED = "conversation.item.input_audio_transcription.started"
TRANSCRIPT_DONE = "conversation.item.input_audio_transcription.completed"


class DeviceSocket:
    def __init__(self, hold_audio_after=None):
        self.sent = []
        self.hold_audio_after = hold_audio_after
        self.audio_blocked = asyncio.Event()
        self.release_audio = asyncio.Event()

    async def send(self, payload):
        if isinstance(payload, bytes):
            if self.hold_audio_after is not None and len(self.audio) >= self.hold_audio_after:
                self.audio_blocked.set()
                await self.release_audio.wait()
            self.sent.append(payload)
        else:
            self.sent.append(json.loads(payload))

    @property
    def audio(self):
        return [message for message in self.sent if isinstance(message, bytes)]

    @property
    def stops(self):
        return [message for message in self.sent if isinstance(message, dict)
                and message.get("type") == "tts" and message.get("state") == "stop"]


class DrainGate:
    """Hold the real completion boundary, without depending on wall-clock speed."""

    def __init__(self, monkeypatch):
        self.entered = asyncio.Event()
        self.release = asyncio.Event()
        self.cancelled = asyncio.Event()
        self.error = None
        self.original = sendAudioHandle._wait_for_audio_completion
        monkeypatch.setattr(sendAudioHandle, "_wait_for_audio_completion", self.wait)

    async def wait(self, conn):
        self.entered.set()
        try:
            await self.release.wait()
            if self.error is not None:
                raise self.error
            await self.original(conn)
        except asyncio.CancelledError:
            self.cancelled.set()
            raise


class Harness:
    def __init__(self, monkeypatch, *, hold_audio_after=None):
        self.session, _, self.conn = make_session()
        self.device = DeviceSocket(hold_audio_after)
        self.conn.websocket = self.device
        self.conn.session_id = "test-session"
        self.conn.sentence_id = None
        self.conn.tts = None  # Realtime deliberately has no traditional TTS provider.
        self.conn.conn_from_mqtt_gateway = False
        self.conn.last_activity_time = 0
        self.conn.close_after_chat = False
        self.conn.abort_generation = 0
        self.conn.reply_lifecycle = ReplyLifecycle()
        self.conn.clear_queues = MethodType(ConnectionHandler.clear_queues, self.conn)
        self.conn.clearSpeakStatus = MethodType(ConnectionHandler.clearSpeakStatus, self.conn)
        self.conn.audio_input_ready.set()
        self.conn.conversation_ready.set()
        self.reports = Mock(return_value=True)
        monkeypatch.setattr("core.providers.realtime.doubao_realtime.enqueue_tts_report", self.reports)
        monkeypatch.setattr("core.providers.realtime.doubao_duplex.enqueue_asr_report", Mock())
        self.upstream = Socket()
        self.transport = DoubaoDuplexTransport({})
        self.transport.websocket = self.upstream
        self.session.transport = self.transport
        self.processed = {}
        self.handler_errors = []

        async def handler(event):
            try:
                await self.session.handle_event(event)
            except Exception as exc:
                self.handler_errors.append(exc)
                raise
            finally:
                marker = event.get("test_marker")
                if marker is not None:
                    self.processed[marker].set()

        self.transport.handler = handler
        self.transport.reader_task = asyncio.create_task(self.transport._reader())

    async def prime(self, frames=1):
        self.session._response_active = True
        self.session._response_id = "old-response"
        self.session.response_text = "previous answer"
        await self.session._handle_audio(PCM_FRAME * frames)
        self.old_generation = self.conn.reply_lifecycle.generation
        self.old_sentence = self.conn.sentence_id

    async def send(self, kind, *, wait=True, **fields):
        marker = len(self.processed)
        processed = self.processed[marker] = asyncio.Event()
        await self.upstream.queue.put({"type": kind, "test_marker": marker, **fields})
        if wait:
            # This is a deadlock budget, not a latency/performance assertion.
            await asyncio.wait_for(processed.wait(), 2)
            assert not self.handler_errors
        return processed

    async def close(self):
        try:
            await asyncio.wait_for(self.session.close(), 2)
        finally:
            controller = getattr(self.conn, "audio_rate_controller", None)
            if controller is not None:
                controller.stop_sending()
                if controller.pending_send_task is not None:
                    await asyncio.gather(controller.pending_send_task, return_exceptions=True)


@pytest.mark.parametrize("interrupt_event", [SPEECH_STARTED, TRANSCRIPT_DONE])
def test_reader_interrupts_during_drain_and_preserves_the_next_reply(monkeypatch, interrupt_event):
    async def scenario():
        gate = DrainGate(monkeypatch)
        harness = Harness(monkeypatch, hold_audio_after=5)
        try:
            await harness.prime(frames=8)
            await asyncio.wait_for(harness.device.audio_blocked.wait(), 2)
            old_sender = harness.conn.audio_rate_controller.pending_send_task
            harness.session.input_audio = [b"new-user-audio"]
            await harness.send(AUDIO_DONE, wait=False, response_id="old-response")
            await asyncio.wait_for(gate.entered.wait(), 2)

            await harness.send(interrupt_event, transcript="next question")

            assert not gate.release.is_set()
            assert gate.cancelled.is_set()
            assert old_sender.done()
            assert not harness.conn.audio_rate_controller.queue
            assert harness.device.stops == [{"type": "tts", "state": "stop",
                "session_id": "test-session", "reason": "abort"}]
            assert harness.conn.reply_lifecycle.state is ReplyState.INTERRUPTED
            if interrupt_event == SPEECH_STARTED:
                assert harness.session.input_audio == [b"new-user-audio"]
                await harness.send(TRANSCRIPT_DONE, transcript="next question")

            # Let the old socket/drain gates go only after a new reply has begun.
            # The cancelled sender and finalizer must not resume into this reply.
            harness.device.hold_audio_after = None
            harness.session.response_text = "new answer"
            await harness.session._handle_audio(PCM_FRAME)
            new_sentence = harness.conn.sentence_id
            gate.release.set()
            harness.device.release_audio.set()
            await harness.send(AUDIO_DONE, response_id="old-response")
            assert len(harness.device.audio) == 6  # Five old prebuffer packets + one new.
            assert len(harness.device.stops) == 1
            assert new_sentence != harness.old_sentence
            assert harness.session.response_text == "new answer"
            assert harness.session._response_active and harness.session._reply_started
            assert harness.conn.reply_lifecycle.is_active
            assert harness.conn.reply_lifecycle.generation > harness.old_generation
            assert harness.reports.call_count == 1
        finally:
            gate.release.set()
            harness.device.release_audio.set()
            await harness.close()

    asyncio.run(scenario())


@pytest.mark.parametrize("include_response_id", [True, False])
def test_normal_done_drains_once_and_duplicate_done_does_not_report_twice(monkeypatch, include_response_id):
    async def scenario():
        gate = DrainGate(monkeypatch)
        harness = Harness(monkeypatch)
        try:
            await harness.prime(frames=8)
            fields = {"response_id": "old-response"} if include_response_id else {}
            await harness.send(AUDIO_DONE, wait=False, **fields)
            await asyncio.wait_for(gate.entered.wait(), 2)
            await harness.send(AUDIO_DONE, **fields)
            assert not harness.device.stops
            assert not harness.session.response_done.is_set()
            gate.release.set()
            await asyncio.wait_for(harness.session.response_done.wait(), 2)
            await harness.send(AUDIO_DONE, **fields)
            assert len(harness.device.audio) == 8
            assert len(harness.device.stops) == 1
            assert "reason" not in harness.device.stops[0]
            assert harness.conn.reply_lifecycle.state is ReplyState.COMPLETED
            harness.reports.assert_called_once()
            assert len(harness.reports.call_args.args[2]) == 8
        finally:
            gate.release.set()
            await harness.close()

    asyncio.run(scenario())


def test_close_cancels_completion_and_queued_audio_without_a_late_stop(monkeypatch):
    async def scenario():
        gate = DrainGate(monkeypatch)
        harness = Harness(monkeypatch, hold_audio_after=5)
        try:
            await harness.prime(frames=8)
            await asyncio.wait_for(harness.device.audio_blocked.wait(), 2)
            sender = harness.conn.audio_rate_controller.pending_send_task
            await harness.send(AUDIO_DONE, wait=False, response_id="old-response")
            await asyncio.wait_for(gate.entered.wait(), 2)
            await harness.session.close()
            assert gate.cancelled.is_set()
            assert sender.done()
            assert not harness.conn.audio_rate_controller.queue
            gate.release.set()
            harness.device.release_audio.set()
            await asyncio.sleep(0)
            assert len(harness.device.audio) == 5
            assert not harness.device.stops
            assert harness.transport.reader_task.done()
        finally:
            gate.release.set()
            harness.device.release_audio.set()
            await harness.close()

    asyncio.run(scenario())


def test_unexpected_completion_failure_converges_and_is_not_an_unhandled_task(monkeypatch):
    async def scenario():
        gate = DrainGate(monkeypatch)
        gate.error = ValueError("synthetic drain failure")
        harness = Harness(monkeypatch)
        failures = []
        loop = asyncio.get_running_loop()
        previous_handler = loop.get_exception_handler()
        loop.set_exception_handler(lambda _loop, context: failures.append(context))
        try:
            await harness.prime()
            await harness.send(AUDIO_DONE, wait=False, response_id="old-response")
            await asyncio.wait_for(gate.entered.wait(), 2)
            gate.release.set()
            await asyncio.wait_for(harness.session.response_done.wait(), 2)
            # A subsequent non-mutating response event is a reader progress barrier.
            await harness.send("response.done", response_id="old-response")
            assert not harness.session._response_active
            assert not harness.session._reply_started
            assert not harness.conn.reply_lifecycle.is_active
            assert len(harness.device.stops) == 1
            assert harness.device.stops[0].get("reason")
            assert not harness.conn.audio_input_ready.is_set()
            assert not harness.conn.conversation_ready.is_set()
            assert not failures
        finally:
            loop.set_exception_handler(previous_handler)
            gate.release.set()
            await harness.close()

    asyncio.run(scenario())


@pytest.mark.parametrize("maintenance", [True, False])
def test_close_cancels_reconnect_without_waiting_for_configuration_ack(monkeypatch, maintenance):
    async def scenario():
        harness = Harness(monkeypatch)
        configure_received = asyncio.Event()

        class NoConfigurationAck(Socket):
            async def send(self, raw):
                if json.loads(raw)["type"] == "session.create":
                    configure_received.set()
                    return  # The upstream never confirms configuration.
                await super().send(raw)

        socket = NoConfigurationAck()

        async def connect(*_args, **_kwargs):
            return socket

        transport = DoubaoDuplexTransport(
            {"base_url": OFFICIAL_URL, "api_key": "test-key"}, websocket_factory=connect)
        harness.session._transport_factory = lambda: transport
        try:
            if maintenance:
                assert harness.session.request_context_reset()
            else:
                reset = asyncio.create_task(harness.session.reset_context_now())
            await asyncio.wait_for(configure_received.wait(), 2)
            if maintenance:
                reset = harness.session._session_maintenance_task

            await asyncio.wait_for(harness.session.close(), 2)

            assert reset.done() and reset.cancelled()
            assert socket.closed and transport.reader_task.done()
            assert not harness.conn.audio_input_ready.is_set()
            assert not harness.conn.conversation_ready.is_set()
            # An old session may be closed again after its connection is reused.
            replacement_controller = Mock()
            harness.conn.audio_rate_controller = replacement_controller
            await harness.session.close()
            replacement_controller.reset.assert_not_called()
            del harness.conn.audio_rate_controller
        finally:
            await harness.close()

    asyncio.run(scenario())


def test_reconnect_configuration_acks_do_not_wait_on_the_turn_lock(monkeypatch):
    async def scenario():
        harness = Harness(monkeypatch)
        socket = Socket()

        async def connect(*_args, **_kwargs):
            return socket

        transport = DoubaoDuplexTransport(
            {"base_url": OFFICIAL_URL, "api_key": "test-key"}, websocket_factory=connect)
        harness.session._transport_factory = lambda: transport
        try:
            assert await asyncio.wait_for(harness.session.reset_context_now(), 2)
            assert harness.conn.audio_input_ready.is_set()
            assert harness.conn.conversation_ready.is_set()
            assert [event["type"] for event in socket.sent] == [
                "session.create", "input_audio_mute.commit", "session.update"]
        finally:
            await harness.close()

    asyncio.run(scenario())
