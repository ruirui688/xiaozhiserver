import queue
import threading
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

from config import settings

settings.config_file_valid = True

from core.spoken_notifications import enqueue_spoken_notification
from core.providers.tts.dto.dto import ContentType


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
    assert conn.tts.sent[0][0] == ContentType.TEXT
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
