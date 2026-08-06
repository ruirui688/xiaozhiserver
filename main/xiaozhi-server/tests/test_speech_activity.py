from pathlib import Path
import asyncio
import sys
from types import SimpleNamespace


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings  # noqa: E402

settings.config_file_valid = True

from core.audio.speech_activity import (  # noqa: E402
    SpeechActivityController,
    SpeechGateConfig,
    VadObservation,
)
from core.providers.vad.silero import VADProvider  # noqa: E402
from core.providers.asr.base import ASRProviderBase  # noqa: E402


def _observation(probability, rms_dbfs, samples=512):
    return VadObservation(
        speech_probability=probability,
        rms_dbfs=rms_dbfs,
        decoded_samples=samples,
        inference_ms=1,
    )


def _controller(**overrides):
    config = {
        "mode": "enforce",
        "start_snr_db": 8,
        "barge_in_start_snr_db": 12,
        "continuation_snr_db": 4,
        "confirmation_frames": 3,
        "confirmation_window": 5,
        "tail_grace_ms": 350,
    }
    config.update(overrides)
    controller = SpeechActivityController(SpeechGateConfig.from_mapping(config))
    state = controller.new_state()
    state.noise_floor_dbfs = -50.0
    return controller, state


def test_rms_snr_gate_rejects_silero_like_low_energy_noise():
    controller, state = _controller()

    decisions = [
        controller.process(state, _observation(0.9, -44.0))
        for _ in range(5)
    ]

    assert not any(decision.speech_started for decision in decisions)
    assert all(decision.rejection_reason == "snr" for decision in decisions)
    assert state.state != "active"


def test_three_of_five_confirmation_accepts_stable_speech():
    controller, state = _controller()
    frames = [
        _observation(0.92, -40.0),
        _observation(0.1, -52.0),
        _observation(0.94, -39.0),
        _observation(0.1, -52.0),
        _observation(0.96, -38.0),
    ]

    decisions = [controller.process(state, frame) for frame in frames]

    assert decisions[-1].speech_started is True
    assert decisions[-1].trusted_voice is True
    assert decisions[-1].state == "active"


def test_connection_starting_with_speech_does_not_stall_noise_floor_calibration():
    config = SpeechGateConfig.from_mapping(
        {
            "mode": "enforce",
            "confirmation_frames": 3,
            "confirmation_window": 5,
            "min_rms_dbfs": -45,
        }
    )
    controller = SpeechActivityController(config)
    state = controller.new_state()

    decisions = [
        controller.process(state, _observation(0.95, -42.0))
        for _ in range(3)
    ]

    assert decisions[-1].speech_started is True


def test_uncalibrated_speech_like_first_frame_cannot_forge_snr():
    config = SpeechGateConfig.from_mapping(
        {"mode": "enforce", "confirmation_frames": 3, "confirmation_window": 5}
    )
    controller = SpeechActivityController(config)
    state = controller.new_state()

    decisions = [
        controller.process(state, _observation(0.95, -42.0))
        for _ in range(3)
    ]

    assert not any(decision.speech_started for decision in decisions)


def test_barge_in_uses_stricter_snr_than_normal_start():
    normal, normal_state = _controller()
    barge, barge_state = _controller()

    normal_decisions = [
        normal.process(normal_state, _observation(0.95, -40.0))
        for _ in range(3)
    ]
    barge_decisions = [
        barge.process(barge_state, _observation(0.95, -40.0), barge_in=True)
        for _ in range(3)
    ]

    assert normal_decisions[-1].speech_started is True
    assert not any(decision.speech_started for decision in barge_decisions)


def test_ambiguous_tail_cannot_extend_active_turn_indefinitely():
    controller, state = _controller()
    for _ in range(3):
        controller.process(state, _observation(0.95, -40.0))

    tail = [
        controller.process(state, _observation(0.7, -47.0))
        for _ in range(11)
    ]

    assert tail[-1].speech_ended is True
    assert state.state == "idle"


def test_speech_like_tail_above_continuation_snr_is_still_bounded():
    controller, state = _controller()
    for _ in range(3):
        controller.process(state, _observation(0.95, -40.0))

    tail = [
        controller.process(state, _observation(0.8, -44.0))
        for _ in range(11)
    ]

    assert tail[-1].speech_ended is True
    assert state.state == "idle"


