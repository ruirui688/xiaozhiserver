"""Guards for model-emitted tool protocol text."""

import re


def looks_like_unhandled_tool_protocol(text: str) -> bool:
    if not text:
        return False
    compact = re.sub(r"\s+", "", text)
    upper = compact.upper()
    if "DSML" in upper and "TOOL_CALLS" in upper:
        return True
    return bool(re.search(r"<[^>]*tool_calls[^>]*>.*invoke\s+name=", text, re.I | re.S))
