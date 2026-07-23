"""Deterministic semantic router."""

import logging

from core.semantic.frames import ASRResult, IntentFrame, RobotContext

logger = logging.getLogger(__name__)
from core.semantic.normalizer import TextNormalizer


class SemanticRouter:
    """Route only high-confidence commands; leave the rest to legacy flow."""

    CONTINUE = {"继续", "接着", "接着说", "继续说", "往下说", "下一段"}
    GAME_EXIT = {"退出游戏", "结束游戏", "不玩了", "游戏结束", "结束", "不猜了", "换游戏", "换个游戏", "停"}
    GAME_HINT = {"提示", "给个提示", "我不会", "不会", "换一个"}

    def __init__(self, normalizer: TextNormalizer | None = None):
        self.normalizer = normalizer or TextNormalizer()

    def route(self, asr_result: ASRResult, context: RobotContext) -> IntentFrame:
        key = self.normalizer.route_key(asr_result.normalized_text)
        key = self._strip_wake_name(key)
        logger.debug("route key=%s normalized=%s", key, asr_result.normalized_text)

        if context.active_game_tool and key in self.GAME_EXIT:
            intent = IntentFrame(
                "game",
                "exit",
                0.98,
                {"tool": context.active_game_tool},
                source="state_rule",
            )
            logger.debug("route matched GAME_EXIT: %s tool=%s", intent.full_name, context.active_game_tool)
            return intent

        if context.active_game_tool:
            source = "state_rule" if key in self.CONTINUE or key in self.GAME_HINT else "game_context"
            # Escape hatch: if input looks like a non-game query, let it fall through to LLM
            non_game_indicators = {
                "几点", "时间", "几号", "星期几", "天气", "温度", "下雨",
                "新闻", "搜索", "什么是", "怎么", "为什么", "帮我", "计算",
                "翻译", "播放", "放歌", "唱歌", "音乐",
            }
            if any(indicator in key for indicator in non_game_indicators):
                intent = IntentFrame("chat", "normal", 0.5, {"text": asr_result.normalized_text}, source="game_escape")
                logger.debug("route game_escape (non-game indicator): %s", intent.full_name)
                return intent
            intent = IntentFrame(
                "game",
                "answer",
                0.92,
                {"tool": context.active_game_tool, "user_message": asr_result.normalized_text},
                source=source,
            )
            logger.debug("route matched active_game: %s tool=%s", intent.full_name, context.active_game_tool)
            return intent

        if key in self.CONTINUE:
            intent = IntentFrame("context", "continue", 0.9, source="rule")
            logger.debug("route matched CONTINUE: %s", intent.full_name)
            return intent

        intent = IntentFrame("chat", "normal", 0.5, {"text": asr_result.normalized_text}, source="fallback")
        logger.debug("route fallback: %s", intent.full_name)
        return intent

    def _strip_wake_name(self, key: str) -> str:
        for prefix in ("你好小智", "小智你好", "小智"):
            if key.startswith(prefix.lower()):
                return key[len(prefix):]
            if key.startswith(prefix):
                return key[len(prefix):]
        return key
