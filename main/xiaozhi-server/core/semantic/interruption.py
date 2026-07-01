"""LLM-based interruption judgment for overlap speech."""

import asyncio
import json
from dataclasses import dataclass

from core.semantic.frames import RobotContext


@dataclass(frozen=True)
class InterruptionClassification:
    category: str
    reason: str
    raw_response: str = ""

    @property
    def should_abort(self) -> bool:
        return self.category in {"interrupt", "new_turn"}


class SemanticInterruptionClassifier:
    """Ask the configured LLM whether overlap speech should interrupt TTS."""

    SYSTEM_PROMPT = """你是语音助手的实时打断裁决器。
当前助手正在播报，用户中途说了一句话。你必须只判断是否应该打断当前播报。

只允许返回 JSON，不要返回解释文字。JSON 格式：
{"decision":"continue|interrupt|new_turn","reason":"不超过12个汉字"}

decision 含义：
- continue：用户只是附和、确认、轻声反馈、无有效新意图，当前播报应该继续。
- interrupt：用户明确要求停止、别说、暂停、打断当前播报。
- new_turn：用户提出新问题、纠正、补充、转移话题，应该打断当前播报并处理新输入。

不要基于固定词表判断，要结合用户文本和少量上下文判断真实意图。"""

    async def classify(
        self,
        conn,
        text: str,
        context: RobotContext,
    ) -> InterruptionClassification:
        config = self._config(conn)
        if not getattr(conn, "llm", None):
            return InterruptionClassification("new_turn", "llm_unavailable")

        user_prompt = self._build_user_prompt(conn, text, context)
        timeout = float(config.get("timeout_seconds", 1.5))

        try:
            # 创建可取消的线程任务，避免超时后线程继续运行
            thread_future = asyncio.to_thread(
                conn.llm.response_no_stream,
                system_prompt=self.SYSTEM_PROMPT,
                user_prompt=user_prompt,
            )
            raw_response = await asyncio.wait_for(thread_future, timeout=timeout)
        except asyncio.TimeoutError:
            conn.logger.bind(tag=__name__).warning(
                f"语义打断大模型裁决超时({timeout}s)，按新用户轮次处理"
            )
            # 取消底层线程任务（asyncio.to_thread 返回的 Future 可取消）
            if not thread_future.done():
                thread_future.cancel()
            return InterruptionClassification("new_turn", "llm_timeout")
        except Exception as e:
            conn.logger.bind(tag=__name__).warning(
                f"语义打断大模型裁决失败，按新用户轮次处理: {e}"
            )
            return InterruptionClassification("new_turn", "llm_error")

        return self._parse_response(raw_response)

    def _config(self, conn) -> dict:
        return getattr(conn, "config", {}).get("semantic_interruption", {}) or {}

    def _build_user_prompt(self, conn, text: str, context: RobotContext) -> str:
        payload = {
            "assistant_state": {
                "tts_playing": True,
                "state": getattr(context, "state", None),
                "current_skill": getattr(context, "current_skill", None),
                "active_game_tool": getattr(context, "active_game_tool", None),
                "last_intent": getattr(context, "last_intent", None),
            },
            "recent_dialogue": self._recent_dialogue(conn),
            "overlap_user_text": text,
        }
        return json.dumps(payload, ensure_ascii=False)

    def _recent_dialogue(self, conn) -> list[dict]:
        dialogue = getattr(getattr(conn, "dialogue", None), "dialogue", [])
        recent = []
        for message in reversed(dialogue):
            if message.role not in {"user", "assistant"} or not message.content:
                continue
            recent.append({"role": message.role, "content": str(message.content)[-120:]})
            if len(recent) >= 4:
                break
        return list(reversed(recent))

    def _parse_response(self, raw_response: str) -> InterruptionClassification:
        raw = (raw_response or "").strip()
        data = self._loads_json(raw)
        decision = str(data.get("decision", "")).strip().lower()
        reason = str(data.get("reason", "")).strip() or "llm_decision"

        if decision == "continue":
            return InterruptionClassification("continue", reason, raw)
        if decision == "interrupt":
            return InterruptionClassification("interrupt", reason, raw)
        if decision == "new_turn":
            return InterruptionClassification("new_turn", reason, raw)
        return InterruptionClassification("new_turn", "invalid_llm_decision", raw)

    def _loads_json(self, raw: str) -> dict:
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            start = raw.find("{")
            end = raw.rfind("}")
            if start >= 0 and end > start:
                try:
                    return json.loads(raw[start : end + 1])
                except json.JSONDecodeError:
                    pass
        return {}
