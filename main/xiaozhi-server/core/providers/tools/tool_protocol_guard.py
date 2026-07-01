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
