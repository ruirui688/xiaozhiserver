import asyncio
import concurrent.futures
from typing import TYPE_CHECKING

from config.manage_api_client import forget_managed_memory, remember_managed_memory
from plugins_func.register import Action, ActionResponse, ToolType, register_function

if TYPE_CHECKING:
    from core.connection import ConnectionHandler


MANAGE_MEMORY_FUNCTION_DESC = {
    "type": "function",
    "function": {
        "name": "manage_memory",
        "description": (
            "由你自主决定是否修改长期记忆时调用。只有你调用本工具才会修改记忆；"
            "服务端不根据关键词或预设场景替你做决定。"
            "remember 用于新增或更新你认为应跨会话保留的信息，forget 用于移除你认为不应继续使用的信息。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "operation": {
                    "type": "string",
                    "enum": ["remember", "forget"],
                    "description": "要执行的记忆动作。",
                },
                "text": {
                    "type": "string",
                    "description": "由你根据完整对话语义生成的记忆内容或忘记目标。",
                },
                "category": {
                    "type": "string",
                    "description": "remember 时由你自主归纳的可读分类，可为空。",
                },
                "reason": {
                    "type": "string",
                    "description": "forget 时由你给出的简短原因，可为空。",
                },
                "visibility": {
                    "type": "string",
                    "enum": ["private", "shared"],
                    "description": (
                        "由你根据完整语义选择：private 仅当前已识别说话人可见；"
                        "shared 可供该智能体后续与任何说话人的对话使用。"
                    ),
                },
            },
            "required": ["operation", "text", "visibility"],
        },
    },
}


@register_function("manage_memory", MANAGE_MEMORY_FUNCTION_DESC, ToolType.SYSTEM_CTL)
def manage_memory(
    conn: "ConnectionHandler",
    operation: str,
    text: str,
    visibility: str,
    category: str = "",
    reason: str = "",
):
    operation = str(operation or "").strip().lower()
    visibility = str(visibility or "").strip().lower()
    memory_text = str(text or "").strip()
    if (
        operation not in {"remember", "forget"}
        or visibility not in {"private", "shared"}
        or not memory_text
    ):
        return ActionResponse(Action.ERROR, None, "记忆工具参数无效")
    if not getattr(conn, "loop", None) or not conn.loop.is_running():
        return ActionResponse(Action.ERROR, None, "记忆服务当前不可用")

    agent_id = (conn.config or {}).get("agent_id")
    speaker_id = getattr(conn, "current_speaker_id", None)
    speaker_name = getattr(conn, "current_speaker", None) if speaker_id else None
    if visibility == "private" and not speaker_id:
        return ActionResponse(Action.ERROR, None, "私有记忆需要可信说话人身份")
    shared = visibility == "shared"
    if operation == "remember":
        coroutine = remember_managed_memory(
            conn.device_id,
            agent_id,
            memory_text,
            category=category or None,
            speaker_id=speaker_id,
            speaker_name=speaker_name,
            shared=shared,
        )
    else:
        coroutine = forget_managed_memory(
            conn.device_id,
            agent_id,
            memory_text,
            reason=reason or None,
            speaker_id=speaker_id,
            speaker_name=speaker_name,
            shared=shared,
        )

    try:
        timeout = int((conn.config or {}).get("tool_call_timeout", 30))
        succeeded = asyncio.run_coroutine_threadsafe(coroutine, conn.loop).result(timeout=timeout)
    except concurrent.futures.TimeoutError:
        return ActionResponse(Action.ERROR, None, "记忆操作超时")
    except Exception as e:
        conn.logger.bind(tag=__name__).warning(
            f"LLM主动记忆操作失败: errorType={type(e).__name__}"
        )
        return ActionResponse(Action.ERROR, None, "记忆操作失败")

    if not succeeded:
        return ActionResponse(Action.ERROR, None, "记忆操作失败")
    return ActionResponse(Action.REQLLM, "记忆操作已完成", None)
