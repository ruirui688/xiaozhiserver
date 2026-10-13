"""Select complete local knowledge records within the tool-context budget."""

from dataclasses import dataclass
from typing import Iterable

from core.utils.prompt_budget import KNOWLEDGE_CONTEXT_MAX_CHARS, _normalize_text


OMITTED_NOTICE = "[部分检索条目因上下文长度限制未注入，不能据此认定资料不存在。]"
BUDGET_EMPTY_NOTICE = (
    "检索到了资料，但完整条目超过本轮上下文预算。"
    "请说明需要更具体的问题，不能把它说成知识库没有记录。"
)


@dataclass(frozen=True)
class KnowledgeContext:
    text: str
    selected_count: int
    omitted_count: int


def select_knowledge_context(header: str, items: Iterable[str]) -> KnowledgeContext:
    """Keep backend order and whole records; never cut a price or its conditions."""
    prefix = _normalize_text(header)
    unique = list(dict.fromkeys(_normalize_text(item) for item in items if _normalize_text(item)))
    separator = "\n\n---\n\n"
    # Reserve the notice up front so adding it cannot truncate an already selected fact.
    remaining = KNOWLEDGE_CONTEXT_MAX_CHARS - len(prefix) - 1 - len(OMITTED_NOTICE) - 1
    selected = []
    for item in unique:
        cost = len(item) + (len(separator) if selected else 0)
        if cost <= remaining:
            selected.append(item)
            remaining -= cost
    omitted = len(unique) - len(selected)
    if not selected:
        return KnowledgeContext(BUDGET_EMPTY_NOTICE, 0, omitted)
    text = prefix + "\n" + separator.join(selected)
    if omitted:
        text += "\n" + OMITTED_NOTICE
    return KnowledgeContext(text, len(selected), omitted)
