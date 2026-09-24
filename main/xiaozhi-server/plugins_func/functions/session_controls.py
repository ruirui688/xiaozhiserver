"""LLM-callable controls that only affect the current device connection."""

from typing import TYPE_CHECKING

from config.logger import setup_logging
from core.session.conversation_context import (
    ConversationContextResetError,
    reset_conversation_context as reset_short_term_context,
)
from plugins_func.register import Action, ActionResponse, ToolType, register_function

if TYPE_CHECKING:
    from core.connection import ConnectionHandler


TAG = __name__
logger = setup_logging()


reset_conversation_context_desc = {
    "type": "function",
    "function": {
        "name": "reset_conversation_context",
        "description": (
            "仅当当前用户消息直接要求忘记此前聊天内容时调用；"
            "其他任何情况都不要调用。"
        ),
        "parameters": {
            "type": "object",
            "properties": {},
            "additionalProperties": False,
        },
    },
}


@register_function(
    "reset_conversation_context",
    reset_conversation_context_desc,
    ToolType.SYSTEM_CTL,
)
def reset_conversation_context(conn: "ConnectionHandler"):
    try:
        result = reset_short_term_context(conn)
    except ConversationContextResetError as exc:
        return ActionResponse(
            action=Action.RESPONSE,
            result="当前状态不允许清空上下文",
            response=str(exc),
        )
    logger.bind(tag=TAG).info(
        "当前会话上下文已清空: removed_messages={}, upstreamResetScheduled={}",
        result.removed_messages,
        result.upstream_reset_scheduled,
    )
    return ActionResponse(
        action=Action.RESPONSE,
        result="当前会话上下文已清空",
        response="好的，前面的对话上下文已经清空，我们重新开始聊。",
    )
