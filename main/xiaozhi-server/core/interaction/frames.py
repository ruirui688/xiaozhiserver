"""Shared data structures for single-channel interaction attention."""

from dataclasses import dataclass
from enum import Enum
from typing import Optional


class AttentionMode(str, Enum):
    DISABLED = "disabled"
    SHADOW = "shadow"
    ENFORCE = "enforce"


class InteractionState(str, Enum):
    DORMANT = "dormant"
    ARMED = "armed"
    ENGAGED = "engaged"
    AWAITING_REPLY = "awaiting_reply"
    SPEAKING = "speaking"
    AMBIGUOUS = "ambiguous"
    MEETING = "meeting"
    CALLING = "calling"
    ENROLLMENT = "enrollment"

    @property
    def is_protected(self) -> bool:
        return self in {
            InteractionState.MEETING,
            InteractionState.CALLING,
            InteractionState.ENROLLMENT,
        }


class AddressingDecision(str, Enum):
    DIRECTED = "directed"
    UNDIRECTED = "undirected"
    UNKNOWN_ADDRESSING = "unknown_addressing"


class InteractionTrigger(str, Enum):
    WAKE_WORD = "wake_word"
    STEERING_BUTTON = "steering_button"
    SCREEN_BUTTON = "screen_button"
    FOLLOW_UP = "follow_up"

    @property
    def is_explicit(self) -> bool:
        return self in {
            InteractionTrigger.WAKE_WORD,
            InteractionTrigger.STEERING_BUTTON,
            InteractionTrigger.SCREEN_BUTTON,
        }


@dataclass(frozen=True)
class AddressingInput:
    """Signals available after ASR for one user utterance.

    ``speaker_identity`` is retained as integration metadata only. It is never
    used to decide whether speech is directed at the assistant.
    """

    text: str = ""
    trigger: Optional[InteractionTrigger] = None
    asr_confidence: Optional[float] = None
    device_addressing_score: Optional[float] = None
    speaker_identity: Optional[str] = None


@dataclass(frozen=True)
class AddressingResult:
    decision: AddressingDecision
    reason: str
    mode: AttentionMode
    state_before: InteractionState
    state_after: InteractionState
    allow_processing: bool
    confidence: Optional[float] = None

    @property
    def shadow(self) -> bool:
        return self.mode == AttentionMode.SHADOW
