"""Deterministic budgets for untrusted, dynamic LLM context."""

import copy
import json
import re
from typing import Any, Dict, Iterable, List


MEMORY_CONTEXT_MAX_CHARS = 4_000
MEMORY_CONTEXT_MAX_ITEMS = 30
DYNAMIC_CONTEXT_MAX_CHARS = 2_000
DYNAMIC_CONTEXT_MAX_ITEMS = 30
SPEAKER_DESCRIPTION_MAX_CHARS = 500
KNOWLEDGE_CONTEXT_MAX_CHARS = 4_000
# 只注入前 5 条：正确答案必须由检索排名保证进前 5，
# 不允许靠放大窗口把排在后面的切片兜进来。
KNOWLEDGE_CONTEXT_MAX_ITEMS = 5
KNOWLEDGE_ITEM_MAX_CHARS = 1_600
TOOL_HISTORY_MAX_CHARS = 8_000
TOOL_RESULT_MAX_CHARS = 4_000
TOOL_DESCRIPTION_MAX_CHARS = 1_000
PARAMETER_DESCRIPTION_MAX_CHARS = 300
TOOL_DESCRIPTION_TOTAL_MAX_CHARS = 1_000
TOOL_DESCRIPTIONS_AGGREGATE_MAX_CHARS = 6_000

TRUNCATION_MARKER = "\n[上下文已按预算截断]"
REPEATED_TOOL_RESULT_MARKER = "[重复工具结果已省略]"


def _normalize_text(value: Any) -> str:
    text = str(value or "").replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[ \t]+\n", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def truncate_context_text(value: Any, max_chars: int) -> str:
    """Bound text without exceeding max_chars, including the truncation marker."""
    text = _normalize_text(value)
    if max_chars <= 0:
        return ""
    if len(text) <= max_chars:
        return text
    if max_chars <= len(TRUNCATION_MARKER):
        return TRUNCATION_MARKER[-max_chars:]
    return text[: max_chars - len(TRUNCATION_MARKER)].rstrip() + TRUNCATION_MARKER


def _compact_structured_context(text: str, max_chars: int) -> str:
    try:
        parsed = json.loads(text)
    except (json.JSONDecodeError, TypeError, ValueError):
        return truncate_context_text(text, max_chars)

    if len(text) <= max_chars:
        return text
    wrapper = {
        "truncated": True,
        "original_type": type(parsed).__name__,
        "preview": truncate_context_text(text, max(0, max_chars - 100)),
    }
    compacted = json.dumps(wrapper, ensure_ascii=False, separators=(",", ":"))
    while len(compacted) > max_chars and wrapper["preview"]:
        overflow = len(compacted) - max_chars
        wrapper["preview"] = wrapper["preview"][: max(0, len(wrapper["preview"]) - overflow - 1)]
        compacted = json.dumps(wrapper, ensure_ascii=False, separators=(",", ":"))
    return compacted if len(compacted) <= max_chars else "{}"


def compact_memory_context(memory_text: Any) -> str:
    """Keep the most relevant leading memory items within count and char budgets."""
    text = _normalize_text(memory_text)
    if not text:
        return ""

    try:
        json.loads(text)
    except (json.JSONDecodeError, TypeError, ValueError):
        pass
    else:
        return _compact_structured_context(text, MEMORY_CONTEXT_MAX_CHARS)

    lines = text.splitlines()
    bullet_pattern = re.compile(r"^\s*(?:[-*•]|\d+[.)])\s+")
    if sum(bool(bullet_pattern.match(line)) for line in lines) < 2:
        return truncate_context_text(text, MEMORY_CONTEXT_MAX_CHARS)

    items = []
    current = []
    for line in lines:
        if bullet_pattern.match(line) and current:
            items.append("\n".join(current).strip())
            current = [line.rstrip()]
        else:
            current.append(line.rstrip())
    if current:
        items.append("\n".join(current).strip())

    unique_items = []
    seen = set()
    for item in items:
        if not item:
            continue
        key = re.sub(r"\s+", " ", item).strip()
        if key in seen:
            continue
        seen.add(key)
        unique_items.append(item)
        if len(unique_items) >= MEMORY_CONTEXT_MAX_ITEMS:
            break

    return truncate_context_text("\n".join(unique_items), MEMORY_CONTEXT_MAX_CHARS)


