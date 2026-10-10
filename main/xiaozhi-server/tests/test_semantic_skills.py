import asyncio
import json
import queue
import threading
from types import SimpleNamespace

from config import settings

settings.config_file_valid = True

from core.handle.receiveAudioHandle import startToChat
from core.providers.tts.dto.dto import ContentType, TTSMessageDTO, SentenceType
from core.semantic.semantic_center import SemanticCenter
from core.semantic.skill_registry import SkillRegistry
from core.session import ReplyLifecycle
from core.utils.dialogue import Dialogue


class _Logger:
    def bind(self, **_kwargs):
        return self

    def debug(self, *_args, **_kwargs):
        pass

    def info(self, *_args, **_kwargs):
        pass

    def warning(self, *_args, **_kwargs):
        pass

    def error(self, *_args, **_kwargs):
        pass


class _WebSocket:
    def __init__(self):
        self.messages = []

    async def send(self, payload):
        self.messages.append(json.loads(payload))


class _TTS:
    def __init__(self):
        self.tts_text_queue = queue.Queue()
        self.stored = {}
        self.skip_memory_sentence_ids = []

    def store_tts_text(self, sentence_id, text):
        self.stored[sentence_id] = text

    def mark_skip_memory_event(self, sentence_id):
        self.skip_memory_sentence_ids.append(sentence_id)

    def tts_one_sentence(
        self, _conn, content_type, content_detail=None, sentence_id=None
    ):
        self.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id=sentence_id,
                sentence_type=SentenceType.MIDDLE,
                content_type=content_type,
                content_detail=content_detail,
            )
        )


class _FailingTTS(_TTS):
    def tts_one_sentence(self, *_args, **_kwargs):
        raise RuntimeError("synthetic TTS failure")


class _GameSessionManager:
    def __init__(self, active_tool=None):
        self.active_tool = active_tool

    def get_active_tool(self):
        return self.active_tool


SKILLS = [
    {
        "id": 10,
        "name": "惠易公司介绍",
        "trigger_rules": [
            {"type": "all_keywords", "terms": ["惠易公司", "介绍"]},
            {"type": "phrase", "terms": ["惠易公司是做什么的"]},
        ],
        "response_text": "惠易公司固定介绍。",
        "priority": 100,
        "sort": 0,
    }
]


def _conn(skills=SKILLS, *, tts=True):
    provider = _TTS() if tts else None
    tts_ready = threading.Event()
    if tts:
        tts_ready.set()
    conn = SimpleNamespace(
        session_id="session-1",
        device_id="device-1",
        logger=_Logger(),
        config={"end_prompt": {}, "semantic_skills": skills},
        websocket=_WebSocket(),
        tts=provider,
        tts_ready=tts_ready,
        reply_lifecycle=ReplyLifecycle(),
        _tts_protocol_sentence_id=None,
        _tts_protocol_start_sent=False,
        _tts_protocol_stop_sent=False,
        sentence_id=None,
        client_abort=False,
        abort_generation=0,
        _abort_lock=threading.Lock(),
        client_is_speaking=False,
        client_listen_mode="auto",
        calling=False,
        game_session_manager=_GameSessionManager(),
        meeting_minutes=None,
        voiceprint_enrollment=None,
        dialogue=Dialogue(),
        turn_metrics=None,
        loop=None,
        llm=None,
    )
    conn.semantic_center = SemanticCenter(
        skill_registry=SkillRegistry(skills)
    )
    return conn


def test_keyword_group_requires_all_terms_and_ignores_order():
    registry = SkillRegistry(SKILLS)

    match = registry.match("小慧，请把惠易公司详细介绍一下")
    assert match is not None
    assert match.skill.skill_id == 10
    assert match.rule.rule_type == "all_keywords"

    assert registry.match("请介绍一号点位") is None
    assert registry.match("惠易公司天气怎么样") is None


def test_phrase_rule_uses_normalized_contains_matching():
    registry = SkillRegistry(SKILLS)

    match = registry.match("我想问：惠易公司，是做什么的？")

    assert match is not None
    assert match.rule.rule_type == "phrase"


def test_conflict_resolution_uses_priority_complexity_sort_and_id():
    skills = [
        {
            "id": 30,
            "name": "低优先级",
            "trigger_rules": [
                {"type": "all_keywords", "terms": ["惠易公司", "介绍"]}
            ],
            "response_text": "low",
            "priority": 1,
            "sort": 0,
        },
        {
            "id": 20,
            "name": "高复杂度",
            "trigger_rules": [
                {
                    "type": "all_keywords",
                    "terms": ["惠易公司", "一号点位", "介绍"],
                }
            ],
            "response_text": "specific",
            "priority": 2,
            "sort": 10,
        },
        {
            "id": 10,
            "name": "同分稳定ID",
            "trigger_rules": [
                {
                    "type": "all_keywords",
                    "terms": ["惠易公司", "一号点位", "介绍"],
                }
            ],
            "response_text": "stable",
            "priority": 2,
            "sort": 10,
        },
    ]

    match = SkillRegistry(skills).match("介绍惠易公司一号点位")

    assert match is not None
    assert match.skill.skill_id == 10


def test_registry_ignores_invalid_rules_and_disabled_skills():
    registry = SkillRegistry(
        [
            {
                "id": 1,
                "name": "错误关键词组",
                "trigger_rules": [
                    {"type": "all_keywords", "terms": ["介绍"]}
                ],
                "response_text": "bad",
            },
            {
                "id": 2,
                "name": "禁用技能",
                "enabled": False,
                "trigger_rules": [
                    {"type": "phrase", "terms": ["介绍惠易公司"]}
                ],
                "response_text": "disabled",
            },
        ]
    )

    assert len(registry) == 0
    assert registry.match("介绍惠易公司") is None


