"""Connection-scoped short-term conversation context controls."""

from dataclasses import dataclass
from typing import Optional


class ConversationContextResetError(RuntimeError):
    """Raised when the current connection cannot safely reset its context."""


@dataclass(frozen=True)
class ConversationContextResetResult:
    removed_messages: int
    upstream_reset_scheduled: bool


def reset_conversation_context(
    conn, *, request_upstream_reset: bool = True
) -> ConversationContextResetResult:
    """Clear local short-term state and rebuild Realtime context when required."""
    protected_reason = context_reset_protected_reason(conn)
    if protected_reason:
        raise ConversationContextResetError(protected_reason)

    upstream_reset_scheduled = False
    router = getattr(conn, "conversation_router", None)
    if (
        request_upstream_reset
        and router is not None
        and getattr(router, "is_realtime", False)
    ):
        capabilities = getattr(router, "capabilities", None)
        if capabilities is None or not capabilities.context_reset:
            raise ConversationContextResetError(
                "当前 Realtime 模型暂不支持安全地重建上游会话。"
            )
        if not router.request_context_reset():
            raise ConversationContextResetError(
                "当前 Realtime 会话暂时无法重建，请稍后再试。"
            )
        upstream_reset_scheduled = True

    removed = conn.dialogue.clear_conversation_history(preserve_system=True)
    conn.last_tool_context = None
    conn.current_speaker = None
    conn.current_speaker_id = None
    if getattr(conn, "turn_context", None) is not None:
        conn.turn_context.clear()
    if getattr(conn, "game_session_manager", None) is not None:
        conn.game_session_manager.clear_active_games()
    semantic_center = getattr(conn, "semantic_center", None)
    if semantic_center is not None:
        semantic_center.state_manager.reset()
    conn._inject_tool_call_fewshot()
    return ConversationContextResetResult(
        removed_messages=removed,
        upstream_reset_scheduled=upstream_reset_scheduled,
    )


def context_reset_protected_reason(conn) -> Optional[str]:
    meeting = getattr(conn, "meeting_minutes", None)
    if meeting is not None and meeting.is_active():
        return "会议记录进行中，暂时不能清空上下文；请先结束会议。"
    enrollment = getattr(conn, "voiceprint_enrollment", None)
    status = getattr(enrollment, "status", None)
    if status is not None and getattr(status, "active", False):
        return "声纹采集进行中，暂时不能清空上下文；请先结束声纹采集。"
    if getattr(conn, "calling", False):
        return "设备通话进行中，暂时不能清空上下文；请先结束通话。"
    return None
