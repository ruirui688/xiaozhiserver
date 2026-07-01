"""Idiom chain game — server and user take turns linking four-character idioms."""

import random
import time


class IdiomChainGame:
    """Server-side state machine for idiom chain (成语接龙) game.

    The server picks a starting idiom, the user must respond with another
    idiom whose first character matches the previous idiom's last character.
    """

    def __init__(self, manager):
        self.manager = manager

    def handle(self, tool_name, user_message):
        normalized = self.manager._normalize(user_message)
        state = self.manager.sessions.get(tool_name)
        if self.manager._is_end_message(normalized):
            count = state.get("user_correct", 0) if state else 0
            self.manager.sessions.pop(tool_name, None)
            return f"成语接龙先到这。你刚才接对了{count}个。"

        if not state or not state.get("active") or self.manager._is_restart_message(normalized):
            state = self._start(tool_name)
            idiom = state["last_idiom"]
            return self._format_opening(idiom)

        expected = state["expected_char"]
        if (
            self.manager._is_hint_request(normalized)
            or self.manager._is_give_up_message(normalized)
            or self.manager._is_unknown_message(normalized)
        ):
            hint = self._peek(expected)
            state["hints_given"] += 1
            if hint:
                return f"可以接“{hint}”。你也可以换一个“{expected}”字开头的成语。"
            idiom = self._restart_with_common_start(tool_name)
            return f"这个字不好接。我换一个常见的：{idiom}。你接“{idiom[-1]}”字。"

        user_idiom = self._extract_answer(user_message)
        if not user_idiom.startswith(expected):
            if len(user_idiom) == 4:
                return f"这个成语没接上。现在要以“{expected}”字开头，再试一个。"
            return f"这个成语没接上。要以“{expected}”字开头，再试一个。"

        if not self._is_known_idiom(user_idiom):
            hint = self._peek(expected)
            if hint:
                return f"这句不在当前成语库里。请接“{expected}”字，比如“{hint}”。"
            return f"这句不在当前成语库里。还是接“{expected}”字。"

        if user_idiom in state["used"]:
            return f"“{user_idiom}”刚才用过了。请换一个“{expected}”字开头的成语。"

        state["user_correct"] += 1
        state["used"].add(user_idiom)
        state["last_idiom"] = user_idiom
        state["expected_char"] = user_idiom[-1]
        next_idiom = self._server_next(state)
        if not next_idiom:
            return f"接得上。你这个“{user_idiom[-1]}”字有点难，我认输一回。要不要重新开一局？"
        return f"接得上。{next_idiom}。你接“{next_idiom[-1]}”字。"

    def _start(self, tool_name):
        recent_words = set(self.manager.last_words[-8:])
        candidates = [
            item for item in self.manager._IDIOM_CHAIN_STARTS
            if item not in recent_words
        ]
        idiom = random.choice(candidates or self.manager._IDIOM_CHAIN_STARTS)
        state = {
            "active": True,
            "game_type": "idiom_chain",
            "last_idiom": idiom,
            "expected_char": idiom[-1],
            "used": {idiom},
            "user_correct": 0,
            "hints_given": 0,
            "started_at": time.time(),
        }
        self.manager.sessions[tool_name] = state
        self.manager.last_words.append(idiom)
        self.manager.last_words = self.manager.last_words[-8:]
        return state

    def _format_opening(self, idiom):
        template = random.choice(self.manager._IDIOM_CHAIN_OPENINGS)
        return template.format(idiom=idiom, char=idiom[-1])

    def _server_next(self, state):
        expected = state["expected_char"]
        candidates = self.manager._IDIOM_NEXT.get(expected, [])
        next_idiom = ""
        for idiom in candidates:
            if idiom not in state["used"]:
                next_idiom = idiom
                break
        if not next_idiom:
            return ""
        state["used"].add(next_idiom)
        state["last_idiom"] = next_idiom
        state["expected_char"] = next_idiom[-1]
        return next_idiom

    def _restart_with_common_start(self, tool_name):
        state = self._start(tool_name)
        return state["last_idiom"]

    def _peek(self, expected):
        return next((idiom for idiom in self.manager._IDIOM_NEXT.get(expected, [])), "")

    def _is_known_idiom(self, idiom):
        return len(idiom) == 4 and idiom in self.manager._IDIOM_SET

    def _extract_answer(self, text):
        normalized = self.manager._normalize(text)
        prefixes = ("我接", "接", "我想是", "我觉得是", "应该是", "答案是")
        for prefix in prefixes:
            if normalized.startswith(prefix):
                normalized = normalized[len(prefix):]
                break
        return normalized[:4]
