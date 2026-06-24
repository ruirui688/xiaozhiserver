"""Deterministic parser for music-domain voice commands."""

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class MusicIntent:
    action: str
    song_name: str = "random"
    reason: str = ""


NON_MUSIC_CONTROL_TERMS = (
    "音量",
    "声音",
    "亮度",
    "屏幕",
    "灯",
    "空调",
)
NON_MUSIC_EXPRESSIONS = (
    "听话",
    "听说",
    "放心",
    "放弃",
    "放学",
    "放假",
)
MUSIC_TERMS = ("歌", "歌曲", "音乐", "曲", "这首", "原版")
MUSIC_PLAY_VERBS = ("播放", "播", "放", "听", "唱", "哼")
MUSIC_STOP_OPERATORS = ("停止", "暂停", "停下", "停掉", "关掉", "关闭", "取消")
MUSIC_NEGATION_OPERATORS = ("不要", "别再", "别", "不想", "不用", "不")
MUSIC_CONTEXT_STOP_PHRASES = (
    "停一下",
    "暂停",
    "停掉",
    "关了",
    "关掉",
    "不要了",
    "算了",
)
def parse_music_intent(text, is_music_context=False, last_song_name=None):
    """Parse only deterministic music stop commands before falling back to the LLM."""
    normalized_text = normalize_music_text(text)
    if not normalized_text:
        return None

    if _looks_like_non_music_control(normalized_text):
        return None
    if _looks_like_non_music_expression(normalized_text):
        return None

    stop_reason = _detect_music_stop(normalized_text, is_music_context)
    if stop_reason:
        return MusicIntent(action="stop", reason=stop_reason)

    return None


def normalize_music_text(text):
    return re.sub(r"[\s，。！？!?,.;；：“”\"'、~～…]+", "", text or "")


def _detect_music_stop(normalized_text, is_music_context):
    if _has_explicit_music_stop(normalized_text):
        return "explicit_music_stop"
    if _has_negative_music_action(normalized_text, is_music_context):
        return "negative_music_action"
    if is_music_context and any(phrase in normalized_text for phrase in MUSIC_CONTEXT_STOP_PHRASES):
        return "music_context_stop"
    return ""


def _has_explicit_music_stop(normalized_text):
    has_stop_operator = any(operator in normalized_text for operator in MUSIC_STOP_OPERATORS)
    if not has_stop_operator:
        return False
    has_music_object = any(term in normalized_text for term in MUSIC_TERMS)
    has_play_verb = any(verb in normalized_text for verb in MUSIC_PLAY_VERBS)
    return has_music_object or has_play_verb


def _has_negative_music_action(normalized_text, is_music_context):
    for operator in MUSIC_NEGATION_OPERATORS:
        start = 0
        while True:
            index = normalized_text.find(operator, start)
            if index < 0:
                break
            after_operator = normalized_text[index + len(operator) : index + len(operator) + 6]
            if (
                any(verb in after_operator for verb in MUSIC_PLAY_VERBS)
                and _has_music_stop_scope(after_operator, is_music_context)
            ):
                return True
            start = index + len(operator)
    return False


def _has_music_stop_scope(after_operator, is_music_context):
    if "听话" in after_operator:
        return False
    if is_music_context:
        return True
    if any(term in after_operator for term in MUSIC_TERMS):
        return True
    return bool(re.fullmatch(r"(播放|播|放|听|唱|哼)(了|啦|吧|啊|呀)?", after_operator))


def _looks_like_non_music_control(normalized_text):
    if "放" not in normalized_text:
        return False
    has_music_language = any(term in normalized_text for term in ("歌", "歌曲", "音乐", "曲", "原版", "唱", "听"))
    return not has_music_language and any(term in normalized_text for term in NON_MUSIC_CONTROL_TERMS)


def _looks_like_non_music_expression(normalized_text):
    return any(expression in normalized_text for expression in NON_MUSIC_EXPRESSIONS)
