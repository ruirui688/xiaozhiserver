import queue
import threading
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import core.handle.reportThreadManager as report_manager_module
from core.handle.reportThreadManager import ReportQueue, ReportThreadManager


class _Logger:
    def bind(self, **kwargs):
        return self

    def info(self, *args, **kwargs):
        pass

    def warning(self, *args, **kwargs):
        pass

    def error(self, *args, **kwargs):
        pass


def _connection():
    return SimpleNamespace(
        read_config_from_api=True,
        need_bind=False,
        chat_history_conf=1,
        report_thread=None,
        report_queue=ReportQueue(),
        stop_event=threading.Event(),
        executor=ThreadPoolExecutor(max_workers=2),
        logger=_Logger(),
    )


def test_stop_drains_reports_even_when_connection_stop_event_is_already_set(monkeypatch):
    processed = []
    processed_lock = threading.Lock()

    async def fake_report(conn, report_type, text, audio_data, report_time):
        with processed_lock:
            processed.append((report_type, text, report_time))

    monkeypatch.setattr(report_manager_module, "report", fake_report)
    conn = _connection()
    manager = ReportThreadManager()
    manager.start(conn)

    expected = [
        (1, "user-one", 1),
        (2, "assistant-one", 2),
        (1, "user-two", 3),
    ]
    for report_type, text, report_time in expected:
        assert conn.report_queue.put(
            (report_type, text, None, report_time)
        ) is True

    # SessionLifecycle.begin_closing() sets this before report cleanup.
    conn.stop_event.set()
    manager.stop(conn, timeout=2)
    conn.executor.shutdown(wait=True)

    assert sorted(processed, key=lambda item: item[2]) == expected
    assert conn.report_queue.unfinished_tasks == 0
    assert conn.report_thread.is_alive() is False


def test_sealed_report_queue_rejects_new_reports_after_fifo_eof():
    report_queue = ReportQueue()
    assert report_queue.put((1, "accepted", None, 1)) is True
    assert report_queue.seal() is True
    assert report_queue.put((1, "rejected", None, 2)) is False

    assert report_queue.get_nowait() == (1, "accepted", None, 1)
    report_queue.task_done()
    assert report_queue.get_nowait() is None
    report_queue.task_done()
    assert report_queue.unfinished_tasks == 0
    try:
        report_queue.get_nowait()
    except queue.Empty:
        pass
    else:
        raise AssertionError("sealed queue accepted an unexpected item")


def test_stop_without_started_worker_seals_queue_without_orphan_sentinel():
    conn = _connection()

    ReportThreadManager().stop(conn, timeout=0.1)
    conn.executor.shutdown(wait=True)

    assert conn.report_queue.unfinished_tasks == 0
    assert conn.report_queue.empty()
    assert conn.report_queue.put((1, "late", None, 1)) is False
