"""Queue short server-originated announcements on an idle device connection."""

import hashlib
import re
import uuid

from core.notifications.admission import NotificationAdmission, NotificationBusy
from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO


_admission = NotificationAdmission()


def configure_notification_limits(config: dict) -> None:
    """Apply process limits from server configuration, never device overrides."""
    _admission.configure(config.get("server_notification_limits", {}))


def enqueue_spoken_notification(
    conn,
    text: str,
    *,
    require_idle: bool = True,
    request_id: str | None = None,
) -> tuple[bool, str]:
    content = _clean_text(text)
    if not content:
        return False, "empty"
    normalized_request_id = str(request_id or "").strip()
    request_key = None
    if normalized_request_id:
        request_key = (_admission.device_key(conn), normalized_request_id)
    agent_id = str((getattr(conn, "config", {}) or {}).get("agent_id") or "")
    fingerprint = hashlib.sha256(
        (agent_id + "\0" + content).encode("utf-8")
    ).hexdigest()

    def check():
        unavailable_reason = _unavailable_reason(conn)
        if unavailable_reason:
            return unavailable_reason
        protected_reason = _protected_busy_reason(conn)
        if protected_reason:
            return protected_reason
        if require_idle and _is_busy(conn):
            return "device_busy"
        # Even callers which allow interruption cannot append to an existing
        # TTS backlog. Admission must never turn this into an unbounded queue.
        pending = conn.tts.tts_text_queue
        if pending.qsize() > 0:
            return "device_busy"
        if 0 < getattr(pending, "maxsize", 0) < 3:
            return "overloaded"
        return None

    def submit():
        _submit_notification(conn, content)

    return _admission.execute(
        conn, request_key, fingerprint, check, submit, _is_busy
    )


def _submit_notification(conn, content):
    sentence_id = str(uuid.uuid4().hex)
    lifecycle = getattr(conn, "reply_lifecycle", None)
    generation = None
    try:
        # Acquire a generation before changing shared sentence/abort fields;
        # another reply winning the race must keep its own state untouched.
        if lifecycle is not None:
            try:
                generation = lifecycle.begin(kind="notification")
            except RuntimeError:
                if lifecycle.is_active:
                    raise NotificationBusy() from None
                raise
        with conn._abort_lock:
            conn.client_abort = False
        conn.sentence_id = sentence_id
        if lifecycle is not None:
            lifecycle.bind_sentence(generation, sentence_id)
            lifecycle.mark_synthesizing(generation)
        else:
            conn.client_is_speaking = True
        tts = conn.tts
        mark_exact_text = getattr(tts, "mark_exact_text", None)
        if callable(mark_exact_text):
            mark_exact_text(sentence_id)
        mark_skip_memory = getattr(tts, "mark_skip_memory_event", None)
        if callable(mark_skip_memory):
            mark_skip_memory(sentence_id)
        tts.store_tts_text(sentence_id, content)
        tts.tts_text_queue.put_nowait(
            TTSMessageDTO(
                sentence_id=sentence_id,
                sentence_type=SentenceType.FIRST,
                content_type=ContentType.ACTION,
                generation=generation,
            )
        )
        # Exactly three queue records, irrespective of punctuation count. This
        # follows the external-TTS boundary and cannot block on a full queue.
        tts.tts_text_queue.put_nowait(
            TTSMessageDTO(
                sentence_id=sentence_id,
                sentence_type=SentenceType.MIDDLE,
                content_type=ContentType.TEXT,
                content_detail=content,
                generation=generation,
            )
        )
        tts.tts_text_queue.put_nowait(
            TTSMessageDTO(
                sentence_id=sentence_id,
                sentence_type=SentenceType.LAST,
                content_type=ContentType.ACTION,
                generation=generation,
            )
        )
    except Exception:
        # Only terminate the generation we created. Never clear shared queues
        # or abort a newer user reply to compensate for a partial enqueue.
        if lifecycle is not None and generation is not None:
            lifecycle.fail(generation, "notification_enqueue_uncertain")
            if lifecycle.is_current(generation) and getattr(conn, "sentence_id", None) == sentence_id:
                conn.sentence_id = ""
        elif lifecycle is None and getattr(conn, "sentence_id", None) == sentence_id:
            conn.client_is_speaking = False
            conn.sentence_id = ""
        raise


def _unavailable_reason(conn) -> str | None:
    if getattr(conn, "tts", None) is None:
        return "tts_unavailable"
    if getattr(conn, "closing", False) or getattr(conn, "closed", False):
        return "connection_closing"
    if getattr(conn, "initialization_failed", False):
        return "tts_unavailable"
    tts_ready = getattr(conn, "tts_ready", None)
    is_set = getattr(tts_ready, "is_set", None)
    if callable(is_set) and not is_set():
        return "tts_not_ready"
    return None


def _protected_busy_reason(conn) -> str | None:
    if getattr(conn, "calling", False):
        return "call_active"
    meeting = getattr(conn, "meeting_minutes", None)
    if meeting is not None and meeting.is_active():
        return "meeting_active"
    enrollment = getattr(conn, "voiceprint_enrollment", None)
    if enrollment is not None and getattr(
        getattr(enrollment, "status", None), "active", False
    ):
        return "voiceprint_enrollment_active"
    return None


def _is_busy(conn) -> bool:
    lifecycle = getattr(conn, "reply_lifecycle", None)
    if lifecycle is not None and getattr(lifecycle, "is_active", False):
        return True
    if getattr(conn, "client_is_speaking", False) or getattr(
        conn, "client_have_voice", False
    ):
        return True
    tts = getattr(conn, "tts", None)
    for queue_name in ("tts_text_queue", "tts_audio_queue"):
        pending_queue = getattr(tts, queue_name, None)
        if pending_queue is not None and pending_queue.qsize() > 0:
            return True
    return False


def _clean_text(value: str) -> str:
    text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", "", str(value or ""))
    text = re.sub(r"\s+", " ", text).strip()
    return text[:500]
