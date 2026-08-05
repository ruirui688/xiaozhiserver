"""LLM-callable controls that only affect the current device connection."""

from typing import TYPE_CHECKING, Optional

from config.logger import setup_logging
from core.session import TTSOptionError
from core.session.conversation_context import (
    ConversationContextResetError,
    reset_conversation_context as reset_short_term_context,
)
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
    router = getattr(conn, "conversation_router", None)
    is_realtime = bool(router is not None and getattr(router, "is_realtime", False))
    if is_realtime:
        if any(value is not None for value in (speed, volume, pitch)):
            return ActionResponse(
                action=Action.RESPONSE,
                result="当前 Realtime 会话仅支持切换音色",
                response="当前端到端实时模型只支持切换音色，不支持临时调整语速、音量或音调。",
            )
        voice_id = str(voice or "").strip()
        if not voice_id:
            return ActionResponse(
                action=Action.RESPONSE,
                result="当前会话音色调整失败",
                response="请提供要切换的音色 ID。",
            )
        catalog = router.voice_catalog()
        allowed = {
            str(item.get("id") or "").strip()
            for item in catalog
            if isinstance(item, dict) and item.get("id")
        }
        # 豆包音色由账号开通资源决定，目录只提供常用项；Qwen 目录为受控列表。
        capabilities = getattr(router, "capabilities", None)
        strict_catalog = bool(
            capabilities is not None
            and not capabilities.custom_voice_ids
            and allowed
        )
        if strict_catalog and voice_id not in allowed:
            return ActionResponse(
                action=Action.RESPONSE,
                result="当前会话音色调整失败",
                response="这个音色不在当前 Realtime 模型的可用列表中。",
            )
        if not router.request_voice_change(voice_id):
            return ActionResponse(
                action=Action.RESPONSE,
                result="当前会话音色调整失败",
                response="当前 Realtime 会话不支持运行时切换音色。",
            )
        logger.bind(tag=TAG).info("当前 Realtime 会话音色已排队切换")
        return ActionResponse(
            action=Action.RESPONSE,
            result="已调整当前会话的音色",
            response="好的，音色已经调整，从下一轮回复开始生效。",
        )

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
