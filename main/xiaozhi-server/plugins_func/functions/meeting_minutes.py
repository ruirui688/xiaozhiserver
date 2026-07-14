"""Main-LLM function tool for meeting session state changes."""

from typing import TYPE_CHECKING, Optional

from core.meeting.config import (
    get_meeting_entry_prompt,
    is_meeting_mode_enabled,
)
from core.meeting.executor import finish_meeting
from plugins_func.register import Action, ActionResponse, ToolType, register_function

if TYPE_CHECKING:
    from core.connection import ConnectionHandler


MEETING_MINUTES_TOOL_DESCRIPTION = {
    "type": "function",
    "function": {
        "name": "meeting_minutes",
        "description": (
            "管理服务端真实会议记录状态。只有工具调用成功才代表状态发生变化，禁止仅用文字声称已开启或结束。"
            "未激活时，用户明确要求现在开始记录会议才调用 start，并结合上下文理解 ASR 近音字、错别字和缺少标点；"
            "会议激活后，每条用户发言都必须调用本工具：普通会议正文、引用退出说法、讨论未来结束时间或含糊表达调用 record；"
            "只有用户明确要求现在结束记录并生成纪要时调用 end。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["start", "record", "end"],
                    "description": "start=开始会议；record=记录当前会议正文；end=结束并生成完整纪要。",
                },
                "topic": {
                    "type": "string",
                    "description": "action=start 时可填写从用户表达中理解到的会议主题；没有明确主题则留空。",
                },
            },
            "required": ["action"],
        },
    },
}


@register_function(
    "meeting_minutes",
    MEETING_MINUTES_TOOL_DESCRIPTION,
    ToolType.SYSTEM_CTL,
)
def meeting_minutes(
    conn: "ConnectionHandler",
    action: str,
    topic: Optional[str] = None,
):
    meeting = getattr(conn, "meeting_minutes", None)
    if meeting is None:
        return ActionResponse(
            action=Action.ERROR,
            response="会议运行状态未初始化，暂时无法执行。",
        )
    if not is_meeting_mode_enabled(conn):
        return ActionResponse(
            action=Action.RESPONSE,
            response="当前智能体没有启用会议纪要功能，请先在功能管理中开启。",
        )

    normalized_action = str(action or "").strip().lower()
    if normalized_action == "start":
        if meeting.is_active():
            return ActionResponse(
                action=Action.RECORD,
                result="meeting_already_active",
                response="[会议已在记录，继续保持静默]",
            )
        meeting.start(str(topic or "").strip())
        _refresh_tools(conn)
        conn.logger.bind(tag=__name__).info("主LLM工具调用已进入会议模式")
        return ActionResponse(
            action=Action.RESPONSE,
            result="meeting_started",
            response=get_meeting_entry_prompt(conn),
        )

    if normalized_action == "record":
        if not meeting.is_active():
            return ActionResponse(
                action=Action.RESPONSE,
                response="会议尚未开始，不能记录会议正文。",
            )
        appended = meeting.record_pending_turn()
        conn.logger.bind(tag=__name__).info(
            f"主LLM工具调用继续会议记录: utteranceCount={appended}"
        )
        return ActionResponse(
            action=Action.RECORD,
            result=f"meeting_recorded:{appended}",
            response="[会议内容已记录，继续保持静默]",
        )

    if normalized_action == "end":
        if not meeting.is_active():
            return ActionResponse(
                action=Action.RESPONSE,
                response="会议尚未开始，不能结束会议。",
            )
        meeting.discard_pending_turn()
        finish_meeting(conn)
        _refresh_tools(conn)
        return ActionResponse(
            action=Action.RECORD,
            result="meeting_ended_summary_pending",
            response="[会议已结束，正在生成完整会议纪要]",
        )

    conn.logger.bind(tag=__name__).warning(
        f"拒绝主LLM返回的非法会议动作: action={normalized_action}"
    )
    return ActionResponse(
        action=Action.RECORD,
        result="invalid_meeting_action",
        response="[会议动作无效，状态未改变]",
    )


def _refresh_tools(conn) -> None:
    handler = getattr(conn, "func_handler", None)
    if handler is not None:
        handler.upload_functions_desc()
