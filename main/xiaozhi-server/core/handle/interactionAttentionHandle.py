"""Bridge device and session signals into interaction-attention decisions."""

import inspect
import math
from typing import Any, Mapping

from core.interaction import (
    AddressingDecision,
    AddressingInput,
    AttentionMode,
    InteractionAttentionConfig,
    InteractionAttentionManager,
    InteractionState,
    InteractionTrigger,
)


TAG = __name__
_ALLOWED_METADATA_KEYS = {
    "trigger",
    "asr_confidence",
    "addressing_score",
    "device_addressing_score",
    "dds_score",
    "speaker_id",
}


def get_interaction_attention(conn):
    attention = getattr(conn, "interaction_attention", None)
    if attention is not None:
        return attention
    raw_config = (getattr(conn, "config", None) or {}).get(
        "interaction_attention"
    )
    raw_mapping = raw_config if isinstance(raw_config, Mapping) else {}
    try:
        config = InteractionAttentionConfig.from_mapping(raw_config)
    except (OverflowError, TypeError, ValueError) as error:
        mode = str(raw_mapping.get("mode", "disabled")).lower()
        conn.logger.bind(tag=TAG).error(
            "交互注意力配置无效: "
            f"mode={mode}, errorType={type(error).__name__}"
        )
        config = InteractionAttentionConfig(
            enabled=mode == AttentionMode.ENFORCE.value,
            mode=(
                AttentionMode.ENFORCE
                if mode == AttentionMode.ENFORCE.value
                else AttentionMode.SHADOW
            ),
        )
    attention = InteractionAttentionManager(config=config)
    conn.interaction_attention = attention
    return attention


def parse_interaction_metadata(message: Mapping[str, Any]) -> dict[str, Any] | None:
    raw = message.get("interaction")
    if raw is None:
        return None
    if not isinstance(raw, Mapping) or any(
        key not in _ALLOWED_METADATA_KEYS for key in raw
    ):
        raise ValueError("invalid_interaction_metadata")
    metadata = dict(raw)
    trigger = metadata.get("trigger")
    valid_triggers = {item.value for item in InteractionTrigger}
    if trigger is not None and trigger != "" and (
        not isinstance(trigger, str) or trigger not in valid_triggers
    ):
        raise ValueError("invalid_interaction_metadata")
    for key in (
        "asr_confidence",
        "addressing_score",
        "device_addressing_score",
        "dds_score",
    ):
        value = metadata.get(key)
        if value is None:
            continue
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError("invalid_interaction_metadata")
        if not math.isfinite(float(value)) or not 0.0 <= float(value) <= 1.0:
            raise ValueError("invalid_interaction_metadata")
    speaker_id = metadata.get("speaker_id")
    if speaker_id is not None and not isinstance(speaker_id, str):
        raise ValueError("invalid_interaction_metadata")
    return metadata


def activate_interaction_attention(conn, metadata) -> bool:
    if not metadata:
        return False
    trigger = metadata.get("trigger")
    if trigger not in {
        InteractionTrigger.WAKE_WORD.value,
        InteractionTrigger.STEERING_BUTTON.value,
        InteractionTrigger.SCREEN_BUTTON.value,
    }:
        return False
    attention = get_interaction_attention(conn)
    if isinstance(attention, InteractionAttentionManager):
        attention.activate(InteractionTrigger(trigger))
    else:
        attention.activate(metadata)
    return True


async def evaluate_interaction_attention(
    conn,
    text,
    *,
    turn_context=None,
    turn_id=None,
    interaction_metadata=None,
    speaker_identity=None,
):
    attention = get_interaction_attention(conn)
    metadata = interaction_metadata or getattr(
        conn, "pending_interaction_metadata", None
    )
    conn.pending_interaction_metadata = None
    try:
        if hasattr(attention, "evaluate_turn"):
            result = attention.evaluate_turn(
                text,
                turn_context=turn_context,
                turn_id=turn_id,
                interaction=metadata,
            )
            if inspect.isawaitable(result):
                result = await result
        else:
            metadata = metadata or {}
            score = metadata.get("addressing_score")
            if score is None:
                score = metadata.get(
                    "device_addressing_score", metadata.get("dds_score")
                )
            result = attention.evaluate(
                AddressingInput(
                    text=text,
                    trigger=metadata.get("trigger"),
                    asr_confidence=metadata.get("asr_confidence"),
                    device_addressing_score=score,
                    speaker_identity=metadata.get("speaker_id") or speaker_identity,
                )
            )
    except Exception as error:
        mode = _attention_mode(attention)
        conn.logger.bind(tag=TAG).error(
            "交互注意力判断失败: "
            f"mode={mode}, errorType={type(error).__name__}"
        )
        return mode != AttentionMode.ENFORCE.value

    decision = str(getattr(result.decision, "value", result.decision)).lower()
    mode = (
        str(getattr(result.mode, "value", result.mode)).lower()
        if hasattr(result, "mode")
        else _attention_mode(attention)
    )
    allow_processing = getattr(
        result,
        "allow_processing",
        mode != AttentionMode.ENFORCE.value
        or decision == AddressingDecision.DIRECTED.value,
    )
    conn.logger.bind(tag=TAG).info(
        "交互注意力决策: "
        f"mode={mode}, decision={decision}, "
        f"reason={getattr(result, 'reason', '')}, textLength={len(text or '')}"
    )
    return bool(allow_processing)


def allow_immediate_barge_in(conn) -> bool:
    attention = getattr(conn, "interaction_attention", None)
    if attention is None:
        attention = get_interaction_attention(conn)
    if _attention_mode(attention) != AttentionMode.ENFORCE.value:
        return True
    config = getattr(attention, "config", None)
    policy = getattr(config, "barge_in_policy", "balanced")
    if policy == "open":
        return True
    if policy == "strict":
        return False
    state = getattr(attention, "state", InteractionState.DORMANT)
    try:
        state = InteractionState(str(getattr(state, "value", state)).lower())
    except ValueError:
        return False
    return state in {
        InteractionState.ARMED,
        InteractionState.ENGAGED,
        InteractionState.AWAITING_REPLY,
    }


def mark_assistant_speaking(conn) -> None:
    marker = getattr(
        getattr(conn, "interaction_attention", None),
        "mark_assistant_speaking",
        None,
    )
    if callable(marker):
        marker()


def mark_assistant_finished(conn, text) -> None:
    marker = getattr(
        getattr(conn, "interaction_attention", None),
        "mark_assistant_finished",
        None,
    )
    if callable(marker):
        marker(asked_question=str(text or "").rstrip().endswith(("?", "？")))


def _attention_mode(attention) -> str:
    config = getattr(attention, "config", None)
    mode = getattr(config, "effective_mode", None)
    if mode is None:
        mode = getattr(attention, "mode", None)
    if mode is None:
        mode = getattr(config, "mode", AttentionMode.DISABLED)
    return str(getattr(mode, "value", mode)).lower()
