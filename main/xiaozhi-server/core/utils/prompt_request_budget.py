"""Whole-request character guard; never represents a model token count."""

import json


DEFAULT_MAX_INPUT_CHARS = 64_000
HARD_MAX_INPUT_CHARS = 512_000


class PromptBudgetExceeded(ValueError):
    """Required rules, tools and the current user turn cannot fit safely."""


def request_chars(messages, functions):
    return len(json.dumps({"messages": messages, "tools": functions or []},
                          ensure_ascii=False, separators=(",", ":")))


def _tool_ids(message):
    if message.get("role") == "assistant":
        calls = message.get("tool_calls") or []
        return {call["id"] for call in calls
                if isinstance(call, dict) and isinstance(call.get("id"), str) and call["id"]}
    if message.get("role") == "tool":
        call_id = message.get("tool_call_id")
        return {call_id} if isinstance(call_id, str) and call_id else set()
    return set()


def _drop_exchange(messages, begin, end, current_user):
    """Remove an exchange and its tool dependencies, wherever results occur.

    Dialogue can append interrupted tool results after a later user message.
    A positional slice alone would leave those results orphaned. An assistant
    with multiple calls also ties all its result messages into one group.
    """
    message_ids = [_tool_ids(message) for message in messages]
    members = {}
    for index, call_ids in enumerate(message_ids):
        for call_id in call_ids:
            members.setdefault(call_id, set()).add(index)

    removing = {
        index for index in range(begin, end)
        if messages[index].get("role") != "system"
    }
    pending = list(removing)
    while pending:
        index = pending.pop()
        for call_id in message_ids[index]:
            linked = members[call_id] - removing
            removing.update(linked)
            pending.extend(linked)

    # Old trailing tool results may be removed, but a dependency shared with a
    # current-turn assistant must never silently erase the current tool chain.
    if any(messages[index].get("role") == "system"
           or (index >= current_user and messages[index].get("role") != "tool")
           for index in removing):
        raise PromptBudgetExceeded("history tool dependency overlaps required prompt")
    kept = [message for index, message in enumerate(messages) if index not in removing]
    return kept, len(removing)


def bound_prompt_request(messages, functions=None, config=None):
    """Drop oldest complete user exchanges, preserving every system message.

    The current user turn and its tool chain, tool schemas and trusted rules are
    never silently truncated. An oversized required input is rejected before I/O.
    The original Dialogue/evidence objects are not mutated.
    """
    settings = (config or {}).get("prompt_budget") or {}
    if not isinstance(settings, dict):
        settings = {}
    limit = settings.get("max_input_chars", DEFAULT_MAX_INPUT_CHARS)
    if type(limit) is not int or not 1_024 <= limit <= HARD_MAX_INPUT_CHARS:
        limit = DEFAULT_MAX_INPUT_CHARS
    result = list(messages)
    removed = 0
    while request_chars(result, functions) > limit:
        users = [i for i, m in enumerate(result) if m.get("role") == "user"]
        if len(users) < 2:
            raise PromptBudgetExceeded("required prompt exceeds character budget")
        begin, end = users[0], users[1]
        result, dropped = _drop_exchange(result, begin, end, users[-1])
        removed += dropped
    return result, removed
