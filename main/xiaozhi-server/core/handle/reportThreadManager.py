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


def _payload_size(value):
    if value is None:
        return 0
    if isinstance(value, str):
        return len(value.encode("utf-8"))
    if isinstance(value, memoryview):
        return value.nbytes
    if isinstance(value, (bytes, bytearray)):
        return len(value)
    if isinstance(value, (list, tuple)):
        return sum(_payload_size(item) for item in value)
    return 0


class ReportQueue(queue.Queue):
    """A report queue that can atomically reject new items and enqueue EOF."""

    DEFAULT_MAX_ITEMS = 32
    DEFAULT_MAX_BYTES = 4 * 1024 * 1024
    DEFAULT_MAX_ITEM_BYTES = 2 * 1024 * 1024

    def __init__(
        self,
        max_items=DEFAULT_MAX_ITEMS,
        max_bytes=DEFAULT_MAX_BYTES,
        max_item_bytes=DEFAULT_MAX_ITEM_BYTES,
    ):
        max_items = int(max_items)
        max_bytes = int(max_bytes)
        max_item_bytes = int(max_item_bytes)
        if max_items <= 0 or max_bytes <= 0 or max_item_bytes <= 0:
            raise ValueError("report queue limits must be positive")
        # Reserve one internal slot for the shutdown sentinel.
        super().__init__(maxsize=max_items + 1)
        self.max_items = max_items
        self.max_bytes = max_bytes
        self.max_item_bytes = min(max_item_bytes, max_bytes)
        self._input_lock = threading.Lock()
        self._sealed = False
        self.rejected_items = 0
        self.rejected_bytes = 0
        self.current_bytes = 0

    @staticmethod
    def _item_size(item):
        if not isinstance(item, tuple):
            return _payload_size(item)
        text = item[1] if len(item) > 1 else None
        audio = item[2] if len(item) > 2 else None
        return _payload_size(text) + _payload_size(audio)

    def _put(self, item):
        super()._put(item)
        self.current_bytes += self._item_size(item)

    def _get(self):
        item = super()._get()
        self.current_bytes = max(0, self.current_bytes - self._item_size(item))
        return item

    def put(self, item, block=True, timeout=None):
        del block, timeout
        with self._input_lock:
            if self._sealed:
                return False
            item_bytes = self._item_size(item)
            if item is not None and (
                self.qsize() >= self.max_items
                or item_bytes > self.max_item_bytes
                or self.current_bytes + item_bytes > self.max_bytes
            ):
                self.rejected_items += 1
                self.rejected_bytes += item_bytes
                return False
            super().put(item, block=False)
            return True

    def seal(self, enqueue_sentinel=True):
        with self._input_lock:
            if self._sealed:
                return False
            self._sealed = True
            if enqueue_sentinel:
                super().put(None, block=False)
            return True

    def discard_pending(self, enqueue_sentinel=False):
        """Discard queued reports while preserving any currently running item."""
        discarded = 0
        with self._input_lock:
            while True:
                try:
                    item = self.get_nowait()
                except queue.Empty:
                    break
                self.task_done()
                if item is not None:
                    discarded += 1
            if enqueue_sentinel:
                super().put(None, block=False)
        return discarded


class ReportThreadManager:
    """Manages report worker thread for a connection."""

    def __init__(self):
        self._lifecycle_lock = threading.Lock()
        self._stopped = False

    def update_chat_history_config(self, conn: "ConnectionHandler", value: int) -> bool:
        """Apply a chat-history mode change to an active connection.

        Disabling keeps an already-started worker alive so reports accepted before
        the boundary can drain and the same connection can be enabled again later.
        New enqueue operations observe ``chat_history_conf == 0`` immediately.
        """
        if value not in (0, 1, 2):
            raise ValueError("chat_history_conf must be 0, 1, or 2")
        with self._lifecycle_lock:
            if self._stopped or not conn.read_config_from_api or conn.need_bind:
                return False
            if getattr(conn, "closing", False) or getattr(conn, "closed", False):
                return False

            previous = conn.chat_history_conf
            conn.chat_history_conf = value
            config = getattr(conn, "config", None)
            if isinstance(config, dict):
                config["chat_history_conf"] = value

            if value != 0 and not self._start_locked(conn):
                return False

        conn.logger.bind(tag=TAG).info(
            "聊天记录配置已热更新: deviceId={}, previous={}, current={}",
            getattr(conn, "device_id", None),
            previous,
            value,
        )
        return True

    def start(self, conn: "ConnectionHandler"):
        """初始化ASR和TTS上报线程"""
        with self._lifecycle_lock:
            return self._start_locked(conn)

    def _start_locked(self, conn: "ConnectionHandler") -> bool:
        if self._stopped:
            return False
        if not conn.read_config_from_api or conn.need_bind:
            return False
        if conn.chat_history_conf == 0:
            return False
        if getattr(conn, "closing", False) or getattr(conn, "closed", False):
            return False
        if conn.report_thread is None or not conn.report_thread.is_alive():
            conn.report_thread = threading.Thread(
                target=self._report_worker, args=(conn,), daemon=True
            )
            conn.report_thread.start()
            conn.logger.bind(tag=TAG).info("TTS上报线程已启动")
        return True

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
                    # 专用线程串行上报，避免把含音频的任务继续转移到
                    # ThreadPoolExecutor 的无界内部工作队列。
                    self._process_report(conn, *item)
                except Exception as e:
                    conn.logger.bind(tag=TAG).error(f"聊天记录上报线程异常: {e}")
            except queue.Empty:
                continue
            except Exception as e:
                conn.logger.bind(tag=TAG).error(f"聊天记录上报工作线程异常: {e}")

        conn.logger.bind(tag=TAG).info("聊天记录上报线程已退出")

    def _process_report(
        self,
        conn: "ConnectionHandler",
        type,
        text,
        audio_data,
        report_time,
        metrics=None,
        skip_memory_event=False,
    ):
        """处理上报任务"""
        try:
            # 执行异步上报（在事件循环中运行）
            try:
                report_coro = report(
                    conn,
                    type,
                    text,
                    audio_data,
                    report_time,
                    metrics,
                    skip_memory_event,
                )
            except TypeError:
                try:
                    report_coro = report(
                        conn, type, text, audio_data, report_time, metrics
                    )
                except TypeError:
                    report_coro = report(conn, type, text, audio_data, report_time)
            asyncio.run(report_coro)
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
        with self._lifecycle_lock:
            self._stopped = True
            worker_alive = bool(conn.report_thread and conn.report_thread.is_alive())
            seal = getattr(conn.report_queue, "seal", None)
            if callable(seal):
                seal(enqueue_sentinel=worker_alive)
            elif worker_alive:
                conn.report_queue.put(None)

        if not worker_alive:
            discard_pending = getattr(conn.report_queue, "discard_pending", None)
            if callable(discard_pending):
                discard_pending(enqueue_sentinel=False)
            return

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
            discard_pending = getattr(conn.report_queue, "discard_pending", None)
            if callable(discard_pending):
                discarded = discard_pending(
                    enqueue_sentinel=bool(
                        conn.report_thread and conn.report_thread.is_alive()
                    )
                )
                if discarded:
                    conn.logger.bind(tag=TAG).warning(
                        "关闭期限内未完成的聊天记录已丢弃: count={}",
                        discarded,
                    )
