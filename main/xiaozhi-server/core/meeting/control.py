"""Semantic control decisions for an active meeting."""

import asyncio
import json
import math
from dataclasses import dataclass

from core.meeting.minutes import MEETING_STARTED_PROMPT


MEETING_PLUGIN_NAME = "meeting_minutes"


@dataclass(frozen=True)
class MeetingControlDecision:
    decision: str
    confidence: float
    reason: str
    model_source: str
    raw_response: str = ""

    @property
    def should_end(self) -> bool:
        return self.decision == "end"


class MeetingControlClassifier:
    """Use the configured SLM/LLM to distinguish meeting speech from exit intent."""

    SYSTEM_PROMPT = """你是会议记录模式的控制意图裁决器。
当前系统正在静默记录会议。你只判断当前 ASR 文本是否明确要求助手结束记录并生成会议纪要。

只允许返回 JSON：
{"decision":"continue|end","confidence":0.0,"reason":"不超过16个汉字"}

判断规则：
- end：说话人明确在命令或请求助手现在停止会议记录、结束会议模式，或立即生成会议纪要。
- continue：普通会议内容、讨论未来何时结束、引用退出命令、询问为何退出、含糊表达或无法确认真实意图。
- 可以结合上下文理解 ASR 近音字、错别字和缺少标点，但不能仅因出现“结束”“退出”“纪要”等词就结束。
- 只有明确且当前生效的结束请求才给高置信度；宁可继续记录，也不要误结束会议。
"""

    async def classify(self, conn, text: str, meeting) -> MeetingControlDecision:
        model, model_source = self._select_model(conn)
        if model is None:
            return MeetingControlDecision(
                "continue", 0.0, "model_unavailable", "none"
            )

        config = self._config(conn)
        timeout = self._positive_float(config.get("control_timeout_seconds"), 1.5)
        threshold = self._bounded_float(
            config.get("end_confidence_threshold"), 0.9
        )
        user_prompt = self._build_user_prompt(text, meeting)
        task = None

        try:
            task = asyncio.create_task(
                asyncio.to_thread(
                    model.response_no_stream,
                    system_prompt=self.SYSTEM_PROMPT,
                    user_prompt=user_prompt,
                )
            )
            raw_response = await asyncio.wait_for(task, timeout=timeout)
        except asyncio.TimeoutError:
            if task is not None and not task.done():
                task.cancel()
            conn.logger.bind(tag=__name__).warning(
                f"会议控制语义裁决超时({timeout}s)，继续记录当前发言"
            )
            return MeetingControlDecision(
                "continue", 0.0, "model_timeout", model_source
            )
        except Exception as error:
            conn.logger.bind(tag=__name__).warning(
                "会议控制语义裁决失败，继续记录当前发言: "
                f"errorType={type(error).__name__}"
            )
            return MeetingControlDecision(
                "continue", 0.0, "model_error", model_source
            )

        return self._parse_response(raw_response, threshold, model_source)

    @staticmethod
    def _select_model(conn):
        slm = getattr(conn, "slm", None)
        if slm is not None:
            return slm, "slm"
        llm = getattr(conn, "llm", None)
        if llm is not None:
            return llm, "llm"
        return None, "none"

    @staticmethod
    def _config(conn) -> dict:
        config = getattr(conn, "config", {}) or {}
        meeting_config = config.get("meeting", {}) or {}
        result = dict(meeting_config) if isinstance(meeting_config, dict) else {}
        plugin_config = (config.get("plugins", {}) or {}).get(MEETING_PLUGIN_NAME)
        if isinstance(plugin_config, dict):
            result.update(plugin_config)
        return result

    @staticmethod
    def _build_user_prompt(text: str, meeting) -> str:
        transcript = meeting.build_transcript()
        recent = [
            {
                "speaker": item.get("displaySpeaker"),
                "text": str(item.get("text") or "")[-160:],
            }
            for item in transcript[-6:]
        ]
        payload = {
            "meeting_status": meeting.get_status(),
            "recent_transcript": recent,
            "current_asr_text": str(text or "").strip(),
        }
        return json.dumps(payload, ensure_ascii=False)

    def _parse_response(
        self, raw_response, threshold: float, model_source: str
    ) -> MeetingControlDecision:
        raw = str(raw_response or "").strip()
        data = self._loads_json(raw)
        decision = str(data.get("decision") or "").strip().lower()
        confidence = self._bounded_float(data.get("confidence"), 0.0)
        reason = str(data.get("reason") or "model_decision").strip()[:32]

        if decision == "end" and confidence >= threshold:
            return MeetingControlDecision(
                "end", confidence, reason, model_source, raw
            )
        if decision == "end":
            reason = f"low_confidence:{reason}"
        elif decision != "continue":
            reason = "invalid_model_decision"
        return MeetingControlDecision(
            "continue", confidence, reason, model_source, raw
        )

    @staticmethod
    def _loads_json(raw: str) -> dict:
        try:
            value = json.loads(raw)
            return value if isinstance(value, dict) else {}
        except json.JSONDecodeError:
            start = raw.find("{")
            end = raw.rfind("}")
            if start >= 0 and end > start:
                try:
                    value = json.loads(raw[start : end + 1])
                    return value if isinstance(value, dict) else {}
                except json.JSONDecodeError:
                    pass
        return {}

    @staticmethod
    def _positive_float(value, default: float) -> float:
        try:
            parsed = float(value)
        except (TypeError, ValueError):
            return default
        return parsed if math.isfinite(parsed) and parsed > 0 else default

    @staticmethod
    def _bounded_float(value, default: float) -> float:
        try:
            parsed = float(value)
        except (TypeError, ValueError):
            return default
        if not math.isfinite(parsed):
            return default
        return max(0.0, min(1.0, parsed))


def is_meeting_mode_enabled(conn) -> bool:
    config = getattr(conn, "config", {}) or {}
    plugins = config.get("plugins", {}) or {}
    if getattr(conn, "read_config_from_api", False):
        return isinstance(plugins, dict) and MEETING_PLUGIN_NAME in plugins
    meeting_config = config.get("meeting", {}) or {}
    if not isinstance(meeting_config, dict):
        return False
    return bool(meeting_config.get("enabled", True))


def get_meeting_entry_prompt(conn) -> str:
    config = MeetingControlClassifier._config(conn)
    prompt = str(config.get("entry_prompt") or "").strip()
    return prompt or MEETING_STARTED_PROMPT
