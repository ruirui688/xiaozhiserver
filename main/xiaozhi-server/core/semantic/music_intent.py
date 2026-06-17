"""Deterministic parser for music-domain voice commands."""

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class MusicIntent:
    action: str
    song_name: str = "random"
    reason: str = ""


MUSIC_LIBRARY_QUERIES = (
    "有什么歌",
    "有哪些歌",
    "有啥歌",
    "歌单",
    "曲库",
    "音乐库",
    "有哪些音乐",
    "有什么音乐",
)
MUSIC_CHANGE_PHRASES = (
    "换一首",
    "换首",
    "换个",
    "换一个",
    "换1",
    "下一首",
    "下首",
    "重新播放",
    "重播一首",
)
MUSIC_CONTEXT_REPEAT_PHRASES = (
    "再放一遍",
    "再播放一遍",
    "再来一遍",
    "再听一遍",
    "放一遍吧",
    "给我放一遍",
    "播放一遍",
    "来一遍",
    "听一遍",
    "放原版",
    "播放原版",
    "原版",
)
MUSIC_PLAY_TRIGGERS = (
    "重新播放",
    "随机播放音乐",
    "播放一遍",
    "再播放一遍",
    "播放音乐",
    "播放歌曲",
    "播放",
    "放一首",
    "放一下",
    "放一遍",
    "再放一遍",
    "放首",
    "放",
    "听一首",
    "听一下",
    "听一遍",
    "再听一遍",
    "听首",
    "听",
    "来一首",
    "来一遍",
    "再来一遍",
    "来首",
    "唱一首",
    "唱一下",
    "唱一遍",
    "唱首",
    "唱",
    "哼一遍",
    "哼两句",
    "哼",
)
MUSIC_GENERIC_OBJECTS = {"歌", "歌曲", "音乐", "曲", "主题曲", "一首歌", "首歌"}
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
SONG_CANDIDATE_PARTICLES = {
    "了",
    "啦",
    "吧",
    "啊",
    "呀",
    "嘛",
    "呢",
    "呗",
    "哈",
}


def parse_music_intent(text, is_music_context=False, last_song_name=None):
    """Parse deterministic music commands before falling back to the LLM."""
    normalized_text = normalize_music_text(text)
    if not normalized_text:
        return None

    if any(keyword in normalized_text for keyword in MUSIC_LIBRARY_QUERIES):
        return MusicIntent(action="library", reason="library_query")

    if _looks_like_non_music_control(normalized_text):
        return None
    if _looks_like_non_music_expression(normalized_text):
        return None

    stop_reason = _detect_music_stop(normalized_text, is_music_context)
    if stop_reason:
        return MusicIntent(action="stop", reason=stop_reason)

    if is_music_context and any(phrase in normalized_text for phrase in MUSIC_CHANGE_PHRASES):
        return MusicIntent(action="play", song_name="random", reason="music_context_change")

    trigger = _find_music_trigger(normalized_text)
    if trigger:
        song_name = _extract_song_name_after_trigger(normalized_text, trigger)
        if song_name == "random" and is_music_context and _is_repeat_request(normalized_text):
            song_name = last_song_name or "random"
        return MusicIntent(action="play", song_name=song_name, reason=f"trigger:{trigger}")

    if is_music_context and _is_repeat_request(normalized_text):
        return MusicIntent(
            action="play",
            song_name=last_song_name or "random",
            reason="music_context_repeat",
        )

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


def _find_music_trigger(normalized_text):
    for trigger in sorted(MUSIC_PLAY_TRIGGERS, key=len, reverse=True):
        if trigger in normalized_text:
            return trigger
    return None


def _extract_song_name_after_trigger(normalized_text, trigger):
    text = normalized_text.split(trigger, 1)[1] if trigger in normalized_text else ""
    text = _cleanup_song_candidate(text)
    if not text and trigger in {"播放音乐", "播放歌曲", "随机播放音乐"}:
        return "random"
    if not text and _trigger_contains_generic_music_object(trigger):
        return "random"
    return text or "random"


def _cleanup_song_candidate(text):
    text = re.sub(r"^(你|请|帮我|给我|我想|想|要|把|一首|首|个|一个|一下|一遍|再)", "", text)
    text = re.sub(r"(给我|给您|给你)?(听听|听|播放|放|唱|哼)?$", "", text)
    text = re.sub(
        r"(这首歌|这首|这歌|歌曲|音乐|歌|曲|原版|旋律|一遍|一下|吧|呀|啊|哈|啦|嘛|呢|呗|了)+$",
        "",
        text,
    )
    if text in MUSIC_GENERIC_OBJECTS or text in SONG_CANDIDATE_PARTICLES:
        return "random"
    return text


def _trigger_contains_generic_music_object(trigger):
    return any(obj in trigger for obj in MUSIC_GENERIC_OBJECTS)


def _is_repeat_request(normalized_text):
    return any(phrase in normalized_text for phrase in MUSIC_CONTEXT_REPEAT_PHRASES)


def _looks_like_non_music_control(normalized_text):
    if "放" not in normalized_text:
        return False
    has_music_language = any(term in normalized_text for term in ("歌", "歌曲", "音乐", "曲", "原版", "唱", "听"))
    return not has_music_language and any(term in normalized_text for term in NON_MUSIC_CONTROL_TERMS)


def _looks_like_non_music_expression(normalized_text):
    return any(expression in normalized_text for expression in NON_MUSIC_EXPRESSIONS)
