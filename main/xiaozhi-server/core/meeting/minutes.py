"""In-memory meeting minutes state, independent from the connection lifecycle."""

from dataclasses import dataclass
from datetime import datetime
import re
import threading
from typing import Any, Dict, List, Optional

MEETING_STARTED_PROMPT = "已进入会议模式，我会静默记录。需要结束时，直接告诉我即可。"


@dataclass
class MeetingUtterance:
    text: str
    speaker_label: str
    resolved_speaker: Optional[str] = None
    audio: Optional[bytes] = None
    timestamp: Optional[Any] = None
    duration_ms: Optional[float] = None

    @property
    def display_speaker(self) -> str:
        return self.resolved_speaker or self.speaker_label

    def as_dict(self) -> Dict[str, Any]:
        return {
            "text": self.text,
            "speakerLabel": self.speaker_label,
            "resolvedSpeaker": self.resolved_speaker,
            "displaySpeaker": self.display_speaker,
            "timestamp": _format_timestamp(self.timestamp),
            "durationMs": self.duration_ms,
            "audioBytes": len(self.audio) if self.audio is not None else None,
        }


class MeetingMinutesRuntime:
    """Owns the state collected for one meeting without invoking any provider."""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._active = False
        self._topic = ""
        self._started_at: Optional[Any] = None
        self._ended_at: Optional[Any] = None
        self._utterances: List[MeetingUtterance] = []
        self._anonymous_speaker_count = 0
        self._anonymous_label_map: Dict[str, str] = {}
        self._pending_turn: Optional[Dict[str, Any]] = None

    def start(self, topic: str = "", timestamp: Optional[Any] = None) -> Dict[str, Any]:
        """Start a new meeting and discard state from any previous meeting."""
        with self._lock:
            self._active = True
            self._topic = str(topic).strip()
            self._started_at = timestamp if timestamp is not None else datetime.now()
            self._ended_at = None
            self._utterances = []
            self._anonymous_speaker_count = 0
            self._anonymous_label_map = {}
            self._pending_turn = None
            return self.get_status()

    def end(self, timestamp: Optional[Any] = None) -> Dict[str, Any]:
        """End the current meeting without generating a summary."""
        with self._lock:
            if self._active:
                self._active = False
                self._ended_at = timestamp if timestamp is not None else datetime.now()
            self._pending_turn = None
            return self.get_status()

    def is_active(self) -> bool:
        with self._lock:
            return self._active

    def prepare_turn(
        self,
        payload: Optional[Dict[str, Any]],
        fallback_text: str,
        fallback_speaker: Optional[str] = None,
    ) -> None:
        """Hold the current ASR turn until the main LLM selects record or end."""
        with self._lock:
            self._pending_turn = {
                "payload": payload if isinstance(payload, dict) else None,
                "fallback_text": str(fallback_text or "").strip(),
                "fallback_speaker": _optional_text(fallback_speaker),
            }

    def has_pending_turn(self) -> bool:
        with self._lock:
            return self._pending_turn is not None

    def discard_pending_turn(self) -> None:
        with self._lock:
            self._pending_turn = None

    def record_pending_turn(self) -> int:
        """Commit the pending authoritative ASR payload to the transcript."""
        with self._lock:
            pending = self._pending_turn
            self._pending_turn = None
            if pending is None or not self._active:
                return 0
            return self._append_payload(
                pending.get("payload"),
                pending.get("fallback_text", ""),
                pending.get("fallback_speaker"),
            )

    def append_utterance(
        self,
        text: str,
        speaker_label: Optional[str] = None,
        resolved_speaker: Optional[str] = None,
        audio: Optional[bytes] = None,
        timestamp: Optional[Any] = None,
        duration_ms: Optional[float] = None,
    ) -> Dict[str, Any]:
        """Append one recognized utterance to the active meeting."""
        with self._lock:
            if not self._active:
                raise RuntimeError("Cannot append an utterance when no meeting is active.")

            normalized_label = str(speaker_label or "").strip()
            if not normalized_label:
                self._anonymous_speaker_count += 1
                normalized_label = f"说话人{self._anonymous_speaker_count}"
            elif normalized_label.startswith("说话人"):
                pass
            else:
                normalized_label = self._normalize_provider_label(normalized_label)

            utterance = MeetingUtterance(
                text=str(text).strip(),
                speaker_label=normalized_label,
                resolved_speaker=_optional_text(resolved_speaker),
                audio=audio,
                timestamp=timestamp,
                duration_ms=duration_ms,
            )
            self._utterances.append(utterance)
            return utterance.as_dict()

    def build_transcript(self) -> List[Dict[str, Any]]:
        """Return structured utterances in their original meeting order."""
        with self._lock:
            return [utterance.as_dict() for utterance in self._utterances]

    def build_summary_prompt(self) -> str:
        """Build a Chinese prompt for a caller-owned LLM summary request."""
        participants = []
        for utterance in self._utterances:
            if utterance.display_speaker not in participants:
                participants.append(utterance.display_speaker)

        transcript = "\n".join(
            "[{timestamp}] {speaker}: {text}".format(
                timestamp=item["timestamp"] or "时间未记录",
                speaker=item["displaySpeaker"],
                text=item["text"],
            )
            for item in self.build_transcript()
        ) or "（暂无逐句记录）"
        meeting_time = "至".join(
            value
            for value in (
                _format_timestamp(self._started_at),
                _format_timestamp(self._ended_at),
            )
            if value
        ) or "时间未记录"

        return "\n".join(
            [
                "请根据以下会议逐句记录生成中文会议纪要。不得编造未提及的事实；信息不足时明确标注“待确认”。",
                f"会议主题：{self._topic or '未命名会议'}",
                f"会议时间：{meeting_time}",
                f"参会人：{'、'.join(participants) or '未识别'}",
                "逐句记录：",
                transcript,
                "请按以下固定结构输出：",
                "1. 会议主题/时间",
                "2. 参会人",
                "3. 议题总结",
                "4. 关键结论",
                "5. 待办事项（负责人、截止时间如有）",
                "6. 风险与待确认",
            ]
        )

    def get_status(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "active": self._active,
                "topic": self._topic,
                "startedAt": _format_timestamp(self._started_at),
                "endedAt": _format_timestamp(self._ended_at),
                "utteranceCount": len(self._utterances),
                "pendingTurn": self._pending_turn is not None,
            }

    def _append_payload(
        self,
        payload: Optional[Dict[str, Any]],
        fallback_text: str,
        fallback_speaker: Optional[str],
    ) -> int:
        if isinstance(payload, dict) and isinstance(payload.get("utterances"), list):
            appended = 0
            utterances = payload["utterances"]
            for item in utterances:
                if not isinstance(item, dict):
                    continue
                text = str(item.get("text") or "").strip()
                if not text:
                    continue
                self.append_utterance(
                    text,
                    speaker_label=_meeting_speaker_label(item),
                    resolved_speaker=item.get("resolved_speaker")
                    or (fallback_speaker if len(utterances) == 1 else None),
                    timestamp=_meeting_timestamp(item),
                    duration_ms=_meeting_duration_ms(item),
                )
                appended += 1
            if appended:
                return appended

        text = str(fallback_text or "").strip()
        if not text:
            return 0
        self.append_utterance(
            text,
            speaker_label=None,
            resolved_speaker=fallback_speaker,
        )
        return 1

    def _normalize_provider_label(self, label: str) -> str:
        """Map provider speaker ids such as 1/spk_1 to stable Chinese labels."""
        if label in self._anonymous_label_map:
            return self._anonymous_label_map[label]
        match = re.search(r"(\d+)", label)
        if match:
            speaker_number = int(match.group(1))
            self._anonymous_speaker_count = max(
                self._anonymous_speaker_count,
                speaker_number,
            )
            normalized = f"说话人{speaker_number}"
        else:
            self._anonymous_speaker_count += 1
            normalized = f"说话人{self._anonymous_speaker_count}"
        self._anonymous_label_map[label] = normalized
        return normalized


