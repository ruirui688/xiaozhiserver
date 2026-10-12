"""Authorized, connection-local activity selection with rules loaded on demand."""

from dataclasses import dataclass
import hashlib
import json

from core.game.base import GameSessionManager
from core.game.freeform import effective_interaction_config, render_interaction_prompt


END_INTERACTION_TOOL = "end_interaction"


def configured_function_names(config):
    selected = config.get("selected_module", {}).get("Intent")
    functions = config.get("Intent", {}).get(selected, {}).get("functions", [])
    if isinstance(functions, str):
        functions = functions.split(";")
    if isinstance(functions, dict):
        functions = functions.keys()
    try:
        return {name.strip() for name in functions if isinstance(name, str) and name.strip()}
    except TypeError:
        return set()


def supports_interactions(conn):
    router = getattr(conn, "conversation_router", None)
    if router is not None and getattr(router, "is_realtime", False):
        return bool(getattr(getattr(router, "capabilities", None), "native_function_calling", False))
    llm = getattr(conn, "llm", None)
    if llm is None or getattr(llm, "supports_local_context", True) is False:
        return False
    intent = getattr(conn, "intent_type", None)
    return intent == "intent_llm" or (
        intent == "function_call" and getattr(llm, "supports_native_function_call", False)
    )


def available_interactions(conn):
    if not supports_interactions(conn):
        return {}
    from plugins_func.register import all_function_registry

    config = getattr(conn, "config", {}) or {}
    plugins = config.get("plugins", {}) or {}
    if not isinstance(plugins, dict):
        return {}
    available = {}
    for name in sorted(configured_function_names(config)):
        if name in {END_INTERACTION_TOOL, "manage_memory", "set_session_voice"}:
            continue
        plugin = plugins.get(name)
        if not isinstance(plugin, dict):
            continue
        flag = plugin.get("custom_plugin", name in GameSessionManager.FREEFORM_GAME_TOOLS)
        if flag is not True and str(flag).lower() != "true":
            continue
        if name in all_function_registry and name not in GameSessionManager.FREEFORM_GAME_TOOLS:
            continue
        effective = effective_interaction_config(
            name, plugin, GameSessionManager.DEFAULT_FREEFORM_GAME_CONFIGS,
        )
        if render_interaction_prompt(name, effective):
            available[name] = effective
    return available


def _signature(config):
    return hashlib.sha256(json.dumps(config, ensure_ascii=False, sort_keys=True,
                                    default=str).encode()).hexdigest()


def _speaker(conn):
    return (getattr(conn, "current_speaker", None) or "",
            getattr(conn, "current_speaker_id", None))


@dataclass(frozen=True)
class ActiveInteraction:
    name: str
    config: object
    signature: str
    speaker: tuple
    prompt_manager: object


def reset_active_interaction(conn):
    changed = getattr(conn, "_active_interaction", None) is not None
    conn._active_interaction = None
    return changed


def activate_interaction(conn, name):
    """Return false when unavailable; callers must never invent a successful start."""
    available = available_interactions(conn)
    if name not in available or getattr(conn, "closed", False) or getattr(conn, "closing", False):
        return False
    conn._active_interaction = ActiveInteraction(
        name, conn.config, _signature(available[name]), _speaker(conn),
        getattr(conn, "prompt_manager", None),
    )
    return True


def build_active_interaction_prompt(conn):
    state = getattr(conn, "_active_interaction", None)
    if not isinstance(state, ActiveInteraction):
        return None
    available = available_interactions(conn)
    config = available.get(state.name)
    if (state.config is not conn.config or config is None
            or state.signature != _signature(config)
            or state.speaker != _speaker(conn)
            or state.prompt_manager is not getattr(conn, "prompt_manager", None)
            or getattr(conn, "closed", False) or getattr(conn, "closing", False)):
        reset_active_interaction(conn)
        return None
    return render_interaction_prompt(state.name, config)


def active_interaction_name(conn):
    """Return only the validated selection for routers that do not need rules."""
    return conn._active_interaction.name if build_active_interaction_prompt(conn) else None


def interaction_tool_description(name, config, routing_description=None):
    aliases = GameSessionManager.FREEFORM_GAME_ALIASES.get(name, ())
    label = str(config.get("display_name") or config.get("name") or
                (aliases[0] if aliases else name)).strip()[:80]
    # Routing prose is intentionally bounded and never falls back to full rules.
    discovery = " ".join(str(routing_description or config.get("description") or "").split())[:160]
    discovery = f"用途：{discovery}。" if discovery else ""
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": (
                "仅在用户明确要求开始或切换时调用；询问、提及、否定或引用不算开始。"
                "活动已开始后直接继续对话，不重复调用。"
                f"启动或切换到活动“{label}”。{discovery}"
            ),
            "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
        },
    }


def end_interaction_tool_description(routing_description=None):
    discovery = " ".join(str(routing_description or "").split())[:160]
    return {"type": "function", "function": {
        "name": END_INTERACTION_TOOL,
        "description": ("用户明确结束当前活动或换话题离开活动时调用；‘不要结束’、引用和询问不算退出。"
                        "结束后按用户本轮请求正常回答。" + discovery),
        "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
    }}
