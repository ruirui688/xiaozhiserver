import queue
import threading
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import pytest

from config import settings

settings.config_file_valid = True

from core.spoken_notifications import enqueue_spoken_notification
from core.notifications.admission import NotificationAdmission, NotificationLimits
import core.spoken_notifications as spoken_module
from core.providers.tts.dto.dto import ContentType, SentenceType
from core.session.reply_lifecycle import ReplyLifecycle


@pytest.fixture(autouse=True)
def isolated_admission(monkeypatch):
    monkeypatch.setattr(spoken_module, "_admission", NotificationAdmission())


class _TTS:
    def __init__(self):
        self.tts_text_queue = queue.Queue()
        self.stored = []
        self.sent = []
        self.exact = []
        self.skip_memory = []

    def store_tts_text(self, sentence_id, text):
        self.stored.append((sentence_id, text))

    def tts_one_sentence(self, _conn, content_type, content_detail=None, sentence_id=None):
        self.sent.append((content_type, content_detail, sentence_id))

    def mark_exact_text(self, sentence_id):
        self.exact.append(sentence_id)

    def mark_skip_memory_event(self, sentence_id):
        self.skip_memory.append(sentence_id)


def _conn(**overrides):
    values = {
        "tts": _TTS(),
        "device_id": "AA:BB:CC:DD:EE:FF",
        "meeting_minutes": None,
        "voiceprint_enrollment": None,
        "client_is_speaking": False,
        "client_have_voice": False,
        "client_abort": False,
        "_abort_lock": threading.Lock(),
        "sentence_id": "",
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def test_idle_device_accepts_proactive_spoken_notification():
    conn = _conn()

    accepted, reason = enqueue_spoken_notification(conn, "飞书群消息：发布完成")

    assert accepted is True
    assert reason == "queued"
    assert conn.tts.stored[0][1] == "飞书群消息：发布完成"
    records = [conn.tts.tts_text_queue.get_nowait() for _ in range(3)]
    assert [item.sentence_type for item in records] == [SentenceType.FIRST, SentenceType.MIDDLE, SentenceType.LAST]
    assert records[1].content_type == ContentType.TEXT
    assert records[1].content_detail == "飞书群消息：发布完成"
    assert conn.tts.exact == [conn.sentence_id]
    assert conn.tts.skip_memory == [conn.sentence_id]


def test_busy_device_does_not_receive_proactive_interruption():
    conn = _conn(client_is_speaking=True)

    accepted, reason = enqueue_spoken_notification(conn, "飞书群消息：发布完成")

    assert accepted is False
    assert reason == "device_busy"
    assert conn.tts.stored == []


def test_duplicate_request_is_not_enqueued_twice():
    conn = _conn(device_id="AA:BB:CC:DD:EE:10")

    first = enqueue_spoken_notification(
        conn, "两点了，该开会了", request_id="reminder-event-10"
    )
    duplicate = enqueue_spoken_notification(
        conn, "两点了，该开会了", request_id="reminder-event-10"
    )

    assert first == (True, "queued")
    assert duplicate == (True, "duplicate")
    assert len(conn.tts.stored) == 1


def test_failed_attempt_is_not_recorded_as_duplicate():
    conn = _conn(device_id="AA:BB:CC:DD:EE:11", client_have_voice=True)

    rejected = enqueue_spoken_notification(
        conn, "提醒内容", request_id="reminder-event-11"
    )
    conn.client_have_voice = False
    accepted = enqueue_spoken_notification(
        conn, "提醒内容", request_id="reminder-event-11"
    )

    assert rejected == (False, "device_busy")
    assert accepted == (True, "queued")


def test_concurrent_retries_queue_only_once():
    conn = _conn(device_id="AA:BB:CC:DD:EE:12")
    barrier = threading.Barrier(2)

    def submit():
        barrier.wait()
        return enqueue_spoken_notification(
            conn, "并发提醒", request_id="reminder-event-12"
        )

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = (executor.submit(submit), executor.submit(submit))
        results = [future.result() for future in futures]

    assert sorted(results) == [(True, "duplicate"), (True, "queued")]
    assert len(conn.tts.stored) == 1


def test_protected_and_unavailable_states_have_stable_reasons():
    meeting = SimpleNamespace(is_active=lambda: True)
    assert enqueue_spoken_notification(
        _conn(meeting_minutes=meeting), "提醒"
    ) == (False, "meeting_active")
    assert enqueue_spoken_notification(
        _conn(calling=True), "提醒"
    ) == (False, "call_active")
    assert enqueue_spoken_notification(
        _conn(tts=None), "提醒"
    ) == (False, "tts_unavailable")


def test_long_punctuated_text_uses_only_three_queue_records():
    conn = _conn()
    text = "提醒。" * 160

    assert enqueue_spoken_notification(conn, text) == (True, "queued")
    assert conn.tts.tts_text_queue.qsize() == 3
    conn.tts.tts_text_queue.get_nowait()
    assert conn.tts.tts_text_queue.get_nowait().content_detail == text


def test_100_concurrent_duplicate_requests_consume_one_budget(monkeypatch):
    admission = NotificationAdmission(NotificationLimits(burst=1))
    monkeypatch.setattr(spoken_module, "_admission", admission)
    conn = _conn()
    with ThreadPoolExecutor(max_workers=16) as executor:
        results = list(executor.map(
            lambda _: enqueue_spoken_notification(conn, "提醒", request_id="same"),
            range(100),
        ))

    assert results.count((True, "queued")) == 1
    assert results.count((True, "duplicate")) == 99
    assert conn.tts.tts_text_queue.qsize() == 3
    assert admission.snapshot()["pending"] == 1


def test_same_id_with_changed_payload_is_not_reported_delivered():
    conn = _conn()
    assert enqueue_spoken_notification(conn, "第一次", request_id="same") == (True, "queued")
    assert enqueue_spoken_notification(conn, "更改内容", request_id="same") == (False, "request_conflict")
    assert conn.tts.tts_text_queue.qsize() == 3


def test_partial_enqueue_failure_is_uncertain_and_never_retried():
    class FailingQueue(queue.Queue):
        def put_nowait(self, item):
            if self.qsize() == 1:
                raise RuntimeError("queue write interrupted")
            return super().put_nowait(item)

    conn = _conn()
    conn.tts.tts_text_queue = FailingQueue()

    first = enqueue_spoken_notification(conn, "部分入队", request_id="partial")
    retry = enqueue_spoken_notification(conn, "部分入队", request_id="partial")

    assert first == retry == (False, "delivery_uncertain")
    assert conn.tts.tts_text_queue.qsize() == 1
    assert len(conn.tts.stored) == 1


def test_busy_rejection_does_not_spend_global_budget(monkeypatch):
    admission = NotificationAdmission(NotificationLimits(burst=1))
    monkeypatch.setattr(spoken_module, "_admission", admission)
    assert enqueue_spoken_notification(_conn(client_have_voice=True), "提醒") == (False, "device_busy")
    assert enqueue_spoken_notification(_conn(), "提醒") == (True, "queued")
    assert enqueue_spoken_notification(_conn(device_id="another-device"), "提醒") == (False, "overloaded")


def test_too_small_bounded_queue_is_rejected_without_partial_write():
    conn = _conn()
    conn.tts.tts_text_queue = queue.Queue(maxsize=2)
    assert enqueue_spoken_notification(conn, "提醒", request_id="bounded") == (False, "overloaded")
    assert conn.tts.tts_text_queue.qsize() == 0
    assert conn.tts.stored == []


def test_enqueue_exceptions_release_own_lifecycle_and_capacity(monkeypatch):
    now = [0.0]
    admission = NotificationAdmission(NotificationLimits(max_pending=1), clock=lambda: now[0])
    monkeypatch.setattr(spoken_module, "_admission", admission)
    conn = _conn(reply_lifecycle=ReplyLifecycle())

    def fail_store(*_args):
        raise RuntimeError("local failure before queue write")

    conn.tts.store_tts_text = fail_store
    for index in range(10):
        now[0] = index * 3.0
        assert enqueue_spoken_notification(conn, "失败", request_id=f"failed-{index}") == (False, "delivery_uncertain")
        assert not conn.reply_lifecycle.is_active
    now[0] += 3
    healthy = _conn(device_id="healthy")
    assert enqueue_spoken_notification(healthy, "恢复", request_id="recovered") == (True, "queued")
    assert admission.snapshot()["pending"] == 1
    assert enqueue_spoken_notification(conn, "失败", request_id="failed-0") == (False, "delivery_uncertain")


def test_failure_does_not_terminate_a_newer_reply_generation():
    conn = _conn(reply_lifecycle=ReplyLifecycle())

    def concurrent_new_reply(*_args):
        old_generation = conn.reply_lifecycle.generation
        conn.reply_lifecycle.interrupt(old_generation, "user_interruption")
        conn.reply_lifecycle.begin(kind="assistant")
        raise RuntimeError("notification enqueue failed after interruption")

    conn.tts.store_tts_text = concurrent_new_reply
    assert enqueue_spoken_notification(conn, "提醒", request_id="raced") == (False, "delivery_uncertain")
    assert conn.reply_lifecycle.is_active
    assert conn.reply_lifecycle.snapshot().kind == "assistant"


def test_competing_reply_before_enqueue_returns_retryable_busy_and_releases_budget(monkeypatch):
    class RacingLifecycle(ReplyLifecycle):
        race_once = True

        def begin(self, *, kind="assistant"):
            if self.race_once and kind == "notification":
                self.race_once = False
                super().begin(kind="assistant")
            return super().begin(kind=kind)

    admission = NotificationAdmission(NotificationLimits(burst=1))
    monkeypatch.setattr(spoken_module, "_admission", admission)
    conn = _conn(reply_lifecycle=RacingLifecycle(), sentence_id="original")
    assert enqueue_spoken_notification(conn, "提醒", request_id="busy-race") == (False, "device_busy")
    assert conn.sentence_id == "original"
    assert conn.tts.tts_text_queue.qsize() == 0
    assert admission.snapshot()["receipts"] == admission.snapshot()["pending"] == 0
    assert conn.reply_lifecycle.snapshot().kind == "assistant"
    conn.reply_lifecycle.complete(conn.reply_lifecycle.generation)
    assert enqueue_spoken_notification(conn, "提醒", request_id="busy-race") == (True, "queued")
