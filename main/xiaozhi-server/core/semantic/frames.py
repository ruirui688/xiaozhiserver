"""Shared semantic data structures."""

from dataclasses import dataclass, field
from typing import Any, Dict, Optional


@dataclass
class ASRResult:
    session_id: str
    device_id: Optional[str]
    raw_text: str
    normalized_text: str
    display_text: str
    asr_confidence: Optional[float] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class RobotContext:
    device_id: Optional[str]
    session_id: str
    state: str
    current_skill: Optional[str] = None
    tts_playing: bool = False
    motion_running: bool = False
    active_game_tool: Optional[str] = None
    client_is_speaking: bool = False
    client_listen_mode: str = "auto"
    last_intent: Optional[str] = None


@dataclass
class IntentFrame:
    domain: str
    intent: str
    confidence: float
    slots: Dict[str, Any] = field(default_factory=dict)
    source: str = "rule"

    @property
    def full_name(self) -> str:
        return f"{self.domain}.{self.intent}"


@dataclass
class Decision:
    decision: str
    priority: int = 0
    executor: Optional[str] = None
    arguments: Dict[str, Any] = field(default_factory=dict)
    reply: Optional[str] = None
    next_state: Optional[str] = None
    reason: Optional[str] = None

