"""Profiles are a legacy fallback; only explicit activity selection exposes rules."""
import pytest

from core.game import GameSessionManager
from core.game.prompt_profiles import PROFILES, default_configs


@pytest.mark.parametrize("name", PROFILES)
def test_profile_is_loaded_only_on_explicit_selection_without_hidden_answer(name):
    manager = GameSessionManager()
    enabled = {name: {"custom_plugin": True}}
    assert manager.build_freeform_game_prompt(PROFILES[name]["aliases"][0], enabled) is None
    first = manager.build_freeform_game_prompt("开始", enabled, active_name=name)
    assert PROFILES[name]["instructions"] in first
    followup = manager.build_freeform_game_prompt("继续", enabled, active_name=name)
    assert first == followup
    assert manager.freeform_game_state is None
    assert manager.sessions == {}
    assert manager.get_requested_game_tool(PROFILES[name]["aliases"][0]) is None


def test_idiom_prompt_does_not_load_other_enabled_activity_rules():
    prompt = GameSessionManager().build_freeform_game_prompt("成语接龙", default_configs(), active_name="idiom_chain")
    assert "亦步亦趋" in prompt
    assert "每轮只问一个是/否" not in prompt
    for name, profile in PROFILES.items():
        if name != "idiom_chain":
            assert profile["instructions"] not in prompt


def test_explicit_switch_and_revocation_leave_no_old_rules():
    manager = GameSessionManager()
    enabled = default_configs()
    first = manager.build_freeform_game_prompt("开始", enabled, active_name="riddle")
    second = manager.build_freeform_game_prompt("换一个", enabled, active_name="crosstalk")
    assert PROFILES["riddle"]["instructions"] in first
    assert PROFILES["riddle"]["instructions"] not in second
    assert PROFILES["crosstalk"]["instructions"] in second
    assert manager.build_freeform_game_prompt("继续", {"riddle": {}}, active_name="crosstalk") is None


def test_admin_rules_are_not_silently_overwritten():
    enabled = {"idiom_chain": {"custom_plugin": True, "instructions": "管理员定义的同音快玩规则"}}
    prompt = GameSessionManager().build_freeform_game_prompt("开始", enabled, active_name="idiom_chain")
    assert "管理员定义的同音快玩规则" in prompt
    assert PROFILES["idiom_chain"]["instructions"] not in prompt


def test_connections_do_not_share_selection_or_hidden_answers():
    a, b = GameSessionManager(), GameSessionManager()
    assert a.build_freeform_game_prompt("开始", default_configs(), active_name="riddle")
    assert b.build_freeform_game_prompt("继续", default_configs()) is None
    assert a.active_freeform_game is None and b.active_freeform_game is None


def test_ab_cases_explicitly_select_one_profile_without_changing_history():
    from tools.evaluate_activity_prompts import CASES, messages_for
    for _, profile, history, _ in CASES:
        baseline = messages_for(profile, history, False)
        enhanced = messages_for(profile, history, True)
        extra = enhanced[-2]
        assert extra["role"] == "system"
        assert PROFILES[profile]["instructions"] in extra["content"]
        assert enhanced[:-2] + enhanced[-1:] == baseline
