import math
import time
from collections.abc import Mapping
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler


DEFAULT_WAKEUP_VAD_IGNORE_MS = 0


def get_wakeup_vad_ignore_ms(conn: "ConnectionHandler") -> int:
    """Return a safe, non-negative wake-up audio ignore duration."""
    config = getattr(conn, "config", {})
    raw_value = (
        config.get("wakeup_vad_ignore_ms", DEFAULT_WAKEUP_VAD_IGNORE_MS)
        if isinstance(config, Mapping)
        else DEFAULT_WAKEUP_VAD_IGNORE_MS
    )
    try:
        if isinstance(raw_value, bool):
            raise ValueError("boolean is not a valid duration")
        value = float(raw_value)
        if not math.isfinite(value) or value < 0:
            raise ValueError("duration must be finite and non-negative")
        return int(value)
    except (TypeError, ValueError):
        if not getattr(conn, "_wakeup_vad_ignore_warning_emitted", False):
            logger = getattr(conn, "logger", None)
            if logger is not None:
                logger.bind(tag=__name__).warning(
                    "wakeup_vad_ignore_ms配置无效，按0毫秒处理: "
                    f"{raw_value!r}"
                )
            conn._wakeup_vad_ignore_warning_emitted = True
        return DEFAULT_WAKEUP_VAD_IGNORE_MS


def arm_wakeup_vad_ignore(conn: "ConnectionHandler") -> None:
    """Start the configurable post-wake audio ignore window."""
    ignore_ms = get_wakeup_vad_ignore_ms(conn)
    conn.wakeup_vad_ignore_until = time.monotonic() + ignore_ms / 1000
    conn.just_woken_up = ignore_ms > 0


def should_ignore_wakeup_audio(conn: "ConnectionHandler") -> bool:
    """Return whether this packet is inside the post-wake ignore window."""
    if not getattr(conn, "just_woken_up", False):
        return False

    deadline = getattr(conn, "wakeup_vad_ignore_until", None)
    if deadline is None:
        # Compatibility with callers that only set ``just_woken_up``.
        ignore_ms = get_wakeup_vad_ignore_ms(conn)
        deadline = time.monotonic() + ignore_ms / 1000
        conn.wakeup_vad_ignore_until = deadline

    if time.monotonic() < deadline:
        return True

    conn.just_woken_up = False
    conn.wakeup_vad_ignore_until = 0.0
    return False
