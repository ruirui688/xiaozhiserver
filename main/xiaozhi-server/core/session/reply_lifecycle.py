"""Generation-scoped assistant reply lifecycle for one device connection."""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from enum import Enum
from typing import Optional


class ReplyState(str, Enum):
    IDLE = "IDLE"
    PENDING_LLM = "PENDING_LLM"
    SYNTHESIZING = "SYNTHESIZING"
    STREAMING = "STREAMING"
    STOPPING = "STOPPING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    INTERRUPTED = "INTERRUPTED"


ACTIVE_REPLY_STATES = {
    ReplyState.PENDING_LLM,
    ReplyState.SYNTHESIZING,
    ReplyState.STREAMING,
    ReplyState.STOPPING,
}


@dataclass(frozen=True)
class ReplySnapshot:
    generation: int
    state: ReplyState
    started_at: float
    sentence_id: Optional[str]
    last_progress_at: float
    terminal_reason: str
    kind: str

    @property
    def is_active(self) -> bool:
        return self.state in ACTIVE_REPLY_STATES

    @property
    def age_seconds(self) -> float:
        if not self.started_at:
            return 0.0
        return max(0.0, time.monotonic() - self.started_at)


class ReplyLifecycle:
    """Keep reply state convergent across the event loop and worker threads."""

    def __init__(self):
        self._lock = threading.RLock()
        self._generation = 0
        self._state = ReplyState.IDLE
        self._started_at = 0.0
        self._sentence_id = None
        self._last_progress_at = 0.0
        self._terminal_reason = ""
        self._kind = ""

    def begin(self, *, kind: str = "assistant") -> int:
        with self._lock:
            if self._state in ACTIVE_REPLY_STATES:
                raise RuntimeError(
                    "cannot begin a reply while another generation is active"
                )
            now = time.monotonic()
            self._generation += 1
            self._state = ReplyState.PENDING_LLM
            self._started_at = now
            self._sentence_id = None
            self._last_progress_at = now
            self._terminal_reason = ""
            self._kind = kind
            return self._generation

    def ensure_started(
        self, sentence_id: Optional[str] = None, *, kind: str = "assistant"
    ) -> int:
        with self._lock:
            if self._state not in ACTIVE_REPLY_STATES:
                generation = self.begin(kind=kind)
            else:
                generation = self._generation
            if sentence_id and not self._sentence_id:
                self._sentence_id = sentence_id
                self._last_progress_at = time.monotonic()
            return generation

    def bind_sentence(self, generation: int, sentence_id: Optional[str]) -> bool:
        if not sentence_id:
            return False
        with self._lock:
            if not self._matches_active(generation):
                return False
            self._sentence_id = sentence_id
            self._last_progress_at = time.monotonic()
            return True

    def mark_synthesizing(self, generation: Optional[int] = None) -> bool:
        return self._progress(ReplyState.SYNTHESIZING, generation)

    def mark_streaming(self, generation: Optional[int] = None) -> bool:
        return self._progress(ReplyState.STREAMING, generation)

    def mark_stopping(self, generation: Optional[int] = None) -> bool:
        return self._progress(ReplyState.STOPPING, generation)

    def complete(self, generation: Optional[int] = None, reason: str = "stop") -> bool:
        return self._terminate(ReplyState.COMPLETED, generation, reason)

    def fail(self, generation: Optional[int] = None, reason: str = "failed") -> bool:
        return self._terminate(ReplyState.FAILED, generation, reason)

    def interrupt(
        self, generation: Optional[int] = None, reason: str = "interrupted"
    ) -> bool:
        return self._terminate(ReplyState.INTERRUPTED, generation, reason)

    def is_current(self, generation: Optional[int]) -> bool:
        with self._lock:
            return generation is not None and generation == self._generation

    @property
    def is_active(self) -> bool:
        with self._lock:
            return self._state in ACTIVE_REPLY_STATES

    @property
    def generation(self) -> int:
        with self._lock:
            return self._generation

    @property
    def sentence_id(self) -> Optional[str]:
        with self._lock:
            return self._sentence_id

    @property
    def state(self) -> ReplyState:
        with self._lock:
            return self._state

    def snapshot(self) -> ReplySnapshot:
        with self._lock:
            return ReplySnapshot(
                generation=self._generation,
                state=self._state,
                started_at=self._started_at,
                sentence_id=self._sentence_id,
                last_progress_at=self._last_progress_at,
                terminal_reason=self._terminal_reason,
                kind=self._kind,
            )

    def _matches_active(self, generation: Optional[int]) -> bool:
        return (
            self._state in ACTIVE_REPLY_STATES
            and (generation is None or generation == self._generation)
        )

    def _progress(self, state: ReplyState, generation: Optional[int]) -> bool:
        with self._lock:
            if not self._matches_active(generation):
                return False
            self._state = state
            self._last_progress_at = time.monotonic()
            return True

    def _terminate(
        self, state: ReplyState, generation: Optional[int], reason: str
    ) -> bool:
        with self._lock:
            if not self._matches_active(generation):
                return False
            self._state = state
            self._last_progress_at = time.monotonic()
            self._terminal_reason = str(reason or state.value.lower())
            return True
