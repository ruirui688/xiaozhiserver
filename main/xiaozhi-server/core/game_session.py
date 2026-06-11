"""In-memory game sessions for voice interaction plugins."""

import random
import re
import time


class GameSessionManager:
    """Keep lightweight per-connection game state.

    State is intentionally scoped to one connection. It solves continuity during
    an active voice game without persisting hidden answers beyond the session.
    """

    GUESS_MY_WORD_TOOLS = {
        "xiaozhi_thinks_you_guess",
        "guess_xiaozhi_word",
        "i_think_you_guess",
    }

    _WORD_BANK = [
        {
            "word": "雨伞",
            "category": "日用品",
            "hints": ["下雨天经常用到它", "它可以打开和收起来", "它常被放在门口或包里"],
        },
        {
            "word": "冰箱",
            "category": "家用电器",
            "hints": ["它通常在厨房或餐厅", "它能让东西保持低温", "水果、饮料和剩菜常放在里面"],
        },
        {
            "word": "地铁",
            "category": "交通工具",
            "hints": ["它在城市里很常见", "它通常按线路和站点运行", "早晚高峰时人很多"],
        },
        {
            "word": "铅笔",
            "category": "学习用品",
            "hints": ["它可以用来写字画画", "写错了通常可以擦掉", "它常和橡皮一起出现"],
        },
        {
            "word": "西瓜",
            "category": "水果",
            "hints": ["夏天很常见", "外面通常是绿色的", "切开后里面多半是红色的"],
        },
        {
            "word": "熊猫",
            "category": "动物",
            "hints": ["它很有中国辨识度", "它身上主要是黑白两色", "它喜欢吃竹子"],
        },
        {
            "word": "书包",
            "category": "日用品",
            "hints": ["学生经常用它", "它可以背在肩上", "里面常放书、本子和文具"],
        },
        {
            "word": "月亮",
            "category": "自然事物",
            "hints": ["夜晚更容易看到它", "它有时圆有时弯", "中秋节常会提到它"],
        },
    ]

    def __init__(self):
        self.sessions = {}
        self.last_words = []

    def is_guess_my_word_tool(self, tool_name):
        return tool_name in self.GUESS_MY_WORD_TOOLS

    def get_active_tool(self):
        for tool_name, state in self.sessions.items():
            if state.get("active"):
                return tool_name
        return None

    def has_active_game(self):
        return self.get_active_tool() is not None

    def handle_guess_my_word(self, tool_name, user_message):
        normalized = self._normalize(user_message)
        if self._is_end_message(normalized):
            self.sessions.pop(tool_name, None)
            return "这局先到这。想再玩时直接说“你想一个词我来猜”。"

        state = self.sessions.get(tool_name)
        if not state or not state.get("active") or self._is_restart_message(normalized):
            state = self._start_guess_my_word(tool_name)
            return (
                f"我想好了，是一个{state['category']}。"
                f"第一个提示：{state['hints'][0]}。你来猜。"
            )

        if self._is_give_up_message(normalized):
            target_word = state["target_word"]
            self.sessions.pop(tool_name, None)
            return f"答案是{target_word}。这局先到这，要不要再来一局？"

        if self._is_hint_request(normalized) or self._is_unknown_message(normalized):
            return self._next_hint_response(state)

        guess = self._extract_guess(user_message)
        if not guess:
            return self._next_hint_response(state)

        state["turn_count"] += 1
        if self._is_correct_guess(guess, state["target_word"]):
            target_word = state["target_word"]
            self.sessions.pop(tool_name, None)
            return f"猜对了，就是{target_word}。要不要再来一局？"

        if guess not in state["user_guesses"]:
            state["user_guesses"].append(guess)
        hint = self._consume_next_hint(state)
        if hint:
            return f"不是{guess}。再给你一个提示：{hint}。继续猜。"
        return f"不是{guess}。提示已经给完了，你再想想。"

    def _start_guess_my_word(self, tool_name):
        candidates = [
            item for item in self._WORD_BANK if item["word"] not in self.last_words[-3:]
        ] or self._WORD_BANK
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
        self.sessions[tool_name] = state
        self.last_words.append(item["word"])
        self.last_words = self.last_words[-8:]
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

    @staticmethod
    def _normalize(text):
        return re.sub(r"[\s，。！？、,.!?;；:：~～\"'“”‘’（）()]+", "", text or "")

    @classmethod
    def _extract_guess(cls, text):
        normalized = cls._normalize(text)
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

    @classmethod
    def _is_correct_guess(cls, guess, target_word):
        guess = cls._normalize(guess)
        target_word = cls._normalize(target_word)
        return guess == target_word or target_word in guess

    @staticmethod
    def _is_end_message(normalized):
        return any(
            phrase in normalized
            for phrase in ("不玩了", "结束", "退出", "不猜了", "换游戏")
        ) or normalized in {"停"}

    @staticmethod
    def _is_restart_message(normalized):
        return normalized in {
            "再来",
            "再来一局",
            "重新开始",
            "重新来",
            "新的一局",
            "换一个",
            "换题",
            "换个词",
        }

    @staticmethod
    def _is_hint_request(normalized):
        return normalized in {"提示", "给提示", "给个提示", "再提示一下", "不知道"}

    @staticmethod
    def _is_unknown_message(normalized):
        return normalized in {"不知道", "没思路", "没有思路"}

    @staticmethod
    def _is_give_up_message(normalized):
        return any(
            phrase in normalized
            for phrase in (
                "猜不到",
                "想不到",
                "不会",
                "放弃",
                "揭晓答案",
                "公布答案",
                "告诉我答案",
                "答案是什么",
            )
        )
