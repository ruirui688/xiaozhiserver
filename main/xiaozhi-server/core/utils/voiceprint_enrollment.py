import io
import json
import math
import time
import uuid
import wave
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, TYPE_CHECKING

import opuslib_next

from config.manage_api_client import (
    report as report_chat_history,
    report_voiceprint_enrollment_sample,
)

DEFAULT_VOICEPRINT_SAMPLE_TEXT = (
    "今天天气不错，我正在用正常音量录入声纹。读完这句话后，我会保持安静，等待系统完成检测。"
)

if TYPE_CHECKING:
    from core.connection import ConnectionHandler


@dataclass
class VoiceprintEnrollmentSample:
    index: int
    duration_ms: int
    bytes_size: int
    uploaded: bool
    response: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


@dataclass
class VoiceprintEnrollmentStatus:
    active: bool = False
    request_id: str = ""
    agent_id: str = ""
    source_name: str = ""
    introduce: str = ""
    sample_count: int = 0
    target_samples: int = 3
    prompt_text: str = DEFAULT_VOICEPRINT_SAMPLE_TEXT
    state: str = "idle"
    reason: str = ""
    samples: List[VoiceprintEnrollmentSample] = field(default_factory=list)

    def as_dict(self) -> Dict[str, Any]:
        return {
            "active": self.active,
            "requestId": self.request_id,
            "agentId": self.agent_id,
            "sourceName": self.source_name,
            "introduce": self.introduce,
            "sampleCount": self.sample_count,
            "targetSamples": self.target_samples,
            "promptText": self.prompt_text,
            "state": self.state,
            "reason": self.reason,
            "samples": [sample.__dict__ for sample in self.samples],
        }


