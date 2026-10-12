"""Text classifier contract: real conversation data in, one authorized route out."""

import json
import re
from dataclasses import dataclass

from core.utils.prompt_budget import truncate_context_text

CONTRACT_VERSION = "tool-routing-v2"
CONTINUE_CHAT = '{"function_call":{"name":"continue_chat"}}'


@dataclass(frozen=True, repr=False)
class IntentRequestSnapshot:
    """Bind an asynchronous route to the session that actually requested it."""

    identities: tuple
    bindings: tuple
    speaker: tuple
    reply_generation: object

    @classmethod
    def capture(cls, conn):
        config = getattr(conn, "config", {})
        lifecycle = getattr(conn, "reply_lifecycle", None)
        return cls(
            tuple(getattr(conn, key, None) for key in (
                "config", "func_handler", "intent", "dialogue", "prompt_manager",
                "reply_lifecycle", "_intent_request_marker",
            )),
            (getattr(conn, "session_id", None), getattr(conn, "agent_id", None),
             config.get("agent_id") if isinstance(config, dict) else None,
             getattr(conn, "abort_generation", 0)),
            (getattr(conn, "current_speaker_id", None), getattr(conn, "current_speaker", None)),
            getattr(lifecycle, "generation", None),
        )

    def is_current(self, conn, *, check_reply=True, check_speaker=True):
        current = self.capture(conn)
        return (
            not getattr(conn, "closing", False) and not getattr(conn, "closed", False)
            and all(old is new for old, new in zip(self.identities, current.identities))
            and self.bindings == current.bindings
            and (not check_speaker or self.speaker == current.speaker)
            and (not check_reply or self.reply_generation == current.reply_generation)
        )


def conversation_prompt(history, text, limit=4, speaker_id=None):
    records = []
    target_speaker = str(speaker_id or "").strip() or None
    same_speaker_turn = False
    for message in history or []:
        get = message.get if isinstance(message, dict) else lambda k, d=None: getattr(message, k, d)
        if (get("is_temporary", False) or get("is_internal", False)
                or get("role") not in {"user", "assistant", "tool"}):
            continue
        if get("role") == "user":
            history_speaker = None
            try:
                payload = json.loads(get("memory_content", get("content")))
                if isinstance(payload, dict) and "content" in payload:
                    history_speaker = str(payload.get("speaker_id") or "").strip() or None
            except (TypeError, ValueError):
                pass
            same_speaker_turn = history_speaker == target_speaker
            if not same_speaker_turn:
                records.clear()
        if not same_speaker_turn:
            continue
        content = get("content")
        if content:
            records.append({"role": get("role"), "content": truncate_context_text(str(content), 4000)})
    return json.dumps({"history": records[-limit:], "current_user": text}, ensure_ascii=False)


def normalize_route(raw, names):
    """Fail closed on invalid or invented tools; support only one call per turn."""
    if not isinstance(raw, str):
        return CONTINUE_CHAT
    raw = re.sub(r"^```(?:json)?\s*|\s*```$", "", raw.strip(), flags=re.IGNORECASE)
    try:
        data = json.loads(raw)
        call = data.get("function_call") if isinstance(data, dict) else None
        if not isinstance(call, dict):
            return CONTINUE_CHAT
        name = call.get("name")
        if not isinstance(name, str) or name not in names:
            return CONTINUE_CHAT
        args = call.get("arguments")
        if args is None:
            args = {}
        if isinstance(args, str):
            args = json.loads(args)
        if not isinstance(args, dict):
            return CONTINUE_CHAT
        return json.dumps({"function_call": {"name": name, "arguments": args}}, ensure_ascii=False)
    except (ValueError, TypeError):
        return CONTINUE_CHAT
