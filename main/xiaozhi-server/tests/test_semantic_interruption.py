import asyncio
import json
import sys
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.semantic.frames import RobotContext
from core.semantic.interruption import SemanticInterruptionClassifier
from core.utils.dialogue import Dialogue, Message


class _Logger:
    def bind(self, **kwargs):
        return self

    def warning(self, message):
        pass


class _LLM:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def response_no_stream(self, **kwargs):
        self.calls.append(kwargs)
        return self.response


class _Conn:
    def __init__(self, llm_response):
        self.config = {"semantic_interruption": {"timeout_seconds": 1.0}}
        self.llm = _LLM(llm_response)
        self.logger = _Logger()
        self.dialogue = Dialogue()
        self.dialogue.put(Message(role="user", content="介绍一下这个展品"))
        self.dialogue.put(Message(role="assistant", content="这个展品来自明代，主要看它的纹饰。"))


def _context():
    return RobotContext(
        device_id="d1",
        session_id="s1",
        state="speaking",
        current_skill=None,
        tts_playing=True,
        active_game_tool=None,
        last_intent=None,
    )


def _classify(llm_response, text):
    conn = _Conn(llm_response)
    result = asyncio.run(
        SemanticInterruptionClassifier().classify(conn, text, _context())
    )
    return result, conn


def test_llm_continue_decision_does_not_abort_even_for_stop_like_text():
    result, conn = _classify(
        '{"decision":"continue","reason":"只是反馈"}',
        "别说了",
    )

    assert result.category == "continue"
    assert not result.should_abort
    assert "别说了" in conn.llm.calls[0]["user_prompt"]


def test_llm_new_turn_decision_aborts_even_for_short_ack_like_text():
    result, conn = _classify(
        '{"decision":"new_turn","reason":"有新意图"}',
        "嗯",
    )

    assert result.category == "new_turn"
    assert result.should_abort
    payload = json.loads(conn.llm.calls[0]["user_prompt"])
    assert payload["overlap_user_text"] == "嗯"
    assert len(payload["recent_dialogue"]) == 2


def test_llm_interrupt_decision_aborts():
    result, _ = _classify(
        '{"decision":"interrupt","reason":"要求停止"}',
        "先停一下",
    )

    assert result.category == "interrupt"
    assert result.should_abort


def test_invalid_llm_decision_falls_back_to_new_turn():
    result, _ = _classify("not json", "我想问个问题")

    assert result.category == "new_turn"
    assert result.should_abort
    assert result.reason == "invalid_llm_decision"


def test_acknowledgement_examples_follow_llm_continue_decision():
    for text in ["嗯", "好", "对", "明白了", "继续听着呢"]:
        result, conn = _classify(
            '{"decision":"continue","reason":"短反馈"}',
            text,
        )

        assert result.category == "continue"
        assert not result.should_abort
        payload = json.loads(conn.llm.calls[0]["user_prompt"])
        assert payload["overlap_user_text"] == text


def test_new_turn_examples_follow_llm_abort_decision():
    for text in ["等等我问另一个问题", "不是这个", "你说错了", "换个话题"]:
        result, conn = _classify(
            '{"decision":"new_turn","reason":"新问题"}',
            text,
        )

        assert result.category == "new_turn"
        assert result.should_abort
        payload = json.loads(conn.llm.calls[0]["user_prompt"])
        assert payload["overlap_user_text"] == text


def test_explicit_stop_example_follows_llm_interrupt_decision():
    result, _ = _classify(
        '{"decision":"interrupt","reason":"停止播报"}',
        "别说了",
    )

    assert result.category == "interrupt"
    assert result.should_abort
