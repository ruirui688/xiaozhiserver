"""Deterministic semantic router."""

from core.semantic.frames import ASRResult, IntentFrame, RobotContext
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
    DRINK_INTENT_TERMS = (
        "酒",
        "鸡尾酒",
        "特调",
        "微醺",
        "喝什么",
        "来一杯",
    )
    DRINK_RECOMMEND_TERMS = (
        "推荐",
        "介绍",
        "适合",
        "好喝",
        "拍照",
        "好看",
        "颜值",
        "招牌",
        "特色",
        "明星款",
    )

    def __init__(self, normalizer: TextNormalizer | None = None):
        self.normalizer = normalizer or TextNormalizer()

    def route(self, asr_result: ASRResult, context: RobotContext) -> IntentFrame:
        key = self.normalizer.route_key(asr_result.normalized_text)
        key = self._strip_wake_name(key)

        if key in self.STOP_ALL:
            return IntentFrame("interrupt", "stop_all", 0.99, source="rule")

        if self._is_drink_recommendation(key):
            return IntentFrame(
                "bar",
                "recommend_drink",
                0.94,
                {"preference": asr_result.normalized_text},
                source="bar_rule",
            )

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

    def _is_drink_recommendation(self, key: str) -> bool:
        has_drink_term = any(term in key for term in self.DRINK_INTENT_TERMS)
        has_recommend_term = any(term in key for term in self.DRINK_RECOMMEND_TERMS)
        return has_drink_term and has_recommend_term

    def _strip_wake_name(self, key: str) -> str:
        for prefix in ("小智", "你好小智", "小智你好"):
            if key.startswith(prefix.lower()):
                return key[len(prefix):]
            if key.startswith(prefix):
                return key[len(prefix):]
        return key
