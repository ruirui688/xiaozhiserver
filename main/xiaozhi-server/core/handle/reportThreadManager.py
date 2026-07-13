"""Report thread management for connections."""
import queue
import threading
import asyncio
import time
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

from core.handle.reportHandle import report

TAG = __name__


class ReportQueue(queue.Queue):
    """A report queue that can atomically reject new items and enqueue EOF."""

    def __init__(self):
        super().__init__()
        self._input_lock = threading.Lock()
        self._sealed = False

    def put(self, item, block=True, timeout=None):
        with self._input_lock:
            if self._sealed:
                return False
            super().put(item, block=block, timeout=timeout)
            return True

    def seal(self, enqueue_sentinel=True):
        with self._input_lock:
            if self._sealed:
                return False
            self._sealed = True
            if enqueue_sentinel:
                super().put(None)
            return True


class ReportThreadManager:
    """Manages report worker thread for a connection."""

    def start(self, conn: "ConnectionHandler"):
        """初始化ASR和TTS上报线程"""
        if not conn.read_config_from_api or conn.need_bind:
            return
        if conn.chat_history_conf == 0:
            return
        if conn.report_thread is None or not conn.report_thread.is_alive():
            conn.report_thread = threading.Thread(
                target=self._report_worker, args=(conn,), daemon=True
            )
            conn.report_thread.start()
            conn.logger.bind(tag=TAG).info("TTS上报线程已启动")

    def _report_worker(self, conn: "ConnectionHandler"):
        """聊天记录上报工作线程"""
        while True:
            try:
                # 关闭时依靠 FIFO 队尾的毒丸退出，不能因 stop_event
                # 提前退出，否则会丢失尚未上报的最后几条会话事件。
                item = conn.report_queue.get(timeout=1)
                if item is None:  # 检测毒丸对象
                    conn.report_queue.task_done()
                    break
                try:
                    # 检查线程池状态
                    if conn.executor is None:
                        conn.report_queue.task_done()
                        continue
                    # 提交任务到线程池
                    conn.executor.submit(self._process_report, conn, *item)
                except Exception as e:
                    conn.report_queue.task_done()
                    conn.logger.bind(tag=TAG).error(f"聊天记录上报线程异常: {e}")
            except queue.Empty:
                continue
            except Exception as e:
                conn.logger.bind(tag=TAG).error(f"聊天记录上报工作线程异常: {e}")

        conn.logger.bind(tag=TAG).info("聊天记录上报线程已退出")

    def _process_report(self, conn: "ConnectionHandler", type, text, audio_data, report_time):
        """处理上报任务"""
        try:
            # 执行异步上报（在事件循环中运行）
            asyncio.run(report(conn, type, text, audio_data, report_time))
        except Exception as e:
            conn.logger.bind(tag=TAG).error(f"上报处理异常: {e}")
        finally:
            # 标记任务完成
            conn.report_queue.task_done()

    def stop(self, conn: "ConnectionHandler", timeout=10.0):
        """Stop the report thread within one monotonic deadline.

        This method is intentionally synchronous. Connection shutdown runs it
        via ``asyncio.to_thread`` so the join and polling sleeps never block the
        server event loop.
        """
        deadline = time.monotonic() + max(0.0, float(timeout))
        worker_alive = bool(conn.report_thread and conn.report_thread.is_alive())
        seal = getattr(conn.report_queue, "seal", None)
        if callable(seal):
            seal(enqueue_sentinel=worker_alive)
        elif worker_alive:
            conn.report_queue.put(None)

        if worker_alive:
            remaining = max(0.0, deadline - time.monotonic())
            if remaining > 0:
                conn.report_thread.join(timeout=remaining)

        while (
            getattr(conn.report_queue, "unfinished_tasks", 0) > 0
            and time.monotonic() < deadline
        ):
            time.sleep(min(0.05, max(0.0, deadline - time.monotonic())))
        if getattr(conn.report_queue, "unfinished_tasks", 0) > 0:
            conn.logger.bind(tag=TAG).warning("聊天记录上报队列仍有未完成任务，关闭流程继续")
