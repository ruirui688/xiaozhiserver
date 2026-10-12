"""Legacy game entry points cannot select activities from utterance keywords."""

import pytest

from core.game import GameSessionManager


def test_freeform_games_do_not_force_stateful_routing():
    manager = GameSessionManager()
    assert GameSessionManager.GUESS_MY_WORD_TOOLS == set()
    assert GameSessionManager.USER_DESCRIBES_TOOLS == set()
    assert GameSessionManager.IDIOM_CHAIN_TOOLS == set()
    enabled = {"guess_what_i_say": {"custom_plugin": True}}
    for text in ("玩个游戏", "成语接龙", "你想一个词我来猜", "我已经想好了，请你猜", "你说我猜"):
        assert manager.get_requested_game_tool(text) is None
        assert manager.build_freeform_game_prompt(text, enabled) is None
    assert manager.sessions == {}
    assert manager.freeform_game_state is None


def test_explicit_selection_renders_only_selected_editable_rules():
    manager = GameSessionManager()
    enabled = {
        "guess_what_i_say": {"custom_plugin": True,
            "instructions": "首轮请用户描述线索，之后每次猜一个词。", "opening_message": "你描述，我来猜。"},
        "idiom_chain": {"custom_plugin": True, "instructions": "只用标准四字成语。"},
    }
    prompt = manager.build_freeform_game_prompt("任意用户原文", enabled, active_name="guess_what_i_say")
    assert "首轮请用户描述线索，之后每次猜一个词。" in prompt
    assert "你描述，我来猜。" in prompt
    assert "只用标准四字成语" not in prompt
    assert "下一句必须直接提问" not in prompt
    assert manager.freeform_game_state is None


@pytest.mark.parametrize("name", ["guess_what_i_say", "xiaozhi_thinks_you_guess", "guess_xiaozhi_word", "i_think_you_guess"])
def test_legacy_defaults_remain_available_only_for_explicit_selection(name):
    manager = GameSessionManager()
    enabled = {name: {"custom_plugin": True}}
    assert manager.build_freeform_game_prompt("开始", enabled) is None
    prompt = manager.build_freeform_game_prompt("开始", enabled, active_name=name)
    assert f'name="{name}"' in prompt
    assert "当前玩法规则" in prompt
    assert "固定答案：" not in prompt
    assert manager.freeform_game_state is None
    assert manager.sessions == {}


def test_words_cannot_start_stop_or_switch_a_selection():
    manager = GameSessionManager()
    enabled = {"idiom_chain": {"custom_plugin": True}}
    prompts = [manager.build_freeform_game_prompt(text, enabled, active_name="idiom_chain")
               for text in ("成语接龙是什么", "不要结束", "不玩了", "换一个游戏", "北京天气")]
    assert all(prompt == prompts[0] for prompt in prompts)
    assert "end_interaction" in prompts[0]
    assert "无关问题正常回答" in prompts[0]
    assert manager.active_freeform_game is None


@pytest.mark.parametrize("config", [{}, {"idiom_chain": {"custom_plugin": False}},
    {"idiom_chain": {"custom_plugin": "false"}},
    {"idiom_chain": {"custom_plugin": True, "instructions": "", "opening_message": ""}}])
def test_disabled_or_missing_rules_are_not_restored_by_compatibility_renderer(config):
    assert GameSessionManager().build_freeform_game_prompt("继续", config, active_name="idiom_chain") is None
