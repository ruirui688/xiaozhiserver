"""In-memory game sessions for voice interaction plugins.

GameSessionManager keeps lightweight per-connection game state, delegates to
specialised game modules, and provides shared utilities consumed by those modules.
"""

import re

from core.game.guess_my_word import GuessMyWordGame
from core.game.user_describes import UserDescribesGame
from core.game.idiom_chain import IdiomChainGame
from core.game.freeform import FreeformGameManager


class GameSessionManager:
    """Keep lightweight per-connection game state.

    State is intentionally scoped to one connection. It solves continuity during
    an active voice game without persisting hidden answers beyond the session.
    """

    FREEFORM_GAME_TOOLS = {
        "xiaozhi_thinks_you_guess",
        "guess_xiaozhi_word",
        "i_think_you_guess",
        "guess_what_i_say",
        "idiom_chain",
    }
    ASSISTANT_THINKS_GAMES = {
        "xiaozhi_thinks_you_guess",
        "guess_xiaozhi_word",
    }
    USER_THINKS_GAMES = {
        "guess_what_i_say",
        "i_think_you_guess",
    }
    FREEFORM_GAME_ALIASES = {
        "idiom_chain": (
            "成语接龙",
            "成語接龍",
        ),
        "guess_what_i_say": (
            "你说我猜",
            "说我猜",
            "咱们说我猜",
            "我们说我猜",
            "我说你猜",
            "猜人物挑战",
            "猜人物",
            "人物挑战",
            "猜人游戏",
            "猜人",
            "猜物游戏",
            "猜物",
            "我描述你猜",
            "我描述你来猜",
            "我想好了你猜",
            "我想好了你来猜",
            "我想好你猜",
            "我已经想好了你猜",
            "我已经想好了你来猜",
            "我已经想了一个物品",
            "我想了一个物品",
            "我想好了一个物品",
            "我已经想了一个东西",
            "我想了一个东西",
            "我想好了一个东西",
            "猜词",
            "猜个词",
        ),
        "xiaozhi_thinks_you_guess": (
            "你想我猜",
            "你想一个词我来猜",
            "你出题我猜",
            "我来猜你想的词",
            "我猜你想的词",
        ),
        "guess_xiaozhi_word": (
            "你想一个词我来猜",
            "你出题我猜",
            "我来猜你想的词",
            "我猜你想的词",
        ),
        "i_think_you_guess": (
            "我想你猜",
            "我想一个词你来猜",
            "我出题你猜",
        ),
    }
    # 这些互动游戏交给主 LLM 自由对话，不再用服务端状态机接管。
    GUESS_MY_WORD_TOOLS = set()
    USER_DESCRIBES_TOOLS = set()
    IDIOM_CHAIN_TOOLS = set()
    GUESS_WHAT_I_SAY_QUESTIONS_RULES = (
        "【你说我猜纠偏规则】这个玩法采用二十问/猜物模式：用户心里想一个答案，小智负责主动提问并最终猜出答案。"
        "角色绝不能反：不是小智心里默想东西让用户来问；小智不能自选、暗藏或虚构目标答案。"
        "用户说“想好了”“开始”“来”后，不要让用户描述第一条线索，不要问答案是什么；你要直接问第一个可用“对/不是/不确定”回答的区分性问题。"
        "用户说“我已经想了一个物品/东西/人物/动物/地点”时，表示答案已经在用户心里，下一句必须直接提问，例如“它是日常能用到的物品吗？”，禁止再说“开始描述，我来猜”，不能要求用户描述线索。"
        "如果用户反过来问“是吃的吗、是人物吗、哪个朝代”等排查问题，不要回答这些问题；"
        "要简短纠正“这局是我问你答”，然后立刻改由小智提出一个是/否问题。"
        "每轮只问一个是/否问题，优先从大类、是否人造、是否用电、尺寸、使用场景、主要功能等维度缩小范围；"
        "如果是猜人物，也只能问“是否真实人物、是否中国人、是否现代、是否主要从事政治/影视/体育/文化”等是/否问题，"
        "禁止要求用户描述这个人的事迹、经历、特征或关键线索。"
        "用户回答“对/不是/不确定”后，要先吸收这个事实，再继续问下一个问题。"
        "线索足够时再明确猜一个答案；猜错后根据否定反馈继续缩小范围。"
        "回答要短，通常只输出一个问题；不要说“继续问、接着问、再猜猜”这类让用户提问的话。"
        "用户说不玩了、结束、退出时，简短结束，不要继续追问。"
    )
    DEFAULT_FREEFORM_GAME_CONFIGS = {
        "guess_what_i_say": {
            "custom_plugin": True,
            "instructions": (
                "你是二十问式猜词游戏玩家。用户心里想一个答案，小智主动通过连续提问缩小范围，最后猜出答案。"
                "小智绝不能自己心里默想答案，也不能让用户来问小智。"
                "用户刚开始游戏时，让用户先在心里想一个物品、人物、动物、地点、虚拟角色或常见概念，不要说出答案；"
                "用户说“想好了”“开始”“来”或直接回答一个范围后，直接问第一个是/否问题。"
                "如果用户说“我已经想了一个物品/东西/人物/动物/地点”，要把这当作已经想好答案，下一句直接问第一个问题，不能要求用户先给提示。"
                "每轮只问一个能用“对、不是、不确定”回答的问题；不要同时抛多个问题；"
                "不要让用户描述第一句提示；禁止问“答案是什么”“你心里想的是什么”“目标词是什么”。"
                "如果用户反过来问问题，不要替隐藏答案回答，只纠正玩法并继续由小智提问。"
                "用户回答后要吸收事实再问下一个区分性问题；线索足够时再明确猜一个答案。"
            ),
            "opening_message": "你先在心里想一个答案，不用告诉我。想好了我来问问题猜。",
        },
        "idiom_chain": {
            "custom_plugin": True,
            "instructions": (
                "你是成语接龙主持人。每轮只处理一个成语；用户成语的第一个字应接上上一轮成语的最后一个字；"
                "答对后立刻给出你的下一个常见成语；用户接不上时只给一个提示。"
            ),
            "opening_message": "来玩成语接龙。我先出一个常见的，你接最后一个字。",
        },
        "xiaozhi_thinks_you_guess": {
            "custom_plugin": True,
            "instructions": (
                "你先在心里选定一个常见词，用户根据提示来猜。每局围绕同一个答案推进；"
                "用户猜错时给一个新提示，用户猜对后揭晓并询问是否再来一局。"
            ),
            "opening_message": "我想好了，是一个常见词。第一个提示来了，你来猜。",
        },
        "guess_xiaozhi_word": {
            "custom_plugin": True,
            "instructions": (
                "你先在心里选定一个常见词，用户根据提示来猜。每局围绕同一个答案推进；"
                "用户猜错时给一个新提示，用户猜对后揭晓并询问是否再来一局。"
            ),
            "opening_message": "我想好了，是一个常见词。第一个提示来了，你来猜。",
        },
        "i_think_you_guess": {
            "custom_plugin": True,
            "instructions": (
                "用户先在心里选定一个答案，小智通过连续提问缩小范围，最后猜出答案。"
                "每轮只问一个能用“对、不是、不确定”回答的问题；不要让用户直接描述线索或说出答案。"
            ),
            "opening_message": "你先在心里想一个答案，不用告诉我。想好了我来问问题猜。",
        },
    }

    _HISTORICAL_PEOPLE = [
        {"name": "秦始皇", "region": "china", "period": "ancient", "era": "秦汉", "ruler": True, "military": True, "culture": False, "poet": False, "founder": True, "reformer": True},
        {"name": "刘邦", "region": "china", "period": "ancient", "era": "秦汉", "ruler": True, "military": True, "culture": False, "poet": False, "founder": True, "reformer": False},
        {"name": "汉武帝", "region": "china", "period": "ancient", "era": "秦汉", "ruler": True, "military": True, "culture": False, "poet": False, "founder": False, "reformer": True},
        {"name": "曹操", "region": "china", "period": "ancient", "era": "三国", "ruler": False, "military": True, "culture": True, "poet": True, "founder": False, "reformer": False},
        {"name": "诸葛亮", "region": "china", "period": "ancient", "era": "三国", "ruler": False, "military": True, "culture": True, "poet": False, "founder": False, "reformer": False},
        {"name": "唐太宗", "region": "china", "period": "ancient", "era": "隋唐", "ruler": True, "military": True, "culture": False, "poet": False, "founder": False, "reformer": True},
        {"name": "武则天", "region": "china", "period": "ancient", "era": "隋唐", "ruler": True, "military": False, "culture": False, "poet": False, "founder": True, "reformer": True},
        {"name": "李白", "region": "china", "period": "ancient", "era": "隋唐", "ruler": False, "military": False, "culture": True, "poet": True, "founder": False, "reformer": False},
        {"name": "岳飞", "region": "china", "period": "ancient", "era": "宋元", "ruler": False, "military": True, "culture": False, "poet": False, "founder": False, "reformer": False},
        {"name": "朱元璋", "region": "china", "period": "ancient", "era": "明清", "ruler": True, "military": True, "culture": False, "poet": False, "founder": True, "reformer": False},
        {"name": "康熙", "region": "china", "period": "ancient", "era": "明清", "ruler": True, "military": True, "culture": False, "poet": False, "founder": False, "reformer": True},
        {"name": "林则徐", "region": "china", "period": "modern", "era": "近现代", "ruler": False, "military": False, "culture": False, "poet": False, "founder": False, "reformer": True},
        {"name": "孙中山", "region": "china", "period": "modern", "era": "近现代", "ruler": False, "military": False, "culture": False, "poet": False, "founder": True, "reformer": True},
        {"name": "毛泽东", "region": "china", "period": "modern", "era": "近现代", "ruler": False, "military": True, "culture": False, "poet": False, "founder": True, "reformer": True},
        {"name": "邓小平", "region": "china", "period": "modern", "era": "近现代", "ruler": False, "military": False, "culture": False, "poet": False, "founder": False, "reformer": True},
        {"name": "孔子", "region": "china", "period": "ancient", "era": "先秦", "ruler": False, "military": False, "culture": True, "poet": False, "founder": False, "reformer": False},
        {"name": "拿破仑", "region": "foreign", "period": "modern", "era": "欧洲近代", "ruler": True, "military": True, "culture": False, "poet": False, "founder": True, "reformer": True},
        {"name": "华盛顿", "region": "foreign", "period": "modern", "era": "欧美近现代", "ruler": False, "military": True, "culture": False, "poet": False, "founder": True, "reformer": False},
        {"name": "林肯", "region": "foreign", "period": "modern", "era": "欧美近现代", "ruler": False, "military": False, "culture": False, "poet": False, "founder": False, "reformer": True},
        {"name": "丘吉尔", "region": "foreign", "period": "modern", "era": "欧美近现代", "ruler": False, "military": False, "culture": False, "poet": False, "founder": False, "reformer": False},
    ]

    _HISTORICAL_QUESTIONS = [
        ("region", "china", "先确定范围：他主要属于中国历史吗？"),
        ("period", "modern", "从年代看，他主要活跃在近现代吗？比如1840年以后。"),
        ("ruler", True, "从身份看，他本人是皇帝或君主吗？"),
        ("military", True, "从事迹看，他主要和战争、军事或建国过程有关吗？"),
        ("culture", True, "从领域看，他主要以思想、文化或文学出名吗？"),
        ("poet", True, "更具体一点，他是诗人或文学创作者吗？"),
        ("founder", True, "他是否和建立政权、开国或创建重要制度有关？"),
        ("reformer", True, "他是否以改革、变法或推动社会转折出名？"),
    ]

    _GENERAL_GUESS_CANDIDATES = [
        {"name": "雨伞", "target_type": "object", "portable": True, "electric": False, "food": False, "rain": True, "school": False, "kitchen": False},
        {"name": "冰箱", "target_type": "object", "portable": False, "electric": True, "food": False, "rain": False, "school": False, "kitchen": True},
        {"name": "铅笔", "target_type": "object", "portable": True, "electric": False, "food": False, "rain": False, "school": True, "kitchen": False},
        {"name": "书包", "target_type": "object", "portable": True, "electric": False, "food": False, "rain": False, "school": True, "kitchen": False},
        {"name": "手机", "target_type": "object", "portable": True, "electric": True, "food": False, "rain": False, "school": False, "kitchen": False},
        {"name": "西瓜", "target_type": "object", "portable": False, "electric": False, "food": True, "rain": False, "school": False, "kitchen": True},
        {"name": "熊猫", "target_type": "animal", "pet": False, "water": False, "china": True, "black_white": True, "small": False},
        {"name": "猫", "target_type": "animal", "pet": True, "water": False, "china": False, "black_white": False, "small": True},
        {"name": "狗", "target_type": "animal", "pet": True, "water": False, "china": False, "black_white": False, "small": False},
        {"name": "金鱼", "target_type": "animal", "pet": True, "water": True, "china": False, "black_white": False, "small": True},
        {"name": "学校", "target_type": "place", "building": True, "outdoor": False, "education": True, "medical": False, "transport": False, "food": False},
        {"name": "医院", "target_type": "place", "building": True, "outdoor": False, "education": False, "medical": True, "transport": False, "food": False},
        {"name": "公园", "target_type": "place", "building": False, "outdoor": True, "education": False, "medical": False, "transport": False, "food": False},
        {"name": "地铁站", "target_type": "place", "building": True, "outdoor": False, "education": False, "medical": False, "transport": True, "food": False},
        {"name": "餐厅", "target_type": "place", "building": True, "outdoor": False, "education": False, "medical": False, "transport": False, "food": True},
    ]

    _GENERAL_QUESTIONS = {
        "object": [
            ("food", True, "它是能吃的东西吗？"),
            ("electric", True, "它需要用电吗？"),
            ("portable", True, "它通常能随身带着吗？"),
            ("school", True, "它经常出现在学习或上学场景里吗？"),
            ("kitchen", True, "它经常出现在厨房或餐桌附近吗？"),
            ("rain", True, "它和下雨天有关吗？"),
        ],
        "animal": [
            ("pet", True, "它通常会被人当作宠物吗？"),
            ("water", True, "它主要生活在水里吗？"),
            ("china", True, "它很有中国辨识度吗？"),
            ("black_white", True, "它身上主要是黑白两色吗？"),
            ("small", True, "它体型通常比较小吗？"),
        ],
        "place": [
            ("outdoor", True, "它主要是户外场所吗？"),
            ("education", True, "它和学习、上课有关吗？"),
            ("medical", True, "它和看病、治疗有关吗？"),
            ("transport", True, "它和交通出行有关吗？"),
            ("food", True, "它和吃饭有关吗？"),
            ("building", True, "它通常是在建筑里面吗？"),
        ],
    }

    _IDIOM_CHAIN_STARTS = [
        "一心一意",
        "水到渠成",
        "心想事成",
        "万事如意",
        "海阔天空",
        "山清水秀",
        "大显身手",
        "开门见山",
        "安居乐业",
        "日新月异",
        "人山人海",
        "声东击西",
        "足智多谋",
        "胸有成竹",
        "长年累月",
        "云开见日",
        "实事求是",
        "理直气壮",
        "可歌可泣",
        "中流砥柱",
    ]
    _IDIOM_CHAIN_OPENINGS = [
        "来一局成语接龙，我出“{idiom}”，你接“{char}”字。",
        "这次从“{idiom}”开始，你接最后的“{char}”字。",
        "我先抛一个：“{idiom}”。轮到你接“{char}”字。",
        "开局成语是“{idiom}”，下一句请用“{char}”字开头。",
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
        "拙": ["拙口钝腮"],
        "钝": ["钝学累功"],
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
        "萃": ["萃英拔萃"],
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
    _IDIOM_SET = set()

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
        self.active_freeform_game = None
        self.freeform_game_state = None
        self._guess_my_word_game = GuessMyWordGame(self)
        self._user_describes_game = UserDescribesGame(self)
        self._idiom_chain_game = IdiomChainGame(self)
        self._freeform_game_manager = FreeformGameManager(self)

    # ------------------------------------------------------------------ #
    #  Tool classification helpers
    # ------------------------------------------------------------------ #

    def is_guess_my_word_tool(self, tool_name):
        return tool_name in self.GUESS_MY_WORD_TOOLS

    def is_user_describes_tool(self, tool_name):
        return tool_name in self.USER_DESCRIBES_TOOLS

    def is_idiom_chain_tool(self, tool_name):
        return tool_name in self.IDIOM_CHAIN_TOOLS

    # ------------------------------------------------------------------ #
    #  Session lifecycle
    # ------------------------------------------------------------------ #

    def get_active_tool(self):
        for tool_name, state in self.sessions.items():
            if state.get("active"):
                return tool_name
        return None

    def has_active_game(self):
        return self.get_active_tool() is not None

    def clear_active_games(self):
        self.sessions.clear()
        self.active_freeform_game = None
        self.freeform_game_state = None

    def get_requested_game_tool(self, user_message):
        normalized = self._normalize(user_message)
        for tool_name, aliases in self.FREEFORM_GAME_ALIASES.items():
            if self._contains_any(normalized, aliases):
                return tool_name
        return None

    # ------------------------------------------------------------------ #
    #  Delegation to specialised game modules
    # ------------------------------------------------------------------ #

    def handle_guess_my_word(self, tool_name, user_message):
        return self._guess_my_word_game.handle(tool_name, user_message)

    def handle_idiom_chain(self, tool_name, user_message):
        return self._idiom_chain_game.handle(tool_name, user_message)

    def handle_user_describes_guess(self, tool_name, user_message):
        return self._user_describes_game.handle(tool_name, user_message)

    def build_freeform_game_prompt(self, user_message, enabled_plugins):
        return self._freeform_game_manager.build_freeform_game_prompt(
            user_message, enabled_plugins
        )

    # ------------------------------------------------------------------ #
    #  Shared helper methods (consumed by game modules via self.manager)
    # ------------------------------------------------------------------ #

    @staticmethod
    def _normalize(text):
        return re.sub(r"[\s，。！？、,.!?;；:：~～\"'“”‘’（）()]+", "", text or "")

    @staticmethod
    def _contains_any(text, phrases):
        return any(phrase in text for phrase in phrases)

    @staticmethod
    def _is_end_message(normalized):
        return any(
            phrase in normalized
            for phrase in ("不玩了", "结束", "退出", "不猜了", "换游戏", "换个游戏", "游戏结束", "退出游戏")
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


# Build _IDIOM_SET from _IDIOM_CHAIN_STARTS and all _IDIOM_NEXT values.
# This cannot be done inline during class body because _IDIOM_NEXT values
# reference strings that must be fully defined.
GameSessionManager._IDIOM_SET = set(GameSessionManager._IDIOM_CHAIN_STARTS)
for _items in GameSessionManager._IDIOM_NEXT.values():
    GameSessionManager._IDIOM_SET.update(_items)
