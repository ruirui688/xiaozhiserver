"""RMS/SNR speech gate layered after a frame-level VAD observation."""

from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass, field
from typing import Deque, Literal, Mapping, Optional


SpeechActivityMode = Literal["disabled", "shadow", "enforce"]
SpeechActivityStateName = Literal["idle", "candidate", "active", "hangover"]


def _bounded_float(value, default, minimum, maximum):
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        parsed = float(default)
    return min(maximum, max(minimum, parsed))


def _bounded_int(value, default, minimum, maximum):
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = int(default)
    return min(maximum, max(minimum, parsed))


@dataclass(frozen=True)
class VadObservation:
    speech_probability: float
    rms_dbfs: float
    decoded_samples: int
    inference_ms: int


@dataclass(frozen=True)
class SpeechActivityDecision:
    state: SpeechActivityStateName
    active: bool
    speech_started: bool
    speech_ended: bool
    trusted_voice: bool
    ambiguous_voice: bool
    noise_floor_dbfs: Optional[float]
    snr_db: Optional[float]
    rejection_reason: Optional[str]


@dataclass(frozen=True)
class SpeechGateConfig:
    mode: SpeechActivityMode = "disabled"
    start_probability: float = 0.8
    continuation_probability: float = 0.5
    start_snr_db: float = 8.0
    barge_in_start_snr_db: float = 12.0
    continuation_snr_db: float = 4.0
    tail_grace_ms: int = 350
    noise_floor_adaptation_ms: int = 5000
    confirmation_frames: int = 3
    confirmation_window: int = 5
    preroll_ms: int = 320
    min_rms_dbfs: Optional[float] = None

    @classmethod
    def from_mapping(cls, value: Optional[Mapping]) -> "SpeechGateConfig":
        raw = value if isinstance(value, Mapping) else {}
        mode = str(raw.get("mode", "disabled")).strip().lower()
        if mode not in {"disabled", "shadow", "enforce"}:
            mode = "disabled"

        window = _bounded_int(raw.get("confirmation_window"), 5, 1, 20)
        required = _bounded_int(raw.get("confirmation_frames"), 3, 1, window)
        min_rms_raw = raw.get("min_rms_dbfs")
        min_rms = None
        if min_rms_raw not in (None, "", "null"):
            min_rms = _bounded_float(min_rms_raw, -96.0, -96.0, 0.0)

        return cls(
            mode=mode,
            start_probability=_bounded_float(
                raw.get("start_probability"), 0.8, 0.0, 1.0
            ),
            continuation_probability=_bounded_float(
                raw.get("continuation_probability"), 0.5, 0.0, 1.0
            ),
            start_snr_db=_bounded_float(raw.get("start_snr_db"), 8.0, 0.0, 60.0),
            barge_in_start_snr_db=_bounded_float(
                raw.get("barge_in_start_snr_db"), 12.0, 0.0, 60.0
            ),
            continuation_snr_db=_bounded_float(
                raw.get("continuation_snr_db"), 4.0, -10.0, 60.0
            ),
            tail_grace_ms=_bounded_int(raw.get("tail_grace_ms"), 350, 64, 5000),
            noise_floor_adaptation_ms=_bounded_int(
                raw.get("noise_floor_adaptation_ms"), 5000, 250, 60000
            ),
            confirmation_frames=required,
            confirmation_window=window,
            preroll_ms=_bounded_int(raw.get("preroll_ms"), 320, 0, 2000),
            min_rms_dbfs=min_rms,
        )


@dataclass
class SpeechActivityState:
    state: SpeechActivityStateName = "idle"
    noise_floor_dbfs: Optional[float] = None
    confirmation: Deque[bool] = field(default_factory=deque)
    untrusted_ms: float = 0.0

    def reset_activity(self, preserve_noise_floor: bool = True) -> None:
        noise_floor = self.noise_floor_dbfs if preserve_noise_floor else None
        self.state = "idle"
        self.noise_floor_dbfs = noise_floor
        self.confirmation.clear()
        self.untrusted_ms = 0.0


