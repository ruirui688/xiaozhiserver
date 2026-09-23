"""Official-style streaming for ``direct_answer.response`` text."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping

from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO
from core.providers.tools.tool_protocol_guard import (
    TOOL_PROTOCOL_ERROR_REPLY,
    ToolProtocolStreamGuard,
)


def _positive_int(value, default, field_name):
    if value is None:
        return default
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{field_name} 必须是大于 0 的整数") from exc
    if parsed <= 0:
        raise ValueError(f"{field_name} 必须是大于 0 的整数")
    return parsed


@dataclass(frozen=True)
class DirectAnswerStreamConfig:
    enabled: bool = True
    json_tail_chars: int = 5

    @classmethod
    def from_mapping(cls, value):
        if value is None:
            return cls()
        if isinstance(value, bool):
            return cls(enabled=value)
        if not isinstance(value, Mapping):
            raise ValueError("direct_answer_streaming 必须是布尔值或对象")

        enabled_value = value.get("enabled", True)
        if isinstance(enabled_value, str):
            enabled = enabled_value.strip().lower() not in {
                "false",
                "0",
                "off",
                "no",
            }
        else:
            enabled = bool(enabled_value)
        config = cls(
            enabled=enabled,
            json_tail_chars=_positive_int(
                value.get("json_tail_chars"), 5, "json_tail_chars"
            ),
        )
        return config


class DirectAnswerTTSStream:
    """Send each stable direct-answer delta to TTS, matching upstream."""

    def __init__(
        self,
        tts,
        sentence_id,
        config: DirectAnswerStreamConfig,
        generation=None,
    ):
        self.tts = tts
        self.sentence_id = sentence_id
        self.config = config
        self.generation = generation
        self._snapshot = ""
        self._emitted_chars = 0
        self._preview_stored = False
        self._protocol_guard = ToolProtocolStreamGuard()
        self._rejection_reported = False

    @property
    def blocked(self):
        return self._protocol_guard.blocked

    def update(self, full_text):
        text = str(full_text or "")
        self._validate_snapshot(text)
        safe_end = max(
            self._emitted_chars,
            len(text) - self.config.json_tail_chars,
        )
        return self._enqueue_until(text, safe_end)

    def finish(self, full_text):
        text = str(full_text or "")
        self._validate_snapshot(text)
        chunks = self._enqueue_until(text, len(text))
        tail, blocked = self._protocol_guard.finish()
        if blocked and not self._rejection_reported:
            self._rejection_reported = True
            chunks += self._enqueue_text(TOOL_PROTOCOL_ERROR_REPLY)
        elif tail:
            chunks += self._enqueue_text(tail)
        return chunks

    def _validate_snapshot(self, snapshot):
        if snapshot.startswith(self._snapshot):
            self._snapshot = snapshot
            return
        common_length = 0
        for old_char, new_char in zip(self._snapshot, snapshot):
            if old_char != new_char:
                break
            common_length += 1
        if common_length < self._emitted_chars:
            raise ValueError("direct_answer 已确认文本在发送后发生变化")
        self._snapshot = snapshot

    def _enqueue_until(self, text, end):
        if end <= self._emitted_chars:
            return []
        chunk = text[self._emitted_chars : end]
        self._emitted_chars = end
        safe, _ = self._protocol_guard.feed(chunk)
        return self._enqueue_text(safe)

    def _enqueue_text(self, chunk):
        if not chunk:
            return []
        if not self._preview_stored:
            store_preview = getattr(self.tts, "store_tts_preview", None)
            if callable(store_preview):
                store_preview(self.sentence_id, chunk)
            self._preview_stored = True
        self.tts.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id=self.sentence_id,
                sentence_type=SentenceType.MIDDLE,
                content_type=ContentType.TEXT,
                content_detail=chunk,
                generation=self.generation,
            )
        )
        return [chunk]
