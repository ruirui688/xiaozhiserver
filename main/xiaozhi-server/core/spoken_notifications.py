"""Queue short server-originated announcements on an idle device connection."""

import re
import uuid

from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO


def enqueue_spoken_notification(
    conn, text: str, *, require_idle: bool = True
) -> tuple[bool, str]:
    content = _clean_text(text)
    if not content:
        return False, "empty"
    if getattr(conn, "tts", None) is None:
        return False, "tts_unavailable"
    meeting = getattr(conn, "meeting_minutes", None)
    if meeting is not None and meeting.is_active():
        return False, "meeting_active"
    enrollment = getattr(conn, "voiceprint_enrollment", None)
    if enrollment is not None and getattr(getattr(enrollment, "status", None), "active", False):
        return False, "voiceprint_enrollment_active"
    if require_idle and (
        getattr(conn, "client_is_speaking", False)
        or getattr(conn, "client_have_voice", False)
    ):
        return False, "device_busy"

    with conn._abort_lock:
        conn.client_abort = False
    sentence_id = str(uuid.uuid4().hex)
    conn.sentence_id = sentence_id
    conn.tts.store_tts_text(sentence_id, content)
    conn.tts.tts_text_queue.put(
        TTSMessageDTO(
            sentence_id=sentence_id,
            sentence_type=SentenceType.FIRST,
            content_type=ContentType.ACTION,
        )
    )
    conn.tts.tts_one_sentence(
        conn,
        ContentType.TEXT,
        content_detail=content,
        sentence_id=sentence_id,
    )
    conn.tts.tts_text_queue.put(
        TTSMessageDTO(
            sentence_id=sentence_id,
            sentence_type=SentenceType.LAST,
            content_type=ContentType.ACTION,
        )
    )
    return True, "queued"


def _clean_text(value: str) -> str:
    text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", "", str(value or ""))
    text = re.sub(r"\s+", " ", text).strip()
    return text[:500]
