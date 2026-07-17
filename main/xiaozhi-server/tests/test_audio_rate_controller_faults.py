import asyncio
import json
import sys
import time
from pathlib import Path
from types import SimpleNamespace

import pytest


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings

settings.config_file_valid = True

from core.handle import sendAudioHandle
from core.utils.audioRateController import AudioRateController
from core.providers.tts.dto.dto import SentenceType


class FakeLogger:
    def bind(self, **kwargs):
        return self

    def debug(self, message):
        return None

    def info(self, message):
        return None


class FakeWebSocket:
    def __init__(self, behavior):
        self.behavior = behavior
        self.calls = 0

    async def send(self, payload):
        self.calls += 1
        if self.behavior == "raise":
            raise ConnectionError("simulated websocket failure")
        if self.behavior == "hang":
            await asyncio.Event().wait()


def _connection(websocket, send_timeout=0.03, completion_grace=0.03):
    return SimpleNamespace(
        websocket=websocket,
        client_abort=False,
        conn_from_mqtt_gateway=False,
        last_activity_time=0,
        config={"tts_audio_completion_grace_seconds": completion_grace},
        tts=SimpleNamespace(audio_send_timeout_seconds=send_timeout),
        logger=FakeLogger(),
    )


async def _run_sender(conn, controller):
    conn.audio_rate_controller = controller
    flow_control = {"packet_count": 5, "sequence": 0}
    sendAudioHandle._start_background_sender(conn, controller, flow_control)
    controller.add_audio(b"opus")


def test_websocket_send_error_releases_waiter_and_next_sender_recovers():
    async def scenario():
        failed_ws = FakeWebSocket("raise")
        conn = _connection(failed_ws)
        controller = AudioRateController(frame_duration=1)
        await _run_sender(conn, controller)

        started = time.monotonic()
        with pytest.raises(RuntimeError, match="音频发送失败"):
            await sendAudioHandle._wait_for_audio_completion(conn)
        elapsed = time.monotonic() - started

        assert elapsed < 0.1
        assert controller.queue_empty_event.is_set()
        assert len(controller.queue) == 0

        recovered_ws = FakeWebSocket("success")
        conn.websocket = recovered_ws
        controller.start_sending(
            lambda packet: sendAudioHandle._do_send_audio(
                conn,
                packet,
                {"packet_count": 5, "sequence": 0},
            )
        )
        controller.add_audio(b"next-opus")
        await sendAudioHandle._wait_for_audio_completion(conn)

        assert recovered_ws.calls == 1
        assert controller.last_send_error is None
        controller.stop_sending()
        await asyncio.sleep(0)

    asyncio.run(scenario())


def test_half_open_websocket_send_times_out_without_blocking_heartbeat():
    async def scenario():
        conn = _connection(FakeWebSocket("hang"), send_timeout=0.03)
        controller = AudioRateController(frame_duration=1)
        await _run_sender(conn, controller)

        heartbeat_count = 0

        async def heartbeat():
            nonlocal heartbeat_count
            for _ in range(12):
                await asyncio.sleep(0.005)
                heartbeat_count += 1

        heartbeat_task = asyncio.create_task(heartbeat())
        started = time.monotonic()
        with pytest.raises(RuntimeError, match="音频发送失败"):
            await sendAudioHandle._wait_for_audio_completion(conn)
        elapsed = time.monotonic() - started
        await heartbeat_task

        assert elapsed < 0.1
        assert heartbeat_count == 12
        assert controller.queue_empty_event.is_set()
        assert len(controller.queue) == 0
        assert controller.pending_send_task.cancelled() or controller.pending_send_task.done()

    asyncio.run(scenario())


def test_audio_completion_timeout_tracks_queue_duration_without_absolute_cap():
    conn = _connection(
        FakeWebSocket("success"),
        send_timeout=5,
        completion_grace=5,
    )
    controller = AudioRateController(frame_duration=60)

    controller.queue.extend(("audio", b"opus") for _ in range(50))
    assert sendAudioHandle._audio_completion_timeout(conn, controller) == 13.0

    controller.queue.extend(("audio", b"opus") for _ in range(950))
    assert sendAudioHandle._audio_completion_timeout(conn, controller) == 70.0

    controller.queue.clear()
    controller.queue.append(("audio", b"opus"))
    assert sendAudioHandle._audio_completion_timeout(conn, controller) == pytest.approx(10.06)


def test_first_audio_sets_speaking_and_preserves_protocol_order(monkeypatch):
    async def scenario():
        sent = []

        class RecordingWebSocket:
            async def send(self, payload):
                sent.append(payload)

        conn = SimpleNamespace(
            websocket=RecordingWebSocket(),
            client_abort=False,
            client_is_speaking=False,
            conn_from_mqtt_gateway=False,
            last_activity_time=0,
            config={"tts_audio_send_delay": -1},
            logger=FakeLogger(),
            tts=SimpleNamespace(tts_audio_first_sentence=True),
            sentence_id="turn-1",
            session_id="session-1",
            calling=False,
            close_after_chat=False,
            _tts_started_at=0,
        )

        async def fake_send_audio(_conn, _audios, frame_duration=60):
            sent.append(b"opus")

        monkeypatch.setattr(sendAudioHandle, "sendAudio", fake_send_audio)
        await sendAudioHandle.sendAudioMessage(
            conn, SentenceType.FIRST, b"opus", "你好", "turn-1"
        )

        states = [json.loads(payload)["state"] for payload in sent[:2]]
        assert states == ["start", "sentence_start"]
        assert sent[2] == b"opus"
        assert conn.client_is_speaking is True
        assert conn._tts_started_at > 0

    asyncio.run(scenario())


@pytest.mark.parametrize(
    ("features", "expected_close_calls", "expected_reset_calls"),
    [
        ({"persistent_websocket": True}, 0, 1),
        ({"persistent_websocket": False}, 1, 0),
        ({}, 1, 0),
        (None, 1, 0),
    ],
)
def test_exit_after_last_respects_persistent_websocket_capability(
    monkeypatch, features, expected_close_calls, expected_reset_calls
):
    async def scenario():
        close_calls = 0
        reset_calls = 0

        async def fake_send_tts_message(_conn, _state, _text=None):
            return None

        async def fake_send_audio(_conn, _audios, frame_duration=60):
            return None

        async def close():
            nonlocal close_calls
            close_calls += 1

        def reset_audio_states():
            nonlocal reset_calls
            reset_calls += 1

        conn = SimpleNamespace(
            tts=SimpleNamespace(tts_audio_first_sentence=False),
            sentence_id="turn-1",
            calling=False,
            close_after_chat=True,
            client_abort=True,
            features=features,
            close=close,
            reset_audio_states=reset_audio_states,
            logger=FakeLogger(),
        )
        monkeypatch.setattr(sendAudioHandle, "send_tts_message", fake_send_tts_message)
        monkeypatch.setattr(sendAudioHandle, "sendAudio", fake_send_audio)

        await sendAudioHandle.sendAudioMessage(
            conn, SentenceType.LAST, [], None, "turn-1"
        )

        assert close_calls == expected_close_calls
        assert reset_calls == expected_reset_calls
        if features == {"persistent_websocket": True}:
            assert conn.close_after_chat is False
            assert conn.client_abort is False

    asyncio.run(scenario())
