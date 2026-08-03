"""LLM-callable controls that only affect the current device connection."""

from typing import TYPE_CHECKING, Optional

from config.logger import setup_logging
from core.session import TTSOptionError
from plugins_func.register import Action, ActionResponse, ToolType, register_function

if TYPE_CHECKING:
    from core.connection import ConnectionHandler


TAG = __name__
logger = setup_logging()


set_session_voice_desc = {
    "type": "function",
    "function": {
        "name": "set_session_voice",
        "description": (
            "调整当前设备连接后续回复使用的语音参数，仅对本次在线会话生效。"
            "用户要求换声音、换音色、说快/慢一点、调高/低音量或音调时调用。"
            "音色必须填写当前 TTS 服务可用的音色 ID；不要把人物称呼或自然语言风格猜成音色 ID。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "voice": {
                    "type": "string",
                    "description": "供应商音色 ID。仅在用户给出或上下文明确提供可用 ID 时填写。",
                },
                "speed": {
                    "type": "number",
                    "minimum": -100,
                    "maximum": 100,
                    "description": "语速百分比，0 为默认，负数更慢，正数更快。",
                },
                "volume": {
                    "type": "number",
                    "minimum": -100,
                    "maximum": 100,
                    "description": "音量百分比，0 为默认，负数更小，正数更大。",
                },
                "pitch": {
                    "type": "number",
                    "minimum": -100,
                    "maximum": 100,
                    "description": "音调百分比，0 为默认，负数更低，正数更高。",
                },
            },
            "additionalProperties": False,
        },
    },
}


reset_conversation_context_desc = {
    "type": "function",
    "function": {
        "name": "reset_conversation_context",
        "description": (
            "清空当前设备连接的短期对话上下文并重新开始聊天。"
            "用户明确要求忘掉前面的对话、清空上下文、重新开始时调用。"
            "不会删除后台聊天记录、长期记忆或智能体配置。"
        ),
        "parameters": {
            "type": "object",
            "properties": {},
            "additionalProperties": False,
        },
    },
}


@register_function("set_session_voice", set_session_voice_desc, ToolType.SYSTEM_CTL)
def set_session_voice(
    conn: "ConnectionHandler",
    voice: Optional[str] = None,
    speed: Optional[float] = None,
    volume: Optional[float] = None,
    pitch: Optional[float] = None,
):
    if voice is not None:
        catalog = conn.config.get("tts_voice_catalog") or []
        allowed = {
            str(item.get("id") or "").strip()
            for item in catalog
            if isinstance(item, dict) and item.get("id")
        } if isinstance(catalog, list) else set()
        if allowed and str(voice).strip() not in allowed:
            return ActionResponse(
                action=Action.RESPONSE,
                result="当前会话音色调整失败",
                response="这个音色不在当前语音服务的可用列表中。",
            )
    try:
        result = conn.tts_session_options.apply(
            conn.tts,
            provider_identity=conn.tts_session_options.provider_identity(conn),
            voice=voice,
            speed=speed,
            volume=volume,
            pitch=pitch,
        )
    except TTSOptionError as exc:
        return ActionResponse(
            action=Action.RESPONSE,
            result="当前会话语音参数调整失败",
            response=str(exc),
        )

    labels = {
        "voice": "音色",
        "speed": "语速",
        "volume": "音量",
        "pitch": "音调",
    }
    changed = "、".join(labels[name] for name in result.applied)
    logger.bind(tag=TAG).info(
        "当前会话 TTS 参数已调整: fields={}",
        sorted(result.applied),
    )
    return ActionResponse(
        action=Action.RESPONSE,
        result=f"已调整当前会话的{changed}",
        response=f"好的，{changed}已经调整，从下一轮回复开始生效。",
    )


@register_function(
    "reset_conversation_context",
    reset_conversation_context_desc,
    ToolType.SYSTEM_CTL,
)
def reset_conversation_context(conn: "ConnectionHandler"):
    protected_reason = _context_reset_protected_reason(conn)
    if protected_reason:
        return ActionResponse(
            action=Action.RESPONSE,
            result="当前状态不允许清空上下文",
            response=protected_reason,
        )

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
    logger.bind(tag=TAG).info("当前会话上下文已清空: removed_messages={}", removed)
    return ActionResponse(
        action=Action.RESPONSE,
        result="当前会话上下文已清空",
        response="好的，前面的对话上下文已经清空，我们重新开始聊。",
    )


def _context_reset_protected_reason(conn: "ConnectionHandler") -> Optional[str]:
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