class SpeechActivityController:
    """Confirm speech starts using VAD probability plus relative acoustic energy."""

    _MIN_DBFS = -96.0

    def __init__(self, config: SpeechGateConfig):
        self.config = config

    @staticmethod
    def rms_dbfs(samples) -> float:
        if samples is None or len(samples) == 0:
            return SpeechActivityController._MIN_DBFS
        square_mean = float((samples.astype("float64") ** 2).mean())
        if square_mean <= 1e-12:
            return SpeechActivityController._MIN_DBFS
        return max(
            SpeechActivityController._MIN_DBFS,
            10.0 * math.log10(square_mean),
        )

    def new_state(self) -> SpeechActivityState:
        return SpeechActivityState(
            confirmation=deque(maxlen=self.config.confirmation_window)
        )

    def _adapt_noise_floor(
        self,
        state: SpeechActivityState,
        rms_dbfs: float,
        frame_ms: float,
    ) -> None:
        if state.noise_floor_dbfs is None:
            state.noise_floor_dbfs = rms_dbfs
            return
        alpha = math.exp(-max(1.0, frame_ms) / self.config.noise_floor_adaptation_ms)
        old_power = 10.0 ** (state.noise_floor_dbfs / 10.0)
        frame_power = 10.0 ** (rms_dbfs / 10.0)
        smoothed = alpha * old_power + (1.0 - alpha) * frame_power
        state.noise_floor_dbfs = max(
            self._MIN_DBFS,
            10.0 * math.log10(max(smoothed, 1e-12)),
        )

    def process(
        self,
        state: SpeechActivityState,
        observation: VadObservation,
        *,
        barge_in: bool = False,
        tail_grace_ms: Optional[int] = None,
    ) -> SpeechActivityDecision:
        frame_ms = max(1.0, observation.decoded_samples / 16.0)
        probability_ok = (
            observation.speech_probability >= self.config.start_probability
        )
        required_start_snr = (
            self.config.barge_in_start_snr_db
            if barge_in
            else self.config.start_snr_db
        )
        if state.noise_floor_dbfs is None:
            # Never manufacture SNR from an uncalibrated speech-like first
            # frame: intermittent noise can also produce a high Silero score.
            # A device-calibrated absolute floor is the explicit escape hatch
            # for connections that begin while the user is already speaking.
            if (
                probability_ok
                and self.config.min_rms_dbfs is not None
                and observation.rms_dbfs >= self.config.min_rms_dbfs
            ):
                state.noise_floor_dbfs = (
                    observation.rms_dbfs - required_start_snr
                )
            else:
                state.noise_floor_dbfs = observation.rms_dbfs
        noise_floor = state.noise_floor_dbfs
        snr_db = (
            observation.rms_dbfs - noise_floor
            if noise_floor is not None
            else None
        )
        min_rms_ok = (
            self.config.min_rms_dbfs is None
            or observation.rms_dbfs >= self.config.min_rms_dbfs
        )
        start_pass = bool(
            probability_ok
            and min_rms_ok
            and snr_db is not None
            and snr_db >= required_start_snr
        )

        speech_started = False
        speech_ended = False
        trusted_voice = False
        ambiguous_voice = False
        rejection_reason = None

        if state.state in {"idle", "candidate"}:
            state.confirmation.append(start_pass)
            if start_pass:
                state.state = "candidate"
            elif any(state.confirmation):
                state.state = "candidate"
            else:
                state.state = "idle"

            if sum(state.confirmation) >= self.config.confirmation_frames:
                state.state = "active"
                state.untrusted_ms = 0.0
                state.confirmation.clear()
                speech_started = True
                trusted_voice = True
            else:
                ambiguous_voice = probability_ok
                if not probability_ok:
                    rejection_reason = "silero_probability"
                elif not min_rms_ok:
                    rejection_reason = "absolute_rms"
                else:
                    rejection_reason = "snr"
                if not start_pass:
                    self._adapt_noise_floor(
                        state, observation.rms_dbfs, frame_ms
                    )
        else:
            confident_voice = bool(
                observation.speech_probability >= self.config.start_probability
                and min_rms_ok
                and snr_db is not None
                and snr_db >= self.config.start_snr_db
            )
            possible_voice = bool(
                observation.speech_probability
                >= self.config.continuation_probability
                and min_rms_ok
                and snr_db is not None
                and snr_db >= self.config.continuation_snr_db
            )
            if state.state == "active" and confident_voice:
                trusted_voice = True
                state.state = "active"
                state.untrusted_ms = 0.0
                state.confirmation.clear()
            else:
                if state.state == "active":
                    state.confirmation.clear()
                state.state = "hangover"
                state.confirmation.append(confident_voice)
                state.untrusted_ms += frame_ms
                ambiguous_voice = bool(
                    possible_voice
                    or observation.speech_probability
                    > self.config.continuation_probability
                )
                if sum(state.confirmation) >= self.config.confirmation_frames:
                    state.state = "active"
                    state.untrusted_ms = 0.0
                    state.confirmation.clear()
                    trusted_voice = True
                else:
                    rejection_reason = (
                        "continuation_confidence" if ambiguous_voice else "silence"
                    )
                    grace_ms = max(
                        64, int(tail_grace_ms or self.config.tail_grace_ms)
                    )
                    if state.untrusted_ms >= grace_ms:
                        state.reset_activity(preserve_noise_floor=True)
                        speech_ended = True
                    # Do not learn possible speech or light tail syllables into
                    # the background estimate. Clear non-speech frames may
                    # still follow a changing ambient floor.
                    if not ambiguous_voice:
                        self._adapt_noise_floor(
                            state, observation.rms_dbfs, frame_ms
                        )

        return SpeechActivityDecision(
            state=state.state,
            active=state.state in {"active", "hangover"},
            speech_started=speech_started,
            speech_ended=speech_ended,
            trusted_voice=trusted_voice,
            ambiguous_voice=ambiguous_voice,
            noise_floor_dbfs=(
                round(state.noise_floor_dbfs, 3)
                if state.noise_floor_dbfs is not None
                else None
            ),
            snr_db=round(snr_db, 3) if snr_db is not None else None,
            rejection_reason=rejection_reason,
        )
