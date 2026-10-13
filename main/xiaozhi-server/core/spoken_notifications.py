"""Queue short server-originated announcements on an idle device connection."""

from collections import OrderedDict
import re
import threading
import time
import uuid

from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO


_IDEMPOTENCY_TTL_SECONDS = 6 * 60 * 60
_IDEMPOTENCY_MAX_ENTRIES = 4096
_idempotency_lock = threading.RLock()
_queued_requests: "OrderedDict[tuple[str, str], float]" = OrderedDict()


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
        device_key = str(getattr(conn, "device_id", "") or "").strip().lower()
        request_key = (device_key or f"connection:{id(conn)}", normalized_request_id)

    # Hold the idempotency lock through the short queue submission section. This
    # prevents concurrent scheduler retries from both passing the duplicate check.
    lock = _idempotency_lock if request_key is not None else _NullLock()
    with lock:
        if request_key is not None:
            _expire_idempotency_entries()
            if request_key in _queued_requests:
                return True, "duplicate"

        unavailable_reason = _unavailable_reason(conn)
        if unavailable_reason:
            return False, unavailable_reason
        protected_reason = _protected_busy_reason(conn)
        if protected_reason:
            return False, protected_reason
        if require_idle and _is_busy(conn):
            return False, "device_busy"

        with conn._abort_lock:
            conn.client_abort = False
        sentence_id = str(uuid.uuid4().hex)
        conn.sentence_id = sentence_id
        tts = conn.tts
        mark_exact_text = getattr(tts, "mark_exact_text", None)
        if callable(mark_exact_text):
            mark_exact_text(sentence_id)
        mark_skip_memory = getattr(tts, "mark_skip_memory_event", None)
        if callable(mark_skip_memory):
            mark_skip_memory(sentence_id)
        tts.store_tts_text(sentence_id, content)
        tts.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id=sentence_id,
                sentence_type=SentenceType.FIRST,
                content_type=ContentType.ACTION,
            )
        )
        tts.tts_one_sentence(
            conn,
            ContentType.TEXT,
            content_detail=content,
            sentence_id=sentence_id,
        )
        tts.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id=sentence_id,
                sentence_type=SentenceType.LAST,
                content_type=ContentType.ACTION,
            )
        )
        if request_key is not None:
            _queued_requests[request_key] = time.monotonic()
            _queued_requests.move_to_end(request_key)
            while len(_queued_requests) > _IDEMPOTENCY_MAX_ENTRIES:
                _queued_requests.popitem(last=False)
    return True, "queued"


class _NullLock:
    def __enter__(self):
        return self

    def __exit__(self, _exc_type, _exc_value, _traceback):
        return False


def _expire_idempotency_entries() -> None:
    cutoff = time.monotonic() - _IDEMPOTENCY_TTL_SECONDS
    while _queued_requests:
        _, queued_at = next(iter(_queued_requests.items()))
        if queued_at > cutoff:
            break
        _queued_requests.popitem(last=False)


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
