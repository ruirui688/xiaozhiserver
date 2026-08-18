import pytest

from core.interaction import (
    AddressingDecision,
    AddressingInput,
    AttentionMode,
    InteractionAttentionConfig,
    InteractionAttentionManager,
    InteractionState,
    InteractionTrigger,
)


class Clock:
    def __init__(self):
        self.now = 100.0

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


def manager(mode=AttentionMode.ENFORCE, **overrides):
    clock = Clock()
    config = InteractionAttentionConfig(enabled=True, mode=mode, **overrides)
    return InteractionAttentionManager(config=config, clock=clock), clock


@pytest.mark.parametrize(
    "trigger",
    [
        InteractionTrigger.WAKE_WORD,
        InteractionTrigger.STEERING_BUTTON,
        InteractionTrigger.SCREEN_BUTTON,
    ],
)
def test_explicit_trigger_is_directed(trigger):
    attention, _ = manager()

    result = attention.evaluate(AddressingInput(text="打开空调", trigger=trigger))

    assert result.decision == AddressingDecision.DIRECTED
    assert result.allow_processing is True
    assert result.state_after == InteractionState.ENGAGED


def test_activation_opens_window_for_first_utterance():
    attention, clock = manager(armed_window_ms=8000)
    attention.activate(InteractionTrigger.WAKE_WORD)
    clock.advance(7.9)

    result = attention.evaluate(
        AddressingInput(text="打开空调", asr_confidence=0.91)
    )

    assert result.decision == AddressingDecision.DIRECTED
    assert result.reason == "within_wake_window"


def test_pending_wake_window_is_not_consumed_by_protocol_speaking_marker():
    attention, _ = manager(armed_window_ms=8000)
    attention.activate(InteractionTrigger.WAKE_WORD)

    attention.mark_assistant_speaking()

    assert attention.state == InteractionState.ARMED


def test_expired_activation_does_not_accept_utterance():
    attention, clock = manager(armed_window_ms=8000)
    attention.activate(InteractionTrigger.WAKE_WORD)
    clock.advance(8.1)

    result = attention.evaluate(AddressingInput(text="晚上吃什么"))

    assert result.decision == AddressingDecision.UNDIRECTED
    assert result.allow_processing is False
    assert result.state_before == InteractionState.DORMANT


def test_assistant_question_opens_reply_window():
    attention, clock = manager(awaiting_reply_window_ms=12000)
    attention.mark_assistant_finished(asked_question=True)
    clock.advance(11.9)

    result = attention.evaluate(AddressingInput(text="二十四度"))

    assert result.decision == AddressingDecision.DIRECTED
    assert result.reason == "within_reply_window"


def test_low_asr_confidence_is_unknown_even_in_wake_window():
    attention, _ = manager(asr_confidence_floor=0.5)
    attention.activate(InteractionTrigger.WAKE_WORD)

    result = attention.evaluate(
        AddressingInput(text="打开空调", asr_confidence=0.2)
    )

    assert result.decision == AddressingDecision.UNKNOWN_ADDRESSING
    assert result.allow_processing is False
    assert result.state_after == InteractionState.AMBIGUOUS


def test_follow_up_without_device_evidence_is_unknown():
    attention, clock = manager(follow_up_window_ms=6000)
    attention.evaluate(
        AddressingInput(text="小智", trigger=InteractionTrigger.WAKE_WORD)
    )
    clock.advance(2)

    result = attention.evaluate(AddressingInput(text="你把手机递给我"))

    assert result.decision == AddressingDecision.UNKNOWN_ADDRESSING
    assert result.reason == "follow_up_requires_addressing_evidence"


def test_device_score_resolves_follow_up():
    attention, _ = manager()
    attention.evaluate(
        AddressingInput(text="小智", trigger=InteractionTrigger.WAKE_WORD)
    )

    directed = attention.evaluate(
        AddressingInput(text="调低一点", device_addressing_score=0.91)
    )
    undirected = attention.evaluate(
        AddressingInput(text="你去拿一下", device_addressing_score=0.1)
    )

    assert directed.decision == AddressingDecision.DIRECTED
    assert undirected.decision == AddressingDecision.UNDIRECTED
    assert undirected.allow_processing is False


def test_speaker_identity_never_changes_addressing_decision():
    first, _ = manager()
    second, _ = manager()

    known = first.evaluate(
        AddressingInput(text="晚上吃什么", speaker_identity="driver")
    )
    unknown = second.evaluate(
        AddressingInput(text="晚上吃什么", speaker_identity="UNKNOWN_SPEECH")
    )

    assert known.decision == AddressingDecision.UNDIRECTED
    assert unknown.decision == known.decision
    assert unknown.reason == known.reason


@pytest.mark.parametrize(
    "state",
    [
        InteractionState.MEETING,
        InteractionState.CALLING,
        InteractionState.ENROLLMENT,
    ],
)
def test_protected_states_block_normal_interaction(state):
    attention, _ = manager()
    attention.enter_protected_state(state)

    result = attention.evaluate(
        AddressingInput(
            text="打开空调",
            trigger=InteractionTrigger.WAKE_WORD,
            device_addressing_score=1.0,
        )
    )

    assert result.decision == AddressingDecision.UNDIRECTED
    assert result.reason == f"protected_state:{state.value}"
    assert result.allow_processing is False
    assert result.state_after == state


def test_shadow_mode_observes_rejection_but_allows_existing_pipeline():
    attention, _ = manager(mode=AttentionMode.SHADOW)

    result = attention.evaluate(AddressingInput(text="乘客之间的对话"))

    assert result.decision == AddressingDecision.UNDIRECTED
    assert result.shadow is True
    assert result.allow_processing is True


def test_disabled_mode_bypasses_attention_without_changing_state():
    attention, _ = manager(mode=AttentionMode.DISABLED)

    result = attention.evaluate(AddressingInput(text="任意输入"))

    assert result.decision == AddressingDecision.DIRECTED
    assert result.reason == "attention_disabled"
    assert result.state_before == InteractionState.DORMANT
    assert result.state_after == InteractionState.DORMANT
    assert result.allow_processing is True


def test_invalid_device_score_is_unknown():
    attention, _ = manager()

    result = attention.evaluate(
        AddressingInput(text="打开空调", device_addressing_score=1.2)
    )

    assert result.decision == AddressingDecision.UNKNOWN_ADDRESSING
    assert result.confidence is None


def test_config_mapping_and_validation():
    config = InteractionAttentionConfig.from_mapping(
        {
            "enabled": True,
            "mode": "shadow",
            "armed_window_ms": 5000,
            "follow_up_window_ms": 6000,
            "awaiting_reply_window_ms": 12000,
            "directed_threshold": 0.88,
            "undirected_threshold": 0.30,
            "barge_in_policy": "balanced",
            "asr_confidence_floor": 0.20,
        }
    )

    assert config.enabled is True
    assert config.mode == AttentionMode.SHADOW
    assert config.armed_window_ms == 5000
    assert config.barge_in_policy == "balanced"

    with pytest.raises(ValueError):
        InteractionAttentionConfig(
            undirected_threshold=0.9,
            directed_threshold=0.8,
        )


def test_protected_state_exit_requires_new_activation():
    attention, _ = manager()
    attention.enter_protected_state(InteractionState.CALLING)
    attention.leave_protected_state()

    result = attention.evaluate(AddressingInput(text="继续"))

    assert attention.state == InteractionState.DORMANT
    assert result.decision == AddressingDecision.UNDIRECTED
