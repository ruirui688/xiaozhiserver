"""Character-budget journeys preserve complete tool exchanges and required input."""

import copy
from collections import Counter

import pytest

from core.utils.dialogue import Dialogue, Message
from core.utils.prompt_request_budget import (
    PromptBudgetExceeded,
    bound_prompt_request,
    request_chars,
)


LIMIT = {"prompt_budget": {"max_input_chars": 1024}}
TOOLS = [{"type": "function", "function": {
    "name": "lookup", "description": "查询当前资料", "parameters": {
        "type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]
    }
}}]


def _call(call_id):
    return {"id": call_id, "type": "function", "function": {"name": "lookup", "arguments": '{"query":"资料"}'}}


def _assistant(*call_ids):
    return {"role": "assistant", "tool_calls": [_call(call_id) for call_id in call_ids]}


def _tool(call_id, content="查询结果"):
    return {"role": "tool", "tool_call_id": call_id, "content": content}


def _assert_tool_pairs(messages):
    calls = Counter(call["id"] for message in messages if message["role"] == "assistant"
                    for call in message.get("tool_calls", []))
    results = Counter(message["tool_call_id"] for message in messages if message["role"] == "tool")
    assert calls == results


def test_complete_old_turns_are_removed_without_mutating_rules_question_or_schema():
    messages = [
        {"role": "system", "content": "核心规则"},
        {"role": "user", "content": "旧问题" + "旧" * 1500},
        {"role": "system", "content": "仍必须保留的上下文规则"},
        {"role": "assistant", "content": "旧回答"},
        {"role": "user", "content": "请查现在的资料"},
        _assistant("current"), _tool("current"),
    ]
    original, original_tools = copy.deepcopy(messages), copy.deepcopy(TOOLS)
    result, removed = bound_prompt_request(messages, TOOLS, LIMIT)
    assert result == [messages[0], messages[2], *messages[4:]]
    assert removed == 2 and request_chars(result, TOOLS) <= 1024
    _assert_tool_pairs(result)
    assert messages == original and TOOLS == original_tools


def test_dialogue_interruption_dummy_is_removed_with_old_call():
    dialogue = Dialogue()
    dialogue.update_system_message("保留规则")
    dialogue.put(Message("user", "旧问题" + "旧" * 1500))
    dialogue.put(Message("assistant", tool_calls=[_call("interrupted")]))
    dialogue.put(Message("user", "停止旧请求，请查新的资料"))
    dialogue.put(Message("assistant", tool_calls=[_call("current")]))
    dialogue.put(Message("tool", "新结果", tool_call_id="current"))
    original_history = [(item.uniq_id, item.role, item.content, copy.deepcopy(item.tool_calls))
                        for item in dialogue.dialogue]
    outgoing = dialogue.get_llm_dialogue()
    old_call = next(index for index, item in enumerate(outgoing)
                    if item.get("tool_calls") and item["tool_calls"][0]["id"] == "interrupted")
    new_user = next(index for index, item in enumerate(outgoing)
                    if item.get("content") == "停止旧请求，请查新的资料")
    assert outgoing[old_call + 1].get("tool_call_id") == "interrupted"
    assert old_call + 1 < new_user
    original_outgoing = copy.deepcopy(outgoing)
    result, removed = bound_prompt_request(outgoing, TOOLS, LIMIT)
    _assert_tool_pairs(result)
    assert removed == 3
    assert not any(item.get("tool_call_id") == "interrupted" for item in result)
    assert any(item.get("tool_call_id") == "current" for item in result)
    assert any(item.get("content") == "停止旧请求，请查新的资料" for item in result)
    assert outgoing == original_outgoing
    assert [(item.uniq_id, item.role, item.content, item.tool_calls) for item in dialogue.dialogue] == original_history


def test_multi_call_dependencies_are_removed_across_multiple_user_boundaries():
    messages = [{"role": "system", "content": "规则"},
                {"role": "user", "content": "很旧" * 1000}, _assistant("old-a", "old-b"),
                _tool("old-a"), {"role": "user", "content": "上一轮"},
                {"role": "assistant", "content": "上一轮回答"},
                {"role": "user", "content": "当前问题"}, _assistant("new-a", "new-b"),
                _tool("new-a"), _tool("new-b"), _tool("old-b", "已被打断")]
    result, removed = bound_prompt_request(messages, TOOLS, LIMIT)
    assert removed == 4
    assert result == [messages[0], *messages[4:10]]
    _assert_tool_pairs(result)


def test_removed_result_also_removes_its_assistant_and_sibling_result():
    messages = [{"role": "system", "content": "规则"}, _assistant("a", "b"),
                {"role": "user", "content": "旧内容" * 1000}, _tool("a"),
                {"role": "user", "content": "当前问题"}, _tool("b")]
    result, removed = bound_prompt_request(messages, config=LIMIT)
    assert result == [messages[0], messages[4]] and removed == 4
    _assert_tool_pairs(result)


def test_dependency_on_current_assistant_is_rejected_without_erasing_current_chain():
    messages = [{"role": "user", "content": "旧" * 1500}, _assistant("reused"),
                {"role": "user", "content": "当前问题"}, _assistant("reused"), _tool("reused")]
    before = copy.deepcopy(messages)
    with pytest.raises(PromptBudgetExceeded, match="overlaps"):
        bound_prompt_request(messages, config=LIMIT)
    assert messages == before


@pytest.mark.parametrize("oversized", ["rules", "question", "tools", "current_tool_chain"])
def test_oversized_required_input_is_rejected_instead_of_truncated(oversized):
    messages = [{"role": "system", "content": "规则"},
                {"role": "user", "content": "可删除历史"}, {"role": "assistant", "content": "旧回答"},
                {"role": "user", "content": "当前问题"}]
    tools = copy.deepcopy(TOOLS)
    if oversized == "rules":
        messages[0]["content"] = "规则" * 1000
    elif oversized == "question":
        messages[-1]["content"] = "问题" * 1000
    elif oversized == "tools":
        tools[0]["function"]["description"] = "说明" * 1000
    else:
        messages.extend([_assistant("current"), _tool("current", "重要当前证据" * 1000)])
    before, before_tools = copy.deepcopy(messages), copy.deepcopy(tools)
    with pytest.raises(PromptBudgetExceeded):
        bound_prompt_request(messages, tools, LIMIT)
    assert messages == before and tools == before_tools


def test_character_boundary_counts_serialized_envelope_and_unicode_not_tokens():
    messages = [{"role": "system", "content": "规则"}, {"role": "user", "content": ""}]
    remaining = 1024 - request_chars(messages, TOOLS)
    messages[-1]["content"] = "中" * remaining
    assert request_chars(messages, TOOLS) == 1024
    result, removed = bound_prompt_request(messages, TOOLS, LIMIT)
    assert result == messages and removed == 0
    messages[-1]["content"] += "文"
    with pytest.raises(PromptBudgetExceeded):
        bound_prompt_request(messages, TOOLS, LIMIT)


@pytest.mark.parametrize("setting", [None, [], {"max_input_chars": True}, {"max_input_chars": 10}])
def test_invalid_configuration_uses_safe_default(setting):
    messages = [{"role": "user", "content": "大于一千字符" * 500}]
    result, removed = bound_prompt_request(messages, config={"prompt_budget": setting})
    assert result == messages and removed == 0
