import os
import io
import copy
import wave
import uuid
import json
import time
import queue
import shutil
import asyncio
import tempfile
import math
import traceback
import threading
import opuslib_next

from abc import ABC, abstractmethod
from config.logger import setup_logging
from core.providers.asr.dto.dto import InterfaceType
from core.handle.receiveAudioHandle import startToChat
from core.handle.reportHandle import enqueue_asr_report
from core.session.turn_context import build_contextual_user_content
from core.utils.util import remove_punctuation_and_length
from core.handle.receiveAudioHandle import handleAudioMessage
from typing import Any, Dict, Optional, Tuple, List, NamedTuple, TYPE_CHECKING


if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__
logger = setup_logging()


class ASRProviderBase(ABC):
    def __init__(self):
        self._current_artifacts = None

    # 打开音频通道
    async def open_audio_channels(self, conn: "ConnectionHandler"):
        conn.asr_priority_thread = threading.Thread(
            target=self.asr_text_priority_thread, args=(conn,), daemon=True
        )
        conn.asr_priority_thread.start()

    # 有序处理ASR音频
    def asr_text_priority_thread(self, conn: "ConnectionHandler"):
        while not conn.stop_event.is_set():
            try:
                message = conn.asr_audio_queue.get(timeout=1)
                future = asyncio.run_coroutine_threadsafe(
                    handleAudioMessage(conn, message),
                    conn.loop,
                )
                # 已知瓶颈：future.result() 会阻塞 ASR 消费者线程直到协程完成。
                # 在同步上下文中这是必要的，但阻塞期间新音频包将堆积在队列中。
                # 架构改进方案：改为异步生产者-消费者模式，或使用回压机制控制队列长度。  # noqa: SIM115
                future.result()
            except queue.Empty:
                continue
            except Exception as e:
                logger.bind(tag=TAG).error(
                    f"处理ASR文本失败: {str(e)}, 类型: {type(e).__name__}, 堆栈: {traceback.format_exc()}"
                )
                continue

    # 接收音频
    async def receive_audio(self, conn: "ConnectionHandler", audio, audio_have_voice):
        if conn.client_listen_mode == "manual":
            # 手动模式：缓存音频用于ASR识别
            conn.asr_audio.append(audio)
        else:
            # 自动/实时模式：使用VAD检测
            conn.asr_audio.append(audio)

            # 如果没有语音，且之前也没有声音，缓存部分音频
            if not audio_have_voice and not conn.client_have_voice:
                preroll_packets = 10
                vad = getattr(conn, "vad", None)
                if (
                    vad is not None
                    and hasattr(vad, "is_speech_gate_enforced")
                    and vad.is_speech_gate_enforced(conn)
                    and hasattr(vad, "get_preroll_ms")
                ):
                    preroll_ms = max(0, int(vad.get_preroll_ms(conn)))
                    welcome_msg = getattr(conn, "welcome_msg", {}) or {}
                    audio_params = (
                        welcome_msg.get("audio_params") or {}
                        if isinstance(welcome_msg, dict)
                        else {}
                    )
                    try:
                        packet_duration_ms = max(
                            1, int(audio_params.get("frame_duration", 60))
                        )
                    except (TypeError, ValueError):
                        packet_duration_ms = 60
                    preroll_packets = int(
                        math.ceil(preroll_ms / float(packet_duration_ms))
                    )
                conn.asr_audio = (
                    conn.asr_audio[-preroll_packets:]
                    if preroll_packets > 0
                    else []
                )
                return

            # 自动模式下通过VAD检测到语音停止时触发识别
            if conn.asr.interface_type != InterfaceType.STREAM and conn.client_voice_stop:
                asr_audio_task = conn.asr_audio.copy()
                conn.reset_audio_states(preserve_voiceprint=True)

                if len(asr_audio_task) > 15:
                    await self.handle_voice_stop(conn, asr_audio_task)
                elif conn.voiceprint_provider is not None:
                    conn.voiceprint_provider.reset_utterance()

    # 处理语音停止
    async def handle_voice_stop(self, conn: "ConnectionHandler", asr_audio_task: List[bytes]):
        """VAD句末后并行处理ASR与整句声纹识别。"""
        turn_context_buffer = getattr(conn, "turn_context", None)
        turn_context = (
            turn_context_buffer.take() if turn_context_buffer is not None else None
        )
        try:
            total_start_time = time.monotonic()
            enrollment = getattr(conn, "voiceprint_enrollment", None)
            if enrollment is not None and enrollment.status.active:
                consumed = await enrollment.collect_utterance(
                    conn,
                    asr_audio_task,
                    conn.audio_format,
                )
                if consumed:
                    logger.bind(tag=TAG).info(
                        "声纹采集模式已消费当前语音分段，跳过ASR/LLM"
                    )
                    if conn.voiceprint_provider is not None:
                        conn.voiceprint_provider.reset_utterance()
                    conn.turn_metrics.discard_vad_segment()
                    return

            turn_id = conn.turn_metrics.begin_turn(
                asr_audio_task, conn.audio_format
            )

            wav_data = None
            if conn.voiceprint_provider is not None:
                wav_data = self._audio_frames_to_wav(
                    asr_audio_task,
                    conn.audio_format,
                )

            # 定义ASR任务
            asr_task = self._timed_awaitable(
                self.speech_to_text_wrapper(
                    asr_audio_task, conn.session_id, conn.audio_format
                )
            )

            voiceprint_task = None
            if conn.voiceprint_provider is not None and wav_data:
                voiceprint_task = asyncio.create_task(
                    self._timed_awaitable(
                        conn.voiceprint_provider.identify_speaker(
                            wav_data,
                            conn.session_id,
                        )
                    )
                )

            asr_timed = await asr_task
            asr_result, asr_elapsed_ms = self._unwrap_timed_result(asr_timed)

            # 记录识别结果 - 检查是否为异常
            if isinstance(asr_result, Exception):
                logger.bind(tag=TAG).error(f"ASR识别失败: {asr_result}")
                raw_text = ""
                audio_artifacts = None
            else:
                raw_text, _ = asr_result
                audio_artifacts = self.get_current_artifacts()
            conn.turn_metrics.record_asr(
                asr_elapsed_ms,
                not isinstance(asr_result, Exception),
                len(str(raw_text or "")),
                turn_id=turn_id,
            )

            if voiceprint_task is not None:
                voiceprint_timed = await voiceprint_task
                voiceprint_result, voiceprint_elapsed_ms = self._unwrap_timed_result(
                    voiceprint_timed
                )
            else:
                voiceprint_result = None
                voiceprint_elapsed_ms = None

            if isinstance(voiceprint_result, Exception):
                logger.bind(tag=TAG).error(f"声纹识别失败: {voiceprint_result}")
                speaker_name = ""
                speaker_id = None
                speaker_score = None
            else:
                speaker_name = getattr(voiceprint_result, "name", voiceprint_result) or ""
                speaker_id = getattr(voiceprint_result, "speaker_id", None)
                speaker_score = getattr(voiceprint_result, "score", None)
            conn.turn_metrics.record_voiceprint(
                voiceprint_elapsed_ms,
                conn.voiceprint_provider is not None,
                speaker_name,
                speaker_id,
                speaker_score,
                turn_id=turn_id,
            )

            # 判断 ASR 结果类型
            if isinstance(raw_text, dict):
                # FunASR 返回的 dict 格式
                await self._resolve_meeting_utterance_speakers(
                    conn,
                    raw_text,
                    audio_artifacts,
                    speaker_name,
                    speaker_id,
                    speaker_score,
                )
                if speaker_name:
                    raw_text["speaker"] = speaker_name
                    if speaker_id:
                        raw_text["speaker_id"] = speaker_id
                    if speaker_score is not None:
                        raw_text["speaker_score"] = round(float(speaker_score), 4)

                # 记录识别结果
                if raw_text.get("language"):
                    logger.bind(tag=TAG).info(f"识别语言: {raw_text['language']}")
                if raw_text.get("emotion"):
                    logger.bind(tag=TAG).info(f"识别情绪: {raw_text['emotion']}")
                if raw_text.get("content"):
                    logger.bind(tag=TAG).info(f"识别文本: {raw_text['content']}")
                if speaker_name:
                    logger.bind(tag=TAG).info(f"识别说话人: {speaker_name}")
                if raw_text.get("utterances"):
                    logger.bind(tag=TAG).debug(
                        f"识别到分段数量: {len(raw_text.get('utterances') or [])}"
                    )

                # 报告保留声纹置信度用于诊断；主 LLM 只接收身份，不接收内部评分。
                report_text = json.dumps(raw_text, ensure_ascii=False)
                enhanced_text = json.dumps(
                    self._without_speaker_scores(raw_text), ensure_ascii=False
                )
                content_for_length_check = raw_text.get("content", "")
            else:
                # 其他 ASR 返回的纯文本
                if raw_text:
                    logger.bind(tag=TAG).info(f"识别文本: {raw_text}")
                if speaker_name:
                    logger.bind(tag=TAG).info(f"识别说话人: {speaker_name}")

                # 构建包含说话人信息的JSON字符串
                enhanced_text = self._build_enhanced_text(
                    raw_text, speaker_name, speaker_id, speaker_score
                )
                report_text = self._build_enhanced_text(
                    raw_text,
                    speaker_name,
                    speaker_id,
                    speaker_score,
                    include_speaker_score=True,
                )
                content_for_length_check = raw_text

            # 性能监控
            total_time = time.monotonic() - total_start_time
            logger.bind(tag=TAG).debug(f"总处理耗时: {total_time:.3f}s")

            # 检查文本长度
            text_len, _ = remove_punctuation_and_length(content_for_length_check)
            self.stop_ws_connection()

            if text_len > 0:
                audio_snapshot = asr_audio_task.copy()
                # Intent/semantic tools may report synchronously before this method
                # returns. Reserve the user row first so logical order stays
                # user -> tool -> assistant regardless of report completion time.
                conn.turn_metrics.reserve_user_message(turn_id)
                # 使用自定义模块进行上报
                accepted = await startToChat(
                    conn,
                    enhanced_text,
                    turn_context=turn_context,
                    turn_id=turn_id,
                )
                conn.turn_metrics.record_routing(accepted, turn_id=turn_id)
                if accepted:
                    enqueue_asr_report(
                        conn,
                        build_contextual_user_content(
                            report_text,
                            turn_context,
                        ),
                        audio_snapshot,
                        metrics=conn.turn_metrics.build_user_metrics(turn_id),
                    )
                else:
                    conn.turn_metrics.discard_active_turn(turn_id)
            else:
                conn.turn_metrics.discard_active_turn(turn_id)
        except Exception as e:
            logger.bind(tag=TAG).error(f"处理语音停止失败: {e}")
            import traceback

            logger.bind(tag=TAG).debug(f"异常详情: {traceback.format_exc()}")

    async def _timed_awaitable(self, awaitable):
        started_at = time.monotonic()
        try:
            result = await awaitable
            return result, int((time.monotonic() - started_at) * 1000)
        except Exception as exc:
            return exc, int((time.monotonic() - started_at) * 1000)

    def _unwrap_timed_result(self, timed_result):
        if isinstance(timed_result, Exception):
            return timed_result, None
        if (
            isinstance(timed_result, tuple)
            and len(timed_result) == 2
            and isinstance(timed_result[1], int)
        ):
            return timed_result
        return timed_result, None

    def record_first_text_metric(self, conn: "ConnectionHandler", text):
        if not text:
            return
        turn_metrics = getattr(conn, "turn_metrics", None)
        if turn_metrics is None:
            return
        try:
            turn_metrics.record_asr_first_text(len(str(text)))
        except Exception as exc:
            logger.bind(tag=TAG).debug(f"记录ASR首段指标失败: {exc}")

    def record_final_text_metric(self, conn: "ConnectionHandler", text):
        if not text:
            return
        turn_metrics = getattr(conn, "turn_metrics", None)
        if turn_metrics is None:
            return
        try:
            turn_metrics.record_asr_final_text(len(str(text)))
        except Exception as exc:
            logger.bind(tag=TAG).debug(f"记录ASR最终文本指标失败: {exc}")

    def _audio_frames_to_wav(self, audio_frames: List[bytes], audio_format: str) -> bytes:
        if not audio_frames:
            return b""
        if audio_format == "pcm":
            pcm_frames = audio_frames
        else:
            pcm_frames = self.decode_opus(audio_frames)
        return self._pcm_to_wav(b"".join(pcm_frames))

    def _build_enhanced_text(
        self,
        text: str,
        speaker_name: Optional[str],
        speaker_id: Optional[str] = None,
        speaker_score: Optional[float] = None,
        include_speaker_score: bool = False,
    ) -> str:
        """构建说话人文本；内部置信度默认不进入主 LLM。"""
        if speaker_name and speaker_name.strip():
            payload = {"speaker": speaker_name, "content": text}
            if speaker_id:
                payload["speaker_id"] = speaker_id
            if include_speaker_score and speaker_score is not None:
                payload["speaker_score"] = round(float(speaker_score), 4)
            return json.dumps(
                payload, ensure_ascii=False
            )
        else:
            return text

    @staticmethod
    def _without_speaker_scores(payload: Dict[str, Any]) -> Dict[str, Any]:
        """Copy an ASR payload while removing internal voiceprint confidence."""
        sanitized = copy.deepcopy(payload)

        def strip_scores(value):
            if isinstance(value, dict):
                value.pop("speaker_score", None)
                for nested in value.values():
                    strip_scores(nested)
            elif isinstance(value, list):
                for nested in value:
                    strip_scores(nested)

        strip_scores(sanitized)
        return sanitized

    async def _resolve_meeting_utterance_speakers(
        self,
        conn: "ConnectionHandler",
        payload: Dict[str, Any],
        artifacts: Optional["ASRProviderBase.AudioArtifacts"],
        fallback_speaker_name: str,
        fallback_speaker_id: Optional[str],
        fallback_speaker_score: Optional[float],
    ) -> None:
        """Resolve diarized utterance labels with voiceprint during meeting mode."""
        meeting = getattr(conn, "meeting_minutes", None)
        if meeting is None or not meeting.is_active():
            return

        utterances = payload.get("utterances")
        if not isinstance(utterances, list) or not utterances:
            return

        if len(utterances) == 1 and fallback_speaker_name:
            self._apply_resolved_speaker(
                utterances[0],
                fallback_speaker_name,
                fallback_speaker_id,
                fallback_speaker_score,
            )
            return

        voiceprint_provider = getattr(conn, "voiceprint_provider", None)
        if voiceprint_provider is None or artifacts is None or not artifacts.pcm_bytes:
            return

        for utterance in utterances:
            if not isinstance(utterance, dict):
                continue
            start_ms, end_ms = self._extract_utterance_time_ms(utterance)
            if start_ms is None or end_ms is None or end_ms - start_ms < 500:
                continue
            pcm_slice = self._slice_pcm_by_ms(artifacts.pcm_bytes, start_ms, end_ms)
            wav_data = self._pcm_to_wav(pcm_slice)
            if not wav_data:
                continue
            try:
                match = await voiceprint_provider.identify_speaker(
                    wav_data,
                    conn.session_id,
                )
            except Exception as error:
                logger.bind(tag=TAG).warning(
                    "会议分段声纹识别失败: "
                    f"errorType={type(error).__name__}"
                )
                continue
            if match is None:
                continue
            speaker_name = getattr(match, "name", match) or ""
            if not speaker_name:
                continue
            self._apply_resolved_speaker(
                utterance,
                speaker_name,
                getattr(match, "speaker_id", None),
                getattr(match, "score", None),
            )

    @staticmethod
    def _apply_resolved_speaker(
        utterance: Dict[str, Any],
        speaker_name: str,
        speaker_id: Optional[str],
        speaker_score: Optional[float],
    ) -> None:
        utterance["resolved_speaker"] = speaker_name
        if speaker_id:
            utterance["resolved_speaker_id"] = speaker_id
        if speaker_score is not None:
            utterance["speaker_score"] = round(float(speaker_score), 4)

    @staticmethod
    def _extract_utterance_time_ms(
        utterance: Dict[str, Any],
    ) -> Tuple[Optional[float], Optional[float]]:
        start = _first_present(
            utterance,
            "start_time",
            "startTime",
            "start_ms",
            "startMs",
            "begin_time",
            "beginTime",
            "start",
        )
        end = _first_present(
            utterance,
            "end_time",
            "endTime",
            "end_ms",
            "endMs",
            "stop_time",
            "stopTime",
            "end",
        )
        start_ms = _coerce_time_ms(start)
        end_ms = _coerce_time_ms(end)
        if (
            start_ms is not None
            and end_ms is not None
            and 0 <= start_ms < end_ms <= 300
            and end_ms - start_ms <= 60
        ):
            start_ms *= 1000
            end_ms *= 1000
        return start_ms, end_ms

    @staticmethod
    def _slice_pcm_by_ms(pcm_bytes: bytes, start_ms: float, end_ms: float) -> bytes:
        bytes_per_second = 16000 * 2
        start_byte = max(0, int(start_ms / 1000 * bytes_per_second))
        end_byte = max(start_byte, int(end_ms / 1000 * bytes_per_second))
        start_byte -= start_byte % 2
        end_byte -= end_byte % 2
        return pcm_bytes[start_byte:end_byte]

    def _pcm_to_wav(self, pcm_data: bytes) -> bytes:
        """将PCM数据转换为WAV格式"""
        if len(pcm_data) == 0:
            logger.bind(tag=TAG).warning("PCM数据为空，无法转换WAV")
            return b""

        # 确保数据长度是偶数（16位音频）
        if len(pcm_data) % 2 != 0:
            pcm_data = pcm_data[:-1]

        # 创建WAV文件头
        wav_buffer = io.BytesIO()
        try:
            with wave.open(wav_buffer, "wb") as wav_file:
                wav_file.setnchannels(1)  # 单声道
                wav_file.setsampwidth(2)  # 16位
                wav_file.setframerate(16000)  # 16kHz采样率
                wav_file.writeframes(pcm_data)

            wav_buffer.seek(0)
            wav_data = wav_buffer.read()

            return wav_data
        except Exception as e:
            logger.bind(tag=TAG).error(f"WAV转换失败: {e}")
            return b""

    def stop_ws_connection(self):
        pass

    async def close(self):
        pass

    class AudioArtifacts(NamedTuple):
        pcm_frames: List[bytes]
        """PCM音频帧列表"""
        pcm_bytes: bytes
        """合并后的PCM音频字节数据"""
        file_path: Optional[str]
        """WAV文件路径"""
        temp_path: Optional[str]
        """临时WAV文件路径"""

    def get_current_artifacts(self) -> Optional["ASRProviderBase.AudioArtifacts"]:
        return self._current_artifacts

    def requires_file(self) -> bool:
        """是否需要文件输入"""
        return False

    def prefers_temp_file(self) -> bool:
        """是否优先使用临时文件"""
        return False

    def build_temp_file(self, pcm_bytes: bytes) -> Optional[str]:
        try:
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as temp_file:
                temp_path = temp_file.name
            with wave.open(temp_path, "wb") as wav_file:
                wav_file.setnchannels(1)
                wav_file.setsampwidth(2)
                wav_file.setframerate(16000)
                wav_file.writeframes(pcm_bytes)
            return temp_path
        except Exception as e:
            logger.bind(tag=TAG).error(f"临时音频文件生成失败: {e}")
            return None

    def save_audio_to_file(self, pcm_data: List[bytes], session_id: str) -> str:
        """PCM数据保存为WAV文件"""
        module_name = __name__.split(".")[-1]
        file_name = f"asr_{module_name}_{session_id}_{uuid.uuid4()}.wav"
        file_path = os.path.join(self.output_dir, file_name)

        with wave.open(file_path, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)  # 2 bytes = 16-bit
            wf.setframerate(16000)
            wf.writeframes(b"".join(pcm_data))

        return file_path

    async def speech_to_text_wrapper(
        self, opus_data: List[bytes], session_id: str, audio_format="opus"
    ) -> Tuple[Optional[Any], Optional[str]]:
        file_path = None
        temp_path = None
        try:
            if audio_format == "pcm":
                pcm_data = opus_data
            else:
                pcm_data = self.decode_opus(opus_data)
            combined_pcm_data = b"".join(pcm_data)

            free_space = shutil.disk_usage(self.output_dir).free
            if free_space < len(combined_pcm_data) * 2:
                raise OSError("磁盘空间不足")

            if self.requires_file() and self.prefers_temp_file():
                temp_path = self.build_temp_file(combined_pcm_data)

            if (hasattr(self, "delete_audio_file") and not self.delete_audio_file) or (
                self.requires_file() and not self.prefers_temp_file()
            ):
                file_path = self.save_audio_to_file(pcm_data, session_id)

            if len(combined_pcm_data) == 0:
                artifacts = None
            else:
                artifacts = ASRProviderBase.AudioArtifacts(
                    pcm_frames=pcm_data,
                    pcm_bytes=combined_pcm_data,
                    file_path=file_path,
                    temp_path=temp_path,
                )
            self._current_artifacts = artifacts

            text, _ = await self.speech_to_text(
                opus_data, session_id, audio_format, artifacts
            )
            return text, file_path
        except OSError as e:
            logger.bind(tag=TAG).error(f"文件操作错误: {e}")
            return None, None
        except Exception as e:
            logger.bind(tag=TAG).error(f"语音识别失败: {e}")
            return None, None
        finally:
            try:
                if temp_path and os.path.exists(temp_path):
                    os.unlink(temp_path)
                if (
                    hasattr(self, "delete_audio_file")
                    and self.delete_audio_file
                    and file_path
                    and os.path.exists(file_path)
                ):
                    os.remove(file_path)
            except Exception as e:
                logger.bind(tag=TAG).error(f"文件清理失败: {e}")

    @abstractmethod
    async def speech_to_text(
        self,
        opus_data: List[bytes],
        session_id: str,
        audio_format="opus",
        artifacts: Optional[AudioArtifacts] = None,
    ) -> Tuple[Optional[str], Optional[str]]:
        """将语音数据转换为文本

        :param opus_data: 输入的Opus音频数据
        :param session_id: 会话ID
        :param audio_format: 音频格式，默认"opus"
        :param artifacts: 音频工件，包含PCM数据、文件路径等
        :return: 识别结果文本和文件路径（如果有）
        """
        pass

    @staticmethod
    def decode_opus(opus_data: List[bytes]) -> List[bytes]:
        """将Opus音频数据解码为PCM数据"""
        decoder = None
        try:
            decoder = opuslib_next.Decoder(16000, 1)
            pcm_data = []
            buffer_size = 960  # 每次处理960个采样点 (60ms at 16kHz)

            for i, opus_packet in enumerate(opus_data):
                try:
                    if not opus_packet or len(opus_packet) == 0:
                        continue

                    pcm_frame = decoder.decode(opus_packet, buffer_size)
                    if pcm_frame and len(pcm_frame) > 0:
                        pcm_data.append(pcm_frame)

                except opuslib_next.OpusError as e:
                    logger.bind(tag=TAG).warning(f"Opus解码错误，跳过数据包 {i}: {e}")
                except Exception as e:
                    logger.bind(tag=TAG).error(f"音频处理错误，数据包 {i}: {e}")

            return pcm_data

        except Exception as e:
            logger.bind(tag=TAG).error(f"音频解码过程发生错误: {e}")
            return []
        finally:
            if decoder is not None:
                try:
                    del decoder
                except Exception as e:
                    logger.bind(tag=TAG).debug(f"释放decoder资源时出错: {e}")


def _first_present(payload: Dict[str, Any], *keys: str) -> Any:
    for key in keys:
        value = payload.get(key)
        if value is not None:
            return value
    return None


def _coerce_time_ms(value: Any) -> Optional[float]:
    if value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number < 0:
        return None
    return number
