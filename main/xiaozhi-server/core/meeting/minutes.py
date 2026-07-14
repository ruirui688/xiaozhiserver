"""In-memory meeting minutes state, independent from the connection lifecycle."""

from dataclasses import dataclass
from datetime import datetime
import re
from typing import Any, Dict, List, Optional

START_COMMANDS = (
    "进入会议模式",
    "开启会议模式",
    "打开会议模式",
    "开始会议模式",
    "开始会议纪要",
    "开始会议记录",
    "开始记录会议",
)

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
        self._active = False
        self._topic = ""
        self._started_at: Optional[Any] = None
        self._ended_at: Optional[Any] = None
        self._utterances: List[MeetingUtterance] = []
        self._anonymous_speaker_count = 0
        self._anonymous_label_map: Dict[str, str] = {}

    def start(self, topic: str = "", timestamp: Optional[Any] = None) -> Dict[str, Any]:
        """Start a new meeting and discard state from any previous meeting."""
        self._active = True
        self._topic = str(topic).strip()
        self._started_at = timestamp if timestamp is not None else datetime.now()
        self._ended_at = None
        self._utterances = []
        self._anonymous_speaker_count = 0
        self._anonymous_label_map = {}
        return self.get_status()

    def end(self, timestamp: Optional[Any] = None) -> Dict[str, Any]:
        """End the current meeting without generating a summary."""
        if self._active:
            self._active = False
            self._ended_at = timestamp if timestamp is not None else datetime.now()
        return self.get_status()

    def is_active(self) -> bool:
        return self._active

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
        return {
            "active": self._active,
            "topic": self._topic,
            "startedAt": _format_timestamp(self._started_at),
            "endedAt": _format_timestamp(self._ended_at),
            "utteranceCount": len(self._utterances),
        }

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


def is_meeting_start_command(text: str) -> bool:
    normalized = _normalize_command_text(text)
    return any(command in normalized for command in START_COMMANDS)


def extract_meeting_topic(text: str) -> str:
    normalized = str(text or "").strip()
    for command in START_COMMANDS:
        normalized = normalized.replace(command, "")
    normalized = re.sub(r"^(请|帮我|小智|，|,|。|\s)+", "", normalized)
    normalized = re.sub(r"^(主题是|会议主题是|主题为|记录一下|记录)", "", normalized)
    return normalized.strip(" ，,。；;")


def _optional_text(value: Optional[str]) -> Optional[str]:
    text = str(value or "").strip()
    return text or None


def _normalize_command_text(text: str) -> str:
    return re.sub(r"[\s，,。.!！?？；;：:]", "", str(text or ""))


def _format_timestamp(value: Optional[Any]) -> Optional[str]:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    return str(value)
