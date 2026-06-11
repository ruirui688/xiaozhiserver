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
    USER_DESCRIBES_TOOLS = {"guess_what_i_say"}
    IDIOM_CHAIN_TOOLS = {"idiom_chain"}

    _HISTORICAL_PEOPLE = [
        {"name": "秦始皇", "region": "china", "period": "ancient", "era": "秦汉", "ruler": True, "military": True, "culture": False, "founder": True, "reformer": True},
        {"name": "刘邦", "region": "china", "period": "ancient", "era": "秦汉", "ruler": True, "military": True, "culture": False, "founder": True, "reformer": False},
        {"name": "汉武帝", "region": "china", "period": "ancient", "era": "秦汉", "ruler": True, "military": True, "culture": False, "founder": False, "reformer": True},
        {"name": "曹操", "region": "china", "period": "ancient", "era": "三国", "ruler": False, "military": True, "culture": True, "founder": False, "reformer": False},
        {"name": "诸葛亮", "region": "china", "period": "ancient", "era": "三国", "ruler": False, "military": True, "culture": True, "founder": False, "reformer": False},
        {"name": "唐太宗", "region": "china", "period": "ancient", "era": "隋唐", "ruler": True, "military": True, "culture": False, "founder": False, "reformer": True},
        {"name": "武则天", "region": "china", "period": "ancient", "era": "隋唐", "ruler": True, "military": False, "culture": False, "founder": True, "reformer": True},
        {"name": "李白", "region": "china", "period": "ancient", "era": "隋唐", "ruler": False, "military": False, "culture": True, "founder": False, "reformer": False},
        {"name": "岳飞", "region": "china", "period": "ancient", "era": "宋元", "ruler": False, "military": True, "culture": False, "founder": False, "reformer": False},
        {"name": "朱元璋", "region": "china", "period": "ancient", "era": "明清", "ruler": True, "military": True, "culture": False, "founder": True, "reformer": False},
        {"name": "康熙", "region": "china", "period": "ancient", "era": "明清", "ruler": True, "military": True, "culture": False, "founder": False, "reformer": True},
        {"name": "林则徐", "region": "china", "period": "modern", "era": "近现代", "ruler": False, "military": False, "culture": False, "founder": False, "reformer": True},
        {"name": "孙中山", "region": "china", "period": "modern", "era": "近现代", "ruler": False, "military": False, "culture": False, "founder": True, "reformer": True},
        {"name": "毛泽东", "region": "china", "period": "modern", "era": "近现代", "ruler": False, "military": True, "culture": False, "founder": True, "reformer": True},
        {"name": "邓小平", "region": "china", "period": "modern", "era": "近现代", "ruler": False, "military": False, "culture": False, "founder": False, "reformer": True},
        {"name": "孔子", "region": "china", "period": "ancient", "era": "先秦", "ruler": False, "military": False, "culture": True, "founder": False, "reformer": False},
        {"name": "拿破仑", "region": "foreign", "period": "modern", "era": "欧洲近代", "ruler": True, "military": True, "culture": False, "founder": True, "reformer": True},
        {"name": "华盛顿", "region": "foreign", "period": "modern", "era": "欧美近现代", "ruler": False, "military": True, "culture": False, "founder": True, "reformer": False},
        {"name": "林肯", "region": "foreign", "period": "modern", "era": "欧美近现代", "ruler": False, "military": False, "culture": False, "founder": False, "reformer": True},
        {"name": "丘吉尔", "region": "foreign", "period": "modern", "era": "欧美近现代", "ruler": False, "military": False, "culture": False, "founder": False, "reformer": False},
    ]

    _HISTORICAL_QUESTIONS = [
        ("region", "china", "先确定范围：他主要属于中国历史吗？"),
        ("period", "modern", "从年代看，他主要活跃在近现代吗？比如1840年以后。"),
        ("ruler", True, "从身份看，他本人是皇帝或君主吗？"),
        ("military", True, "从事迹看，他主要和战争、军事或建国过程有关吗？"),
        ("culture", True, "从领域看，他主要以思想、文化或文学出名吗？"),
        ("founder", True, "他是否和建立政权、开国或创建重要制度有关？"),
        ("reformer", True, "他是否以改革、变法或推动社会转折出名？"),
    ]

    _IDIOM_CHAIN_STARTS = [
        "一心一意",
        "水到渠成",
        "心想事成",
    ]

    _IDIOM_NEXT = {
        "意": ["意气风发", "意味深长", "意犹未尽"],
        "发": ["发扬光大", "发奋图强", "发人深省"],
        "大": ["大显身手", "大公无私", "大器晚成"],
        "手": ["手到擒来", "手舞足蹈"],
        "来": ["来日方长", "来之不易"],
        "长": ["长年累月", "长驱直入"],
        "月": ["月明星稀", "月下老人"],
        "稀": ["稀世之宝"],
        "宝": ["宝刀未老"],
        "老": ["老当益壮", "老马识途"],
        "壮": ["壮志凌云"],
        "云": ["云开见日", "云淡风轻"],
        "日": ["日新月异", "日久天长"],
        "异": ["异想天开"],
        "开": ["开门见山", "开卷有益"],
        "山": ["山清水秀", "山高水长"],
        "秀": ["秀外慧中"],
        "中": ["中流砥柱"],
        "柱": ["柱石之臣"],
        "成": ["成人之美", "成竹在胸"],
        "胸": ["胸有成竹"],
        "竹": ["竹报平安"],
        "安": ["安居乐业", "安然无恙"],
        "业": ["业精于勤"],
        "勤": ["勤能补拙"],
        "美": ["美中不足"],
        "足": ["足智多谋"],
        "谋": ["谋事在人"],
        "人": ["人山人海", "人才济济"],
        "海": ["海阔天空", "海纳百川"],
        "空": ["空前绝后"],
        "后": ["后来居上"],
        "上": ["上善若水"],
        "水": ["水到渠成", "水落石出"],
        "出": ["出类拔萃", "出口成章"],
        "华": ["华而不实"],
        "实": ["实事求是"],
        "是": ["是非分明"],
        "明": ["明察秋毫", "明辨是非"],
        "非": ["非同小可"],
        "可": ["可歌可泣"],
        "泣": ["泣不成声"],
        "声": ["声东击西", "声势浩大"],
        "西": ["西装革履"],
        "强": ["强词夺理", "强身健体"],
        "理": ["理直气壮", "理所当然"],
        "省": ["省吃俭用"],
        "用": ["用心良苦"],
        "尽": ["尽善尽美"],
    }
    _IDIOM_SET = set(_IDIOM_CHAIN_STARTS)
    for _items in _IDIOM_NEXT.values():
        _IDIOM_SET.update(_items)

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

    def is_user_describes_tool(self, tool_name):
        return tool_name in self.USER_DESCRIBES_TOOLS

    def is_idiom_chain_tool(self, tool_name):
        return tool_name in self.IDIOM_CHAIN_TOOLS

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

    def handle_idiom_chain(self, tool_name, user_message):
        normalized = self._normalize(user_message)
        state = self.sessions.get(tool_name)
        if self._is_end_message(normalized):
            count = state.get("user_correct", 0) if state else 0
            self.sessions.pop(tool_name, None)
            return f"成语接龙先到这。你刚才接对了{count}个。"

        if not state or not state.get("active") or self._is_restart_message(normalized):
            state = self._start_idiom_chain(tool_name)
            idiom = state["last_idiom"]
            return f"成语接龙开始。我先出：{idiom}。你接“{idiom[-1]}”字。"

        expected = state["expected_char"]
        if self._is_give_up_message(normalized) or self._is_unknown_message(normalized):
            hint = self._peek_idiom(expected)
            state["hints_given"] += 1
            if hint:
                return f"可以接“{hint}”。你也可以换一个“{expected}”字开头的成语。"
            idiom = self._restart_idiom_chain_with_common_start(tool_name)
            return f"这个字不好接。我换一个常见的：{idiom}。你接“{idiom[-1]}”字。"

        user_idiom = self._extract_idiom_answer(user_message)
        if not self._is_known_idiom(user_idiom):
            hint = self._peek_idiom(expected)
            if hint:
                return f"这句不算常见四字成语。请接“{expected}”字，比如“{hint}”。"
            return f"这句不算常见四字成语。还是接“{expected}”字。"

        if not user_idiom.startswith(expected):
            return f"这个成语没接上。要以“{expected}”字开头，再试一个。"

        state["user_correct"] += 1
        state["used"].add(user_idiom)
        state["last_idiom"] = user_idiom
        state["expected_char"] = user_idiom[-1]
        next_idiom = self._server_next_idiom(state)
        if not next_idiom:
            return f"接得上。你这个“{user_idiom[-1]}”字有点难，我认输一回。要不要重新开一局？"
        return f"接得上。{next_idiom}。你接“{next_idiom[-1]}”字。"

    def handle_user_describes_guess(self, tool_name, user_message):
        normalized = self._normalize(user_message)
        if self._is_end_message(normalized):
            self.sessions.pop(tool_name, None)
            return "这局先到这。下次你再描述，我继续猜。"

        state = self.sessions.get(tool_name)
        if not state or not state.get("active") or self._is_restart_message(normalized):
            state = self._start_user_describes_guess(tool_name)
            return "我们玩你说我猜。你描述特点、身份或年代，我来猜；先别直接说答案。"

        self._update_user_describes_state(state, user_message)
        if state.get("target_type") == "historical_person":
            return self._next_historical_person_response(state)

        if self._contains_any(normalized, ("历史人物", "古代人", "皇帝", "将军", "诗人")):
            state["target_type"] = "historical_person"
            return self._next_historical_person_response(state)

        return "我先缩小范围：它是人物、物品、地点，还是动物？"

    def _start_user_describes_guess(self, tool_name):
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
        self.sessions[tool_name] = state
        return state

    def _update_user_describes_state(self, state, user_message):
        normalized = self._normalize(user_message)
        state["turn_count"] += 1

        if self._contains_any(normalized, ("历史人物", "古代人", "皇帝", "将军", "诗人")):
            state["target_type"] = "historical_person"
        if self._contains_any(normalized, ("人物", "人名")) and not state.get("target_type"):
            state["target_type"] = "person"

        last_question = state.get("last_question")
        answer = self._classify_yes_no(normalized)
        if last_question and answer:
            if last_question.get("type") == "guess":
                if answer == "yes":
                    state["solved"] = True
                    state["solved_name"] = last_question["name"]
                else:
                    state["excluded_names"].add(last_question["name"])
            elif last_question.get("type") == "fact":
                key = last_question["key"]
                yes_value = last_question["yes_value"]
                state["facts"][key] = yes_value if answer == "yes" else self._opposite_value(yes_value)
            state["last_question"] = None

        facts = state["facts"]
        if self._contains_any(normalized, ("中国", "国内", "我国", "中华")):
            facts["region"] = "china"
            state["target_type"] = "historical_person"
        if self._contains_any(normalized, ("外国", "国外", "欧洲", "美国", "西方")):
            facts["region"] = "foreign"
            state["target_type"] = "historical_person"
        if self._contains_any(normalized, ("近代", "现代", "近现代", "新中国", "民国")):
            facts["period"] = "modern"
            state["target_type"] = "historical_person"
        if self._contains_any(normalized, ("古代", "秦朝", "汉朝", "唐朝", "宋朝", "明朝", "清朝", "三国")):
            facts["period"] = "ancient"
            state["target_type"] = "historical_person"
        if self._contains_any(normalized, ("不是皇帝", "不是君主")):
            facts["ruler"] = False
        elif self._contains_any(normalized, ("皇帝", "君主", "帝王")):
            facts["ruler"] = True
            state["target_type"] = "historical_person"
        if self._contains_any(normalized, ("战争", "军事", "将军", "打仗", "建国")):
            facts["military"] = True
            state["target_type"] = "historical_person"
        if self._contains_any(normalized, ("诗人", "文学", "思想", "文化", "教育")):
            facts["culture"] = True
            state["target_type"] = "historical_person"
        if self._contains_any(normalized, ("开国", "建立", "创建")):
            facts["founder"] = True
            state["target_type"] = "historical_person"
        if self._contains_any(normalized, ("改革", "变法", "转折")):
            facts["reformer"] = True
            state["target_type"] = "historical_person"

    def _next_historical_person_response(self, state):
        if state.get("solved"):
            name = state.get("solved_name")
            self.sessions.pop(state.get("tool_name"), None)
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

        state["facts"].pop("reformer", None)
        state["facts"].pop("founder", None)
        candidates = self._filter_historical_candidates(state)
        if candidates:
            return self._ask_candidate_guess(state, candidates[0])

        return "我这边线索有点冲突。你再给我一个年代、国家或身份线索，我继续排除。"

    def _filter_historical_candidates(self, state):
        facts = state.get("facts", {})
        excluded = state.get("excluded_names", set())
        candidates = []
        for person in self._HISTORICAL_PEOPLE:
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
        for key, yes_value, prompt in self._HISTORICAL_QUESTIONS:
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
    def _contains_any(text, phrases):
        return any(phrase in text for phrase in phrases)

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

    def _start_idiom_chain(self, tool_name):
        candidates = [item for item in self._IDIOM_CHAIN_STARTS if item not in self.last_words[-3:]]
        idiom = random.choice(candidates or self._IDIOM_CHAIN_STARTS)
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
        self.sessions[tool_name] = state
        self.last_words.append(idiom)
        self.last_words = self.last_words[-8:]
        return state

    def _server_next_idiom(self, state):
        expected = state["expected_char"]
        candidates = self._IDIOM_NEXT.get(expected, [])
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

    def _restart_idiom_chain_with_common_start(self, tool_name):
        state = self._start_idiom_chain(tool_name)
        return state["last_idiom"]

    def _peek_idiom(self, expected):
        return next((idiom for idiom in self._IDIOM_NEXT.get(expected, [])), "")

    @classmethod
    def _is_known_idiom(cls, idiom):
        return len(idiom) == 4 and idiom in cls._IDIOM_SET

    @classmethod
    def _extract_idiom_answer(cls, text):
        normalized = cls._normalize(text)
        prefixes = ("我接", "接", "我想是", "我觉得是", "应该是", "答案是")
        for prefix in prefixes:
            if normalized.startswith(prefix):
                normalized = normalized[len(prefix):]
                break
        return normalized[:4]

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
                "想不出来",
                "不会",
                "接不上",
                "放弃",
                "揭晓答案",
                "公布答案",
                "告诉我答案",
                "答案是什么",
            )
        )
