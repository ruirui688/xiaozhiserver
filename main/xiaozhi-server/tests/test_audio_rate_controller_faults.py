import asyncio
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


class FakeLogger:
    def bind(self, **kwargs):
        return self

    def debug(self, message):
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


def _connection(websocket, timeout=0.03):
    return SimpleNamespace(
        websocket=websocket,
        client_abort=False,
        conn_from_mqtt_gateway=False,
        last_activity_time=0,
        config={"tts_audio_completion_timeout_seconds": timeout},
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
        conn = _connection(FakeWebSocket("hang"), timeout=0.03)
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
        with pytest.raises(TimeoutError, match="等待音频发送完成超时"):
            await sendAudioHandle._wait_for_audio_completion(conn)
        elapsed = time.monotonic() - started
        await heartbeat_task

        assert elapsed < 0.1
        assert heartbeat_count == 12
        assert controller.queue_empty_event.is_set()
        assert len(controller.queue) == 0
        assert controller.pending_send_task.cancelled() or controller.pending_send_task.done()

    asyncio.run(scenario())


def test_audio_completion_timeout_uses_queue_duration_with_absolute_cap():
    conn = _connection(FakeWebSocket("success"), timeout=20)
    controller = AudioRateController(frame_duration=60)

    controller.queue.extend(("audio", b"opus") for _ in range(50))
    assert sendAudioHandle._audio_completion_timeout(conn, controller) == 8.0

    controller.queue.extend(("audio", b"opus") for _ in range(300))
    assert sendAudioHandle._audio_completion_timeout(conn, controller) == 20.0

    controller.queue.clear()
    controller.queue.append(("audio", b"opus"))
    assert sendAudioHandle._audio_completion_timeout(conn, controller) == pytest.approx(5.06)
