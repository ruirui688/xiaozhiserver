"""Connection-scoped interaction attention primitives."""

from core.interaction.attention_manager import InteractionAttentionManager
from core.interaction.config import InteractionAttentionConfig
from core.interaction.frames import (
    AddressingDecision,
    AddressingInput,
    AddressingResult,
    AttentionMode,
    InteractionState,
    InteractionTrigger,
)

__all__ = [
    "AddressingDecision",
    "AddressingInput",
    "AddressingResult",
    "AttentionMode",
    "InteractionAttentionConfig",
    "InteractionAttentionManager",
    "InteractionState",
    "InteractionTrigger",
]
