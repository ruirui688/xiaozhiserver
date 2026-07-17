"""Text normalization for semantic routing."""

import json
import re
from typing import Any, Dict, Tuple

from core.semantic.frames import ASRResult


class TextNormalizer:
    """Normalize ASR/client text before deterministic routing."""

    DEFAULT_REPLACEMENTS = {
        "小志": "小智",
        "小知": "小智",
        "别演啦": "别演了",
        "成语接龙呢": "成语接龙",
    }

    def __init__(self, replacements: Dict[str, str] | None = None):
        self.replacements = dict(self.DEFAULT_REPLACEMENTS)
        if replacements:
            self.replacements.update(replacements)

    def normalize(self, conn, text: str) -> ASRResult:
        display_text, metadata = self._extract_payload(text)
        display_text = self.strip_leading_wake_address(display_text)
        normalized = display_text.strip()
        for source, target in self.replacements.items():
            normalized = normalized.replace(source, target)
        normalized = re.sub(r"\s+", " ", normalized).strip()
        return ASRResult(
            session_id=getattr(conn, "session_id", ""),
            device_id=getattr(conn, "device_id", None),
            raw_text=text,
            normalized_text=normalized,
            display_text=display_text,
            metadata=metadata,
        )

    def route_key(self, text: str) -> str:
        return re.sub(r"[\s，。！？!?,.;；：“”\"'、~～…]+", "", text).lower()

    def normalize_runtime_text(self, text: str) -> str:
        """Clean text before semantic/LLM handling while preserving metadata."""
        stripped = str(text or "").strip()
        if stripped.startswith("{") and stripped.endswith("}"):
            try:
                payload = json.loads(stripped)
            except json.JSONDecodeError:
                return self.strip_leading_wake_address(text)
            if isinstance(payload, dict) and "content" in payload:
                content = str(payload.get("content") or "")
                cleaned = self.strip_leading_wake_address(content)
                if cleaned != content:
                    payload["content"] = cleaned
                    return json.dumps(payload, ensure_ascii=False)
                return text
        return self.strip_leading_wake_address(text)

    def strip_leading_wake_address(self, text: str) -> str:
        """Strip only a leading wake-name address.

        ``你好小智`` / ``小智你好`` / ``小智`` are removed when used as a
        sentence-head address. Body terms such as ``小智服务器`` are preserved.
        """
        value = str(text or "")
        match = re.match(
            r"^\s*(?:(?:好(?:的)?|嗯+|呃+|哦+|那|哎)[，。！？!?,.\s]*)?"
            r"(?:(?:你好[，。！？!?,.\s]*)?小[智志知](?:你好)?|小[智志知]你好)"
            r"[，。！？!?,.\s]*",
            value,
        )
        if not match:
            return value

        remainder = value[match.end():]
        if not remainder:
            return ""
        if self._wake_address_can_precede(remainder):
            return remainder.lstrip(" ，。！？!?,.\t")
        return value

    def _wake_address_can_precede(self, remainder: str) -> bool:
        text = remainder.lstrip(" ，。！？!?,.\t")
        if not text:
            return True
        return text.startswith(
            (
                "你",
                "我",
                "帮",
                "请",
                "进入",
                "开始",
                "开启",
                "停止",
                "结束",
                "不用",
                "不要",
                "在",
                "给",
                "把",
                "来",
                "听",
                "说",
                "播",
                "放",
                "查",
                "看",
                "要",
                "能",
                "可以",
                "继续",
                "退出",
                "记录",
                "生成",
                "先",
                "现在",
                "立即",
            )
        )

    def _extract_payload(self, text: str) -> Tuple[str, Dict[str, Any]]:
        metadata: Dict[str, Any] = {}
        stripped = text.strip()
        if stripped.startswith("{") and stripped.endswith("}"):
            try:
                payload = json.loads(stripped)
            except json.JSONDecodeError:
                return text, metadata
            if isinstance(payload, dict) and "content" in payload:
                metadata["speaker"] = payload.get("speaker")
                metadata["language"] = payload.get("language")
                return str(payload.get("content") or ""), metadata
        return text, metadata
