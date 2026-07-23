"""Semantic control center orchestration."""

import json
import uuid
from typing import Optional

from core.providers.tts.dto.dto import ContentType, TTSMessageDTO, SentenceType
from core.semantic.frames import Decision
from core.semantic.normalizer import TextNormalizer
from core.semantic.policy_engine import PolicyEngine
from core.semantic.router import SemanticRouter
from core.semantic.state_manager import StateManager
from core.utils.dialogue import Message


class SemanticCenter:
    """Pre-LLM deterministic controller for high-confidence voice operations."""

    def __init__(
        self,
        normalizer: Optional[TextNormalizer] = None,
        router: Optional[SemanticRouter] = None,
        state_manager: Optional[StateManager] = None,
        policy_engine: Optional[PolicyEngine] = None,
    ):
        self.normalizer = normalizer or TextNormalizer()
        self.router = router or SemanticRouter(self.normalizer)
        self.state_manager = state_manager or StateManager()
        self.policy_engine = policy_engine or PolicyEngine()

    async def handle_user_text(self, conn, text: str, turn_id=None) -> bool:
        asr_result = self.normalizer.normalize(conn, text)
        context = self.state_manager.get_context(conn)
        intent = self.router.route(asr_result, context)
        decision = self.policy_engine.decide(intent, context)

        conn.logger.bind(tag=__name__).debug(
            "handle_user_text text=%s intent=%s decision=%s reply=%s",
            asr_result.normalized_text, intent.full_name, decision.decision, decision.reply,
        )

        self.state_manager.record_intent(intent.full_name)
        if intent.full_name == "chat.normal" or decision.decision == "fallback_chat":
            conn.logger.bind(tag=__name__).debug(
                "handle_user_text fallthrough intent=%s decision=%s",
                intent.full_name, decision.decision,
            )
            return False

        from core.handle.sendAudioHandle import send_stt_message

        await send_stt_message(conn, asr_result.display_text)

        if decision.decision == "reject":
            conn.logger.bind(tag=__name__).debug("handle_user_text handled reject: %s", decision.reply)
            await self._speak(
                conn, decision.reply or "这个指令现在不能执行。", turn_id
            )
            self.state_manager.transition(decision.next_state)
            return True

        if decision.decision != "execute":
            conn.logger.bind(tag=__name__).debug("handle_user_text fallthrough decision=%s", decision.decision)
            return False

        if intent.full_name == "game.exit":
            conn.logger.bind(tag=__name__).debug("handle_user_text handled game.exit")
            if hasattr(conn, "game_session_manager") and conn.game_session_manager:
                conn.game_session_manager.clear_active_games()
            await self._speak(conn, decision.reply or "好的，游戏先到这。", turn_id)
            self.state_manager.transition(decision.next_state, None)
            return True

        if decision.executor:
            conn.logger.bind(tag=__name__).debug(
                "handle_user_text handled executor=%s arguments=%s",
                decision.executor, decision.arguments,
            )
            result = await self._invoke_tool(conn, decision, turn_id)
            await self._handle_tool_result(conn, result, decision, turn_id)
            return True

        return False

    async def _invoke_tool(self, conn, decision: Decision, turn_id=None):
        from plugins_func.register import Action, ActionResponse

        if not getattr(conn, "func_handler", None):
            return ActionResponse(Action.ERROR, response="工具系统还没有准备好。")
        if not conn.func_handler.has_tool(decision.executor):
            return ActionResponse(Action.NOTFOUND, response=f"工具 {decision.executor} 不存在。")

        tool_input = decision.arguments or {}
        from core.handle.reportHandle import enqueue_tool_report

        enqueue_tool_report(
            conn, decision.executor, tool_input, turn_id=turn_id
        )
        function_call_data = {
            "name": decision.executor,
            "id": str(uuid.uuid4().hex),
            "arguments": json.dumps(tool_input, ensure_ascii=False),
        }
        result = None
        try:
            result = await conn.func_handler.handle_llm_function_call(conn, function_call_data)
        except Exception as e:
            conn.logger.bind(tag=__name__).error(f"工具调用失败: {e}")
            return ActionResponse(Action.RESPONSE, response="工具调用出了点问题，请稍后再试。")
        report_result = result.result if result and result.result else result.response if result else None
        enqueue_tool_report(
            conn,
            decision.executor,
            tool_input,
            str(report_result) if report_result else None,
            report_tool_call=False,
            turn_id=turn_id,
        )
        return result or ActionResponse(Action.ERROR, response="工具没有返回结果。")

    async def _handle_tool_result(
        self, conn, result, decision: Decision, turn_id=None
    ) -> None:
        from plugins_func.register import Action

        if result.action in (Action.RESPONSE, Action.NOTFOUND, Action.ERROR):
            text = result.response or result.result or "已处理。"
            await self._speak(conn, str(text), turn_id)
            conn.dialogue.put(Message(role="assistant", content=str(text)))
        elif result.action == Action.REQLLM:
            text = result.result or result.response or ""
            if text:
                conn.executor.submit(
                    conn.chat, str(text), 0, False, None, turn_id
                )
        elif result.action == Action.RECORD:
            text = result.response or result.result
            if text:
                await self._speak(conn, str(text), turn_id)
                conn.dialogue.put(Message(role="assistant", content=str(text)))
        self.state_manager.transition(decision.next_state, decision.executor)

    async def _speak(self, conn, text: str, turn_id=None) -> None:
        if not text or not getattr(conn, "tts", None):
            return
        conn.client_abort = False
        conn.sentence_id = str(uuid.uuid4().hex)
        turn_metrics = getattr(conn, "turn_metrics", None)
        if turn_metrics is not None:
            turn_metrics.bind_sentence(conn.sentence_id, turn_id)
        conn.tts.store_tts_text(conn.sentence_id, text)
        conn.tts.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id=conn.sentence_id,
                sentence_type=SentenceType.FIRST,
                content_type=ContentType.ACTION,
            )
        )
        conn.tts.tts_one_sentence(
            conn,
            ContentType.TEXT,
            content_detail=text,
            sentence_id=conn.sentence_id,
        )
        conn.tts.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id=conn.sentence_id,
                sentence_type=SentenceType.LAST,
                content_type=ContentType.ACTION,
            )
        )
