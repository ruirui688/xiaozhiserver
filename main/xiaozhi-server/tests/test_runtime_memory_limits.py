import threading
from types import SimpleNamespace

import pytest
import config.settings

config.settings.config_file_valid = True

from core.session.runtime_memory import (
    BoundedThreadPoolExecutor,
    BoundedAudioFrameBuffer,
    BoundedAudioFrameQueue,
    RejectedExecutionError,
    build_connection_audio_buffers,
)


def test_connection_executor_rejects_work_when_bounded_queue_is_full():
    executor = BoundedThreadPoolExecutor(max_workers=1, max_pending_tasks=1)
    started = threading.Event()
    release = threading.Event()
    try:
        running = executor.submit(
            lambda: (started.set(), release.wait(timeout=2))
        )
        assert started.wait(timeout=1)
        queued = executor.submit(lambda: "queued")
        with pytest.raises(RejectedExecutionError):
            executor.submit(lambda: "rejected")
        release.set()
        running.result(timeout=1)
        assert queued.result(timeout=1) == "queued"
    finally:
        release.set()
        executor.shutdown(wait=True, cancel_futures=True)


def test_connection_audio_overflow_marks_current_utterance_discontinuous():
    bound_logger = SimpleNamespace(warning=lambda *_args, **_kwargs: None)
    conn = SimpleNamespace(
        logger=SimpleNamespace(bind=lambda **_kwargs: bound_logger)
    )
    _buffer, audio_queue = build_connection_audio_buffers(conn)

    for _index in range(audio_queue.maxsize + 1):
        audio_queue.put(b"frame")

    assert conn._discard_asr_until_voice_boundary is True


def test_audio_frame_queue_drops_oldest_frames_at_item_limit():
    audio_queue = BoundedAudioFrameQueue(max_frames=3, max_bytes=100)

    for frame in (b"one", b"two", b"three", b"four"):
        assert audio_queue.put(frame) is True

    assert audio_queue.qsize() == 3
    assert audio_queue.dropped_frames == 1
    assert audio_queue.get_nowait() == b"two"
    audio_queue.task_done()
    assert audio_queue.get_nowait() == b"three"
    audio_queue.task_done()
    assert audio_queue.get_nowait() == b"four"
    audio_queue.task_done()
    assert audio_queue.unfinished_tasks == 0


def test_audio_limits_emit_overflow_callback_without_frame_content():
    events = []
    audio_queue = BoundedAudioFrameQueue(
        max_frames=1,
        max_bytes=4,
        max_frame_bytes=4,
        on_overflow=lambda *event: events.append(event[:3]),
    )

    assert audio_queue.put(b"aa") is True
    assert audio_queue.put(b"bb") is True
    assert audio_queue.put(b"large") is False
    assert events == [("queue", 1, 0), ("queue", 0, 1)]


def test_audio_frame_queue_enforces_total_bytes_and_rejects_oversized_frame():
    audio_queue = BoundedAudioFrameQueue(max_frames=10, max_bytes=6)

    assert audio_queue.put(b"aaa") is True
    assert audio_queue.put(b"bbb") is True
    assert audio_queue.put(b"cccc") is True
    assert audio_queue.qsize() == 1
    assert audio_queue.current_bytes == 4
    assert audio_queue.dropped_frames == 2

    assert audio_queue.put(b"oversized") is False
    assert audio_queue.rejected_frames == 1
    assert audio_queue.qsize() == 1


def test_audio_frame_buffer_preserves_recent_list_compatible_window():
    audio_buffer = BoundedAudioFrameBuffer(max_frames=3, max_bytes=6)

    for frame in (b"one", b"two", b"six", b"ten"):
        assert audio_buffer.append(frame) is True

    assert audio_buffer == [b"six", b"ten"]
    assert audio_buffer.current_bytes == 6
    assert audio_buffer.dropped_frames == 2
    assert audio_buffer[-1:] == [b"ten"]

    audio_buffer.retain_last(1)
    assert audio_buffer == [b"ten"]
    audio_buffer.clear()
    assert audio_buffer == []
    assert audio_buffer.current_bytes == 0


def test_audio_frame_buffer_rejects_single_frame_over_byte_budget():
    audio_buffer = BoundedAudioFrameBuffer(max_frames=3, max_bytes=3)

    assert audio_buffer.append(b"four") is False
    assert audio_buffer == []
    assert audio_buffer.rejected_frames == 1