class VoiceprintEnrollmentCollector:
    """Connection-level voiceprint enrollment sample collector.

    The collector owns only runtime state and upload boundaries. It does not
    create voiceprint records; manager-api remains responsible for ownership,
    sample count and final registration.
    """

    def __init__(self):
        self.status = VoiceprintEnrollmentStatus()
        self.min_duration_seconds = 3.0
        self.max_duration_seconds = 30.0
        self.upload_mode = "chat_history"
        self.report_endpoint = ""

    def start(self, options: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        options = options or {}
        target_samples = self._bounded_int(options.get("target_samples") or options.get("targetSamples"), 1, 5, 3)
        self.min_duration_seconds = self._bounded_float(
            options.get("min_duration_seconds") or options.get("minDurationSeconds"),
            0.3,
            30.0,
            3.0,
        )
        self.max_duration_seconds = self._bounded_float(
            options.get("max_duration_seconds") or options.get("maxDurationSeconds"),
            self.min_duration_seconds,
            60.0,
            30.0,
        )
        self.upload_mode = str(options.get("upload_mode") or options.get("uploadMode") or "chat_history").strip() or "chat_history"
        self.report_endpoint = str(options.get("report_endpoint") or options.get("reportEndpoint") or "").strip()
        self.status = VoiceprintEnrollmentStatus(
            active=True,
            request_id=str(options.get("request_id") or options.get("requestId") or uuid.uuid4().hex),
            agent_id=str(options.get("agent_id") or options.get("agentId") or "").strip(),
            source_name=str(options.get("source_name") or options.get("sourceName") or "").strip(),
            introduce=str(options.get("introduce") or options.get("description") or "").strip(),
            target_samples=target_samples,
            prompt_text=str(
                options.get("prompt_text")
                or options.get("promptText")
                or DEFAULT_VOICEPRINT_SAMPLE_TEXT
            ).strip() or DEFAULT_VOICEPRINT_SAMPLE_TEXT,
            state="collecting",
        )
        return self.status.as_dict()

    def stop(self, reason: str = "stopped") -> Dict[str, Any]:
        self.status.active = False
        self.status.state = "stopped"
        self.status.reason = reason
        return self.status.as_dict()

    def get_status(self) -> Dict[str, Any]:
        return self.status.as_dict()

    async def collect_utterance(
        self,
        conn: "ConnectionHandler",
        audio_frames: List[bytes],
        audio_format: str,
    ) -> bool:
        """Consume one VAD segment while enrollment mode is active."""
        if not self.status.active:
            return False

        sample_index = self.status.sample_count + 1
        try:
            wav_data = self._frames_to_wav(audio_frames, audio_format)
            duration_ms = self._wav_duration_ms(wav_data)
            if duration_ms <= 0:
                self._append_sample(sample_index, 0, len(wav_data), False, error="invalid_wav")
                self._notify(conn, "sample_rejected", "没有听到有效语音，请重新读一遍。")
                return True

            duration_seconds = duration_ms / 1000.0
            if duration_seconds < self.min_duration_seconds:
                self._append_sample(
                    sample_index,
                    duration_ms,
                    len(wav_data),
                    False,
                    error="sample_too_short",
                )
                self._notify(
                    conn,
                    "sample_rejected",
                    "这段语音太短了，请重新读一遍。",
                )
                return True
            if duration_seconds > self.max_duration_seconds:
                self._append_sample(
                    sample_index,
                    duration_ms,
                    len(wav_data),
                    False,
                    error="sample_too_long",
                )
                self._notify(
                    conn,
                    "sample_rejected",
                    "这段语音太长了，请重新读一遍。",
                )
                return True

            response = await self._upload_sample(conn, wav_data, sample_index, duration_ms)
            self._append_sample(
                sample_index,
                duration_ms,
                len(wav_data),
                True,
                response=response,
            )
            self.status.sample_count += 1
            if self.status.sample_count >= self.status.target_samples:
                self.status.active = False
                self.status.state = "completed"
                self.status.reason = "target_samples_collected"
                self._notify(
                    conn,
                    "completed",
                    "已采集完成，请点击结束录音并注册。",
                )
            else:
                remaining = self.status.target_samples - self.status.sample_count
                self._notify(
                    conn,
                    "sample_collected",
                    f"已采集 {self.status.sample_count} 段，还需要 {remaining} 段。",
                )
        except Exception as error:
            conn.logger.bind(tag=__name__).warning(
                "声纹采集样本处理失败: errorType={}".format(type(error).__name__)
            )
            self._append_sample(sample_index, 0, 0, False, error=type(error).__name__)
            self._notify(conn, "sample_failed", "声纹样本上传失败，请重新读一遍。")
        return True

    async def _upload_sample(
        self,
        conn: "ConnectionHandler",
        wav_data: bytes,
        sample_index: int,
        duration_ms: int,
    ) -> Optional[Dict[str, Any]]:
        metadata = {
            "type": "voiceprint_enrollment_sample",
            "requestId": self.status.request_id,
            "agentId": self.status.agent_id or conn.config.get("agent_id"),
            "sourceName": self.status.source_name,
            "introduce": self.status.introduce,
            "sampleIndex": sample_index,
            "durationMs": duration_ms,
        }
        if self.report_endpoint:
            return await report_voiceprint_enrollment_sample(
                endpoint=self.report_endpoint,
                mac_address=conn.device_id,
                session_id=conn.session_id,
                agent_id=metadata["agentId"],
                request_id=self.status.request_id,
                source_name=self.status.source_name,
                introduce=self.status.introduce,
                sample_index=sample_index,
                duration_ms=duration_ms,
                audio=wav_data,
            )

        return await report_chat_history(
            mac_address=conn.device_id,
            session_id=conn.session_id,
            chat_type=1,
            content=json.dumps(metadata, ensure_ascii=False),
            audio=wav_data,
            report_time=int(time.time() * 1000),
        )

    def _frames_to_wav(self, audio_frames: List[bytes], audio_format: str) -> bytes:
        if audio_format == "pcm":
            pcm_bytes = b"".join(audio_frames)
        else:
            decoder = opuslib_next.Decoder(16000, 1)
            pcm_parts = []
            for frame in audio_frames:
                try:
                    pcm_parts.append(decoder.decode(frame, 960))
                except opuslib_next.OpusError:
                    continue
            pcm_bytes = b"".join(pcm_parts)

        if len(pcm_bytes) % 2 != 0:
            pcm_bytes = pcm_bytes[:-1]
        if not pcm_bytes:
            return b""
        wav_buffer = io.BytesIO()
        with wave.open(wav_buffer, "wb") as wav_file:
            wav_file.setnchannels(1)
            wav_file.setsampwidth(2)
            wav_file.setframerate(16000)
            wav_file.writeframes(pcm_bytes)
        return wav_buffer.getvalue()

    @staticmethod
    def _wav_duration_ms(wav_data: bytes) -> int:
        if not wav_data:
            return 0
        try:
            with wave.open(io.BytesIO(wav_data), "rb") as wav_file:
                frame_rate = wav_file.getframerate()
                if frame_rate <= 0:
                    return 0
                return int(wav_file.getnframes() * 1000 / frame_rate)
        except wave.Error:
            return 0

    def _append_sample(
        self,
        index: int,
        duration_ms: int,
        bytes_size: int,
        uploaded: bool,
        response: Optional[Dict[str, Any]] = None,
        error: Optional[str] = None,
    ) -> None:
        self.status.samples.append(
            VoiceprintEnrollmentSample(
                index=index,
                duration_ms=duration_ms,
                bytes_size=bytes_size,
                uploaded=uploaded,
                response=response,
                error=error,
            )
        )

    def _notify(self, conn: "ConnectionHandler", event: str, prompt: str) -> None:
        notifier = getattr(conn, "notify_voiceprint_enrollment_event", None)
        if callable(notifier):
            notifier(event, self.status.as_dict(), prompt)

    @staticmethod
    def _bounded_float(value, minimum: float, maximum: float, default: float) -> float:
        try:
            parsed = float(value)
        except (TypeError, ValueError):
            return default
        if not math.isfinite(parsed):
            return default
        return min(maximum, max(minimum, parsed))

    @staticmethod
    def _bounded_int(value, minimum: int, maximum: int, default: int) -> int:
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            return default
        return min(maximum, max(minimum, parsed))
