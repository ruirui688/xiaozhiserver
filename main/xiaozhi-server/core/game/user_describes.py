"""User describes game — user gives clues, server guesses the answer."""

import time


class UserDescribesGame:
    """Server-side state machine for 'user describes, assistant guesses' games.

    The user thinks of a target (historical person, object, animal, or place)
    and provides clues. The server asks yes/no questions and makes guesses.
    """

    def __init__(self, manager):
        self.manager = manager

    def handle(self, tool_name, user_message):
        normalized = self.manager._normalize(user_message)
        if self.manager._is_end_message(normalized):
            self.manager.sessions.pop(tool_name, None)
            return "这局先到这。下次你再描述，我继续猜。"

        state = self.manager.sessions.get(tool_name)
        if not state or not state.get("active") or self.manager._is_restart_message(normalized):
            state = self._start(tool_name)
            if self._has_user_describes_clue(normalized):
                self._update_state(state, user_message)
                if state.get("unsupported_reason"):
                    self.manager.sessions.pop(tool_name, None)
                    return self._unsupported_user_describes_response(state)
                if state.get("target_type") == "historical_person":
                    return self._next_historical_person_response(state)
                if state.get("target_type") == "person":
                    return self._next_person_scope_response(state)
                if self._is_general_target_type(state.get("target_type")):
                    return self._next_general_guess_response(state)
            return "可以，你先给我第一条线索，不要直接说答案。可以描述用途、外观、地点、年代或身份。"

        self._update_state(state, user_message)
        if state.get("unsupported_reason"):
            self.manager.sessions.pop(tool_name, None)
            return self._unsupported_user_describes_response(state)
        if state.get("target_type") == "historical_person":
            return self._next_historical_person_response(state)
        if state.get("target_type") == "person":
            return self._next_person_scope_response(state)
        if self._is_general_target_type(state.get("target_type")):
            return self._next_general_guess_response(state)

        if self.manager._contains_any(normalized, ("历史人物", "古代人", "皇帝", "将军", "诗人")):
            state["target_type"] = "historical_person"
            return self._next_historical_person_response(state)

        return "我还缺线索。你描述一个用途、外观、出现地点、年代或身份，不要直接说答案。"

    def _start(self, tool_name):
        state = {
            "active": True,
            "game_type": "user_describes_assistant_guesses",
            "tool_name": tool_name,
            "target_type": "",
            "facts": {},
            "asked": [],
            "excluded_names": set(),
            "last_question": None,
            "turn_count": 0,
            "started_at": time.time(),
        }
        self.manager.sessions[tool_name] = state
        return state

    def _update_state(self, state, user_message):
        normalized = self.manager._normalize(user_message)
        state["turn_count"] += 1

        last_question = state.get("last_question")
        answer = self._classify_answer_to_last_question(normalized, last_question)
        if last_question and answer:
            if last_question.get("type") == "guess":
                if answer == "yes":
                    state["solved"] = True
                    state["solved_name"] = last_question["name"]
                else:
                    state["excluded_names"].add(last_question["name"])
            elif last_question.get("type") == "target_type":
                if answer == "yes":
                    state["target_type"] = last_question["target_type"]
                else:
                    state["target_type"] = ""
            elif last_question.get("type") == "fact":
                key = last_question["key"]
                yes_value = last_question["yes_value"]
                state["facts"][key] = yes_value if answer == "yes" else self._opposite_value(yes_value)
            state["last_question"] = None

        if state.get("unsupported_reason"):
            return

        if self.manager._contains_any(normalized, ("历史人物", "古代人", "皇帝", "将军", "诗人")):
            state["target_type"] = "historical_person"
        if self.manager._contains_any(normalized, ("人物", "人名")) and not state.get("target_type"):
            state["target_type"] = "person"
        if self.manager._contains_any(normalized, ("物品", "东西", "物件")) and not state.get("target_type"):
            state["target_type"] = "object"
        if self.manager._contains_any(normalized, ("地点", "地方", "场所")) and not state.get("target_type"):
            state["target_type"] = "place"
        if self.manager._contains_any(normalized, ("动物", "宠物")) and not state.get("target_type"):
            state["target_type"] = "animal"

        facts = state["facts"]
        if self.manager._contains_any(normalized, ("不是中国", "不属于中国", "不是国内", "不是我国")):
            facts["region"] = "foreign"
            state["target_type"] = "historical_person"
        elif self.manager._contains_any(normalized, ("中国", "国内", "我国", "中华")):
            facts["region"] = "china"
            state["target_type"] = "historical_person"
        if self.manager._contains_any(normalized, ("不是外国", "不是国外", "不是欧洲", "不是美国", "不是西方")):
            facts["region"] = "china"
            state["target_type"] = "historical_person"
        elif self.manager._contains_any(normalized, ("外国", "国外", "欧洲", "美国", "西方")):
            facts["region"] = "foreign"
            state["target_type"] = "historical_person"
        if self.manager._contains_any(normalized, ("不是近代", "不是现代", "不是近现代", "不是民国")):
            facts["period"] = "ancient"
            state["target_type"] = "historical_person"
        elif self.manager._contains_any(normalized, ("近代", "现代", "近现代", "新中国", "民国")):
            facts["period"] = "modern"
            state["target_type"] = "historical_person"
        if self.manager._contains_any(normalized, ("不是古代", "不是秦朝", "不是汉朝", "不是唐朝", "不是宋朝", "不是明朝", "不是清朝", "不是三国")):
            facts["period"] = "modern"
            state["target_type"] = "historical_person"
        elif self.manager._contains_any(normalized, ("古代", "秦朝", "汉朝", "唐朝", "宋朝", "明朝", "清朝", "三国")):
            facts["period"] = "ancient"
            state["target_type"] = "historical_person"
        if self.manager._contains_any(normalized, ("不是皇帝", "不是君主")):
            facts["ruler"] = False
        elif self.manager._contains_any(normalized, ("皇帝", "君主", "帝王")):
            facts["ruler"] = True
            state["target_type"] = "historical_person"
        if self.manager._contains_any(normalized, ("不是战争", "不是军事", "不是将军", "不打仗", "不是建国")):
            facts["military"] = False
            state["target_type"] = "historical_person"
        elif self.manager._contains_any(normalized, ("战争", "军事", "将军", "打仗", "建国")):
            facts["military"] = True
            state["target_type"] = "historical_person"
        if self.manager._contains_any(normalized, ("不是诗人", "不是文学家", "不是作家")):
            facts["poet"] = False
            if not self.manager._contains_any(normalized, ("文学", "思想", "文化", "教育")):
                facts["culture"] = False
            state["target_type"] = "historical_person"
        elif self.manager._contains_any(normalized, ("诗人", "文学家", "作家")):
            facts["culture"] = True
            facts["poet"] = True
            facts["military"] = False
            facts["ruler"] = False
            state["target_type"] = "historical_person"
        elif self.manager._contains_any(normalized, ("文学", "思想", "文化", "教育")):
            facts["culture"] = True
            state["target_type"] = "historical_person"
        if self.manager._contains_any(normalized, ("不是开国", "没有建立", "没有创建")):
            facts["founder"] = False
            state["target_type"] = "historical_person"
        elif self.manager._contains_any(normalized, ("开国", "建立", "创建")):
            facts["founder"] = True
            state["target_type"] = "historical_person"
        if self.manager._contains_any(normalized, ("不是改革", "不是变法", "不是转折")):
            facts["reformer"] = False
            state["target_type"] = "historical_person"
        elif self.manager._contains_any(normalized, ("改革", "变法", "转折")):
            facts["reformer"] = True
            state["target_type"] = "historical_person"
        self._update_general_guess_facts(state, normalized)

    def _next_historical_person_response(self, state):
        if state.get("solved"):
            name = state.get("solved_name")
            self.manager.sessions.pop(state.get("tool_name"), None)
            return f"猜对了，是{name}。这题线索给得挺清楚。"

        candidates = self._filter_historical_candidates(state)
        if len(candidates) == 1:
            return self._ask_candidate_guess(state, candidates[0])

        question = self._choose_historical_question(state, candidates)
        if question:
            key, yes_value, prompt = question
            state["asked"].append(key)
            state["last_question"] = {"type": "fact", "key": key, "yes_value": yes_value}
            return prompt

        if candidates:
            return self._ask_candidate_guess(state, candidates[0])

        return "按目前线索候选排完了。你再给我一个更具体线索，比如年代、国家、身份或代表事件。"

    def _next_person_scope_response(self, state):
        state["last_question"] = {
            "type": "target_type",
            "target_type": "historical_person",
            "unsupported_reason": "non_historical_person",
        }
        return "人物范围很大。它是历史人物吗？如果是，你可以继续给国家、年代、身份或代表事件；如果不是，建议换成物品、动物或地点。"

    @staticmethod
    def _unsupported_user_describes_response(state):
        if state.get("unsupported_reason") == "non_historical_person":
            return "人物题当前主要支持历史人物。你可以换成历史人物，或改选物品、动物、地点继续玩。"
        return "这类题当前还不稳定。你可以选人物、物品、动物或地点，并给一个更明确的线索。"

    def _next_general_guess_response(self, state):
        candidates = self._filter_general_candidates(state)
        if len(candidates) == 1:
            return self._ask_general_candidate_guess(state, candidates[0])

        question = self._choose_general_question(state, candidates)
        if question:
            key, yes_value, prompt = question
            state["asked"].append(key)
            state["last_question"] = {"type": "fact", "key": key, "yes_value": yes_value}
            return prompt

        if candidates:
            return self._ask_general_candidate_guess(state, candidates[0])

        target_text = self._target_type_text(state.get("target_type"))
        return f"按目前线索我还排不出来。你再给一个{target_text}的用途、外观、出现地点或特点。"

    def _filter_general_candidates(self, state):
        target_type = state.get("target_type")
        facts = state.get("facts", {})
        excluded = state.get("excluded_names", set())
        candidates = []
        for item in self.manager._GENERAL_GUESS_CANDIDATES:
            if item["target_type"] != target_type or item["name"] in excluded:
                continue
            matched = True
            for key, value in facts.items():
                if key in item and item.get(key) != value:
                    matched = False
                    break
            if matched:
                candidates.append(item)
        return candidates

    def _choose_general_question(self, state, candidates):
        if not candidates:
            return None
        target_type = state.get("target_type")
        facts = state.get("facts", {})
        asked = set(state.get("asked", []))
        for key, yes_value, prompt in self.manager._GENERAL_QUESTIONS.get(target_type, []):
            if key in facts or key in asked:
                continue
            yes_count = sum(1 for item in candidates if item.get(key) == yes_value)
            no_count = len(candidates) - yes_count
            if yes_count == 0 or no_count == 0:
                continue
            return key, yes_value, prompt
        return None

    def _ask_general_candidate_guess(self, state, item):
        name = item["name"]
        state["last_question"] = {"type": "guess", "name": name}
        return f"按这些线索排下来，我猜是{name}。对吗？"

    def _update_general_guess_facts(self, state, normalized):
        if state.get("target_type") == "historical_person":
            return
        if self.manager._contains_any(normalized, ("能吃", "吃的", "水果", "甜", "餐厅", "饭")):
            if not state.get("target_type"):
                state["target_type"] = "object"
            state["facts"]["food"] = True
        if self.manager._contains_any(normalized, ("用电", "电器", "充电", "插电")):
            state["target_type"] = state.get("target_type") or "object"
            state["facts"]["electric"] = True
        if self.manager._contains_any(normalized, ("不用电", "不需要电")):
            state["facts"]["electric"] = False
        if self.manager._contains_any(normalized, ("随身", "带着", "便携", "拿着", "背")):
            state["target_type"] = state.get("target_type") or "object"
            state["facts"]["portable"] = True
        if self.manager._contains_any(normalized, ("下雨", "雨天", "遮雨")):
            state["target_type"] = state.get("target_type") or "object"
            state["facts"]["rain"] = True
        if self.manager._contains_any(normalized, ("学校", "上学", "学习", "写字", "上课")):
            if state.get("target_type") == "place":
                state["facts"]["education"] = True
            else:
                state["target_type"] = state.get("target_type") or "object"
                state["facts"]["school"] = True
        if self.manager._contains_any(normalized, ("厨房", "餐桌", "做饭")):
            state["target_type"] = state.get("target_type") or "object"
            state["facts"]["kitchen"] = True
        if self.manager._contains_any(normalized, ("宠物", "家养")):
            state["target_type"] = "animal"
            state["facts"]["pet"] = True
        if self.manager._contains_any(normalized, ("水里", "水中", "鱼缸")):
            state["target_type"] = "animal"
            state["facts"]["water"] = True
        if self.manager._contains_any(normalized, ("黑白", "竹子", "国宝")):
            state["target_type"] = "animal"
            state["facts"]["black_white"] = True
            state["facts"]["china"] = True
        if self.manager._contains_any(normalized, ("户外", "外面", "散步", "游玩")):
            state["target_type"] = "place"
            state["facts"]["outdoor"] = True
        if self.manager._contains_any(normalized, ("看病", "治疗", "医生")):
            state["target_type"] = "place"
            state["facts"]["medical"] = True
        if self.manager._contains_any(normalized, ("交通", "坐车", "地铁", "出行")):
            state["target_type"] = "place"
            state["facts"]["transport"] = True

    @staticmethod
    def _is_general_target_type(target_type):
        return target_type in {"object", "animal", "place"}

    @staticmethod
    def _target_type_text(target_type):
        return {
            "object": "物品",
            "animal": "动物",
            "place": "地点",
        }.get(target_type, "答案")

    def _filter_historical_candidates(self, state):
        facts = state.get("facts", {})
        excluded = state.get("excluded_names", set())
        candidates = []
        for person in self.manager._HISTORICAL_PEOPLE:
            if person["name"] in excluded:
                continue
            matched = True
            for key, value in facts.items():
                if person.get(key) != value:
                    matched = False
                    break
            if matched:
                candidates.append(person)
        return candidates

    def _choose_historical_question(self, state, candidates):
        if not candidates:
            return None
        facts = state.get("facts", {})
        asked = set(state.get("asked", []))
        for key, yes_value, prompt in self.manager._HISTORICAL_QUESTIONS:
            if key in facts or key in asked:
                continue
            yes_count = sum(1 for person in candidates if person.get(key) == yes_value)
            no_count = len(candidates) - yes_count
            if yes_count == 0 or no_count == 0:
                continue
            return key, yes_value, prompt
        return None

    def _ask_candidate_guess(self, state, person):
        name = person["name"]
        state["last_question"] = {"type": "guess", "name": name}
        return f"按这些线索排下来，我猜是{name}。对吗？"

    @staticmethod
    def _classify_yes_no(normalized):
        yes_words = {"是", "是的", "对", "对的", "嗯", "没错", "正确", "差不多", "算是"}
        no_words = {"不是", "不对", "错了", "没有", "不算", "否", "不正确"}
        if normalized in yes_words:
            return "yes"
        if normalized in no_words:
            return "no"
        if any(word in normalized for word in no_words):
            return "no"
        if any(word in normalized for word in yes_words):
            return "yes"
        return ""

    def _classify_answer_to_last_question(self, normalized, last_question):
        if not last_question:
            return ""
        if last_question.get("type") == "guess":
            return self._classify_short_feedback(normalized)
        if last_question.get("type") == "fact":
            return self._classify_fact_answer(normalized)
        if last_question.get("type") == "target_type":
            return self._classify_fact_answer(normalized)
        return ""

    @staticmethod
    def _classify_short_feedback(normalized):
        yes_words = {"是", "是的", "对", "对的", "嗯", "没错", "正确", "猜对了", "差不多", "算是"}
        no_words = {"不是", "不对", "错了", "没有", "不算", "否", "不正确", "没猜对", "还不对"}
        if normalized in yes_words:
            return "yes"
        if normalized in no_words:
            return "no"
        if normalized.startswith(("不是", "不对", "错了", "没猜对", "还不对")) and len(normalized) <= 8:
            return "no"
        if normalized.startswith(("是的", "对的", "没错", "正确", "猜对了")) and len(normalized) <= 8:
            return "yes"
        return ""

    @staticmethod
    def _classify_fact_answer(normalized):
        yes_words = {"是", "是的", "对", "对的", "嗯", "没错", "正确", "差不多", "算是"}
        no_words = {"不是", "不是的", "不对", "错了", "没有", "不算", "否", "不正确"}
        if normalized in yes_words:
            return "yes"
        if normalized in no_words:
            return "no"
        return ""

    @staticmethod
    def _opposite_value(value):
        if isinstance(value, bool):
            return not value
        opposites = {
            "china": "foreign",
            "foreign": "china",
            "modern": "ancient",
            "ancient": "modern",
        }
        return opposites.get(value)

    def _has_user_describes_clue(self, normalized):
        start_phrases = (
            "玩你说我猜",
            "你说我猜",
            "猜个词",
            "猜词",
            "我描述你猜",
            "我描述你来猜",
            "我已经想了一个词",
            "我想了一个词",
            "我心里想了一个词",
            "我想好了一个词",
            "我想好一个词",
            "我已经想好",
            "请你猜吧",
            "你来猜吧",
            "让你猜",
        )
        clue_keywords = (
            "历史人物",
            "古代人",
            "皇帝",
            "将军",
            "诗人",
            "人物",
            "物品",
            "东西",
            "物件",
            "地点",
            "地方",
            "场所",
            "动物",
            "宠物",
            "能吃",
            "水果",
            "用电",
            "电器",
            "下雨",
            "雨天",
            "学校",
            "上学",
            "宠物",
            "水里",
            "黑白",
            "竹子",
            "户外",
            "看病",
            "交通",
            "中国",
            "外国",
            "近代",
            "现代",
            "古代",
            "秦朝",
            "汉朝",
            "唐朝",
            "宋朝",
            "明朝",
            "清朝",
            "三国",
            "战争",
            "军事",
            "文学",
            "思想",
            "文化",
            "开国",
            "改革",
        )
        if not self.manager._contains_any(normalized, start_phrases):
            return bool(self.manager._contains_any(normalized, clue_keywords))
        stripped = normalized
        for phrase in start_phrases:
            stripped = stripped.replace(phrase, "")
        return bool(self.manager._contains_any(stripped, clue_keywords))
