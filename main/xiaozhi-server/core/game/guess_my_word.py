"""Guess My Word game — server picks a word, user guesses via hints."""

import random
import time


class GuessMyWordGame:
    """Server-side state machine for 'xiaozhi thinks, user guesses' games.

    The manager picks a word from the word bank, gives hints one by one,
    and evaluates user guesses.
    """

    def __init__(self, manager):
        self.manager = manager

    def handle(self, tool_name, user_message):
        normalized = self.manager._normalize(user_message)
        if self.manager._is_end_message(normalized):
            self.manager.sessions.pop(tool_name, None)
            return "这局先到这。想再玩时直接说“你想一个词我来猜”。"

        state = self.manager.sessions.get(tool_name)
        if not state or not state.get("active") or self.manager._is_restart_message(normalized):
            state = self._start(tool_name)
            return (
                f"我想好了，是一个{state['category']}。"
                f"第一个提示：{state['hints'][0]}。你来猜。"
            )

        if self.manager._is_give_up_message(normalized):
            target_word = state["target_word"]
            self.manager.sessions.pop(tool_name, None)
            return f"答案是{target_word}。这局先到这，要不要再来一局？"

        if self.manager._is_hint_request(normalized) or self.manager._is_unknown_message(normalized):
            return self._next_hint_response(state)

        guess = self._extract_guess(user_message)
        if not guess:
            return self._next_hint_response(state)

        state["turn_count"] += 1
        if self._is_correct_guess(guess, state["target_word"]):
            target_word = state["target_word"]
            self.manager.sessions.pop(tool_name, None)
            return f"猜对了，就是{target_word}。要不要再来一局？"

        if guess not in state["user_guesses"]:
            state["user_guesses"].append(guess)
        guess_display = self._guess_display(guess)
        hint = self._consume_next_hint(state)
        if hint:
            return f"不是{guess_display}。再给你一个提示：{hint}。继续猜。"
        return f"不是{guess_display}。提示已经给完了，你再想想。"

    def _start(self, tool_name):
        candidates = [
            item for item in self.manager._WORD_BANK
            if item["word"] not in self.manager.last_words[-3:]
        ] or self.manager._WORD_BANK
        item = random.choice(candidates)
        state = {
            "active": True,
            "game_type": "guess_my_word",
            "phase": "waiting_guess",
            "target_word": item["word"],
            "category": item["category"],
            "hints": item["hints"],
            "hints_given": [item["hints"][0]],
            "user_guesses": [],
            "turn_count": 0,
            "started_at": time.time(),
        }
        self.manager.sessions[tool_name] = state
        self.manager.last_words.append(item["word"])
        self.manager.last_words = self.manager.last_words[-8:]
        return state

    def _next_hint_response(self, state):
        hint = self._consume_next_hint(state)
        if hint:
            return f"再给你一个提示：{hint}。你猜是什么？"
        return "提示已经给完了。你可以大胆猜一个。"

    @staticmethod
    def _consume_next_hint(state):
        given_count = len(state["hints_given"])
        if given_count >= len(state["hints"]):
            return ""
        hint = state["hints"][given_count]
        state["hints_given"].append(hint)
        return hint

    def _extract_guess(self, text):
        normalized = self.manager._normalize(text)
        if not normalized:
            return ""
        prefixes = (
            "我猜是",
            "我觉得是",
            "是不是",
            "是",
            "答案是",
            "应该是",
            "可能是",
            "难道是",
            "猜",
        )
        for prefix in prefixes:
            if normalized.startswith(prefix):
                normalized = normalized[len(prefix):]
                break
        suffixes = ("吗", "吧", "呢", "对不对", "是不是")
        for suffix in suffixes:
            if normalized.endswith(suffix):
                normalized = normalized[: -len(suffix)]
                break
        return normalized[:20]

    def _is_correct_guess(self, guess, target_word):
        guess = self.manager._normalize(guess)
        target_word = self.manager._normalize(target_word)
        if self._is_negated_guess(guess, target_word):
            return False
        return guess == target_word or target_word in guess

    def _is_negated_guess(self, guess, target_word):
        if target_word not in guess:
            return False
        negation_markers = (
            f"不是{target_word}",
            f"肯定不是{target_word}",
            f"应该不是{target_word}",
            f"可能不是{target_word}",
            f"不可能是{target_word}",
            f"不会是{target_word}",
            f"{target_word}不对",
            f"{target_word}不是",
        )
        return any(marker in guess for marker in negation_markers)

    def _guess_display(self, guess):
        normalized = self.manager._normalize(guess)
        for prefix in ("肯定不是", "应该不是", "可能不是", "不可能是", "不会是", "不是"):
            if normalized.startswith(prefix):
                return normalized[len(prefix):] or normalized
        for suffix in ("不对", "不是"):
            if normalized.endswith(suffix):
                return normalized[: -len(suffix)] or normalized
        return normalized
