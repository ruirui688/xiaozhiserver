"""Guards for model-emitted tool protocol text."""

import html
import re


def looks_like_unhandled_tool_protocol(text: str) -> bool:
    if not text:
        return False
    normalized = text
    for _ in range(2):
        unescaped = html.unescape(normalized)
        if unescaped == normalized:
            break
        normalized = unescaped

    compact = re.sub(r"\s+", "", normalized)
    upper = compact.upper()
    if "DSML" in upper and "TOOL_CALLS" in upper:
        return True
    return bool(
        re.search(r"<[^>]*tool_calls[^>]*>.*invoke\s+name=", normalized, re.I | re.S)
    )


class ToolProtocolStreamGuard:
    """Hold only suffixes that can still become a streamed tool-protocol marker."""

    _COMPACT_PREFIXES = (
        "<||dsml||",
        "<tool_calls",
        "&lt;||dsml||",
        "&lt;tool_calls",
        "&amp;lt;||dsml||",
        "&amp;lt;tool_calls",
    )

    def __init__(self):
        self._pending = ""
        self.blocked = False
        self.blocked_text = ""

    def feed(self, text: str):
        if self.blocked or not text:
            return "", self.blocked
        self._pending += str(text)
        if looks_like_unhandled_tool_protocol(self._pending):
            self.blocked = True
            self.blocked_text = self._pending
            self._pending = ""
            return "", True

        marker = self._possible_protocol_start(self._pending)
        if marker < 0:
            safe = self._pending
            self._pending = ""
            return safe, False
        safe = self._pending[:marker]
        self._pending = self._pending[marker:]
        return safe, False

    def finish(self):
        if self.blocked:
            return "", True
        if looks_like_unhandled_tool_protocol(self._pending):
            self.blocked = True
            self.blocked_text = self._pending
            self._pending = ""
            return "", True
        safe = self._pending
        self._pending = ""
        return safe, False

    @classmethod
    def _possible_protocol_start(cls, text: str) -> int:
        for match in re.finditer(r"[<&＜]", text):
            candidate = text[match.start():].lower().replace("＜", "<").replace("＞", ">")
            compact = re.sub(r"\s+", "", candidate)
            normalized = compact
            for _ in range(2):
                unescaped = html.unescape(normalized)
                if unescaped == normalized:
                    break
                normalized = unescaped
            raw_possible = any(
                prefix.startswith(compact) or compact.startswith(prefix)
                for prefix in cls._COMPACT_PREFIXES
            )
            normalized_possible = any(
                prefix.startswith(normalized) or normalized.startswith(prefix)
                for prefix in cls._COMPACT_PREFIXES[:2]
            )
            if raw_possible or normalized_possible:
                return match.start()
        return -1