def compact_dynamic_context(context_text: Any) -> str:
    """Bound external context-provider output and remove repeated lines."""
    text = _normalize_text(context_text)
    if not text:
        return ""
    unique_lines = []
    seen = set()
    for line in text.splitlines():
        normalized = line.strip()
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        unique_lines.append(normalized)
        if len(unique_lines) >= DYNAMIC_CONTEXT_MAX_ITEMS:
            break
    return truncate_context_text(
        "\n".join(unique_lines), DYNAMIC_CONTEXT_MAX_CHARS
    )


def build_bounded_knowledge_context(
    header: str,
    items: Iterable[Any],
    separator: str = "\n\n---\n\n",
) -> str:
    """Build a diverse retrieval context while reserving room for several hits."""
    unique_items = []
    seen = set()
    for value in items:
        item = _normalize_text(value)
        if not item:
            continue
        key = re.sub(r"\s+", " ", item)
        if key in seen:
            continue
        seen.add(key)
        unique_items.append(
            truncate_context_text(item, KNOWLEDGE_ITEM_MAX_CHARS)
        )
        if len(unique_items) >= KNOWLEDGE_CONTEXT_MAX_ITEMS:
            break

    prefix = _normalize_text(header)
    if not unique_items:
        return truncate_context_text(prefix, KNOWLEDGE_CONTEXT_MAX_CHARS)
    combined = f"{prefix}\n" + separator.join(unique_items)
    return truncate_context_text(combined, KNOWLEDGE_CONTEXT_MAX_CHARS)


def compact_tool_history(messages: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Cap retained tool payloads, prioritizing the newest results."""
    compacted = [dict(message) for message in messages]
    remaining = TOOL_HISTORY_MAX_CHARS
    seen = set()

    for message in reversed(compacted):
        if message.get("role") != "tool":
            continue
        content = _normalize_text(message.get("content"))
        duplicate = content in seen
        if content:
            seen.add(content)

        if remaining <= 0:
            bounded = ""
        elif duplicate:
            bounded = REPEATED_TOOL_RESULT_MARKER[:remaining]
        else:
            limit = min(TOOL_RESULT_MAX_CHARS, remaining)
            bounded = _compact_structured_context(content, limit)
        message["content"] = bounded
        remaining -= len(bounded)

    return compacted


def compact_tool_schemas(tools: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Deduplicate tools and budget only descriptive prose, preserving schemas."""
    unique_tools = []
    seen_names = set()

    for raw_tool in tools or []:
        if not isinstance(raw_tool, dict):
            continue
        tool = copy.deepcopy(raw_tool)
        function = tool.get("function")
        if not isinstance(function, dict):
            continue
        name = str(function.get("name") or "").strip()
        if not name or name in seen_names:
            continue
        seen_names.add(name)
        unique_tools.append(tool)

    if not unique_tools:
        return []

    compacted = []
    fair_tool_budget = max(
        1, TOOL_DESCRIPTIONS_AGGREGATE_MAX_CHARS // len(unique_tools)
    )
    per_tool_budget = min(TOOL_DESCRIPTION_TOTAL_MAX_CHARS, fair_tool_budget)

    for tool in unique_tools:
        function = tool["function"]

        remaining = per_tool_budget
        seen_descriptions = set()

        def compact_descriptions(value: Any, root: bool = False):
            nonlocal remaining
            if isinstance(value, dict):
                for key in list(value.keys()):
                    item = value[key]
                    if key == "description" and isinstance(item, str):
                        normalized = _normalize_text(item)
                        if normalized in seen_descriptions or remaining <= 0:
                            value.pop(key)
                            continue
                        seen_descriptions.add(normalized)
                        per_field_limit = (
                            TOOL_DESCRIPTION_MAX_CHARS
                            if root
                            else PARAMETER_DESCRIPTION_MAX_CHARS
                        )
                        bounded = truncate_context_text(
                            normalized, min(per_field_limit, remaining)
                        )
                        value[key] = bounded
                        remaining -= len(bounded)
                    else:
                        compact_descriptions(item)
            elif isinstance(value, list):
                for item in value:
                    compact_descriptions(item)

        compact_descriptions(function, root=True)
        compacted.append(tool)

    return compacted
