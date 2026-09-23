"""Guards for model-emitted tool protocol text."""

import html
import re


TOOL_PROTOCOL_ERROR_REPLY = "抱歉，刚才的回复出了问题，请再说一次。"


def _normalize_marker(text: str) -> str:
    normalized = text
    for _ in range(2):
        unescaped = html.unescape(normalized)
        if unescaped == normalized:
            break
        normalized = unescaped

    normalized = normalized.translate(str.maketrans("｜＜＞", "|<>"))
    return re.sub(r"\s+", "", normalized).lower()


def looks_like_unhandled_tool_protocol(text: str) -> bool:
    if not text:
        return False
    normalized = _normalize_marker(text)
    # Recognize the protocol namespace itself, before calls/invoke/parameters
    # can escape. DeepSeek emits both calls and tool_calls, including fullwidth pipes.
    if re.search(r"</?\|{1,2}dsml(?:\||$)", normalized):
        return True
    return bool(
        re.search(r"<[^>]*tool_calls[^>]*>.*invokename=", normalized, re.I | re.S)
    )


class ToolProtocolStreamGuard:
    """Hold only suffixes that can still become a streamed tool-protocol marker."""

    _COMPACT_PREFIXES = (
        "<||dsml",
        "<|dsml",
        "</||dsml",
        "</|dsml",
        "<tool_calls",
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
            candidate = text[match.start():]
            # Do not decode an incomplete numeric entity: html.unescape('&#6')
            # would consume it before the following '0;' arrives.
            candidate = re.sub(r"&(?:[a-zA-Z]*|#[xX]?[0-9a-fA-F]*)$", "", candidate)
            normalized = _normalize_marker(candidate)
            # A double-escaped entity may become incomplete after unescaping.
            normalized = re.sub(r"&(?:[a-z]*|#x?[0-9a-f]*)$", "", normalized)
            if any(
                prefix.startswith(normalized) or normalized.startswith(prefix)
                for prefix in cls._COMPACT_PREFIXES
            ):
                return match.start()
        return -1
