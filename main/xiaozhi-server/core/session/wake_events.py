"""Classify wake input and pair legacy detect/ASR events within one session.

Legacy firmware has no shared wake ID. Pair one opposite-source observation
within the readiness queue's ten-second budget, never ordinary instructions.
Same-source observations start a new wake; a consumed pair cannot suppress the
next wake. State is reserved synchronously before any await on the event loop.
"""

from dataclasses import dataclass
import time

from core.semantic.normalizer import TextNormalizer


NORMALIZER = TextNormalizer()
PAIR_SECONDS = 10.0


def content_text(text):
    return NORMALIZER._extract_payload(str(text or ""))[0]


def text_key(text):
    return "".join(
        char.lower() for char in text
        if char.isalnum()
    )


def classify_input(config, text):
    """Return empty/wake/text without changing the original payload."""
    content = content_text(text)
    key = text_key(content)
    if not key:
        return "empty"
    configured = {text_key(str(word)) for word in config.get("wakeup_words", []) or []}
    if key in configured or not text_key(NORMALIZER.strip_leading_wake_address(content)):
        return "wake"
    if not text_key(content_text(NORMALIZER.normalize_runtime_text(text))):
        return "empty"
    return "text"


@dataclass
class WakeEvent:
    session_id: str
    source: str
    created_at: float
    turn_id: str | None
    paired: bool = False
    generation: int | None = None


def claim_wake(conn, source, turn_id=None):
    now = time.monotonic()
    session = getattr(conn, "session_id", "")
    source = "asr" if source == "device_asr" else source
    previous = getattr(conn, "_wake_event", None)
    if (
        previous is not None and previous.session_id == session
        and turn_id is not None and previous.turn_id == turn_id
        and previous.source == source and now - previous.created_at <= PAIR_SECONDS
    ):
        conn.logger.bind(tag=__name__).info(
            f"输入过滤: source={source}, kind=wake, reason=duplicate_turn"
        )
        return None
    if (
        previous is not None
        and previous.session_id == session
        and not previous.paired
        and now - previous.created_at <= PAIR_SECONDS
        and {previous.source, source} == {"detect", "asr"}
    ):
        previous.paired = True
        conn.logger.bind(tag=__name__).info(
            f"输入过滤: source={source}, kind=wake, reason=paired_wake, "
            f"generation={previous.generation}"
        )
        return None
    event = WakeEvent(session, source, now, turn_id)
    conn._wake_event = event
    return event
