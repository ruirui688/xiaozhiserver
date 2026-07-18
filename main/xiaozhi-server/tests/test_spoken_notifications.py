import queue
import threading
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

    def store_tts_text(self, sentence_id, text):
        self.stored.append((sentence_id, text))

    def tts_one_sentence(self, _conn, content_type, content_detail=None, sentence_id=None):
        self.sent.append((content_type, content_detail, sentence_id))


def _conn(**overrides):
    values = {
        "tts": _TTS(),
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


def test_busy_device_does_not_receive_proactive_interruption():
    conn = _conn(client_is_speaking=True)

    accepted, reason = enqueue_spoken_notification(conn, "飞书群消息：发布完成")

    assert accepted is False
    assert reason == "device_busy"
    assert conn.tts.stored == []
