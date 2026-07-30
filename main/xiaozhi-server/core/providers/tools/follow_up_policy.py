"""Per-request policy for LLM follow-ups after tool execution."""

from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class ToolFollowUpPolicy:
    allow_tools: bool = True
    system_prompt: Optional[str] = None


def resolve_tool_follow_up_policy(tool_results) -> ToolFollowUpPolicy:
    """Merge ActionResponse hints without weakening an explicit tool disable."""
    allow_tools = all(
        getattr(result, "next_llm_tools_enabled", None) is not False
        for result, _ in tool_results
    )
    prompts = []
    for result, _ in tool_results:
        prompt = getattr(result, "next_llm_system_prompt", None)
        prompt_text = str(prompt).strip() if prompt else ""
        if prompt_text and prompt_text not in prompts:
            prompts.append(prompt_text)
    return ToolFollowUpPolicy(
        allow_tools=allow_tools,
        system_prompt="\n\n".join(prompts) or None,
    )


def inject_trusted_follow_up_system_prompt(messages, prompt):
    """Return a request-local message list containing an internal instruction."""
    prompt_text = str(prompt).strip() if prompt else ""
    if not prompt_text:
        return messages

    result = list(messages)
    insert_at = len(result)
    for index in range(len(result) - 1, -1, -1):
        if result[index].get("role") == "user":
            insert_at = index
            break
    result.insert(insert_at, {"role": "system", "content": prompt_text})
    return result
