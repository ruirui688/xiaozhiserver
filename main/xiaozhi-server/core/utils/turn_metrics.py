import copy
import json
import math
import threading
import time
import uuid


def _now_epoch_ms():
    return int(time.time() * 1000)


def _elapsed_ms(started_at):
    if not started_at:
        return None
    return max(0, int((time.monotonic() - started_at) * 1000))


def _audio_duration_ms(audio_frames, audio_format):
    if not audio_frames:
        return 0
    if audio_format == "opus":
        return len(audio_frames) * 60
    return None


def _estimated_token_count(value):
    if not value:
        return 0
    if not isinstance(value, str):
        value = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    # Chinese-heavy text has fewer bytes per token than English. This is only a
    # fallback when the provider does not expose real usage.
    return max(1, math.ceil(len(value) / 2))


class TurnMetrics:
    """Collects low-cost per-turn latency metrics for chat-history export."""

    def __init__(self):
        self._lock = threading.RLock()
        self._vad_segment = None
        self._active_turn = None

    def record_vad_frame(self, have_voice, elapsed_ms, audio_bytes):
        with self._lock:
            if self._vad_segment is None:
                if not have_voice:
                    return
                self._vad_segment = {
                    "speech_started_at_epoch_ms": _now_epoch_ms(),
                    "speech_started_at_monotonic": time.monotonic(),
                    "vad_frame_count": 0,
                    "vad_voice_frame_count": 0,
                    "vad_silence_frame_count": 0,
                    "vad_audio_bytes": 0,
                    "vad_inference_ms": 0,
                    "vad_inference_ms_max": 0,
                }

            segment = self._vad_segment
            segment["vad_frame_count"] += 1
            segment["vad_audio_bytes"] += int(audio_bytes or 0)
            frame_elapsed = max(0, int(elapsed_ms or 0))
            segment["vad_inference_ms"] += frame_elapsed
            segment["vad_inference_ms_max"] = max(
                segment["vad_inference_ms_max"], frame_elapsed
            )
            if have_voice:
                segment["vad_voice_frame_count"] += 1
                segment["last_voice_at_epoch_ms"] = _now_epoch_ms()
                segment["last_voice_at_monotonic"] = time.monotonic()
            else:
                segment["vad_silence_frame_count"] += 1

    def begin_turn(self, audio_frames, audio_format):
        with self._lock:
            now_monotonic = time.monotonic()
            turn_id = uuid.uuid4().hex
            vad = self._vad_segment or {
                "speech_started_at_epoch_ms": _now_epoch_ms(),
                "speech_started_at_monotonic": now_monotonic,
                "vad_frame_count": 0,
                "vad_voice_frame_count": 0,
                "vad_silence_frame_count": 0,
                "vad_audio_bytes": 0,
                "vad_inference_ms": 0,
                "vad_inference_ms_max": 0,
            }
            self._vad_segment = None
            estimated_audio_duration_ms = _audio_duration_ms(audio_frames, audio_format)
            speech_started_at = vad.get("speech_started_at_monotonic") or now_monotonic
            last_voice_at = vad.get("last_voice_at_monotonic")
            self._active_turn = {
                "schema_version": 1,
                "turn_id": turn_id,
                "started_at_epoch_ms": vad.get("speech_started_at_epoch_ms") or _now_epoch_ms(),
                "input": {
                    "audio_format": audio_format,
                    "audio_packet_count": len(audio_frames or []),
                    "estimated_audio_duration_ms": estimated_audio_duration_ms,
                },
                "latency_ms": {
                    "vad": {
                        "frame_count": vad.get("vad_frame_count", 0),
                        "voice_frame_count": vad.get("vad_voice_frame_count", 0),
                        "silence_frame_count": vad.get("vad_silence_frame_count", 0),
                        "audio_bytes": vad.get("vad_audio_bytes", 0),
                        "inference_total_ms": vad.get("vad_inference_ms", 0),
                        "inference_max_ms": vad.get("vad_inference_ms_max", 0),
                        "segment_ms": max(0, int((now_monotonic - speech_started_at) * 1000)),
                        "silence_after_last_voice_ms": (
                            max(0, int((now_monotonic - last_voice_at) * 1000))
                            if last_voice_at
                            else None
                        ),
                    }
                },
                "_runtime": {
                    "speech_started_at": speech_started_at,
                    "audio_duration_ms": estimated_audio_duration_ms,
                },
                "tokens": {},
            }
            return turn_id

    def discard_vad_segment(self):
        with self._lock:
            self._vad_segment = None

    def ensure_text_turn(self):
        with self._lock:
            if self._active_turn is None:
                self._active_turn = {
                    "schema_version": 1,
                    "turn_id": uuid.uuid4().hex,
                    "started_at_epoch_ms": _now_epoch_ms(),
                    "input": {"audio_format": "text"},
                    "latency_ms": {},
                    "tokens": {},
                }
            return self._active_turn["turn_id"]

    def record_asr(self, finalize_ms, success, text_length, first_text_ms=None):
        with self._lock:
            if self._active_turn is None:
                self.ensure_text_turn()
            runtime = self._active_turn.setdefault("_runtime", {})
            response_ms = _elapsed_ms(runtime.get("speech_started_at"))
            audio_duration_ms = runtime.get("audio_duration_ms")
            tail_latency_ms = (
                max(0, int(response_ms) - int(audio_duration_ms or 0))
                if response_ms is not None and audio_duration_ms is not None
                else None
            )
            self._active_turn["latency_ms"]["asr"] = {
                "total_ms": response_ms,
                "response_ms": response_ms,
                "audio_duration_ms": audio_duration_ms,
                "tail_latency_ms": tail_latency_ms,
                "finalize_ms": max(0, int(finalize_ms or 0)),
                "first_text_ms": first_text_ms,
                "first_text_unavailable_reason": (
                    "chat runtime does not expose provider first partial text"
                    if first_text_ms is None
                    else None
                ),
                "success": bool(success),
                "text_length": int(text_length or 0),
            }

    def record_voiceprint(self, elapsed_ms, enabled, speaker, speaker_id, score):
        with self._lock:
            if self._active_turn is None:
                self.ensure_text_turn()
            data = {
                "enabled": bool(enabled),
                "total_ms": max(0, int(elapsed_ms or 0)) if elapsed_ms is not None else None,
                "speaker": speaker or None,
                "speaker_id": speaker_id or None,
                "score": score,
            }
            self._active_turn["latency_ms"]["voiceprint"] = data

    def record_routing(self, accepted):
        with self._lock:
            if self._active_turn is not None:
                self._active_turn["routing"] = {"accepted": bool(accepted)}

    def record_llm_started(self, tools_enabled, depth, dialogue=None, functions=None):
        with self._lock:
            if self._active_turn is None:
                self.ensure_text_turn()
            self._active_turn.setdefault("_runtime", {})["llm_started_at"] = time.monotonic()
            prompt_tokens = _estimated_token_count(dialogue)
            tool_tokens = _estimated_token_count(functions)
            estimated_input_tokens = prompt_tokens + tool_tokens
            self._active_turn["llm"] = {
                "depth": depth,
                "tools_enabled": bool(tools_enabled),
                "input_message_count": len(dialogue or []),
                "tool_schema_count": len(functions or []),
            }
            self._active_turn["tokens"] = {
                "prompt_tokens": estimated_input_tokens,
                "prompt_tokens_from_messages": prompt_tokens,
                "prompt_tokens_from_tools": tool_tokens,
                "completion_tokens": None,
                "total_tokens": estimated_input_tokens,
                "source": "estimated_input_chars",
            }

    def record_llm_first_chunk(self):
        with self._lock:
            turn = self._active_turn
            if not turn:
                return
            started_at = turn.setdefault("_runtime", {}).get("llm_started_at")
            if started_at and "first_chunk_ms" not in turn.setdefault("latency_ms", {}).setdefault("llm", {}):
                turn["latency_ms"]["llm"]["first_chunk_ms"] = _elapsed_ms(started_at)

    def record_llm_complete(self, content_text, usage, tool_call, tool_count):
        with self._lock:
            turn = self._active_turn
            if not turn:
                return
            started_at = turn.setdefault("_runtime", {}).get("llm_started_at")
            turn.setdefault("latency_ms", {}).setdefault("llm", {})["total_ms"] = _elapsed_ms(started_at)
            turn.setdefault("llm", {}).update(
                {
                    "tool_call": bool(tool_call),
                    "tool_count": int(tool_count or 0),
                    "output_chars": len(content_text or ""),
                }
            )
            if isinstance(usage, dict) and usage:
                turn["tokens"] = {
                    "prompt_tokens": usage.get("prompt_tokens"),
                    "completion_tokens": usage.get("completion_tokens"),
                    "total_tokens": usage.get("total_tokens"),
                    "source": "provider_usage",
                }
            elif content_text:
                completion_tokens = _estimated_token_count(content_text)
                prompt_tokens = turn.get("tokens", {}).get("prompt_tokens")
                total_tokens = (
                    prompt_tokens + completion_tokens
                    if prompt_tokens is not None
                    else completion_tokens
                )
                turn["tokens"] = {
                    "prompt_tokens": prompt_tokens,
                    "prompt_tokens_from_messages": turn.get("tokens", {}).get("prompt_tokens_from_messages"),
                    "prompt_tokens_from_tools": turn.get("tokens", {}).get("prompt_tokens_from_tools"),
                    "completion_tokens": completion_tokens,
                    "total_tokens": total_tokens,
                    "source": "estimated_input_output_chars",
                }

    def record_tts_start(self, sentence_id):
        with self._lock:
            if self._active_turn is None:
                return
            runtime = self._active_turn.setdefault("_runtime", {})
            if runtime.get("tts_started_at"):
                return
            runtime["tts_started_at"] = time.monotonic()
            runtime["tts_sentence_id"] = sentence_id
            self._active_turn.setdefault("latency_ms", {}).setdefault("tts", {})
            self._active_turn["tts"] = {
                "sentence_id": sentence_id,
                "audio_frame_count": 0,
                "audio_bytes": 0,
            }

    def record_tts_audio_frame(self, audio_bytes):
        with self._lock:
            turn = self._active_turn
            if not turn:
                return
            runtime = turn.setdefault("_runtime", {})
            started_at = runtime.get("tts_started_at")
            if not started_at:
                runtime["tts_started_at"] = time.monotonic()
                started_at = runtime["tts_started_at"]
            latency = turn.setdefault("latency_ms", {}).setdefault("tts", {})
            if started_at and "first_audio_ms" not in latency:
                latency["first_audio_ms"] = _elapsed_ms(started_at)
            tts = turn.setdefault("tts", {})
            tts["audio_frame_count"] = int(tts.get("audio_frame_count") or 0) + 1
            tts["audio_bytes"] = int(tts.get("audio_bytes") or 0) + int(audio_bytes or 0)

    def build_user_metrics(self):
        with self._lock:
            if not self._active_turn:
                return None
            metrics = copy.deepcopy(self._active_turn)
            metrics.pop("_runtime", None)
            metrics["message_role"] = "user"
            return metrics

    def build_assistant_metrics(self, text):
        with self._lock:
            if not self._active_turn:
                return None
            runtime = self._active_turn.setdefault("_runtime", {})
            tts_started_at = runtime.get("tts_started_at")
            if tts_started_at:
                self._active_turn.setdefault("latency_ms", {}).setdefault("tts", {})[
                    "total_ms"
                ] = _elapsed_ms(tts_started_at)
            self._active_turn.setdefault("tts", {})["text_chars"] = len(text or "")
            metrics = copy.deepcopy(self._active_turn)
            metrics.pop("_runtime", None)
            metrics["message_role"] = "assistant"
            self._active_turn = None
            return metrics
