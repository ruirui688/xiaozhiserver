import time
import os
from dataclasses import replace
import numpy as np
import opuslib_next
import onnxruntime
from config.logger import setup_logging
from core.providers.vad.base import VADProviderBase
from core.utils.model_config import get_runtime_config
from core.audio.speech_activity import (
    SpeechActivityController,
    SpeechGateConfig,
    VadObservation,
)

TAG = __name__
logger = setup_logging()


class VADProvider(VADProviderBase):
    def __init__(self, config):
        logger.bind(tag=TAG).info("SileroVAD", config)

        model_path = os.path.join(
            config["model_dir"], "src", "silero_vad", "data", "silero_vad.onnx"
        )
        opts = onnxruntime.SessionOptions()
        opts.inter_op_num_threads = 1
        opts.intra_op_num_threads = 1
        self.session = onnxruntime.InferenceSession(
            model_path, providers=["CPUExecutionProvider"], sess_options=opts
        )

        threshold = config.get("threshold", "0.5")
        threshold_low = config.get("threshold_low", "0.2")
        min_silence_duration_ms = config.get("min_silence_duration_ms", "1000")

        self.vad_threshold = float(threshold) if threshold else 0.5
        self.vad_threshold_low = float(threshold_low) if threshold_low else 0.2

        self.silence_threshold_ms = (
            int(min_silence_duration_ms) if min_silence_duration_ms else 1000
        )

        self.frame_window_threshold = 3
        self.speech_gate_config = SpeechGateConfig.from_mapping(
            config.get("speech_gate")
        )

    def _connection_vad_config(self, conn):
        config = getattr(conn, "config", {}) or {}
        selected_vad = (config.get("selected_module") or {}).get("VAD")
        if selected_vad:
            runtime_config = get_runtime_config(config, "VAD", selected_vad)
            if isinstance(runtime_config, dict):
                return runtime_config
        return {}

    def _connection_gate_config(self, conn):
        runtime_config = self._connection_vad_config(conn)
        if "speech_gate" in runtime_config:
            return SpeechGateConfig.from_mapping(runtime_config.get("speech_gate"))
        return self.speech_gate_config

    def _init_connection_state(self, conn):
        """为连接初始化独立的 VAD 状态"""
        if not hasattr(conn, "_vad_opus_decoder"):
            conn._vad_opus_decoder = opuslib_next.Decoder(16000, 1)
        if not hasattr(conn, "_vad_state"):
            conn._vad_state = np.zeros((2, 1, 128), dtype=np.float32)
        if not hasattr(conn, "_vad_context"):
            conn._vad_context = np.zeros((1, 64), dtype=np.float32)
        gate_config = self._connection_gate_config(conn)
        if getattr(conn, "_speech_gate_config", None) != gate_config:
            previous_state = getattr(conn, "_speech_activity_state", None)
            controller = SpeechActivityController(gate_config)
            state = controller.new_state()
            if previous_state is not None:
                state.noise_floor_dbfs = previous_state.noise_floor_dbfs
            conn._speech_gate_config = gate_config
            conn._speech_activity_controller = controller
            conn._speech_activity_state = state

    def release_conn_resources(self, conn):
        """释放连接的 VAD 资源（连接关闭时调用）"""
        for attr in (
            "_vad_opus_decoder",
            "_vad_state",
            "_vad_context",
            "_speech_activity_state",
            "_speech_activity_controller",
            "_speech_gate_config",
            "_vad_last_observation",
            "_vad_last_decision",
        ):
            if hasattr(conn, attr):
                try:
                    delattr(conn, attr)
                except Exception:
                    pass

    def reset_conn_state(self, conn, reset_model=False):
        """Reset per-utterance state without implicitly resetting the model.

        A normal endpoint only closes the current speech window.  A new
        listening round (or an explicit abort) must also discard Silero's
        recurrent state and its 64-sample context, otherwise the next round
        inherits acoustic history from the interrupted utterance.
        """
        if reset_model:
            state = getattr(conn, "_vad_state", None)
            if state is not None:
                state[...] = 0
            context = getattr(conn, "_vad_context", None)
            if context is not None:
                context[...] = 0
        state = getattr(conn, "_speech_activity_state", None)
        if state is not None:
            state.reset_activity(preserve_noise_floor=True)
        conn._vad_last_decision = None
        conn._vad_last_observation = None

    def get_last_observation(self, conn):
        return getattr(conn, "_vad_last_observation", None)

    def get_last_decision(self, conn):
        return getattr(conn, "_vad_last_decision", None)

    def is_speech_gate_enforced(self, conn=None) -> bool:
        config = (
            self._connection_gate_config(conn)
            if conn is not None
            else self.speech_gate_config
        )
        return config.mode == "enforce"

    def get_speech_gate_mode(self, conn=None) -> str:
        config = (
            self._connection_gate_config(conn)
            if conn is not None
            else self.speech_gate_config
        )
        return config.mode

    def get_preroll_ms(self, conn=None) -> int:
        config = (
            self._connection_gate_config(conn)
            if conn is not None
            else self.speech_gate_config
        )
        return config.preroll_ms

    def _effective_silence_threshold_ms(self, conn):
        """Use a stricter end-of-speech threshold only during voiceprint enrollment."""
        runtime_config = self._connection_vad_config(conn)
        try:
            threshold_ms = int(
                runtime_config.get(
                    "min_silence_duration_ms", self.silence_threshold_ms
                )
            )
        except (TypeError, ValueError):
            threshold_ms = self.silence_threshold_ms
        enrollment = getattr(conn, "voiceprint_enrollment", None)
        status = getattr(enrollment, "status", None)
        if status is not None and getattr(status, "active", False):
            required_seconds = getattr(
                status,
                "completion_silence_seconds",
                2.0,
            )
            try:
                required_ms = max(2000, int(float(required_seconds) * 1000))
            except (TypeError, ValueError):
                required_ms = 2000
            threshold_ms = max(threshold_ms, required_ms)
        return threshold_ms

    def _effective_gate_tail_ms(self, conn):
        # Enforce mode must retain the selected VAD model's existing endpoint
        # contract. The acoustic tail budget may lengthen that silence window,
        # but must never shorten it (voiceprint enrollment is handled by the
        # same effective-silence helper).
        return max(
            self._connection_gate_config(conn).tail_grace_ms,
            self._effective_silence_threshold_ms(conn),
        )

    @staticmethod
    def _combine_decisions(decisions):
        if not decisions:
            return None
        last = decisions[-1]
        return replace(
            last,
            speech_started=any(item.speech_started for item in decisions),
            speech_ended=any(item.speech_ended for item in decisions),
            trusted_voice=any(item.trusted_voice for item in decisions),
            ambiguous_voice=any(item.ambiguous_voice for item in decisions),
        )

    @staticmethod
    def _combine_observations(observations):
        if not observations:
            return None
        decoded_samples = sum(item.decoded_samples for item in observations)
        weighted_power = sum(
            item.decoded_samples * (10.0 ** (item.rms_dbfs / 10.0))
            for item in observations
        )
        rms_dbfs = 10.0 * np.log10(
            max(weighted_power / max(1, decoded_samples), 1e-12)
        )
        return VadObservation(
            speech_probability=max(
                item.speech_probability for item in observations
            ),
            rms_dbfs=max(-96.0, float(rms_dbfs)),
            decoded_samples=decoded_samples,
            inference_ms=sum(item.inference_ms for item in observations),
        )

    def is_vad(self, conn, opus_packet):
        # 手动模式：直接返回True，不进行实时VAD检测，所有音频都缓存
        if conn.client_listen_mode == "manual":
            conn._vad_last_observation = None
            conn._vad_last_decision = None
            return True

        try:
            self._init_connection_state(conn)
            gate_config = conn._speech_gate_config
            speech_gate = conn._speech_activity_controller
            runtime_config = self._connection_vad_config(conn)
            try:
                vad_threshold = float(
                    runtime_config.get("threshold", self.vad_threshold)
                )
            except (TypeError, ValueError):
                vad_threshold = self.vad_threshold
            try:
                vad_threshold_low = float(
                    runtime_config.get("threshold_low", self.vad_threshold_low)
                )
            except (TypeError, ValueError):
                vad_threshold_low = self.vad_threshold_low

            pcm_frame = conn._vad_opus_decoder.decode(opus_packet, 960)
            conn.client_audio_buffer.extend(pcm_frame)

            client_have_voice = False
            gate_decisions = []
            gate_observations = []
            while len(conn.client_audio_buffer) >= 512 * 2:
                chunk = conn.client_audio_buffer[: 512 * 2]
                conn.client_audio_buffer = conn.client_audio_buffer[512 * 2 :]

                audio_int16 = np.frombuffer(chunk, dtype=np.int16)
                audio_float32 = audio_int16.astype(np.float32) / 32768.0
                audio_input = np.concatenate(
                    [conn._vad_context, audio_float32.reshape(1, -1)], axis=1
                ).astype(np.float32)

                ort_inputs = {
                    "input": audio_input,
                    "state": conn._vad_state,
                    "sr": np.array(16000, dtype=np.int64),
                }
                inference_started_at = time.monotonic()
                out, state = self.session.run(None, ort_inputs)
                inference_ms = int((time.monotonic() - inference_started_at) * 1000)

                conn._vad_state = state
                conn._vad_context = audio_input[:, -64:]
                speech_prob = out.item()
                gate_decision = None
                if gate_config.mode != "disabled":
                    rms_dbfs = speech_gate.rms_dbfs(audio_float32)
                    observation = VadObservation(
                        speech_probability=float(speech_prob),
                        rms_dbfs=rms_dbfs,
                        decoded_samples=len(audio_int16),
                        inference_ms=inference_ms,
                    )
                    gate_observations.append(observation)

                    gate_decision = speech_gate.process(
                        conn._speech_activity_state,
                        observation,
                        barge_in=bool(
                            getattr(conn, "client_aec", False)
                            and getattr(conn, "client_is_speaking", False)
                        ),
                        tail_grace_ms=self._effective_gate_tail_ms(conn),
                    )
                    gate_decisions.append(gate_decision)
                else:
                    conn._vad_last_observation = None

                # 双阈值判断
                if speech_prob >= vad_threshold:
                    is_voice = True
                elif speech_prob <= vad_threshold_low:
                    is_voice = False
                else:
                    is_voice = conn.last_is_voice

                # 声音没低于最低值则延续前一个状态，判断为有声音
                conn.last_is_voice = is_voice

                # 更新官方兼容滑动窗口；disabled/shadow 模式继续使用该结果。
                conn.client_voice_window.append(is_voice)
                legacy_have_voice = (
                    conn.client_voice_window.count(True) >= self.frame_window_threshold
                )

                if self.is_speech_gate_enforced(conn):
                    # Enforce mode always has a decision for a decoded frame.
                    client_have_voice = gate_decision.trusted_voice
                    if gate_decision.speech_started:
                        conn.client_have_voice = True
                    if gate_decision.trusted_voice:
                        conn.vad_last_voice_time = time.time() * 1000
                    if gate_decision.speech_ended and conn.client_have_voice:
                        conn.client_voice_stop = True
                else:
                    client_have_voice = legacy_have_voice

                # 如果之前有声音，但本次没有声音，且与上次有声音的时间差已经超过了静默阈值，则认为已经说完一句话
                if (
                    not self.is_speech_gate_enforced(conn)
                    and conn.client_have_voice
                    and not client_have_voice
                ):
                    stop_duration = time.time() * 1000 - conn.vad_last_voice_time
                    if stop_duration >= self._effective_silence_threshold_ms(conn):
                        conn.client_voice_stop = True
                if not self.is_speech_gate_enforced(conn) and client_have_voice:
                    conn.client_have_voice = True
                    conn.vad_last_voice_time = time.time() * 1000

            conn._vad_last_decision = self._combine_decisions(gate_decisions)
            conn._vad_last_observation = self._combine_observations(
                gate_observations
            )
            return bool(
                client_have_voice
                or any(item.trusted_voice for item in gate_decisions)
            )
        except opuslib_next.OpusError as e:
            logger.bind(tag=TAG).info(f"解码错误: {e}")
        except Exception as e:
            logger.bind(tag=TAG).error(f"Error processing audio packet: {e}")
