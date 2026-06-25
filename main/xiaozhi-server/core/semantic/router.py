"""Deterministic semantic router."""

import re
from core.semantic.frames import ASRResult, IntentFrame, RobotContext
from core.semantic.music_intent import parse_music_intent
from core.semantic.normalizer import TextNormalizer


class SemanticRouter:
    """Route only high-confidence commands; leave the rest to legacy flow."""

    STOP_ALL = {
        "停",
        "停止",
        "停一下",
        "暂停",
        "别动",
        "别说了",
        "不要说了",
        "闭嘴",
        "行了",
        "行了行了",
        "停止表演",
        "停止动作",
        "你先停一下",
        "你先别说了",
        "你先别演了",
    }
    CONTINUE = {"继续", "接着", "接着说", "继续说", "往下说", "下一段"}
    GAME_EXIT = {"退出游戏", "结束游戏", "不玩了", "游戏结束"}
    GAME_HINT = {"提示", "给个提示", "我不会", "不会", "换一个"}

    # ------------------------------------------------------------------
    # Relative volume adjustment patterns (intercepted before the LLM)
    #
    # These match common spoken forms for "turn it up/down a bit".
    # Absolute-setting commands ("设置声音为50") deliberately fall
    # through to the LLM so it can use self_audio_speaker_set_volume
    # with a precise value.
    # ------------------------------------------------------------------
    VOLUME_DOWN_PATTERN = re.compile(
        r"(?:小声(?:一)?[点些]|声音小(?:一)?[点些]|音量(?:调小|减小|小(?:一)?[点些])"
        r"|调小(?:声音|音量)|声音调小|音量关小"
        r"|太(?:大|吵|响)声?了|太大了)"
    )
    VOLUME_UP_PATTERN = re.compile(
        r"(?:大声(?:一)?[点些]|声音大(?:一)?[点些]|音量(?:调大|加大|大(?:一)?[点些])"
        r"|调大(?:声音|音量)|声音调大|音量开大"
        r"|太(?:小|轻|低)声?了|太小了)"
    )

    def __init__(self, normalizer: TextNormalizer | None = None):
        self.normalizer = normalizer or TextNormalizer()

    def route(self, asr_result: ASRResult, context: RobotContext) -> IntentFrame:
        key = self.normalizer.route_key(asr_result.normalized_text)
        key = self._strip_wake_name(key)

        if key in self.STOP_ALL:
            return IntentFrame("interrupt", "stop_all", 0.99, source="rule")

        music_intent = parse_music_intent(
            key,
            is_music_context=context.current_skill == "music"
            or context.last_intent in {"music.play", "music.stop", "music.library"},
        )
        if music_intent and music_intent.action == "stop":
            return IntentFrame(
                "music",
                "stop",
                0.99,
                {"reason": music_intent.reason},
                source="music_rule",
            )

        # Relative volume commands — route to deterministic device tools
        # before the LLM gets a chance to pick an absolute-setter tool.
        if self.VOLUME_DOWN_PATTERN.search(key):
            return IntentFrame("device", "volume_down", 0.98, source="rule")
        if self.VOLUME_UP_PATTERN.search(key):
            return IntentFrame("device", "volume_up", 0.98, source="rule")

        if context.active_game_tool and key in self.GAME_EXIT:
            return IntentFrame(
                "game",
                "exit",
                0.98,
                {"tool": context.active_game_tool},
                source="state_rule",
            )

        if context.active_game_tool:
            source = "state_rule" if key in self.CONTINUE or key in self.GAME_HINT else "game_context"
            return IntentFrame(
                "game",
                "answer",
                0.92,
                {"tool": context.active_game_tool, "user_message": asr_result.normalized_text},
                source=source,
            )

        if key in self.CONTINUE:
            return IntentFrame("context", "continue", 0.9, source="rule")

        return IntentFrame("chat", "normal", 0.5, {"text": asr_result.normalized_text}, source="fallback")

    def _strip_wake_name(self, key: str) -> str:
        for prefix in ("小智", "你好小智", "小智你好"):
            if key.startswith(prefix.lower()):
                return key[len(prefix):]
            if key.startswith(prefix):
                return key[len(prefix):]
        return key
