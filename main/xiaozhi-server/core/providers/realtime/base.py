"""Shared protocol helpers for realtime providers."""

import json
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List


@dataclass(frozen=True)
class RealtimeCapabilities:
    native_function_calling: bool = False
    natural_barge_in: bool = False
    context_reset: bool = False
    session_voice: bool = False
    custom_voice_ids: bool = False


def event(event_type: str, **payload: Any) -> Dict[str, Any]:
    return {"type": event_type, **payload}


def realtime_tools(openai_tools: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Keep valid OpenAI function schemas for Qwen Realtime session.update."""
    converted = []
    for item in openai_tools or []:
        function = item.get("function") if isinstance(item, dict) else None
        if not isinstance(function, dict) or not function.get("name"):
            continue
        converted.append({"type": "function", "function": dict(function)})
    return converted


def tool_output_text(result: Any) -> str:
    payload = {
        "action": getattr(getattr(result, "action", None), "name", None),
        "result": getattr(result, "result", None),
        "response": getattr(result, "response", None),
    }
    return json.dumps(payload, ensure_ascii=False, default=str)
