"""Bounded per-connection audio containers used on the realtime hot path."""

import queue
import threading
import time
from concurrent.futures import ThreadPoolExecutor


DEFAULT_ASR_QUEUE_MAX_FRAMES = 300
DEFAULT_ASR_QUEUE_MAX_BYTES = 1 * 1024 * 1024
DEFAULT_ASR_BUFFER_MAX_FRAMES = 2000
DEFAULT_ASR_BUFFER_MAX_BYTES = 2 * 1024 * 1024
DEFAULT_AUDIO_FRAME_MAX_BYTES = 64 * 1024


class RejectedExecutionError(RuntimeError):
    """Raised when a connection already owns its full task memory budget."""


class BoundedThreadPoolExecutor(ThreadPoolExecutor):
    """Thread pool with a ceiling on running plus queued work."""

    def __init__(self, max_workers=5, max_pending_tasks=16, **kwargs):
        max_workers = int(max_workers)
        max_pending_tasks = int(max_pending_tasks)
        if max_workers <= 0 or max_pending_tasks < 0:
            raise ValueError("executor limits are invalid")
        super().__init__(max_workers=max_workers, **kwargs)
        self._capacity = threading.BoundedSemaphore(
            max_workers + max_pending_tasks
        )

    def submit(self, fn, /, *args, **kwargs):
        if not self._capacity.acquire(blocking=False):
            raise RejectedExecutionError("connection task queue is full")
        try:
            future = super().submit(fn, *args, **kwargs)
        except BaseException:
            self._capacity.release()
            raise
        future.add_done_callback(lambda _future: self._capacity.release())
        return future


def _item_size(item):
    try:
        return len(item)
    except (TypeError, AttributeError):
        return 0


class BoundedAudioFrameQueue(queue.Queue):
    """Drop oldest audio frames instead of allowing an unbounded backlog."""

    def __init__(
        self,
        max_frames=DEFAULT_ASR_QUEUE_MAX_FRAMES,
        max_bytes=DEFAULT_ASR_QUEUE_MAX_BYTES,
        max_frame_bytes=DEFAULT_AUDIO_FRAME_MAX_BYTES,
        on_overflow=None,
    ):
        max_frames = int(max_frames)
        max_bytes = int(max_bytes)
        max_frame_bytes = int(max_frame_bytes)
        if max_frames <= 0 or max_bytes <= 0 or max_frame_bytes <= 0:
            raise ValueError("audio queue limits must be positive")
        super().__init__(maxsize=max_frames)
        self.max_bytes = max_bytes
        self.max_frame_bytes = min(max_frame_bytes, max_bytes)
        self.current_bytes = 0
        self.dropped_frames = 0
        self.dropped_bytes = 0
        self.rejected_frames = 0
        self.on_overflow = on_overflow

    def _put(self, item):
        super()._put(item)
        self.current_bytes += _item_size(item)

    def _get(self):
        item = super()._get()
        self.current_bytes = max(0, self.current_bytes - _item_size(item))
        return item

    def put(self, item, block=True, timeout=None):
        del block, timeout
        item_bytes = _item_size(item)
        dropped_now = 0
        rejected = False
        with self.not_full:
            if item_bytes > self.max_frame_bytes:
                self.rejected_frames += 1
                rejected = True
            else:
                while self._qsize() and (
                    self._qsize() >= self.maxsize
                    or self.current_bytes + item_bytes > self.max_bytes
                ):
                    dropped = self._get()
                    dropped_now += 1
                    self.dropped_frames += 1
                    self.dropped_bytes += _item_size(dropped)
                    if self.unfinished_tasks > 0:
                        self.unfinished_tasks -= 1
                        if self.unfinished_tasks == 0:
                            self.all_tasks_done.notify_all()

                self._put(item)
                self.unfinished_tasks += 1
                self.not_empty.notify()
        if self.on_overflow is not None and (dropped_now or rejected):
            self.on_overflow("queue", dropped_now, int(rejected), self)
        return not rejected


class BoundedAudioFrameBuffer(list):
    """List-compatible utterance buffer with frame and byte ceilings."""

    def __init__(
        self,
        max_frames=DEFAULT_ASR_BUFFER_MAX_FRAMES,
        max_bytes=DEFAULT_ASR_BUFFER_MAX_BYTES,
        max_frame_bytes=DEFAULT_AUDIO_FRAME_MAX_BYTES,
        on_overflow=None,
    ):
        super().__init__()
        self.max_frames = int(max_frames)
        self.max_bytes = int(max_bytes)
        self.max_frame_bytes = int(max_frame_bytes)
        if (
            self.max_frames <= 0
            or self.max_bytes <= 0
            or self.max_frame_bytes <= 0
        ):
            raise ValueError("audio buffer limits must be positive")
        self.max_frame_bytes = min(self.max_frame_bytes, self.max_bytes)
        self.current_bytes = 0
        self.dropped_frames = 0
        self.dropped_bytes = 0
        self.rejected_frames = 0
        self.on_overflow = on_overflow

    def append(self, item):
        item_bytes = _item_size(item)
        if item_bytes > self.max_frame_bytes:
            self.rejected_frames += 1
            if self.on_overflow is not None:
                self.on_overflow("buffer", 0, 1, self)
            return False
        super().append(item)
        self.current_bytes += item_bytes
        dropped_now = self._trim_to_limits()
        if self.on_overflow is not None and dropped_now:
            self.on_overflow("buffer", dropped_now, 0, self)
        return True

    def extend(self, items):
        for item in items:
            self.append(item)

    def clear(self):
        super().clear()
        self.current_bytes = 0

    def retain_last(self, count):
        count = max(0, int(count))
        if count == 0:
            self.clear()
            return
        if len(self) > count:
            self._drop_prefix(len(self) - count)

    def _trim_to_limits(self):
        dropped = 0
        while self and (
            len(self) > self.max_frames or self.current_bytes > self.max_bytes
        ):
            self._drop_prefix(1)
            dropped += 1
        return dropped

    def _drop_prefix(self, count):
        count = min(max(0, count), len(self))
        if count == 0:
            return
        removed = self[:count]
        del self[:count]
        removed_bytes = sum(_item_size(item) for item in removed)
        self.current_bytes = max(0, self.current_bytes - removed_bytes)
        self.dropped_frames += count
        self.dropped_bytes += removed_bytes


def build_connection_audio_buffers(conn):
    """Create bounded ASR containers with rate-limited metadata-only warnings."""

    def _warn_overflow(kind, dropped, rejected, container):
        # A queue discontinuity can splice unrelated audio into one utterance.
        # The ASR consumer discards that segment until the next speech boundary.
        if kind == "queue" and (dropped or rejected):
            conn._discard_asr_until_voice_boundary = True
        now = time.monotonic()
        last_warning = getattr(conn, "_asr_memory_warning_at", 0.0)
        if now - last_warning < 5.0:
            return
        conn._asr_memory_warning_at = now
        conn.logger.bind(tag=__name__).warning(
            "ASR内存保护触发: kind={}, dropped={}, rejected={}, "
            "totalDropped={}, totalRejected={}",
            kind,
            dropped,
            rejected,
            container.dropped_frames,
            container.rejected_frames,
        )

    return (
        BoundedAudioFrameBuffer(on_overflow=_warn_overflow),
        BoundedAudioFrameQueue(on_overflow=_warn_overflow),
    )
