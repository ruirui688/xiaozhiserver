"""Connection-scoped state and deterministic directed-speech decisions."""

import threading
import time
from typing import Callable, Optional

from core.interaction.config import InteractionAttentionConfig
from core.interaction.frames import (
    AddressingDecision,
    AddressingInput,
    AddressingResult,
    AttentionMode,
    InteractionState,
    InteractionTrigger,
)


class InteractionAttentionManager:
    """Track attention state without depending on SemanticCenter.

    The manager is intended to live on a connection. Callers may feed an
    explicit activation event through :meth:`activate`, or include the trigger
    on the first :class:`AddressingInput`. The result's ``allow_processing``
    field is the only mode-aware routing switch needed by ``startToChat``.
    """

    def __init__(
        self,
        config: InteractionAttentionConfig | None = None,
        clock: Callable[[], float] | None = None,
    ):
        self.config = config or InteractionAttentionConfig()
        self._clock = clock or time.monotonic
        self._state = InteractionState.DORMANT
        self._state_since = self._clock()
        self._last_directed_at: Optional[float] = None
        self._lock = threading.RLock()

    @property
    def state(self) -> InteractionState:
        with self._lock:
            return self._state

    def activate(
        self, trigger: InteractionTrigger | str, now: float | None = None
    ) -> None:
        parsed_trigger = self._parse_trigger(trigger)
        if parsed_trigger is None or not parsed_trigger.is_explicit:
            raise ValueError("activation requires an explicit interaction trigger")
        with self._lock:
            self._transition(InteractionState.ARMED, self._now(now))

    def mark_assistant_speaking(self, now: float | None = None) -> None:
        with self._lock:
            if self._state in {
                InteractionState.ENGAGED,
                InteractionState.AWAITING_REPLY,
                InteractionState.SPEAKING,
            }:
                self._transition(InteractionState.SPEAKING, self._now(now))

    def mark_assistant_finished(
        self, asked_question: bool, now: float | None = None
    ) -> None:
        with self._lock:
            if self._state.is_protected:
                return
            current_time = self._now(now)
            self._last_directed_at = current_time
            next_state = (
                InteractionState.AWAITING_REPLY
                if asked_question
                else InteractionState.ENGAGED
            )
            self._transition(next_state, current_time)

    def enter_protected_state(
        self, state: InteractionState | str, now: float | None = None
    ) -> None:
        parsed_state = InteractionState(state)
        if not parsed_state.is_protected:
            raise ValueError("state is not a protected interaction state")
        with self._lock:
            self._transition(parsed_state, self._now(now))

    def leave_protected_state(self, now: float | None = None) -> None:
        with self._lock:
            if self._state.is_protected:
                self._transition(InteractionState.DORMANT, self._now(now))
                self._last_directed_at = None

    def reset(self, now: float | None = None) -> None:
        with self._lock:
            self._transition(InteractionState.DORMANT, self._now(now))
            self._last_directed_at = None

    def evaluate(
        self, signals: AddressingInput, now: float | None = None
    ) -> AddressingResult:
        with self._lock:
            current_time = self._now(now)
            self._expire_state(current_time)
            state_before = self._state

            if self.config.effective_mode == AttentionMode.DISABLED:
                return self._result(
                    AddressingDecision.DIRECTED,
                    "attention_disabled",
                    state_before,
                    state_before,
                )

            trigger = self._parse_trigger(signals.trigger)
            if state_before.is_protected:
                decision = AddressingDecision.UNDIRECTED
                reason = f"protected_state:{state_before.value}"
                confidence = None
            elif trigger is not None and trigger.is_explicit:
                decision = AddressingDecision.DIRECTED
                reason = f"explicit_trigger:{trigger.value}"
                confidence = 1.0
            elif self._is_low_asr_confidence(signals.asr_confidence):
                decision = AddressingDecision.UNKNOWN_ADDRESSING
                reason = "low_asr_confidence"
                confidence = signals.asr_confidence
            elif state_before == InteractionState.ARMED:
                decision = AddressingDecision.DIRECTED
                reason = "within_wake_window"
                confidence = signals.asr_confidence
            elif state_before == InteractionState.AWAITING_REPLY:
                decision = AddressingDecision.DIRECTED
                reason = "within_reply_window"
                confidence = signals.asr_confidence
            else:
                decision, reason, confidence = self._evaluate_device_score(
                    signals.device_addressing_score
                )
                if decision is None:
                    decision, reason = self._evaluate_without_device_score(
                        state_before, current_time
                    )

            state_after = self._apply_decision(decision, current_time)
            return self._result(
                decision,
                reason,
                state_before,
                state_after,
                confidence,
            )

    def _evaluate_device_score(
        self, score: float | None
    ) -> tuple[AddressingDecision | None, str, float | None]:
        if score is None:
            return None, "", None
        if not 0.0 <= score <= 1.0:
            return (
                AddressingDecision.UNKNOWN_ADDRESSING,
                "invalid_device_addressing_score",
                None,
            )
        if score >= self.config.directed_threshold:
            return AddressingDecision.DIRECTED, "device_score_directed", score
        if score <= self.config.undirected_threshold:
            return AddressingDecision.UNDIRECTED, "device_score_undirected", score
        return AddressingDecision.UNKNOWN_ADDRESSING, "device_score_ambiguous", score

    def _evaluate_without_device_score(
        self, state: InteractionState, now: float
    ) -> tuple[AddressingDecision, str]:
        if state in {InteractionState.ENGAGED, InteractionState.SPEAKING}:
            if self._within_last_directed_window(now):
                return (
                    AddressingDecision.UNKNOWN_ADDRESSING,
                    "follow_up_requires_addressing_evidence",
                )
            return AddressingDecision.UNDIRECTED, "engaged_window_expired"
        if state == InteractionState.AMBIGUOUS:
            return AddressingDecision.UNKNOWN_ADDRESSING, "addressing_still_ambiguous"
        return AddressingDecision.UNDIRECTED, "no_active_interaction"

    def _apply_decision(
        self, decision: AddressingDecision, now: float
    ) -> InteractionState:
        if decision == AddressingDecision.DIRECTED:
            self._last_directed_at = now
            self._transition(InteractionState.ENGAGED, now)
        elif decision == AddressingDecision.UNKNOWN_ADDRESSING:
            self._transition(InteractionState.AMBIGUOUS, now)
        return self._state

    def _expire_state(self, now: float) -> None:
        age = now - self._state_since
        if self._state == InteractionState.ARMED:
            expired = age * 1000 > self.config.armed_window_ms
        elif self._state == InteractionState.AWAITING_REPLY:
            expired = age * 1000 > self.config.awaiting_reply_window_ms
        elif self._state in {
            InteractionState.ENGAGED,
            InteractionState.SPEAKING,
            InteractionState.AMBIGUOUS,
        }:
            expired = not self._within_last_directed_window(now)
        else:
            expired = False

        if expired:
            self._transition(InteractionState.DORMANT, now)
            self._last_directed_at = None

    def _within_last_directed_window(self, now: float) -> bool:
        return bool(
            self._last_directed_at is not None
            and (now - self._last_directed_at) * 1000
            <= self.config.follow_up_window_ms
        )

    def _is_low_asr_confidence(self, confidence: float | None) -> bool:
        return confidence is not None and confidence < self.config.asr_confidence_floor

    def _result(
        self,
        decision: AddressingDecision,
        reason: str,
        state_before: InteractionState,
        state_after: InteractionState,
        confidence: float | None = None,
    ) -> AddressingResult:
        allow_processing = (
            self.config.effective_mode != AttentionMode.ENFORCE
            or decision == AddressingDecision.DIRECTED
        )
        return AddressingResult(
            decision=decision,
            reason=reason,
            mode=self.config.effective_mode,
            state_before=state_before,
            state_after=state_after,
            allow_processing=allow_processing,
            confidence=confidence,
        )

    def _transition(self, state: InteractionState, now: float) -> None:
        self._state = state
        self._state_since = now

    def _now(self, now: float | None) -> float:
        return self._clock() if now is None else now

    @staticmethod
    def _parse_trigger(
        trigger: InteractionTrigger | str | None,
    ) -> InteractionTrigger | None:
        if trigger is None or trigger == "":
            return None
        try:
            return InteractionTrigger(trigger)
        except ValueError:
            return None
