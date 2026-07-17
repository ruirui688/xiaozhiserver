"""Deterministic meeting control parsing for high-confidence voice commands."""

from dataclasses import dataclass
from typing import Optional

from core.semantic.normalizer import TextNormalizer


@dataclass(frozen=True)
class MeetingControlMatch:
    action: str
    confidence: float
    topic: str = ""
    reason: str = "meeting_control_rule"


class MeetingControlParser:
    """Parse only direct meeting start/end commands.

    This parser deliberately avoids fuzzy keyword matching. Phrases that merely
    discuss, quote, or plan a command must fall through to the normal LLM path.
    """

    START_EXACT = {
        "进入会议模式",
        "开始会议模式",
        "开启会议模式",
        "开始记录会议",
        "开始会议记录",
        "开始会议纪要",
        "进入会议记录模式",
        "现在开始记录",
        "立即开始记录",
        "开始记录",
        "记录会议",
        "帮我记录会议",
        "帮忙记录会议",
        "帮我开始记录会议",
        "请记录会议",
    }
    START_PREFIXES = (
        "进入会议模式",
        "开始会议模式",
        "开启会议模式",
        "开始记录会议",
        "开始会议记录",
        "开始会议纪要",
        "进入会议记录模式",
        "开始记录",
        "记录会议",
        "帮我记录会议",
        "帮忙记录会议",
        "帮我开始记录会议",
    )

    END_EXACT = {
        "结束会议",
        "会议结束",
        "停止记录",
        "停止会议记录",
        "结束记录",
        "结束会议模式",
        "不用记录了",
        "不要记录了",
        "生成会议纪要",
        "结束并生成纪要",
        "会议结束生成纪要",
    }
    END_PREFIXES = (
        "结束会议",
        "会议结束",
        "停止记录",
        "停止会议记录",
        "结束记录",
        "结束会议模式",
        "不用记录了",
        "不要记录了",
        "生成会议纪要",
        "结束并生成纪要",
    )

    REQUEST_PREFIXES = (
        "麻烦你",
        "麻烦",
        "请",
        "好的",
        "好",
        "那",
    )
    DISCUSSION_MARKERS = (
        "以后可能",
        "可能需要",
        "也许需要",
        "将来需要",
        "这个命令",
        "命令不好用",
        "怎么用",
        "如何使用",
        "他说",
        "她说",
        "他们说",
        "有人说",
        "如果",
        "假如",
        "不要进入会议模式",
        "别进入会议模式",
        "不是进入会议模式",
    )

    def __init__(self, normalizer: Optional[TextNormalizer] = None):
        self.normalizer = normalizer or TextNormalizer()

    def parse(self, text: str, *, active: bool = False) -> Optional[MeetingControlMatch]:
        key = self._control_key(text)
        if not key or self._is_discussion(key):
            return None

        if active:
            if self._matches_end(key):
                return MeetingControlMatch("end", 0.99)
            return None

        if self._matches_start(key):
            return MeetingControlMatch("start", 0.99, topic=self._extract_topic(text))
        if self._matches_end(key):
            return MeetingControlMatch("end", 0.95)
        return None

    def _control_key(self, text: str) -> str:
        display, _metadata = self.normalizer._extract_payload(str(text or ""))
        display = self.normalizer.strip_leading_wake_address(display)
        key = self.normalizer.route_key(display)
        return self._strip_request_prefix(key)

    def _strip_request_prefix(self, key: str) -> str:
        changed = True
        while changed:
            changed = False
            for prefix in self.REQUEST_PREFIXES:
                if key.startswith(prefix) and len(key) > len(prefix):
                    key = key[len(prefix):]
                    changed = True
                    break
        return key

    def _is_discussion(self, key: str) -> bool:
        return any(marker in key for marker in self.DISCUSSION_MARKERS)

    def _matches_start(self, key: str) -> bool:
        return key in self.START_EXACT or any(
            self._allowed_suffix(key, prefix) for prefix in self.START_PREFIXES
        )

    def _matches_end(self, key: str) -> bool:
        return key in self.END_EXACT or any(
            self._allowed_suffix(key, prefix) for prefix in self.END_PREFIXES
        )

    def _allowed_suffix(self, key: str, prefix: str) -> bool:
        if not key.startswith(prefix):
            return False
        suffix = key[len(prefix):]
        if not suffix:
            return True
        return suffix.startswith(
            (
                "吧",
                "一下",
                "并生成纪要",
                "然后生成纪要",
                "生成纪要",
                "主题是",
                "主题为",
                "关于",
            )
        )

    def _extract_topic(self, text: str) -> str:
        display, _metadata = self.normalizer._extract_payload(str(text or ""))
        for marker in ("主题是", "主题为", "关于"):
            if marker in display:
                return display.split(marker, 1)[1].strip(" ，。！？!?")
        return ""