def test_registry_enforces_runtime_size_limits():
    oversized_response = "播" * 3001
    too_short_term = "惠"
    skills = [
        {
            "id": index,
            "name": f"技能{index}",
            "trigger_rules": [
                {"type": "phrase", "terms": [f"有效话术{index}"]}
            ],
            "response_text": "有效回复",
        }
        for index in range(51)
    ]
    skills[0]["response_text"] = oversized_response
    skills[1]["trigger_rules"] = [
        {"type": "all_keywords", "terms": [too_short_term, "介绍"]}
    ]
    skills[2]["trigger_rules"] = [
        {"type": "phrase", "terms": ["有效话术"]}
        for _ in range(20)
    ] + [{"type": "phrase", "terms": ["不应加载的话术"]}]
    skills[50]["trigger_rules"] = [
        {"type": "phrase", "terms": ["仅第五十一条配置命中"]}
    ]

    registry = SkillRegistry(skills)

    assert len(registry) == 48
    assert registry.match("不应加载的话术") is None
    assert registry.match("仅第五十一条配置命中") is None


def test_fixed_skill_uses_tracked_tts_lifecycle_and_skips_memory():
    conn = _conn()

    handled = asyncio.run(
        conn.semantic_center.handle_user_text(
            conn, "小慧，请介绍一下惠易公司", turn_id="turn-1"
        )
    )

    assert handled is True
    assert [item["type"] for item in conn.websocket.messages] == ["stt", "tts"]
    assert conn.websocket.messages[1]["state"] == "start"
    queued = [conn.tts.tts_text_queue.get_nowait() for _ in range(3)]
    assert [item.sentence_type for item in queued] == [
        SentenceType.FIRST,
        SentenceType.MIDDLE,
        SentenceType.LAST,
    ]
    assert queued[1].content_type == ContentType.TEXT
    assert queued[1].content_detail == "惠易公司固定介绍。"
    assert conn.reply_lifecycle.snapshot().sentence_id == conn.sentence_id
    assert conn.tts.skip_memory_sentence_ids == [conn.sentence_id]
    assert getattr(conn, "_skip_memory_events")[0]["chat_type"] == 1
    history = [
        (message.role, message.content) for message in conn.dialogue.dialogue
    ]
    assert history == [
        ("user", "小慧，请介绍一下惠易公司"),
        ("assistant", "惠易公司固定介绍。"),
    ]
    assert all(not message.memory_eligible for message in conn.dialogue.dialogue)


def test_start_to_chat_skill_never_calls_intent_or_llm(monkeypatch):
    import core.handle.receiveAudioHandle as receive_handle

    async def unexpected_intent(*_args, **_kwargs):
        raise AssertionError("intent must not run after a fixed skill match")

    class _UnexpectedExecutor:
        def submit(self, *_args, **_kwargs):
            raise AssertionError("LLM must not be submitted after a skill match")

    monkeypatch.setattr(receive_handle, "handle_user_intent", unexpected_intent)
    conn = _conn()
    conn.executor = _UnexpectedExecutor()
    conn.need_bind = False
    conn.max_output_size = 0
    conn.headers = {"device-id": "device-1"}
    conn.correct_word_replacements = {}

    accepted = asyncio.run(startToChat(conn, "请介绍惠易公司"))

    assert accepted is True
    assert conn.tts.tts_text_queue.qsize() == 3


def test_missing_tts_does_not_fall_back_to_llm():
    conn = _conn(tts=False)

    handled = asyncio.run(
        conn.semantic_center.handle_user_text(conn, "介绍惠易公司")
    )

    assert handled is True
    assert conn.websocket.messages == []
    assert conn.reply_lifecycle.is_active is False
    assert conn.dialogue.dialogue == []


def test_tts_enqueue_failure_terminates_without_llm_or_false_history():
    async def scenario():
        conn = _conn()
        conn.tts = _FailingTTS()
        conn.loop = asyncio.get_running_loop()
        conn.audio_rate_controller = None

        def clear_queues():
            while True:
                try:
                    conn.tts.tts_text_queue.get_nowait()
                except queue.Empty:
                    break

        conn.clear_queues = clear_queues
        handled = await conn.semantic_center.handle_user_text(
            conn, "介绍惠易公司"
        )
        await asyncio.sleep(0)
        return conn, handled

    conn, handled = asyncio.run(scenario())

    assert handled is True
    assert conn.dialogue.dialogue == []
    assert conn.reply_lifecycle.is_active is False
    assert [
        item.get("state")
        for item in conn.websocket.messages
        if item["type"] == "tts"
    ] == ["start", "stop"]


def test_skills_only_match_idle_state():
    conn = _conn()
    conn.calling = True
    context = conn.semantic_center.state_manager.get_context(conn)
    assert (
        conn.semantic_center._match_fixed_skill(
            conn, "介绍惠易公司", context
        )
        is None
    )

    conn.calling = False
    conn.game_session_manager.active_tool = "guessing_game"
    context = conn.semantic_center.state_manager.get_context(conn)
    assert context.state == "game"
    assert (
        conn.semantic_center._match_fixed_skill(
            conn, "介绍惠易公司", context
        )
        is None
    )


def test_no_skill_match_preserves_fallback_chain():
    conn = _conn()

    handled = asyncio.run(
        conn.semantic_center.handle_user_text(conn, "今天天气怎么样")
    )

    assert handled is False
    assert conn.websocket.messages == []
    assert conn.dialogue.dialogue == []
