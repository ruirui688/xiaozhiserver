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

