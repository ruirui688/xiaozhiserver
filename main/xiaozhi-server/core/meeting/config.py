"""Meeting capability configuration and main-LLM runtime context."""

from core.meeting.minutes import MEETING_STARTED_PROMPT


MEETING_PLUGIN_NAME = "meeting_minutes"


def get_meeting_config(conn) -> dict:
    config = getattr(conn, "config", {}) or {}
    meeting_config = config.get("meeting", {}) or {}
    result = dict(meeting_config) if isinstance(meeting_config, dict) else {}
    plugin_config = (config.get("plugins", {}) or {}).get(MEETING_PLUGIN_NAME)
    if isinstance(plugin_config, dict):
        result.update(plugin_config)
    return result


def is_meeting_mode_enabled(conn) -> bool:
    config = getattr(conn, "config", {}) or {}
    plugins = config.get("plugins", {}) or {}
    if getattr(conn, "read_config_from_api", False):
        return isinstance(plugins, dict) and MEETING_PLUGIN_NAME in plugins
    meeting_config = config.get("meeting", {}) or {}
    if not isinstance(meeting_config, dict):
        return False
    return bool(meeting_config.get("enabled", True))


def get_meeting_entry_prompt(conn) -> str:
    prompt = str(get_meeting_config(conn).get("entry_prompt") or "").strip()
    return prompt or MEETING_STARTED_PROMPT


def build_meeting_runtime_context(conn) -> str:
    """Tell the main LLM the authoritative meeting capability and state."""
    enabled = is_meeting_mode_enabled(conn)
    main_llm_tool_available = bool(
        enabled
        and getattr(getattr(conn, "llm", None), "supports_native_function_call", False)
        and getattr(conn, "intent_type", None) == "function_call"
    )
    meeting = getattr(conn, "meeting_minutes", None)
    active = bool(meeting and meeting.is_active())

    if not enabled:
        instruction = (
            "当前智能体没有启用会议纪要功能，也没有可执行的会议工具。"
            "如果用户要求开启、记录或结束会议，必须明确告知需要先在智能体功能管理中启用会议纪要；"
            "不得声称已经开启、正在记录或已经结束会议。"
        )
    elif not main_llm_tool_available:
        instruction = (
            "会议纪要功能已经配置，但当前主 LLM 没有可执行的原生 meeting_minutes function tool。"
            "如果用户要求开启会议，必须明确说明当前主模型工具调用模式不可用；"
            "不得声称已经开启、正在记录或已经结束会议。"
        )
    elif active:
        instruction = (
            "当前会议已经真实激活。你必须根据当前用户发言调用 meeting_minutes 工具："
            "普通会议正文、引用控制语句、讨论未来结束时间或含糊表达调用 action=record；"
            "只有用户明确要求现在结束记录并生成纪要时调用 action=end。"
            "不要输出自然语言回复，不要调用 direct_answer 或其他工具。"
        )
    else:
        instruction = (
            "会议纪要功能已启用，但当前会议尚未开始。"
            "只有用户明确要求现在开始记录会议时才调用 meeting_minutes(action=start)；"
            "需要结合上下文理解 ASR 近音字、错别字和缺少标点。"
            "否定、引用、询问、未来计划或普通讨论不得启动。"
            "只有成功调用工具才代表会议已开始，不得用普通文字模拟状态切换。"
        )

    return (
        "<meeting_runtime>\n"
        f"plugin_enabled={str(enabled).lower()}\n"
        f"main_llm_tool_available={str(main_llm_tool_available).lower()}\n"
        f"active={str(active).lower()}\n"
        f"{instruction}\n"
        "</meeting_runtime>"
    )
