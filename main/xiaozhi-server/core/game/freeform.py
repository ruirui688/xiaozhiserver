"""Render one explicitly selected activity; never infer selection from text."""

import html

from core.game.prompt_profiles import default_configs


ACTIVITY_PROMPT_MAX_CHARS = 12_000
ACTIVITY_SELECTION_RULES = (
    "这里只约束当前活动中的回答；无关问题正常回答，不强迫继续活动。"
    "用户明确结束或换话题离开活动时调用 end_interaction；否定、引用、询问不是退出。"
    "用户明确选择另一个活动时调用对应启动工具。已在进行的活动直接依据可见历史继续，"
    "不要每轮重复调用启动工具；结束后不得自行重启。"
    "服务端只保存活动选择，不保存隐藏答案、进度或成绩；缺失的历史应确认，不得虚构。"
)


def effective_interaction_config(name, config, legacy_defaults=None):
    """Missing legacy fields inherit defaults; explicit empty fields stay empty."""
    defaults = default_configs().get(name) or (legacy_defaults or {}).get(name, {})
    effective = dict(defaults)
    effective.update(config)
    if "instructions" not in effective:
        effective["instructions"] = config.get("description", "")
    return effective


def render_interaction_prompt(name, config):
    instructions = str(config.get("instructions") or "").strip()
    opening = str(config.get("opening_message") or "").strip()
    if not instructions and not opening:
        return None
    parts = [f'<active_interaction name="{html.escape(name, quote=True)}">',
             ACTIVITY_SELECTION_RULES]
    if instructions:
        parts.append("当前玩法规则：" + html.escape(instructions, quote=False))
    if opening:
        parts.append("仅首次开始时可参考的开场：" + html.escape(opening, quote=False))
    parts.append("</active_interaction>")
    prompt = "\n".join(parts)
    # A partial rule can reverse meaning; oversized configurations are unavailable.
    return prompt if len(prompt) <= ACTIVITY_PROMPT_MAX_CHARS else None


class FreeformGameManager:
    """Compatibility renderer. Selection is performed by authorized tools only."""

    def __init__(self, manager):
        self.manager = manager

    def build_freeform_game_prompt(self, user_message, enabled_plugins, active_name=None):
        # The old two-argument API must not reintroduce every enabled rule or
        # select an activity from keywords. Callers must provide explicit state.
        if not active_name or not isinstance(enabled_plugins, dict):
            return None
        config = enabled_plugins.get(active_name)
        if not isinstance(config, dict):
            return None
        flag = config.get("custom_plugin", True)
        if flag is False or str(flag).lower() == "false":
            return None
        return render_interaction_prompt(active_name, effective_interaction_config(
            active_name, config, self.manager.DEFAULT_FREEFORM_GAME_CONFIGS,
        ))
