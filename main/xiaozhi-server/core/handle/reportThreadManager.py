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
        while not conn.stop_event.is_set():
            try:
                # 从队列获取数据，设置超时以便定期检查停止事件
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
        if conn.report_thread and conn.report_thread.is_alive():
            conn.report_queue.put(None)
            remaining = max(0.0, deadline - time.monotonic())
            if remaining > 0:
                conn.report_thread.join(timeout=min(2.0, remaining))

        # stop_event is set before this cleanup starts. If the worker observed
        # it before consuming the poison pill, drain items it can no longer
        # process and balance Queue.unfinished_tasks.
        if not conn.report_thread or not conn.report_thread.is_alive():
            while True:
                try:
                    conn.report_queue.get_nowait()
                except queue.Empty:
                    break
                else:
                    conn.report_queue.task_done()

        while (
            getattr(conn.report_queue, "unfinished_tasks", 0) > 0
            and time.monotonic() < deadline
        ):
            time.sleep(min(0.05, max(0.0, deadline - time.monotonic())))
        if getattr(conn.report_queue, "unfinished_tasks", 0) > 0:
            conn.logger.bind(tag=TAG).warning("聊天记录上报队列仍有未完成任务，关闭流程继续")
