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


def test_hot_reload_enables_reporting_without_reconnecting():
    conn = _connection()
    conn.chat_history_conf = 0
    conn.config = {"chat_history_conf": 0}
    conn.closing = False
    conn.closed = False
    manager = ReportThreadManager()

    assert manager.update_chat_history_config(conn, 2) is True
    assert conn.chat_history_conf == 2
    assert conn.config["chat_history_conf"] == 2
    assert conn.report_thread.is_alive() is True

    manager.stop(conn, timeout=1)
    conn.executor.shutdown(wait=True)


def test_hot_reload_disables_new_reports_without_sealing_worker_queue():
    conn = _connection()
    conn.config = {"chat_history_conf": 1}
    conn.closing = False
    conn.closed = False
    manager = ReportThreadManager()
    manager.start(conn)

    assert manager.update_chat_history_config(conn, 0) is True
    assert conn.chat_history_conf == 0
    assert conn.config["chat_history_conf"] == 0
    assert conn.report_thread.is_alive() is True
    assert conn.report_queue.put((1, "already accepted boundary", None, 1)) is True

    manager.stop(conn, timeout=1)
    conn.executor.shutdown(wait=True)


def test_hot_reload_cannot_restart_reporting_after_connection_stop():
    conn = _connection()
    conn.chat_history_conf = 0
    conn.config = {"chat_history_conf": 0}
    conn.closing = False
    conn.closed = False
    manager = ReportThreadManager()
    manager.stop(conn, timeout=0.1)

    assert manager.update_chat_history_config(conn, 2) is False
    assert conn.report_thread is None
    assert conn.report_queue.put((1, "late", None, 1)) is False

    conn.executor.shutdown(wait=True)
