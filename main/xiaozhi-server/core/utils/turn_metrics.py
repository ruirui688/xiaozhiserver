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


def _elapsed_between_ms(started_at, ended_at):
    if not started_at or not ended_at:
        return None
    return max(0, int((ended_at - started_at) * 1000))


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
    """Collect isolated per-turn latency traces for chat history."""

    SCHEMA_VERSION = 2
    MAX_PENDING_TURNS = 32

    def __init__(self):
        self._lock = threading.RLock()
        self._vad_segment = None
        self._active_turn = None
        self._turns = {}
        self._sentence_turn_ids = {}

    def _register_turn(self, turn):
        turn_id = turn["turn_id"]
        self._turns[turn_id] = turn
        self._active_turn = turn
        while len(self._turns) > self.MAX_PENDING_TURNS:
            oldest_turn_id = next(iter(self._turns))
            if oldest_turn_id == turn_id:
                break
            self._remove_turn(self._turns.get(oldest_turn_id))
        return turn_id

    def _resolve_turn(self, turn_id=None, sentence_id=None):
        if sentence_id:
            mapped_turn_id = self._sentence_turn_ids.get(sentence_id)
            if mapped_turn_id:
                return self._turns.get(mapped_turn_id)
            return None
        if turn_id:
            return self._turns.get(turn_id)
        return self._active_turn

    def _remove_turn(self, turn):
        if not turn:
            return
        turn_id = turn.get("turn_id")
        if turn_id:
            self._turns.pop(turn_id, None)
            for sentence_id, mapped_turn_id in list(self._sentence_turn_ids.items()):
                if mapped_turn_id == turn_id:
                    self._sentence_turn_ids.pop(sentence_id, None)
        if self._active_turn is turn:
            self._active_turn = None

    def bind_sentence(self, sentence_id, turn_id=None):
        if not sentence_id:
            return None
        with self._lock:
            turn = self._resolve_turn(turn_id=turn_id)
            if turn is None:
                return None
            resolved_turn_id = turn["turn_id"]
            self._sentence_turn_ids[sentence_id] = resolved_turn_id
            return resolved_turn_id

    def discard_sentence(self, sentence_id):
        if not sentence_id:
            return
        with self._lock:
            turn = self._resolve_turn(sentence_id=sentence_id)
            self._remove_turn(turn)

    def record_interruption(self, sentence_id, reason):
        """Record an interrupted assistant reply without dropping its trace."""
        if not sentence_id:
            return
        with self._lock:
            turn = self._resolve_turn(sentence_id=sentence_id)
            if not turn:
                return
            runtime = turn.setdefault("_runtime", {})
            if runtime.get("interruption_step") is not None:
                return
            tts_step = runtime.get("tts_step") or {}
            turn["outcome"] = {
                "status": "aborted",
                "reason": reason or "user_interrupt",
                "interrupted_at_epoch_ms": _now_epoch_ms(),
            }
            runtime["interruption_step"] = self._append_step(
                turn,
                "interruption",
                reason=reason or "user_interrupt",
                response_audio_started=bool(runtime.get("tts_first_audio_at")),
                generated_audio_duration_ms=int(
                    tts_step.get("audio_duration_ms") or 0
                ),
            )
            self._refresh_trace_totals(turn)

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
                segment["last_voice_at_monotonic"] = time.monotonic()
            else:
                segment["vad_silence_frame_count"] += 1

    def begin_turn(self, audio_frames, audio_format):
        with self._lock:
            now_monotonic = time.monotonic()
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
            audio_duration_ms = _audio_duration_ms(audio_frames, audio_format)
            speech_started_at = vad.get("speech_started_at_monotonic") or now_monotonic
            last_voice_at = vad.get("last_voice_at_monotonic")
            turn = {
                "schema_version": self.SCHEMA_VERSION,
                "turn_id": uuid.uuid4().hex,
                "started_at_epoch_ms": vad.get("speech_started_at_epoch_ms") or _now_epoch_ms(),
                "input": {
                    "audio_format": audio_format,
                    "audio_packet_count": len(audio_frames or []),
                    "audio_duration_ms": audio_duration_ms,
                },
                "stages": {
                    "vad": {
                        "frame_count": vad.get("vad_frame_count", 0),
                        "voice_frame_count": vad.get("vad_voice_frame_count", 0),
                        "silence_frame_count": vad.get("vad_silence_frame_count", 0),
                        "audio_bytes": vad.get("vad_audio_bytes", 0),
                        "inference_ms": vad.get("vad_inference_ms", 0),
                        "max_frame_inference_ms": vad.get("vad_inference_ms_max", 0),
                        "speech_segment_ms": max(
                            0, int((now_monotonic - speech_started_at) * 1000)
                        ),
                        "silence_after_last_voice_ms": (
                            max(0, int((now_monotonic - last_voice_at) * 1000))
                            if last_voice_at
                            else None
                        ),
                    }
                },
                "trace": {"steps": [], "totals": {}},
                "_runtime": {
                    "speech_started_at": speech_started_at,
                    "audio_duration_ms": audio_duration_ms,
                    "asr_first_text_ms": _elapsed_between_ms(
                        speech_started_at,
                        vad.get("asr_first_text_at_monotonic"),
                    ),
                    "asr_first_text_length": vad.get("asr_first_text_length"),
                    "asr_final_text_ms": _elapsed_between_ms(
                        speech_started_at,
                        vad.get("asr_final_text_at_monotonic"),
                    ),
                    "asr_final_text_length": vad.get("asr_final_text_length"),
                },
            }
            return self._register_turn(turn)

    def discard_vad_segment(self):
        with self._lock:
            self._vad_segment = None

    def discard_active_turn(self, turn_id=None):
        with self._lock:
            self._remove_turn(self._resolve_turn(turn_id=turn_id))

    def ensure_text_turn(self, turn_id=None):
        with self._lock:
            turn = self._resolve_turn(turn_id=turn_id)
            if turn is None:
                turn = {
                    "schema_version": self.SCHEMA_VERSION,
                    "turn_id": turn_id or uuid.uuid4().hex,
                    "started_at_epoch_ms": _now_epoch_ms(),
                    "input": {"audio_format": "text"},
                    "stages": {},
                    "trace": {"steps": [], "totals": {}},
                    "_runtime": {},
                }
                self._register_turn(turn)
            return turn["turn_id"]

    def _append_step(self, turn, step_type, **values):
        steps = turn.setdefault("trace", {}).setdefault("steps", [])
        step = {"sequence": len(steps) + 1, "type": step_type, **values}
        steps.append(step)
        return step

    def _message_identity(self, turn, message_role):
        runtime = turn.setdefault("_runtime", {})
        runtime["message_sequence"] = int(runtime.get("message_sequence") or 0) + 1
        return {
            "schema_version": turn.get("schema_version", self.SCHEMA_VERSION),
            "turn_id": turn.get("turn_id"),
            "started_at_epoch_ms": turn.get("started_at_epoch_ms"),
            "message_role": message_role,
            "message_sequence": runtime["message_sequence"],
        }

    def reserve_user_message(self, turn_id=None):
        """Reserve sequence 1 before synchronous intent/tool handling starts."""
        with self._lock:
            turn = self._resolve_turn(turn_id=turn_id)
            if not turn:
                return None
            runtime = turn.setdefault("_runtime", {})
            identity = runtime.get("user_message_identity")
            if identity is None:
                identity = self._message_identity(turn, "user")
                runtime["user_message_identity"] = identity
            return copy.deepcopy(identity)

    def build_link_metrics(
        self, message_role, turn_id=None, sentence_id=None
    ):
        """Build ordering metadata without consuming the turn trace."""
        with self._lock:
            turn = self._resolve_turn(turn_id=turn_id, sentence_id=sentence_id)
            if not turn:
                return None
            return self._message_identity(turn, message_role)

    def _refresh_trace_totals(self, turn):
        if not turn:
            return
        trace = turn.setdefault("trace", {})
        steps = trace.setdefault("steps", [])
        totals = {}
        llm_steps = [step for step in steps if step.get("type") == "llm"]
        completed_llm_steps = [
            step for step in llm_steps if step.get("total_ms") is not None
        ]
        tool_steps = [step for step in steps if step.get("type") == "tool"]
        tts_steps = [step for step in steps if step.get("type") == "tts"]
        delivery_steps = [
            step for step in steps if step.get("type") == "audio_delivery"
        ]

        totals["llm_call_count"] = len(llm_steps)
        totals["llm_model_ms"] = sum(
            int(step.get("total_ms") or 0) for step in completed_llm_steps
        )
        runtime = turn.setdefault("_runtime", {})
        if (
            completed_llm_steps
            and runtime.get("llm_chain_started_at")
            and runtime.get("model_tool_chain_completed_at")
        ):
            totals["model_tool_chain_ms"] = _elapsed_between_ms(
                runtime["llm_chain_started_at"],
                runtime["model_tool_chain_completed_at"],
            )
        totals["tool_call_count"] = len(tool_steps)
        totals["tool_ms"] = sum(int(step.get("total_ms") or 0) for step in tool_steps)

        token_rows = [step.get("tokens") for step in completed_llm_steps if step.get("tokens")]
        if token_rows:
            sources = {row.get("source") for row in token_rows if row.get("source")}
            totals["tokens"] = {
                "prompt_tokens": sum(int(row.get("prompt_tokens") or 0) for row in token_rows),
                "completion_tokens": sum(
                    int(row.get("completion_tokens") or 0) for row in token_rows
                ),
                "total_tokens": sum(int(row.get("total_tokens") or 0) for row in token_rows),
                "source": next(iter(sources)) if len(sources) == 1 else "aggregate_mixed",
            }

        if tts_steps:
            tts_step = tts_steps[-1]
            totals["tts_request_to_first_audio_ms"] = tts_step.get(
                "request_to_first_audio_ms"
            )
            totals["tts_provider_generation_ms"] = tts_step.get(
                "provider_generation_ms"
            )
            totals["tts_report_ready_ms"] = tts_step.get("report_ready_ms")
            totals["output_audio_duration_ms"] = tts_step.get("audio_duration_ms")
        if delivery_steps:
            delivery_step = delivery_steps[-1]
            totals["server_delivery_from_first_audio_ms"] = delivery_step.get(
                "from_first_audio_ms"
            )
            totals["server_send_complete_from_tts_request_ms"] = delivery_step.get(
                "from_tts_request_ms"
            )
        trace["totals"] = totals

    def record_asr_first_text(self, text_length=None, turn_id=None):
        with self._lock:
            now_monotonic = time.monotonic()
            turn = self._resolve_turn(turn_id=turn_id) if turn_id else None
            if turn is not None:
                runtime = turn.setdefault("_runtime", {})
                if runtime.get("asr_first_text_ms") is None:
                    runtime["asr_first_text_ms"] = _elapsed_between_ms(
                        runtime.get("speech_started_at"), now_monotonic
                    )
                    runtime["asr_first_text_length"] = int(text_length or 0)
                return
            if self._vad_segment is not None:
                if self._vad_segment.get("asr_first_text_at_monotonic") is None:
                    self._vad_segment["asr_first_text_at_monotonic"] = now_monotonic
                    self._vad_segment["asr_first_text_length"] = int(text_length or 0)
                return
            turn = self._resolve_turn()
            if turn is None:
                return
            runtime = turn.setdefault("_runtime", {})
            if runtime.get("asr_first_text_ms") is None:
                runtime["asr_first_text_ms"] = _elapsed_between_ms(
                    runtime.get("speech_started_at"), now_monotonic
                )
                runtime["asr_first_text_length"] = int(text_length or 0)

    def record_asr_final_text(self, text_length=None, turn_id=None):
        with self._lock:
            now_monotonic = time.monotonic()
            turn = self._resolve_turn(turn_id=turn_id) if turn_id else None
            if turn is not None:
                runtime = turn.setdefault("_runtime", {})
                if runtime.get("asr_first_text_ms") is None:
                    runtime["asr_first_text_ms"] = _elapsed_between_ms(
                        runtime.get("speech_started_at"), now_monotonic
                    )
                    runtime["asr_first_text_length"] = int(text_length or 0)
                runtime["asr_final_text_ms"] = _elapsed_between_ms(
                    runtime.get("speech_started_at"), now_monotonic
                )
                runtime["asr_final_text_length"] = int(text_length or 0)
                return
            if self._vad_segment is not None:
                if self._vad_segment.get("asr_first_text_at_monotonic") is None:
                    self._vad_segment["asr_first_text_at_monotonic"] = now_monotonic
                    self._vad_segment["asr_first_text_length"] = int(text_length or 0)
                self._vad_segment["asr_final_text_at_monotonic"] = now_monotonic
                self._vad_segment["asr_final_text_length"] = int(text_length or 0)
                return
            turn = self._resolve_turn()
            if turn is None:
                return
            runtime = turn.setdefault("_runtime", {})
            if runtime.get("asr_first_text_ms") is None:
                runtime["asr_first_text_ms"] = _elapsed_between_ms(
                    runtime.get("speech_started_at"), now_monotonic
                )
                runtime["asr_first_text_length"] = int(text_length or 0)
            runtime["asr_final_text_ms"] = _elapsed_between_ms(
                runtime.get("speech_started_at"), now_monotonic
            )
            runtime["asr_final_text_length"] = int(text_length or 0)

    def record_asr(
        self,
        finalize_ms,
        success,
        text_length,
        first_text_ms=None,
        turn_id=None,
    ):
        with self._lock:
            resolved_turn_id = self.ensure_text_turn(turn_id)
            turn = self._resolve_turn(turn_id=resolved_turn_id)
            runtime = turn.setdefault("_runtime", {})
            final_text_ms = runtime.get("asr_final_text_ms")
            if final_text_ms is None:
                final_text_ms = _elapsed_ms(runtime.get("speech_started_at"))
            audio_duration_ms = runtime.get("audio_duration_ms")
            if first_text_ms is None:
                first_text_ms = runtime.get("asr_first_text_ms")
            if first_text_ms is None and success:
                first_text_ms = final_text_ms
            turn.setdefault("stages", {})["asr"] = {
                "first_text_from_speech_ms": first_text_ms,
                "final_text_from_speech_ms": final_text_ms,
                "tail_after_audio_ms": (
                    max(0, int(final_text_ms) - int(audio_duration_ms or 0))
                    if final_text_ms is not None and audio_duration_ms is not None
                    else None
                ),
                "provider_finalize_ms": max(0, int(finalize_ms or 0)),
                "first_text_length": runtime.get("asr_first_text_length"),
                "text_length": int(text_length or 0),
                "success": bool(success),
            }

    def record_voiceprint(
        self, elapsed_ms, enabled, speaker, speaker_id, score, turn_id=None
    ):
        with self._lock:
            resolved_turn_id = self.ensure_text_turn(turn_id)
            turn = self._resolve_turn(turn_id=resolved_turn_id)
            turn.setdefault("stages", {})["voiceprint"] = {
                "enabled": bool(enabled),
                "total_ms": max(0, int(elapsed_ms or 0)) if elapsed_ms is not None else None,
                "speaker": speaker or None,
                "speaker_id": speaker_id or None,
                "score": score,
            }

    def record_routing(self, accepted, turn_id=None):
        with self._lock:
            turn = self._resolve_turn(turn_id=turn_id)
            if turn is not None:
                turn.setdefault("stages", {})["routing"] = {
                    "accepted": bool(accepted)
                }

    def record_llm_started(
        self,
        tools_enabled,
        depth,
        dialogue=None,
        functions=None,
        turn_id=None,
    ):
        with self._lock:
            resolved_turn_id = self.ensure_text_turn(turn_id)
            turn = self._resolve_turn(turn_id=resolved_turn_id)
            now_monotonic = time.monotonic()
            runtime = turn.setdefault("_runtime", {})
            runtime.setdefault("llm_chain_started_at", now_monotonic)
            call_index = len(
                [
                    step
                    for step in turn["trace"]["steps"]
                    if step.get("type") == "llm"
                ]
            ) + 1
            prompt_tokens = _estimated_token_count(dialogue)
            tool_tokens = _estimated_token_count(functions)
            step = self._append_step(
                turn,
                "llm",
                call_index=call_index,
                depth=depth,
                tools_enabled=bool(tools_enabled),
                input_message_count=len(dialogue or []),
                tool_schema_count=len(functions or []),
                tokens={
                    "prompt_tokens": prompt_tokens + tool_tokens,
                    "prompt_tokens_from_messages": prompt_tokens,
                    "prompt_tokens_from_tools": tool_tokens,
                    "completion_tokens": None,
                    "total_tokens": prompt_tokens + tool_tokens,
                    "source": "estimated_input_chars",
                },
            )
            runtime.setdefault("llm_calls", {})[call_index] = {
                "started_at": now_monotonic,
                "step": step,
            }
            self._refresh_trace_totals(turn)

    def _active_llm_call(self, turn, depth=None):
        runtime_calls = turn.setdefault("_runtime", {}).setdefault(
            "llm_calls", {}
        )
        for call_index in sorted(runtime_calls, reverse=True):
            call = runtime_calls[call_index]
            step = call["step"]
            if step.get("total_ms") is not None:
                continue
            if depth is None or step.get("depth") == depth:
                return call
        return None

    def record_llm_first_chunk(self, depth=None, turn_id=None):
        with self._lock:
            turn = self._resolve_turn(turn_id=turn_id)
            if not turn:
                return
            call = self._active_llm_call(turn, depth)
            if not call or call["step"].get("first_chunk_ms") is not None:
                return
            call["step"]["first_chunk_ms"] = _elapsed_ms(call["started_at"])
            self._refresh_trace_totals(turn)

    def record_llm_complete(
        self,
        content_text,
        usage,
        tool_call,
        tool_count,
        depth=None,
        turn_id=None,
    ):
        with self._lock:
            turn = self._resolve_turn(turn_id=turn_id)
            if not turn:
                return
            call = self._active_llm_call(turn, depth)
            if not call:
                return
            step = call["step"]
            step.update(
                {
                    "total_ms": _elapsed_ms(call["started_at"]),
                    "tool_call": bool(tool_call),
                    "tool_count": int(tool_count or 0),
                    "output_chars": len(content_text or ""),
                }
            )
            if isinstance(usage, dict) and usage:
                step["tokens"] = {
                    "prompt_tokens": usage.get("prompt_tokens"),
                    "completion_tokens": usage.get("completion_tokens"),
                    "total_tokens": usage.get("total_tokens"),
                    "source": "provider_usage",
                }
            elif content_text:
                completion_tokens = _estimated_token_count(content_text)
                prompt_tokens = step.get("tokens", {}).get("prompt_tokens")
                step["tokens"] = {
                    **step.get("tokens", {}),
                    "completion_tokens": completion_tokens,
                    "total_tokens": int(prompt_tokens or 0) + completion_tokens,
                    "source": "estimated_input_output_chars",
                }
            turn.setdefault("_runtime", {})[
                "model_tool_chain_completed_at"
            ] = time.monotonic()
            self._refresh_trace_totals(turn)

    def record_tool_call(
        self,
        name,
        elapsed_ms,
        action=None,
        success=True,
        depth=None,
        turn_id=None,
    ):
        with self._lock:
            turn = self._resolve_turn(turn_id=turn_id)
            if not turn:
                return
            call_index = len(
                [
                    step
                    for step in turn["trace"]["steps"]
                    if step.get("type") == "tool"
                ]
            ) + 1
            self._append_step(
                turn,
                "tool",
                call_index=call_index,
                depth=depth,
                name=name,
                total_ms=max(0, int(elapsed_ms or 0)),
                action=action or None,
                success=bool(success),
            )
            turn.setdefault("_runtime", {})[
                "model_tool_chain_completed_at"
            ] = time.monotonic()
            self._refresh_trace_totals(turn)

    def record_tts_start(self, sentence_id, turn_id=None):
        with self._lock:
            turn = self._resolve_turn(sentence_id=sentence_id)
            if turn is None and turn_id is not None:
                turn = self._resolve_turn(turn_id=turn_id)
            if not turn:
                return
            self._sentence_turn_ids.setdefault(sentence_id, turn["turn_id"])
            runtime = turn.setdefault("_runtime", {})
            if runtime.get("tts_started_at"):
                return
            runtime["tts_started_at"] = time.monotonic()
            runtime["tts_step"] = self._append_step(
                turn,
                "tts",
                sentence_id=sentence_id,
                audio_frame_count=0,
                audio_bytes=0,
                audio_duration_ms=0,
            )
            self._refresh_trace_totals(turn)

    def record_tts_audio_frame(self, audio_bytes, sentence_id=None):
        with self._lock:
            turn = self._resolve_turn(sentence_id=sentence_id)
            if not turn:
                return
            runtime = turn.setdefault("_runtime", {})
            step = runtime.get("tts_step")
            if step is None:
                runtime["tts_started_at"] = time.monotonic()
                runtime["tts_start_missing"] = True
                step = self._append_step(
                    turn,
                    "tts",
                    sentence_id=sentence_id,
                    measurement_error="tts_request_start_missing",
                    audio_frame_count=0,
                    audio_bytes=0,
                    audio_duration_ms=0,
                )
                runtime["tts_step"] = step
            if "request_to_first_audio_ms" not in step:
                runtime["tts_first_audio_at"] = time.monotonic()
                step["request_to_first_audio_ms"] = (
                    None
                    if runtime.get("tts_start_missing")
                    else _elapsed_ms(runtime.get("tts_started_at"))
                )
            step["audio_frame_count"] += 1
            step["audio_bytes"] += int(audio_bytes or 0)
            step["audio_duration_ms"] = step["audio_frame_count"] * 60
            self._refresh_trace_totals(turn)

    def record_audio_delivery_complete(self, sentence_id=None):
        with self._lock:
            turn = self._resolve_turn(sentence_id=sentence_id)
            if not turn:
                return
            runtime = turn.setdefault("_runtime", {})
            if runtime.get("audio_delivery_recorded"):
                return
            runtime["audio_delivery_recorded"] = True
            self._append_step(
                turn,
                "audio_delivery",
                boundary="server_websocket_send_complete",
                from_first_audio_ms=_elapsed_ms(runtime.get("tts_first_audio_at")),
                from_tts_request_ms=(
                    None
                    if runtime.get("tts_start_missing")
                    else _elapsed_ms(runtime.get("tts_started_at"))
                ),
                device_playback_confirmed=False,
            )
            self._refresh_trace_totals(turn)

    def record_tts_generation_complete(self, sentence_id=None):
        with self._lock:
            turn = self._resolve_turn(sentence_id=sentence_id)
            if not turn:
                return
            runtime = turn.setdefault("_runtime", {})
            step = runtime.get("tts_step")
            if step is None:
                return
            step["provider_generation_ms"] = (
                None
                if runtime.get("tts_start_missing")
                else _elapsed_ms(runtime.get("tts_started_at"))
            )
            self._refresh_trace_totals(turn)

    def build_user_metrics(self, turn_id=None):
        with self._lock:
            turn = self._resolve_turn(turn_id=turn_id)
            if not turn:
                return None
            runtime = turn.setdefault("_runtime", {})
            identity = runtime.get("user_message_identity")
            if identity is None:
                identity = self._message_identity(turn, "user")
                runtime["user_message_identity"] = identity
            metrics = copy.deepcopy(turn)
            metrics.pop("_runtime", None)
            metrics.update(identity)
            return metrics

    def build_assistant_metrics(self, text, sentence_id=None, turn_id=None):
        with self._lock:
            turn = self._resolve_turn(turn_id=turn_id, sentence_id=sentence_id)
            if not turn:
                return None
            runtime = turn.setdefault("_runtime", {})
            step = runtime.get("tts_step")
            if step is not None:
                step["report_ready_ms"] = (
                    None
                    if runtime.get("tts_start_missing")
                    else _elapsed_ms(runtime.get("tts_started_at"))
                )
                step["text_chars"] = len(text or "")
            self._refresh_trace_totals(turn)
            identity = self._message_identity(turn, "assistant")
            metrics = copy.deepcopy(turn)
            metrics.pop("_runtime", None)
            metrics.update(identity)
            self._remove_turn(turn)
            return metrics