def _optional_text(value: Optional[str]) -> Optional[str]:
    text = str(value or "").strip()
    return text or None


def _meeting_speaker_label(item: Dict[str, Any]) -> str:
    for key in (
        "provider_speaker",
        "speaker_label",
        "speakerLabel",
        "speaker",
        "speaker_id",
        "speakerId",
        "role",
    ):
        value = item.get(key)
        if value is not None:
            return str(value)
    return ""


def _meeting_timestamp(item: Dict[str, Any]):
    for key in (
        "start_time",
        "startTime",
        "start_ms",
        "startMs",
        "begin_time",
        "beginTime",
    ):
        value = item.get(key)
        if value is not None:
            return value
    return None


def _meeting_duration_ms(item: Dict[str, Any]):
    start = _coerce_float(
        _first_present(item, "start_time", "startTime", "start_ms", "startMs", "start")
    )
    end = _coerce_float(
        _first_present(item, "end_time", "endTime", "end_ms", "endMs", "end")
    )
    if start is None or end is None or end < start:
        return None
    return end - start


def _first_present(payload: Dict[str, Any], *keys):
    for key in keys:
        value = payload.get(key)
        if value is not None:
            return value
    return None


def _coerce_float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _format_timestamp(value: Optional[Any]) -> Optional[str]:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    return str(value)
