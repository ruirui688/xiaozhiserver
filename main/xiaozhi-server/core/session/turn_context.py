"""Connection-scoped state for optional per-turn device background context."""

import json
import threading
from dataclasses import dataclass
from typing import Any, Optional


MAX_TURN_CONTEXT_BYTES = 8 * 1024


@dataclass(frozen=True)
class TurnContextUpdate:
    accepted: bool
    context_type: str = ""
    size_bytes: int = 0
    reason: str = ""


class TurnContextBuffer:
    """Store one validated context value until the next ASR turn consumes it."""

    def __init__(self, max_bytes: int = MAX_TURN_CONTEXT_BYTES):
        self.max_bytes = max_bytes
        self._value: Optional[Any] = None
        self._lock = threading.Lock()

    def replace(self, value: Any) -> TurnContextUpdate:
        normalized, update = normalize_turn_context(value, self.max_bytes)
        with self._lock:
            self._value = normalized
        return update

    def take(self) -> Optional[Any]:
        with self._lock:
            value = self._value
            self._value = None
            return value

    def clear(self) -> None:
        with self._lock:
            self._value = None


def normalize_turn_context(
    value: Any,
    max_bytes: int = MAX_TURN_CONTEXT_BYTES,
) -> tuple[Optional[Any], TurnContextUpdate]:
    if value is None:
        return None, TurnContextUpdate(False, reason="empty")
    if not isinstance(value, (str, dict, list)):
        return None, TurnContextUpdate(
            False,
            context_type=type(value).__name__,
            reason="unsupported_type",
        )
    if isinstance(value, str) and not value.strip():
        return None, TurnContextUpdate(False, context_type="string", reason="empty")

    try:
        serialized = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    except (TypeError, ValueError):
        return None, TurnContextUpdate(
            False,
            context_type=type(value).__name__,
            reason="not_json_serializable",
        )

    size_bytes = len(serialized.encode("utf-8"))
    context_type = "string" if isinstance(value, str) else type(value).__name__
    if size_bytes > max_bytes:
        return None, TurnContextUpdate(
            False,
            context_type=context_type,
            size_bytes=size_bytes,
            reason="too_large",
        )

    normalized = value if isinstance(value, str) else json.loads(serialized)
    return normalized, TurnContextUpdate(True, context_type, size_bytes)


def build_contextual_user_content(content: str, turn_context: Any) -> str:
    """Merge context into the existing structured ASR payload for LLM/report use."""
    if turn_context is None:
        return content

    payload = None
    if isinstance(content, str):
        stripped = content.strip()
        if stripped.startswith("{") and stripped.endswith("}"):
            try:
                parsed = json.loads(content)
                if isinstance(parsed, dict) and "content" in parsed:
                    payload = dict(parsed)
            except json.JSONDecodeError:
                pass
    if payload is None:
        payload = {"content": content}
    payload["context"] = turn_context
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def redact_contextual_user_content(content: Any) -> Any:
    """Redact a structured turn context before rendering diagnostic logs."""
    if not isinstance(content, str):
        return content
    try:
        payload = json.loads(content)
    except json.JSONDecodeError:
        return content
    if not isinstance(payload, dict) or "context" not in payload:
        return content

    redacted = dict(payload)
    context = redacted.get("context")
    context_type = "string" if isinstance(context, str) else type(context).__name__
    redacted["context"] = f"<redacted:{context_type}>"
    return json.dumps(redacted, ensure_ascii=False, separators=(",", ":"))


def redact_dialogue_contexts_for_log(messages: list[dict]) -> list[dict]:
    """Return metadata-only dialogue diagnostics.

    System prompts, user utterances, memory, dynamic context and tool
    arguments can all contain customer data or internal instructions.  Logs
    need the message shape for troubleshooting, but must not retain those
    payloads verbatim.
    """
    redacted_messages = []
    for message in messages:
        item = dict(message)
        if "content" in item:
            content = item.get("content")
            item["content"] = (
                f"<redacted:{len(str(content))} chars>" if content is not None else None
            )
        if "tool_calls" in item:
            safe_tool_calls = []
            for call in item.get("tool_calls") or []:
                if not isinstance(call, dict):
                    safe_tool_calls.append({"type": type(call).__name__})
                    continue
                function = call.get("function") or {}
                safe_tool_calls.append(
                    {
                        "id": call.get("id"),
                        "type": call.get("type"),
                        "function": {
                            "name": function.get("name"),
                            "arguments": "<redacted>",
                        },
                    }
                )
            item["tool_calls"] = safe_tool_calls
        redacted_messages.append(item)
    return redacted_messages
