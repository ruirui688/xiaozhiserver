"""Offline contract tests; no external model calls or gameplay state machine."""
import pytest

from core.game import GameSessionManager
from core.game.prompt_profiles import PROFILES, default_configs


@pytest.mark.parametrize("name", PROFILES)
def test_profile_is_injected_on_followup_without_game_state(name):
    manager = GameSessionManager()
    enabled = {name: {"custom_plugin": True}}
    first = manager.build_freeform_game_prompt(PROFILES[name]["aliases"][0], enabled)
    assert PROFILES[name]["instructions"] in first
    assert manager.freeform_game_state is None
    assert manager.sessions == {}
    assert manager.get_requested_game_tool(PROFILES[name]["aliases"][0]) is None
    followup = manager.build_freeform_game_prompt("继续", enabled)
    assert PROFILES[name]["instructions"] in followup
    assert "<game_state>" not in followup
    assert manager.freeform_game_state is None


def test_idiom_prompt_does_not_inject_guessing_role():
    prompt = GameSessionManager().build_freeform_game_prompt("成语接龙", default_configs())
    assert "用户心里有固定答案" not in prompt
    assert "每轮只问一个是/否" not in prompt
    assert "亦步亦趋" in prompt


def test_switch_exit_and_revocation():
    manager = GameSessionManager()
    enabled = default_configs()
    manager.build_freeform_game_prompt("猜谜语", enabled)
    prompt = manager.build_freeform_game_prompt("现在写相声", enabled)
    assert 'name="crosstalk"' in prompt
    assert PROFILES["riddle"]["instructions"] not in prompt
    assert manager.build_freeform_game_prompt("退出", enabled) is None
    assert manager.build_freeform_game_prompt("继续", enabled) is None
    manager.build_freeform_game_prompt("成语接龙", enabled)
    assert manager.build_freeform_game_prompt("继续", {"riddle": {"custom_plugin": True}}) is None
    assert manager.active_freeform_game is None


def test_admin_rules_are_not_silently_overwritten():
    manager = GameSessionManager()
    enabled = {"idiom_chain": {"custom_plugin": True, "instructions": "管理员定义的同音快玩规则"}}
    prompt = manager.build_freeform_game_prompt("成语接龙", enabled)
    assert "管理员定义的同音快玩规则" in prompt
    assert PROFILES["idiom_chain"]["instructions"] not in prompt


@pytest.mark.parametrize("flag", [False, "false"])
def test_disabled_profile_has_no_prompt(flag):
    manager = GameSessionManager()
    assert manager.build_freeform_game_prompt("猜谜语", {"riddle": {"custom_plugin": flag}}) is None


def test_connections_do_not_share_active_profile():
    a, b = GameSessionManager(), GameSessionManager()
    a.build_freeform_game_prompt("猜谜语", default_configs())
    assert b.build_freeform_game_prompt("继续", default_configs()) is None
    assert a.active_freeform_game == "riddle"


def test_switch_from_legacy_guessing_clears_hidden_answer():
    manager = GameSessionManager()
    enabled = {**default_configs(), "xiaozhi_thinks_you_guess": {"custom_plugin": True}}
    manager.build_freeform_game_prompt("你想一个词我来猜", enabled)
    assert manager.freeform_game_state["target_word"]
    prompt = manager.build_freeform_game_prompt("成语接龙", enabled)
    assert manager.freeform_game_state is None
    assert "固定答案：" not in prompt


def test_unrelated_question_has_explicit_release_instruction():
    manager = GameSessionManager()
    manager.build_freeform_game_prompt("猜谜语", default_configs())
    prompt = manager.build_freeform_game_prompt("北京天气怎么样", default_configs())
    assert "无关问题正常回答" in prompt
    # Topic handling remains the model's job, not a keyword/stateful router.
    assert manager.sessions == {}


def test_ab_cases_differ_only_by_extra_system_prompt():
    from tools.evaluate_activity_prompts import CASES, messages_for
    for _, profile, history, _ in CASES:
        baseline = messages_for(profile, history, False)
        enhanced = messages_for(profile, history, True)
        extra = enhanced[-2]
        assert extra["role"] == "system"
        assert PROFILES[profile]["instructions"] in extra["content"]
        assert enhanced[:-2] + enhanced[-1:] == baseline


def test_actual_chat_request_receives_temporary_profile_each_turn():
    from test_function_call_runtime_mode import CapturingNoToolLLM, CapturingToolHandler, _chat_conn
    llm = CapturingNoToolLLM(["测试回复"])
    conn = _chat_conn(llm, CapturingToolHandler([]))
    conn._get_enabled_plugin_configs = default_configs
    try:
        for text in ("成语接龙", "继续", "退出"):
            conn.chat(text)
        for call in llm.calls[:2]:
            prompts = [m["content"] for m in call["dialogue"] if m["role"] == "system"]
            assert sum(PROFILES["idiom_chain"]["instructions"] in p for p in prompts) == 1
        assert all("<freeform_game" not in str(m.get("content")) for m in llm.calls[2]["dialogue"])
        assert all("<freeform_game" not in str(m.content) for m in conn.dialogue.dialogue)
    finally:
        conn.executor.shutdown(wait=False)
