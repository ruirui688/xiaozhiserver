import asyncio
import threading
from types import SimpleNamespace

from core.session import CloseCoordinator, SessionLifecycle


class _Logger:
    def bind(self, **kwargs):
        return self

    def warning(self, *args, **kwargs):
        pass


class _RateController:
    def __init__(self):
        self.stop_calls = 0
        self.reset_calls = 0

    def stop_sending(self):
        self.stop_calls += 1

    def reset(self):
        self.reset_calls += 1


def _connection():
    return SimpleNamespace(
        logger=_Logger(),
        session_id="session-id",
        closing=False,
        stop_event=threading.Event(),
        _abort_lock=threading.Lock(),
        _lifecycle_lock=threading.Lock(),
        client_abort=False,
        abort_generation=0,
        audio_rate_controller=_RateController(),
        _background_initialize_task=None,
    )


def test_begin_closing_is_idempotent_and_publishes_state():
    conn = _connection()
    lifecycle = SessionLifecycle(conn)

    assert lifecycle.begin_closing() is True
    assert lifecycle.begin_closing() is False

    assert conn.closing is True
    assert conn.stop_event.is_set()
    assert conn.client_abort is True
    assert conn.abort_generation == 1
    assert conn.audio_rate_controller.stop_calls == 1
    assert conn.audio_rate_controller.reset_calls == 1


def test_assign_initialized_component_rejects_values_after_close_begins():
    conn = _connection()
    lifecycle = SessionLifecycle(conn)

    assert lifecycle.assign_initialized_component("tts", "first") is True
    lifecycle.begin_closing()
    assert lifecycle.assign_initialized_component("tts", "replacement") is False
    assert conn.tts == "first"


def test_close_coordinator_wait_for_threads_keeps_event_loop_responsive():
    async def run_test():
        conn = _connection()
        coordinator = CloseCoordinator(conn, timeout_seconds=1)
        release = threading.Event()
        thread = threading.Thread(target=release.wait, args=(0.1,))
        thread.start()

        wait_task = asyncio.create_task(
            coordinator.wait_for_threads([thread], coordinator.new_deadline())
        )
        ticks = 0
        while not wait_task.done():
            ticks += 1
            await asyncio.sleep(0.01)
        await wait_task

        assert ticks >= 5
        assert thread.is_alive() is False

    asyncio.run(run_test())
