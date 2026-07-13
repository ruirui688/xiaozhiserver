import asyncio
import queue
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

from config import settings

settings.config_file_valid = True

import core.connection as connection_module
import core.handle.reportThreadManager as report_manager_module
from core.connection import ConnectionHandler
from core.handle.reportThreadManager import ReportQueue, ReportThreadManager


class _Logger:
    def bind(self, **kwargs):
        return self

    def debug(self, *args, **kwargs):
        pass

    def info(self, *args, **kwargs):
        pass

    def warning(self, *args, **kwargs):
        pass

    def error(self, *args, **kwargs):
        pass


class _RateController:
    def __init__(self, events):
        self.events = events

    def stop_sending(self):
        self.events.append("rate-stop")

    def reset(self):
        self.events.append("rate-reset")


class _Provider:
    def __init__(self):
        self.close_calls = 0
        self.open_calls = 0
        self.tts_text_queue = queue.Queue()
        self.tts_audio_queue = queue.Queue()

    async def close(self):
        self.close_calls += 1
        await asyncio.sleep(0.01)

    async def open_audio_channels(self, conn):
        self.open_calls += 1


class _WebSocket:
    def __init__(self):
        self.close_calls = 0

    async def close(self):
        self.close_calls += 1
        await asyncio.sleep(0.01)


class _SlowReportManager:
    def __init__(self, events, delay=0.15):
        self.events = events
        self.delay = delay
        self.stop_calls = 0
        self.state_at_stop = None

    def stop(self, conn, timeout=10.0):
        self.stop_calls += 1
        self.state_at_stop = (
            conn.closing,
            conn.stop_event.is_set(),
            conn.client_abort,
        )
        self.events.append("report-stop")
        time.sleep(min(self.delay, timeout))


class _Memory:
    def __init__(self, delay=0.15):
        self.delay = delay
        self.saved = False

    async def save_memory(self, dialogue, session_id):
        await asyncio.sleep(self.delay)
        self.saved = True


def _connection(events=None):
    events = events if events is not None else []
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.logger = _Logger()
    conn.closing = False
    conn.closed = False
    conn._close_lock = asyncio.Lock()
    conn._lifecycle_lock = threading.Lock()
    conn._background_initialize_task = None
    conn._components_initialize_future = None
    conn.stop_event = threading.Event()
    conn._abort_lock = threading.Lock()
    conn.client_abort = False
    conn.abort_generation = 0
    conn.audio_rate_controller = _RateController(events)
    conn.vad = None
    conn.func_handler = None
    conn.report_manager = _SlowReportManager(events, delay=0)
    conn.report_thread = None
    conn.report_queue = queue.Queue()
    conn.tts = _Provider()
    conn.asr = _Provider()
    conn.audio_buffer = bytearray(b"audio")
    conn.asr_audio_queue = queue.Queue()
    conn.iot_descriptors = {"test": {}}
    conn.websocket = None
    conn.executor = ThreadPoolExecutor(max_workers=1)
    conn._modules_ready = threading.Event()
    conn.voiceprint_provider = None
    conn.memory = None
    conn.session_id = None
    conn.config = {}
    conn.read_config_from_api = False
    conn.dialogue = SimpleNamespace(dialogue=[])
    return conn


async def _heartbeat_until(task, interval=0.01):
    ticks = []
    while not task.done():
        ticks.append(time.monotonic())
        await asyncio.sleep(interval)
    return ticks


async def _save_and_close_does_not_block_event_loop_during_joins_and_report_stop():
    events = []
    conn = _connection(events)
    conn.report_manager = _SlowReportManager(events, delay=0.15)
    conn.memory = _Memory(delay=0.15)
    ws = _WebSocket()

    close_task = asyncio.create_task(conn._save_and_close(ws))
    ticks = await _heartbeat_until(close_task)
    await close_task

    assert len(ticks) >= 8
    assert max(b - a for a, b in zip(ticks, ticks[1:])) < 0.05
    assert conn.memory.saved is True
    assert conn.report_manager.state_at_stop == (True, True, True)
    assert events[:2] == ["rate-stop", "rate-reset"]
    assert ws.close_calls == 1


async def _close_is_idempotent_for_concurrent_and_repeated_calls():
    conn = _connection()
    ws = _WebSocket()

    await asyncio.gather(conn.close(ws), conn.close(ws))
    await conn.close(ws)

    assert conn.closed is True
    assert conn.report_manager.stop_calls == 1
    assert conn.tts.close_calls == 1
    assert conn.asr.close_calls == 1
    assert ws.close_calls == 1


