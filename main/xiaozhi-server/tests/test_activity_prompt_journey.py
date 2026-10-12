"""Real chat/SDK requests with scripted model choices and no external I/O."""

from types import SimpleNamespace

import pytest

from test_prompt_request_journey import wire_connection, _systems, _tool_reply
from core.game.interactions import build_active_interaction_prompt
from core.meeting.config import build_meeting_runtime_context
from core.providers.tools.unified_tool_handler import UnifiedToolHandler


def _configure(conn):
    conn.config["plugins"] = {
        "idiom_chain": {"custom_plugin": True, "instructions": "管理员规则：同字接龙，轮流作答。"},
        "guided_story": {"custom_plugin": True, "instructions": "管理员规则：每轮由用户选择故事人物。"},
    }
    conn.config["Intent"]["function_call"]["functions"] = ["idiom_chain", "guided_story"]
    conn.func_handler = UnifiedToolHandler(conn)


def test_real_chat_loads_only_selected_rules_and_continuation_needs_one_request(wire_connection):
    script = ["这是一个接成语的活动。", "好，我们先不玩。",
              _tool_reply("idiom_chain"), "一心一意，你接意字。",
              "这个字可以这样接。", "好的，我们继续。", "可以先放松呼吸。",
              _tool_reply("guided_story"), "你想让哪位人物先登场？",
              _tool_reply("end_interaction"), "月亮是地球的天然卫星。", "我们继续聊月亮。"]
    conn, requests = wire_connection(script)
    _configure(conn)
    turns = [
        ("成语接龙是什么？", 1, None),
        ("今天不玩成语接龙。", 1, None),
        ("现在开始成语接龙，你先。", 2, "idiom_chain"),
        ("为什么这个字不能接？", 1, "idiom_chain"),
        ("这局不要结束，继续。", 1, "idiom_chain"),
        ("先回答怎么放松呼吸，然后继续游戏。", 1, "idiom_chain"),
        ("改成一起编故事吧。", 2, "guided_story"),
        ("不玩了，换个话题，用一句话介绍月亮。", 2, None),
        ("继续刚才的话题。", 1, None),
    ]
    for user, count, selected in turns:
        before = len(requests)
        assert conn.chat(user) is True
        assert len(requests) - before == count
        system = _systems(requests[-1])
        assert ("管理员规则：同字接龙" in system) is (selected == "idiom_chain")
        assert ("管理员规则：每轮由用户选择故事人物" in system) is (selected == "guided_story")
        for request in requests[before:]:
            assert all("管理员规则" not in str(tool) for tool in request.get("tools", []))
        if selected:
            assert "无关问题正常回答" in system
            assert "不要每轮重复调用启动工具" in system
        else:
            assert "<active_interaction" not in system
    assert build_active_interaction_prompt(conn) is None
    assert conn.game_session_manager.sessions == {}
    assert conn.game_session_manager.freeform_game_state is None
    assert all("<active_interaction" not in str(m.content) for m in conn.dialogue.dialogue)


def test_real_chat_revocation_drops_rules_on_the_next_request(wire_connection):
    conn, requests = wire_connection([_tool_reply("idiom_chain"), "我们开始。", "该活动目前不可用。"])
    _configure(conn)
    assert conn.chat("开始成语接龙。") is True
    assert "管理员规则：同字接龙" in _systems(requests[-1])
    conn.config["plugins"].pop("idiom_chain")
    conn.config["Intent"]["function_call"]["functions"].remove("idiom_chain")
    conn.func_handler.tool_manager.refresh_tools()
    assert conn.chat("继续。") is True
    assert "管理员规则：同字接龙" not in _systems(requests[-1])
    assert "idiom_chain" not in {t["function"]["name"] for t in requests[-1].get("tools", [])}


@pytest.mark.parametrize("active", [False, True])
def test_meeting_policy_uses_this_requests_actual_tools(active):
    conn = SimpleNamespace(
        config={"plugins": {"meeting_minutes": {}}},
        read_config_from_api=True,
        llm=SimpleNamespace(supports_native_function_call=True),
        intent_type="function_call",
        meeting_minutes=SimpleNamespace(is_active=lambda: active),
    )
    disabled = build_meeting_runtime_context(conn, available_tools=[])
    assert "main_llm_tool_available=false" in disabled
    assert "不得发起会议工具调用" in disabled
    assert "action=record" not in disabled
    enabled = build_meeting_runtime_context(conn, available_tools=[
        {"function": {"name": "meeting_minutes"}}
    ])
    assert "main_llm_tool_available=true" in enabled
    assert ("action=record" if active else "action=start") in enabled
