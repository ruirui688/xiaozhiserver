"""Generation-scoped completion for model-selected terminal tools."""

from typing import Any, Iterable


TERMINAL_TURN_BEHAVIOR = "terminate_silently"


def has_terminal_turn_annotation(annotations: Any) -> bool:
    """Accept only the explicit MCP terminal-turn capability marker."""
    return bool(
        isinstance(annotations, dict)
        and annotations.get("turnBehavior") == TERMINAL_TURN_BEHAVIOR
    )


def select_terminal_tool_calls(
    tool_handler: Any,
    tool_calls: Iterable[dict],
) -> list[dict]:
    """Return calls that the registered tool metadata marks as terminal."""
    if tool_handler is None:
        return []
    return [
        call
        for call in tool_calls
        if tool_handler.is_terminal_turn_tool(str(call.get("name") or ""))
    ]


async def finish_terminal_turn(conn: Any, expected_abort_generation: int) -> bool:
    """Abort exactly once unless the device already closed this generation."""
    with conn._abort_lock:
        already_aborted = bool(
            conn.client_abort
            or conn.abort_generation != int(expected_abort_generation)
        )
    if already_aborted:
        return False

    from core.handle.abortHandle import handleAbortMessage

    await handleAbortMessage(conn)
    return True