async def _cancelled_background_initialize_cannot_start_component_thread():
    conn = _connection()
    started = asyncio.Event()
    cancelled = asyncio.Event()
    release = asyncio.Event()
    initialized = []

    async def slow_private_initialize():
        started.set()
        try:
            await release.wait()
        except asyncio.CancelledError:
            cancelled.set()
            await release.wait()
        return True

    conn._initialize_private_config_async = slow_private_initialize
    conn._initialize_components = lambda: initialized.append(True)
    conn._background_initialize_task = asyncio.create_task(
        conn._background_initialize()
    )
    await started.wait()

    close_task = asyncio.create_task(conn.close())
    await asyncio.wait_for(cancelled.wait(), timeout=1)
    release.set()
    await close_task
    await asyncio.sleep(0.02)

    assert conn._background_initialize_task.done()
    assert initialized == []
    assert conn._components_initialize_future is None


async def _component_created_during_close_is_not_assigned_or_opened():
    conn = _connection()
    conn.tts = None
    conn.asr = None
    conn.loop = asyncio.get_running_loop()
    candidate = _Provider()
    started = threading.Event()
    release = threading.Event()

    def initialize_tts():
        started.set()
        release.wait(timeout=1)
        return candidate

    conn._initialize_tts = initialize_tts
    initialize_future = conn.loop.run_in_executor(None, conn._initialize_components)
    assert await asyncio.to_thread(started.wait, 1)

    await conn.close()
    release.set()
    await initialize_future

    assert conn.tts is None
    assert candidate.open_calls == 0
    assert conn._modules_ready.is_set() is False


async def _close_does_not_trigger_session_memory_processing(monkeypatch):
    trigger_calls = []

    async def fake_title(*args, **kwargs):
        return None

    async def forbidden_trigger(*args, **kwargs):
        trigger_calls.append((args, kwargs))
        return 0

    monkeypatch.setattr(connection_module, "generate_and_save_chat_title", fake_title)
    monkeypatch.setattr(
        connection_module,
        "trigger_session_memory_processing",
        forbidden_trigger,
        raising=False,
    )

    conn = _connection()
    conn.session_id = "session-id"
    conn.config = {"agent_id": "agent-id"}
    conn.read_config_from_api = True
    await conn._save_and_close(_WebSocket())

    assert trigger_calls == []


async def _close_flushes_pending_reports_before_releasing_resources(monkeypatch):
    processed = []

    async def fake_report(conn, report_type, text, audio_data, report_time):
        processed.append((report_type, text, report_time))

    monkeypatch.setattr(report_manager_module, "report", fake_report)
    conn = _connection()
    conn.read_config_from_api = True
    conn.need_bind = False
    conn.chat_history_conf = 1
    conn.report_queue = ReportQueue()
    conn.report_manager = ReportThreadManager()
    conn.report_manager.start(conn)
    expected = [
        (1, "user", 1),
        (2, "assistant", 2),
    ]
    for report_type, text, report_time in expected:
        conn.report_queue.put((report_type, text, None, report_time))

    await conn.close(_WebSocket())

    assert sorted(processed, key=lambda item: item[2]) == expected
    assert conn.report_queue.unfinished_tasks == 0
    assert conn.report_queue.put((1, "late", None, 3)) is False


def test_save_and_close_does_not_block_event_loop_during_joins_and_report_stop():
    asyncio.run(
        _save_and_close_does_not_block_event_loop_during_joins_and_report_stop()
    )


def test_close_is_idempotent_for_concurrent_and_repeated_calls():
    asyncio.run(_close_is_idempotent_for_concurrent_and_repeated_calls())


def test_cancelled_background_initialize_cannot_start_component_thread():
    asyncio.run(_cancelled_background_initialize_cannot_start_component_thread())


def test_component_created_during_close_is_not_assigned_or_opened():
    asyncio.run(_component_created_during_close_is_not_assigned_or_opened())


def test_close_does_not_trigger_session_memory_processing(monkeypatch):
    asyncio.run(_close_does_not_trigger_session_memory_processing(monkeypatch))


def test_close_flushes_pending_reports_before_releasing_resources(monkeypatch):
    asyncio.run(_close_flushes_pending_reports_before_releasing_resources(monkeypatch))
