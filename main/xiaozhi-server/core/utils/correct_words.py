"""Apply agent-level correction words to runtime user text."""

import json
from typing import Any, Dict


def parse_correct_words(correct_words: Any) -> Dict[str, str]:
    """Parse correction words from manager-api/TTS config formats."""
    replacements: Dict[str, str] = {}
    if not correct_words:
        return replacements

    if isinstance(correct_words, dict):
        for source, target in correct_words.items():
            _add_replacement(replacements, source, target)
        return replacements

    if not isinstance(correct_words, list):
        return replacements

    for item in correct_words:
        if isinstance(item, str):
            source, separator, target = item.partition("|")
            if separator:
                _add_replacement(replacements, source, target)
        elif isinstance(item, dict):
            source = (
                item.get("sourceWord")
                or item.get("source_word")
                or item.get("source")
                or item.get("from")
            )
            target = (
                item.get("targetWord")
                or item.get("target_word")
                or item.get("target")
                or item.get("to")
            )
            _add_replacement(replacements, source, target)

    return replacements


def apply_correct_words(conn, text: str) -> str:
    replacements = getattr(conn, "correct_word_replacements", None)
    if replacements is None:
        replacements = parse_correct_words(conn.config.get("correct_words"))
        conn.correct_word_replacements = replacements
    if not replacements:
        return text

    corrected = replace_text(text, replacements)
    if corrected != text:
        conn.logger.bind(tag=__name__).info(f"应用替换词: {text} -> {corrected}")
    return corrected


def replace_text(text: str, replacements: Dict[str, str]) -> str:
    if not isinstance(text, str):
        return text

    stripped = text.strip()
    if stripped.startswith("{") and stripped.endswith("}"):
        try:
            payload = json.loads(stripped)
        except json.JSONDecodeError:
            return _replace_plain_text(text, replacements)
        if isinstance(payload, dict) and "content" in payload:
            payload["content"] = _replace_plain_text(
                str(payload.get("content") or ""), replacements
            )
            return json.dumps(payload, ensure_ascii=False)

    return _replace_plain_text(text, replacements)


def _replace_plain_text(text: str, replacements: Dict[str, str]) -> str:
    corrected = text
    for source, target in sorted(replacements.items(), key=lambda item: len(item[0]), reverse=True):
        corrected = corrected.replace(source, target)
    return corrected


def _add_replacement(replacements: Dict[str, str], source: Any, target: Any) -> None:
    source_text = str(source or "").strip()
    target_text = str(target or "").strip()
    if source_text and target_text:
        replacements[source_text] = target_text
