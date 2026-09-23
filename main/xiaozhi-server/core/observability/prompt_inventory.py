"""Content-free accounting of assembled chat inputs, not a model tokenizer.

Counts use compact ensure_ascii=False JSON as a reproducible denominator. The
sum of buckets equals that representation's length; provider wire serialization
and tokenization may differ. System sections are inferred from template tags,
not authenticated provenance. No text, tool names, arguments or hashes escape.
"""
import json
import re


SECTIONS = {
    "identity": "role_identity",
    "context": "dynamic_context",
    "memory": "memory",
    "speakers_info": "speaker_context",
    "freeform_game": "activity_rules",
    "tool_and_knowledge": "tool_policy",
    "speaker_recognition": "speaker_rules",
    "memory_security": "memory_rules",
}
PATTERN = re.compile(
    r"<(" + "|".join(SECTIONS) + r")(?:\s[^>]*)?>.*?</\1>", re.DOTALL
)
CATEGORIES = (
    "system_other", *dict.fromkeys(SECTIONS.values()), "last_user_message",
    "conversation_history", "tool_results", "tool_call_messages",
    "structured_content", "message_json_structure", "tool_definitions",
)


def _json(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _system_sections(text, counts, default="system_other"):
    cursor = 0
    for match in PATTERN.finditer(text):
        counts[default] += match.start() - cursor
        category = SECTIONS[match.group(1)]
        section = match.group(0)
        if match.group(1) == "context":
            # Context may contain memory and speaker facts; do not double count.
            opening = section.index(">") + 1
            closing = section.rfind("</context>")
            counts[category] += opening + len(section) - closing
            _system_sections(section[opening:closing], counts, category)
        else:
            counts[category] += len(section)
        cursor = match.end()
    counts[default] += len(text) - cursor


def summarize_prompt(dialogue, functions=None):
    messages = dialogue or []
    tools = functions or []
    counts = dict.fromkeys(CATEGORIES, 0)
    content_chars = 0
    last_user = next((i for i in range(len(messages) - 1, -1, -1)
                      if messages[i].get("role") == "user"), None)
    for index, message in enumerate(messages):
        content = message.get("content")
        if content is None:
            continue
        if not isinstance(content, str):
            size = len(_json(content))
            counts["structured_content"] += size
            content_chars += size
            continue
        content_chars += len(content)
        role = message.get("role")
        if role == "system":
            _system_sections(content, counts)
        elif index == last_user:
            counts["last_user_message"] += len(content)
        elif role == "tool":
            counts["tool_results"] += len(content)
        elif message.get("tool_calls"):
            counts["tool_call_messages"] += len(content)
        else:
            counts["conversation_history"] += len(content)
    # Count tool-call IDs/arguments and message envelopes without storing values.
    messages_chars = len(_json(messages))
    tools_chars = len(_json(tools)) if tools else 0
    counts["message_json_structure"] = messages_chars - content_chars
    counts["tool_definitions"] = tools_chars
    total = messages_chars + tools_chars
    return {
        "version": 1,
        "basis": "canonical_json_characters_not_tokens",
        "attribution": "system_template_tags_and_message_roles",
        "message_count": len(messages),
        "system_message_count": sum(m.get("role") == "system" for m in messages),
        "tool_count": len(tools),
        "total_chars": total,
        "messages_chars": messages_chars,
        "tools_chars": tools_chars,
        "categories": {
            name: {"chars": size, "percent": round(size * 100 / total, 2) if total else 0}
            for name, size in counts.items()
        },
        "category_tokens": None,
        "token_note": "No matching tokenizer; do not allocate provider tokens by character share.",
    }


def safe_prompt_summary(dialogue, functions=None):
    """Diagnostic errors must never interrupt the voice reply."""
    try:
        return summarize_prompt(dialogue, functions)
    except Exception:
        return {"version": 1, "status": "unavailable"}


def aggregate_prompt_summaries(summaries):
    """Aggregate numeric inventories from a chosen, non-duplicated call cohort.

    Callers must select session/turn/call_index and request kind before grouping;
    this is not an average over user interactions or over missing samples.
    """
    totals = dict.fromkeys(CATEGORIES, 0)
    count = 0
    for item in summaries:
        if item.get("basis") != "canonical_json_characters_not_tokens":
            continue
        if not all(name in item.get("categories", {}) for name in CATEGORIES):
            continue
        count += 1
        for name in CATEGORIES:
            totals[name] += item["categories"][name]["chars"]
    total = sum(totals.values())
    return {
        "sample_count": count,
        "basis": "canonical_json_characters_not_tokens",
        "total_chars": total,
        "mean_total_chars": round(total / count, 2) if count else None,
        "categories": {
            name: {"chars": size, "mean_chars": round(size / count, 2) if count else None,
                   "weighted_percent": round(size * 100 / total, 2) if total else None}
            for name, size in totals.items()
        },
    }
