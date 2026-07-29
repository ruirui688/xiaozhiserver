"""Natural-chunk buffering for streamed ``direct_answer.response`` text."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping

from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO


_HARD_BOUNDARIES = frozenset("。！？!?；;\n")
_SOFT_BOUNDARIES = frozenset("，,、：:~")
_BOUNDARY_SUFFIXES = frozenset("\"'”’）)]】》」』 ")


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
    first_min_chars: int = 24
    next_min_chars: int = 12
    target_chars: int = 36
    max_chars: int = 60

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
            first_min_chars=_positive_int(
                value.get("first_min_chars"), 24, "first_min_chars"
            ),
            next_min_chars=_positive_int(
                value.get("next_min_chars"), 12, "next_min_chars"
            ),
            target_chars=_positive_int(
                value.get("target_chars"), 36, "target_chars"
            ),
            max_chars=_positive_int(
                value.get("max_chars"), 60, "max_chars"
            ),
        )
        if config.first_min_chars > config.target_chars:
            raise ValueError("first_min_chars 不能大于 target_chars")
        if config.next_min_chars > config.target_chars:
            raise ValueError("next_min_chars 不能大于 target_chars")
        if config.target_chars > config.max_chars:
            raise ValueError("target_chars 不能大于 max_chars")
        return config


class NaturalTextChunkBuffer:
    """Consume growing text snapshots and emit stable, speakable chunks."""

    def __init__(self, config: DirectAnswerStreamConfig):
        self.config = config
        self._snapshot = ""
        self._emitted_chars = 0
        self._first_emitted = False

    @property
    def emitted_chars(self):
        return self._emitted_chars

    def update(self, snapshot, *, final=False):
        snapshot = str(snapshot or "")
        self._validate_snapshot(snapshot)
        self._snapshot = snapshot
        chunks = []
        while self._emitted_chars < len(snapshot):
            pending = snapshot[self._emitted_chars :]
            boundary = self._select_boundary(pending, final=final)
            if boundary is None:
                break
            chunk = pending[:boundary]
            if not chunk:
                break
            chunks.append(chunk)
            self._emitted_chars += len(chunk)
            self._first_emitted = True
        return chunks

    def _validate_snapshot(self, snapshot):
        if snapshot.startswith(self._snapshot):
            return
        common_length = 0
        for old_char, new_char in zip(self._snapshot, snapshot):
            if old_char != new_char:
                break
            common_length += 1
        if common_length < self._emitted_chars:
            raise ValueError("direct_answer 已确认文本在发送后发生变化")

    def _select_boundary(self, text, *, final):
        minimum = (
            self.config.first_min_chars
            if not self._first_emitted
            else self.config.next_min_chars
        )
        hard = self._boundary_positions(text, _HARD_BOUNDARIES)
        soft = self._boundary_positions(text, _SOFT_BOUNDARIES)

        hard_in_range = [
            position
            for position in hard
            if minimum <= position <= self.config.max_chars
        ]
        if hard_in_range:
            return hard_in_range[0]

        if len(text) >= self.config.target_chars:
            natural_before_target = [
                position
                for position in hard + soft
                if minimum <= position <= self.config.target_chars
            ]
            if natural_before_target:
                return max(natural_before_target)

            natural_before_max = [
                position
                for position in hard + soft
                if minimum <= position <= self.config.max_chars
            ]
            if natural_before_max:
                return min(natural_before_max)

        if len(text) >= self.config.max_chars:
            return self.config.max_chars
        if final:
            return len(text)
        return None

    @staticmethod
    def _boundary_positions(text, boundary_chars):
        positions = []
        for index, char in enumerate(text):
            if char not in boundary_chars:
                continue
            end = index + 1
            while end < len(text) and text[end] in _BOUNDARY_SUFFIXES:
                end += 1
            positions.append(end)
        return positions


class DirectAnswerTTSStream:
    """Bridge one growing direct-answer value into natural TTS queue items."""

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
        self.buffer = NaturalTextChunkBuffer(config)
        self._preview_stored = False

    def update(self, full_text):
        text = str(full_text or "")
        safe_end = max(0, len(text) - self.config.json_tail_chars)
        return self._enqueue(self.buffer.update(text[:safe_end]))

    def finish(self, full_text):
        return self._enqueue(self.buffer.update(full_text, final=True))

    def _enqueue(self, chunks):
        for chunk in chunks:
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
        return chunks