def test_hangover_requires_multiframe_confidence_to_resume():
    controller, state = _controller()
    for _ in range(3):
        controller.process(state, _observation(0.95, -40.0))

    controller.process(state, _observation(0.6, -47.0))
    single_peak = controller.process(state, _observation(0.95, -40.0))
    assert single_peak.trusted_voice is False
    assert single_peak.state == "hangover"

    controller.process(state, _observation(0.95, -40.0))
    resumed = controller.process(state, _observation(0.95, -40.0))
    assert resumed.trusted_voice is True
    assert resumed.state == "active"


def test_minimum_rms_can_be_calibrated_per_device():
    controller, state = _controller(min_rms_dbfs=-35)

    decisions = [
        controller.process(state, _observation(0.95, -40.0))
        for _ in range(3)
    ]

    assert not any(decision.speech_started for decision in decisions)
    assert decisions[-1].rejection_reason == "absolute_rms"


def test_silero_resolves_speech_gate_from_connection_runtime_config():
    provider = object.__new__(VADProvider)
    provider.speech_gate_config = SpeechGateConfig.from_mapping({"mode": "shadow"})
    conn = SimpleNamespace(
        config={
            "selected_module": {"VAD": "vad-a"},
            "model_config": {
                "vad-a": {
                    "model_type": "VAD",
                    "typed_config": {
                        "type": "silero",
                        "speech_gate": {"mode": "enforce", "preroll_ms": 240},
                    },
                }
            },
        }
    )

    assert provider.is_speech_gate_enforced(conn) is True
    assert provider.get_preroll_ms(conn) == 240


def test_enforced_gate_does_not_shorten_existing_silence_threshold():
    provider = object.__new__(VADProvider)
    provider.silence_threshold_ms = 200
    provider.speech_gate_config = SpeechGateConfig.from_mapping(
        {"mode": "enforce", "tail_grace_ms": 350}
    )
    conn = SimpleNamespace(
        config={
            "selected_module": {"VAD": "vad-a"},
            "model_config": {
                "vad-a": {
                    "model_type": "VAD",
                    "typed_config": {
                        "type": "silero",
                        "min_silence_duration_ms": 1000,
                        "speech_gate": {"mode": "enforce", "tail_grace_ms": 350},
                    },
                }
            },
        },
        voiceprint_enrollment=None,
    )

    assert provider._effective_gate_tail_ms(conn) == 1000


def test_silero_packet_observation_keeps_actual_decoded_duration():
    combined = VADProvider._combine_observations(
        [_observation(0.4, -50.0), _observation(0.9, -40.0)]
    )

    assert combined.decoded_samples == 1024
    assert combined.speech_probability == 0.9
    assert -50.0 < combined.rms_dbfs < -40.0


class _TestASR(ASRProviderBase):
    async def speech_to_text(self, opus_data, session_id, audio_format="opus", artifacts=None):
        return "", None


def test_enforced_gate_limits_asr_preroll_to_configured_duration():
    class Gate:
        @staticmethod
        def is_speech_gate_enforced(conn=None):
            return True

        @staticmethod
        def get_preroll_ms(conn=None):
            return 320

    conn = SimpleNamespace(
        client_listen_mode="auto",
        client_have_voice=False,
        asr_audio=[],
        vad=Gate(),
    )
    provider = _TestASR()

    async def feed_noise():
        for index in range(10):
            await provider.receive_audio(conn, bytes([index]), False)

    asyncio.run(feed_noise())

    assert len(conn.asr_audio) == 6
    assert conn.asr_audio[0] == bytes([4])


def test_enforced_gate_uses_negotiated_packet_duration_for_preroll():
    class Gate:
        @staticmethod
        def is_speech_gate_enforced(conn=None):
            return True

        @staticmethod
        def get_preroll_ms(conn=None):
            return 320

    conn = SimpleNamespace(
        client_listen_mode="auto",
        client_have_voice=False,
        asr_audio=[],
        vad=Gate(),
        welcome_msg={"audio_params": {"frame_duration": 20}},
    )
    provider = _TestASR()

    async def feed_noise():
        for index in range(20):
            await provider.receive_audio(conn, bytes([index]), False)

    asyncio.run(feed_noise())

    assert len(conn.asr_audio) == 16
    assert conn.asr_audio[0] == bytes([4])
