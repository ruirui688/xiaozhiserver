import time
from collections import deque
import secrets


STOP_PHRASES = frozenset(
    {
        "闭嘴",
        "别说了",
        "不要说了",
        "停止说话",
        "停止播报",
        "停下",
        "安静",
    }
)


def lock_reception_reply(conn, duration_sec: float = 15.0) -> None:
    conn.reception_reply_lock_until = time.monotonic() + max(1.0, float(duration_sec))


def clear_reception_reply_lock(conn) -> None:
    conn.reception_reply_lock_until = 0.0


def reception_reply_locked(conn) -> bool:
    return bool(
        getattr(conn, "client_is_speaking", False)
        and time.monotonic() < float(getattr(conn, "reception_reply_lock_until", 0.0))
    )


def is_explicit_stop(text: str) -> bool:
    normalized = "".join(character for character in str(text).strip() if character.isalnum())
    return normalized in STOP_PHRASES


def defer_reception_turn(conn, text: str, speaker_id: str = "") -> bool:
    normalized = str(text or "").strip()
    if not normalized:
        return False
    queue = getattr(conn, "_reception_deferred_turns", None)
    if queue is None:
        queue = deque(maxlen=16)
        conn._reception_deferred_turns = queue
    item = {
        "text": normalized,
        "speaker_id": str(speaker_id or "anonymous").strip() or "anonymous",
        "queued_at": time.monotonic(),
    }
    if not any(
        queued["text"] == item["text"]
        and queued["speaker_id"] == item["speaker_id"]
        for queued in queue
    ):
        queue.append(item)
        return True
    return False


def pop_next_reception_turn(conn, chooser=None):
    queue = getattr(conn, "_reception_deferred_turns", None)
    if not queue:
        return None
    by_speaker = {}
    for item in queue:
        by_speaker.setdefault(item["speaker_id"], item)
    speaker_ids = tuple(by_speaker)
    selected_speaker = (
        chooser(speaker_ids) if chooser is not None else secrets.choice(speaker_ids)
    )
    selected = by_speaker[selected_speaker]
    queue.remove(selected)
    return selected
