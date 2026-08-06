"""Pure speech capability parsing and route resolution."""

from __future__ import annotations

from dataclasses import dataclass


VALID_ROUTE_MODES = {"server", "device", "auto"}


class SpeechRouteError(ValueError):
    """Raised when an explicit speech route cannot be satisfied safely."""


@dataclass(frozen=True)
class SpeechRoute:
    input: str
    output: str
    input_policy: str
    output_policy: str
    hello_timed_out: bool = False

    @property
    def uses_device_asr(self) -> bool:
        return self.input == "device"

    @property
    def uses_device_tts(self) -> bool:
        return self.output == "device"

    def as_message(self, session_id: str) -> dict:
        return {
            "type": "speech_route",
            "session_id": session_id,
            "version": 1,
            "input": self.input,
            "output": self.output,
            "inputPolicy": self.input_policy,
            "outputPolicy": self.output_policy,
            "helloTimedOut": self.hello_timed_out,
        }


def normalize_speech_capabilities(features) -> dict:
    """Return trusted booleans from the optional ``features.speech`` object."""
    speech = features.get("speech") if isinstance(features, dict) else None
    if not isinstance(speech, dict):
        return {"device_asr": False, "device_tts": False, "tts_ack": False}

    inputs = speech.get("input")
    outputs = speech.get("output")
    if isinstance(inputs, str):
        inputs = [inputs]
    if isinstance(outputs, str):
        outputs = [outputs]
    inputs = {str(item).strip().lower() for item in (inputs or [])}
    outputs = {str(item).strip().lower() for item in (outputs or [])}
    device_asr = bool(
        speech.get("device_asr") is True
        or speech.get("asr_final_text") is True
        or inputs.intersection({"device", "device_asr"})
    )
    device_tts = bool(
        speech.get("device_tts") is True
        or outputs.intersection({"device", "device_tts"})
    )
    tts_ack = bool(
        speech.get("tts_playback_ack") is True
        or speech.get("playback_ack") is True
        or speech.get("tts_ack") is True
    )
    return {
        "device_asr": device_asr,
        "device_tts": device_tts,
        "tts_ack": tts_ack,
    }


def _policy(config, name: str) -> str:
    routing = config.get("speech_routing") if isinstance(config, dict) else None
    raw = routing.get(name) if isinstance(routing, dict) else None
    policy = str(raw or "server").strip().lower()
    if policy not in VALID_ROUTE_MODES:
        raise SpeechRouteError(
            f"speech_routing.{name} must be server, device, or auto"
        )
    return policy


def _resolve_side(policy: str, supported: bool, label: str) -> str:
    if policy == "server":
        return "server"
    if policy == "device":
        if not supported:
            raise SpeechRouteError(f"device does not support required {label}")
        return "device"
    return "device" if supported else "server"


def resolve_speech_route(
    config,
    features,
    *,
    realtime: bool = False,
    hello_timed_out: bool = False,
) -> SpeechRoute:
    capabilities = normalize_speech_capabilities(features)
    input_policy = _policy(config, "input")
    output_policy = _policy(config, "output")
    input_route = _resolve_side(
        input_policy, capabilities["device_asr"], "ASR"
    )
    output_route = _resolve_side(
        output_policy,
        capabilities["device_tts"] and capabilities["tts_ack"],
        "TTS with playback ACK",
    )
    if realtime and (input_route == "device" or output_route == "device"):
        raise SpeechRouteError(
            "Realtime conversation cannot be combined with device ASR or device TTS"
        )
    return SpeechRoute(
        input=input_route,
        output=output_route,
        input_policy=input_policy,
        output_policy=output_policy,
        hello_timed_out=hello_timed_out,
    )
