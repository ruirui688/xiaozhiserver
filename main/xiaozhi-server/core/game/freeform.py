"""Freeform game manager — handles prompt building and state for LLM-driven games."""

import random
import re
import time


class FreeformGameManager:
    """Manages freeform game state and prompt construction for LLM-driven games.

    These games are not governed by a strict server-side state machine; instead,
    the LLM receives rules and state context and drives the game naturally.
    """

    def __init__(self, manager):
        self.manager = manager

    def build_freeform_game_prompt(self, user_message, enabled_plugins):
        """Return editable soft rules for explicit language games."""
        plugin_configs = self._merge_freeform_game_configs(enabled_plugins)
        plugin_name = self._match_enabled_freeform_game(user_message, plugin_configs)
        if not plugin_name:
            return None

        plugin_config = plugin_configs.get(plugin_name, {})
        if not isinstance(plugin_config, dict):
            return None
        instructions = self._config_text(
            plugin_config.get("instructions") or plugin_config.get("description")
        )
        opening_message = self._config_text(plugin_config.get("opening_message"))

        if not instructions and not opening_message:
            return None

        parts = [
            f"<freeform_game name=\"{plugin_name}\">",
            "当前用户正在玩这个功能插件对应的纯语言游戏。你必须在主对话里自然主持游戏，不要调用同名工具，不要要求用户去点屏幕按钮。",
        ]
        legacy_guess_what_i_say = self._is_legacy_guess_what_i_say_mode(
            plugin_name, instructions
        )
        mode_rules = self._freeform_game_mode_rules(
            plugin_name, legacy_guess_what_i_say
        )
        if mode_rules:
            parts.append(mode_rules)
        if instructions and not legacy_guess_what_i_say:
            parts.append(f"玩法规则：{instructions}")
        elif legacy_guess_what_i_say:
            parts.append(
                "后台当前保存的是旧版“用户描述线索”规则，本轮已按二十问/猜物模式覆盖执行；"
                "如需自定义，请在功能插件中把玩法规则改成二十问流程。"
            )
        if opening_message and not legacy_guess_what_i_say:
            parts.append(f"建议开场：{opening_message}")
        state_context = self._freeform_game_state_prompt(plugin_name)
        if state_context:
            parts.append(state_context)
        parts.append("如果用户明确说不玩了、退出游戏、换个话题或换游戏，就自然结束当前游戏。")
        parts.append("</freeform_game>")
        return "\n".join(parts)

    def _freeform_game_mode_rules(self, plugin_name, legacy_guess_what_i_say=False):
        if plugin_name == "guess_what_i_say":
            return self.manager.GUESS_WHAT_I_SAY_QUESTIONS_RULES
        return ""

    def _is_legacy_guess_what_i_say_mode(self, plugin_name, instructions):
        if plugin_name != "guess_what_i_say":
            return False
        normalized = self.manager._normalize(instructions)
        return (
            "描述线索" in normalized
            or "用户描述" in normalized
            or "你描述" in normalized
            or "第一条线索" in normalized
        )

    def _match_enabled_freeform_game(self, user_message, enabled_plugins):
        if not isinstance(enabled_plugins, dict) or not enabled_plugins:
            self.manager.active_freeform_game = None
            return None

        enabled_games = {
            name
            for name in enabled_plugins
            if name in self.manager.FREEFORM_GAME_TOOLS and self._is_enabled_game_config(enabled_plugins.get(name))
        }
        if not enabled_games:
            self.manager.active_freeform_game = None
            return None

        normalized = self.manager._normalize(user_message)
        if self.manager._is_end_message(normalized):
            self.manager.active_freeform_game = None
            self.manager.freeform_game_state = None
            return None

        for game_name in enabled_games:
            aliases = self.manager.FREEFORM_GAME_ALIASES.get(game_name, ())
            if self._contains_freeform_game_alias(normalized, aliases):
                self.manager.active_freeform_game = game_name
                self._ensure_freeform_game_state(game_name, normalized)
                return game_name

        if self.manager.active_freeform_game in enabled_games:
            self._ensure_freeform_game_state(self.manager.active_freeform_game, normalized)
            return self.manager.active_freeform_game

        return None

    def _merge_freeform_game_configs(self, enabled_plugins):
        merged = dict(self.manager.DEFAULT_FREEFORM_GAME_CONFIGS)
        if not isinstance(enabled_plugins, dict):
            return merged
        for name, plugin_config in enabled_plugins.items():
            if name not in self.manager.FREEFORM_GAME_TOOLS:
                continue
            if not isinstance(plugin_config, dict):
                continue
            if isinstance(plugin_config, dict) and not self._is_enabled_game_config(plugin_config):
                merged.pop(name, None)
                continue
            merged[name] = plugin_config
        return merged

    @staticmethod
    def _is_enabled_game_config(plugin_config):
        if not isinstance(plugin_config, dict):
            return False
        custom_flag = plugin_config.get("custom_plugin", True)
        if isinstance(custom_flag, bool):
            return custom_flag
        return str(custom_flag).lower() != "false"

    @staticmethod
    def _config_text(value):
        if value is None:
            return ""
        if isinstance(value, str):
            return value.strip()
        return str(value).strip()

    def _ensure_freeform_game_state(self, plugin_name, normalized_message):
        if (
            self.manager.freeform_game_state
            and self.manager.freeform_game_state.get("game") == plugin_name
            and not self.manager._is_restart_message(normalized_message)
        ):
            self.manager.freeform_game_state["turn_count"] += 1
            return self.manager.freeform_game_state

        if plugin_name in self.manager.ASSISTANT_THINKS_GAMES:
            target = self._select_freeform_target_word()
            state = {
                "game": plugin_name,
                "role_mode": "assistant_thinks_user_guesses",
                "target_word": target["word"],
                "category": target["category"],
                "hints": list(target["hints"]),
                "given_hints": [target["hints"][0]] if target.get("hints") else [],
                "turn_count": 0,
                "started_at": time.time(),
            }
        else:
            state = {
                "game": plugin_name,
                "role_mode": "user_thinks_assistant_guesses",
                "target_word": "",
                "category": "",
                "hints": [],
                "given_hints": [],
                "turn_count": 0,
                "started_at": time.time(),
            }
        self.manager.freeform_game_state = state
        return state

    def _freeform_game_state_prompt(self, plugin_name):
        state = self.manager.freeform_game_state
        if not state or state.get("game") != plugin_name:
            return ""

        if state.get("role_mode") == "assistant_thinks_user_guesses":
            hints = state.get("hints") or []
            first_hint = hints[0] if hints else ""
            parts = [
                "<game_state>",
                "状态来源：服务端会话状态，优先级高于历史对话中的猜测。",
                "玩法角色：小智心里有固定答案，用户来猜；小智必须围绕这个固定答案回应。",
                f"固定答案：{state.get('target_word', '')}",
                f"答案类别：{state.get('category', '')}",
                f"第一条提示：{first_hint}",
                f"当前轮次：{state.get('turn_count', 0)}",
                "规则：不要更换答案，不要临时编造新答案；用户猜错时基于同一个答案给短提示；用户猜对或放弃时再揭晓答案。",
                "输出要短，1到2句，适合语音播报。",
                "</game_state>",
            ]
            return "\n".join(parts)

        if state.get("role_mode") == "user_thinks_assistant_guesses":
            return "\n".join(
                [
                    "<game_state>",
                    "状态来源：服务端会话状态，优先级高于历史对话中的猜测。",
                    "玩法角色：用户心里有固定答案，小智不知道答案；小智负责主动提问并最终猜出答案。",
                    f"当前轮次：{state.get('turn_count', 0)}",
                    "规则：小智不得自选或暗藏目标答案；不要替用户回答排查问题；每轮只问一个是/否/不确定问题。",
                    "输出要短，通常只输出一个问题。",
                    "</game_state>",
                ]
            )

        return ""

    def _select_freeform_target_word(self):
        candidates = [
            item for item in self.manager._WORD_BANK
            if item["word"] not in self.manager.last_words[-3:]
        ] or self.manager._WORD_BANK
        item = random.choice(candidates)
        self.manager.last_words.append(item["word"])
        self.manager.last_words = self.manager.last_words[-8:]
        return item

    def _contains_freeform_game_alias(self, normalized_message, aliases):
        chinese_message = self._remove_ascii_noise(normalized_message)
        for alias in aliases:
            normalized_alias = self.manager._normalize(alias)
            if normalized_alias in normalized_message:
                return True
            if (
                normalized_alias
                and self._remove_ascii_noise(normalized_alias) in chinese_message
            ):
                return True
        return False

    @staticmethod
    def _remove_ascii_noise(text):
        return re.sub(r"[A-Za-z0-9_]+", "", text or "")
