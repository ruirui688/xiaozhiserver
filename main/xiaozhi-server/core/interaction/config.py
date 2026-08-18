"""Configuration for deterministic interaction attention rules."""

from dataclasses import dataclass
from typing import Any, Mapping

from core.interaction.frames import AttentionMode


@dataclass(frozen=True)
class InteractionAttentionConfig:
    enabled: bool = False
    mode: AttentionMode = AttentionMode.SHADOW
    armed_window_ms: int = 8000
    follow_up_window_ms: int = 6000
    awaiting_reply_window_ms: int = 12000
    directed_threshold: float = 0.88
    undirected_threshold: float = 0.30
    barge_in_policy: str = "balanced"
    asr_confidence_floor: float = 0.20

    def __post_init__(self) -> None:
        if not isinstance(self.mode, AttentionMode):
            object.__setattr__(self, "mode", AttentionMode(self.mode))
        if not isinstance(self.enabled, bool):
            raise ValueError("enabled must be a boolean")
        if self.barge_in_policy not in {"strict", "balanced", "open"}:
            raise ValueError("barge_in_policy must be strict, balanced, or open")

        for name in (
            "armed_window_ms",
            "follow_up_window_ms",
            "awaiting_reply_window_ms",
        ):
            value = getattr(self, name)
            if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
                raise ValueError(f"{name} must be greater than zero")

        for name in (
            "directed_threshold",
            "undirected_threshold",
            "asr_confidence_floor",
        ):
            value = getattr(self, name)
            if not 0.0 <= value <= 1.0:
                raise ValueError(f"{name} must be between zero and one")

        if self.undirected_threshold >= self.directed_threshold:
            raise ValueError("undirected_threshold must be lower than directed_threshold")

    @property
    def effective_mode(self) -> AttentionMode:
        return self.mode if self.enabled else AttentionMode.DISABLED

    @classmethod
    def from_mapping(
        cls, config: Mapping[str, Any] | None
    ) -> "InteractionAttentionConfig":
        values = dict(config or {})
        return cls(
            enabled=values.get("enabled", False),
            mode=AttentionMode(values.get("mode", AttentionMode.SHADOW.value)),
            armed_window_ms=int(values.get("armed_window_ms", 8000)),
            follow_up_window_ms=int(values.get("follow_up_window_ms", 6000)),
            awaiting_reply_window_ms=int(values.get("awaiting_reply_window_ms", 12000)),
            directed_threshold=float(values.get("directed_threshold", 0.88)),
            undirected_threshold=float(values.get("undirected_threshold", 0.30)),
            barge_in_policy=str(values.get("barge_in_policy", "balanced")),
            asr_confidence_floor=float(values.get("asr_confidence_floor", 0.20)),
        )
