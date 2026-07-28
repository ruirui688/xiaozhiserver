"""Device-authenticated, exact-text TTS jobs over the existing WebSocket."""

from __future__ import annotations

import asyncio
import concurrent.futures
import json
import math
import threading
import time
import uuid
import weakref
from collections import defaultdict, deque
from dataclasses import dataclass
from typing import Any, Dict, Optional

from core.handle.abortHandle import handleAbortMessage
from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO


TERMINAL_STATUSES = {"completed", "failed", "interrupted"}
STATUS_DETAILS = {
    "queued": ("waiting_for_tts", 5),
    "synthesizing": ("generating_audio", 25),
    "streaming": ("sending_audio", 60),
    "completed": ("server_audio_sent", 100),
    "failed": ("failed", 0),
    "interrupted": ("interrupted", 0),
}


@dataclass
class ExternalTTSJob:
    job_id: str
    sentence_id: str
    device_key: str
    request_id: str
    status: str
    stage: str
    progress_percent: int
    created_at: float
    updated_at: float
    chat_history: bool
    chat_history_audio: bool
    error: str = ""
    timeout_handle: Any = None

    def payload(self, *, idempotent: bool = False) -> Dict[str, Any]:
        body = {
            "type": "external_tts",
            "event": "status",
            "success": self.status not in {"failed", "interrupted"},
            "jobId": self.job_id,
            "sentenceId": self.sentence_id,
            "requestId": self.request_id,
            "status": self.status,
            "stage": self.stage,
            "progressPercent": self.progress_percent,
            "error": self.error,
            "voiceSource": "device_agent",
            "exactText": True,
            "chatHistory": self.chat_history,
            "chatHistoryAudio": self.chat_history_audio,
            "completionBoundary": "server_audio_sent",
            "devicePlaybackConfirmed": False,
        }
        if idempotent:
            body["idempotent"] = True
        return body


class ExternalTTSCoordinator:
    """Coordinates jobs without creating providers or touching LLM state."""

    def __init__(self):
        self._lock = threading.RLock()
        self._jobs: Dict[str, ExternalTTSJob] = {}
        self._jobs_by_sentence: Dict[str, str] = {}
        self._idempotency: Dict[tuple[str, str], str] = {}
        self._request_times = defaultdict(deque)
        self._device_locks: Dict[str, asyncio.Lock] = {}

    async def handle_message(self, conn, message: Dict[str, Any]) -> None:
        self._bind(conn)
        action = message.get("action", "submit")
        if action == "status":
            await self._handle_status(conn, message)
            return
        if action != "submit":
            await self._send_error(
                conn,
                "invalid_request",
                "action must be submit or status",
                request_id=message.get("requestId"),
            )
            return
        await self._handle_submit(conn, message)

    async def _handle_submit(self, conn, message: Dict[str, Any]) -> None:
        validation = self._validate_submit(message)
        if validation is not None:
            status, detail = validation
            await self._send_error(
                conn,
                status,
                detail,
                request_id=message.get("requestId"),
            )
            return

        request_id = message["requestId"]
        text = message["text"]
        interrupt = message.get("interrupt", False)
        device_key = self._device_key(conn)
        device_lock = self._device_locks.setdefault(device_key, asyncio.Lock())

        async with device_lock:
            existing = self._find_idempotent(device_key, request_id, conn)
            if existing is not None:
                await self._send_payload(conn, existing.payload(idempotent=True))
                return

            unavailable = self._unavailable_reason(conn)
            if unavailable is not None:
                await self._send_error(
                    conn,
                    "unavailable",
                    unavailable,
                    request_id=request_id,
                )
                return

            protected_busy = self._protected_busy_reason(conn)
            if protected_busy is not None:
                await self._send_error(
                    conn,
                    "busy",
                    protected_busy,
                    request_id=request_id,
                    busy_reason="protected_session_active",
                )
                return

            if not self._allow_rate(device_key, conn):
                await self._send_error(
                    conn,
                    "rate_limited",
                    "external TTS request rate is too high",
                    request_id=request_id,
                )
                return

            busy_reason = self._busy_reason(conn)
            if busy_reason is not None:
                if not interrupt:
                    self._log_busy_rejection(conn, request_id, busy_reason)
                    await self._send_error(
                        conn,
                        "busy",
                        self._busy_message(busy_reason),
                        request_id=request_id,
                        busy_reason=busy_reason,
                    )
                    return
                await handleAbortMessage(conn)

            job = self._create_job(device_key, request_id, conn)
            with conn._abort_lock:
                conn.client_abort = False
            conn.sentence_id = job.sentence_id
            reply_lifecycle = getattr(conn, "reply_lifecycle", None)
            reply_generation = None
            if reply_lifecycle is not None:
                reply_generation = reply_lifecycle.begin(kind="external_tts")
                reply_lifecycle.bind_sentence(reply_generation, job.sentence_id)
                reply_lifecycle.mark_synthesizing(reply_generation)
            else:
                conn.client_is_speaking = True

            tts = conn.tts
            mark_exact_text = getattr(tts, "mark_exact_text", None)
            if callable(mark_exact_text):
                mark_exact_text(job.sentence_id)
            tts.store_tts_text(job.sentence_id, text)
            # The spoken assistant entry belongs in chat history, but it must not
            # become a long-term-memory source event.
            mark_skip_memory = getattr(tts, "mark_skip_memory_event", None)
            if callable(mark_skip_memory):
                mark_skip_memory(job.sentence_id)

            await self._send_payload(conn, job.payload())
            try:
                self._schedule_timeout(conn, job)
                tts.tts_text_queue.put(
                    TTSMessageDTO(
                        sentence_id=job.sentence_id,
                        sentence_type=SentenceType.FIRST,
                        content_type=ContentType.ACTION,
                    )
                )
                # A single queue item preserves the caller's text byte-for-byte
                # at the server queue boundary. No prefix or semantic processing.
                tts.tts_text_queue.put(
                    TTSMessageDTO(
                        sentence_id=job.sentence_id,
                        sentence_type=SentenceType.MIDDLE,
                        content_type=ContentType.TEXT,
                        content_detail=text,
                    )
                )
                tts.tts_text_queue.put(
                    TTSMessageDTO(
                        sentence_id=job.sentence_id,
                        sentence_type=SentenceType.LAST,
                        content_type=ContentType.ACTION,
                    )
                )
            except Exception:
                self.mark_failed(conn, job.sentence_id, "TTS queue submission failed")
                from core.handle.sendAudioHandle import terminate_pending_reply

                await terminate_pending_reply(
                    conn,
                    "tts_queue_submission_failed",
                    generation=reply_generation,
                )

    async def _handle_status(self, conn, message: Dict[str, Any]) -> None:
        job_id = message.get("jobId")
        if not isinstance(job_id, str) or not job_id:
            await self._send_error(conn, "invalid_request", "jobId is required")
            return
        with self._lock:
            self._expire_locked(conn)
            job = self._jobs.get(job_id)
            if job is None or job.device_key != self._device_key(conn):
                job = None
        if job is None:
            await self._send_error(conn, "not_found", "TTS job was not found")
            return
        await self._send_payload(conn, job.payload())

    def mark_synthesizing(self, conn, sentence_id: Optional[str]) -> None:
        self._transition(conn, sentence_id, "synthesizing")

    def mark_streaming(self, conn, sentence_id: Optional[str]) -> None:
        self._transition(conn, sentence_id, "streaming")

    def mark_completed(self, conn, sentence_id: Optional[str]) -> None:
        with self._lock:
            job = self._job_for_sentence_locked(sentence_id)
            if job is None or job.status in TERMINAL_STATUSES:
                return
            if job.status != "streaming":
                target = "failed"
                error = "TTS produced no audio"
            else:
                target = "completed"
                error = ""
        self._transition(conn, sentence_id, target, error=error)

    def mark_failed(self, conn, sentence_id: Optional[str], error: str = "") -> None:
        self._transition(conn, sentence_id, "failed", error=error or "TTS failed")
        if sentence_id and sentence_id == getattr(conn, "sentence_id", None):
            lifecycle = getattr(conn, "reply_lifecycle", None)
            if lifecycle is None:
                conn.clearSpeakStatus()

    def mark_interrupted(self, conn, sentence_id: Optional[str]) -> None:
        self._transition(conn, sentence_id, "interrupted")

    def connection_closed(self, conn) -> None:
        sentence_id = getattr(conn, "sentence_id", None)
        self._transition(conn, sentence_id, "interrupted", emit=False)

    def _transition(
        self,
        conn,
        sentence_id: Optional[str],
        status: str,
        *,
        error: str = "",
        emit: bool = True,
    ) -> None:
        if status not in STATUS_DETAILS:
            return
        with self._lock:
            job = self._job_for_sentence_locked(sentence_id)
            if job is None or job.status in TERMINAL_STATUSES:
                return
            stage, progress = STATUS_DETAILS[status]
            job.status = status
            job.stage = stage
            job.progress_percent = progress
            job.error = error if status == "failed" else ""
            job.updated_at = time.monotonic()
            if status in TERMINAL_STATUSES and job.timeout_handle is not None:
                job.timeout_handle.cancel()
                job.timeout_handle = None
            payload = job.payload()
        if emit:
            self._schedule_payload(conn, payload)

    def _create_job(self, device_key: str, request_id: str, conn) -> ExternalTTSJob:
        now = time.monotonic()
        try:
            chat_history_conf = int(getattr(conn, "chat_history_conf", 0))
        except (TypeError, ValueError):
            chat_history_conf = 0
        chat_history = bool(
            getattr(conn, "read_config_from_api", False)
            and getattr(conn, "report_tts_enable", False)
            and chat_history_conf in {1, 2}
        )
        job = ExternalTTSJob(
            job_id=f"tts-job-{uuid.uuid4().hex}",
            sentence_id=f"sentence-{uuid.uuid4().hex}",
            device_key=device_key,
            request_id=request_id,
            status="queued",
            stage=STATUS_DETAILS["queued"][0],
            progress_percent=STATUS_DETAILS["queued"][1],
            created_at=now,
            updated_at=now,
            chat_history=chat_history,
            chat_history_audio=chat_history and chat_history_conf == 2,
        )
        with self._lock:
            self._expire_locked(conn)
            self._jobs[job.job_id] = job
            self._jobs_by_sentence[job.sentence_id] = job.job_id
            self._idempotency[(device_key, request_id)] = job.job_id
        return job

    def _find_idempotent(self, device_key: str, request_id: str, conn):
        with self._lock:
            self._expire_locked(conn)
            job_id = self._idempotency.get((device_key, request_id))
            return self._jobs.get(job_id) if job_id else None

    def _expire_locked(self, conn) -> None:
        now = time.monotonic()
        config = self._config(conn)
        ttl = max(600.0, self._number(config.get("idempotency_ttl_seconds"), 600.0))
        for job in list(self._jobs.values()):
            if job.status in TERMINAL_STATUSES and now - job.created_at >= ttl:
                if job.timeout_handle is not None:
                    job.timeout_handle.cancel()
                self._jobs.pop(job.job_id, None)
                self._jobs_by_sentence.pop(job.sentence_id, None)
                self._idempotency.pop((job.device_key, job.request_id), None)

    def _allow_rate(self, device_key: str, conn) -> bool:
        config = self._config(conn)
        limit = max(1, int(self._number(config.get("rate_limit_per_minute"), 20)))
        now = time.monotonic()
        with self._lock:
            values = self._request_times[device_key]
            while values and now - values[0] >= 60:
                values.popleft()
            if len(values) >= limit:
                return False
            values.append(now)
            return True

    @staticmethod
    def _validate_submit(message: Dict[str, Any]):
        request_id = message.get("requestId")
        if not isinstance(request_id, str) or not (1 <= len(request_id) <= 128):
            return "invalid_request", "requestId must contain 1 to 128 characters"
        text = message.get("text")
        if not isinstance(text, str) or len(text) == 0 or not text.strip():
            return "invalid_request", "text must be a non-empty string"
        if len(text) > 1000:
            return "payload_too_large", "text must not exceed 1000 characters"
        if "interrupt" in message and not isinstance(message["interrupt"], bool):
            return "invalid_request", "interrupt must be a boolean"
        return None

    @staticmethod
    def _unavailable_reason(conn) -> Optional[str]:
        if getattr(conn, "need_bind", False):
            return "device is not bound"
        if getattr(conn, "tts", None) is None:
            return "device TTS is not initialized"
        if not ExternalTTSCoordinator._device_key(conn):
            return "device identity is unavailable"
        if getattr(conn, "initialization_failed", False):
            return "device model initialization failed"
        tts_ready = getattr(conn, "tts_ready", None)
        if tts_ready is not None and not tts_ready.is_set():
            return "device TTS is still initializing"
        if getattr(conn, "closing", False) or getattr(conn, "closed", False):
            return "device connection is closing"
        if getattr(conn, "loop", None) is None:
            return "device runtime loop is unavailable"
        return None

    @staticmethod
    def _protected_busy_reason(conn) -> Optional[str]:
        if getattr(conn, "calling", False):
            return "device is in a call"
        meeting = getattr(conn, "meeting_minutes", None)
        if meeting is not None and meeting.is_active():
            return "device meeting mode is active"
        enrollment = getattr(conn, "voiceprint_enrollment", None)
        status = getattr(enrollment, "status", None)
        if status is not None and getattr(status, "active", False):
            return "device voiceprint enrollment is active"
        return None

    def _busy_reason(self, conn) -> Optional[str]:
        reply_lifecycle = getattr(conn, "reply_lifecycle", None)
        if reply_lifecycle is not None and reply_lifecycle.is_active:
            return "assistant_reply_active"
        if reply_lifecycle is None and getattr(conn, "client_is_speaking", False):
            return "assistant_reply_active"
        if getattr(conn, "client_have_voice", False):
            return "user_voice_active"
        tts = getattr(conn, "tts", None)
        if tts is not None:
            text_queue = getattr(tts, "tts_text_queue", None)
            if text_queue is not None and text_queue.qsize() > 0:
                return "tts_text_queue_pending"
            audio_queue = getattr(tts, "tts_audio_queue", None)
            if audio_queue is not None and audio_queue.qsize() > 0:
                return "tts_audio_queue_pending"
        device_key = self._device_key(conn)
        with self._lock:
            active_external_job = any(
                job.device_key == device_key and job.status not in TERMINAL_STATUSES
                for job in self._jobs.values()
            )
        return "external_tts_job_active" if active_external_job else None

    def _is_busy(self, conn) -> bool:
        """Backward-compatible boolean view used by older integrations/tests."""
        return self._busy_reason(conn) is not None

    @staticmethod
    def _busy_message(reason: str) -> str:
        return {
            "assistant_reply_active": "device assistant reply is active",
            "user_voice_active": "device user voice is active",
            "tts_text_queue_pending": "device TTS text queue is pending",
            "tts_audio_queue_pending": "device TTS audio queue is pending",
            "external_tts_job_active": "device external TTS job is active",
        }.get(reason, "device is busy")

    def _log_busy_rejection(self, conn, request_id: str, busy_reason: str) -> None:
        lifecycle = getattr(conn, "reply_lifecycle", None)
        snapshot = lifecycle.snapshot() if lifecycle is not None else None
        tts = getattr(conn, "tts", None)
        text_queue = getattr(tts, "tts_text_queue", None)
        audio_queue = getattr(tts, "tts_audio_queue", None)
        device_key = self._device_key(conn)
        with self._lock:
            active_external = any(
                job.device_key == device_key and job.status not in TERMINAL_STATUSES
                for job in self._jobs.values()
            )
        reply_age = snapshot.age_seconds if snapshot is not None else 0.0
        conn.logger.bind(tag=__name__).warning(
            "external_tts busy: "
            f"requestId={json.dumps(request_id, ensure_ascii=True)}, "
            f"busyReason={busy_reason}, "
            f"replyGeneration={snapshot.generation if snapshot else 0}, "
            f"replyState={snapshot.state.value if snapshot else 'UNKNOWN'}, "
            f"replyAgeSeconds={reply_age:.3f}, "
            f"textQueueSize={text_queue.qsize() if text_queue is not None else 0}, "
            f"audioQueueSize={audio_queue.qsize() if audio_queue is not None else 0}, "
            f"clientHaveVoice={bool(getattr(conn, 'client_have_voice', False))}, "
            f"activeExternalJob={active_external}"
        )

    async def _send_error(
        self,
        conn,
        status: str,
        message: str,
        *,
        request_id: Any = None,
        busy_reason: Optional[str] = None,
    ) -> None:
        payload = {
            "type": "external_tts",
            "event": "status",
            "success": False,
            "status": status,
            "message": message,
            "error": message,
            "completionBoundary": "server_audio_sent",
            "devicePlaybackConfirmed": False,
        }
        if isinstance(request_id, str):
            payload["requestId"] = request_id
        if busy_reason:
            payload["busyReason"] = busy_reason
        await self._send_payload(conn, payload)

    async def _send_payload(self, conn, payload: Dict[str, Any]) -> None:
        await conn.websocket.send(json.dumps(payload, ensure_ascii=False))

    def _schedule_payload(self, conn, payload: Dict[str, Any]) -> None:
        loop = getattr(conn, "loop", None)
        if loop is None or loop.is_closed():
            return
        coroutine = self._send_payload(conn, payload)
        try:
            running_loop = asyncio.get_running_loop()
        except RuntimeError:
            running_loop = None
        try:
            if running_loop is loop:
                future = loop.create_task(coroutine)
            else:
                future = asyncio.run_coroutine_threadsafe(coroutine, loop)
        except Exception:
            coroutine.close()
            return
        future.add_done_callback(self._consume_scheduled_result)

    @staticmethod
    def _consume_scheduled_result(future) -> None:
        """Status delivery is best effort when the device socket disconnects."""
        try:
            future.result()
        except (asyncio.CancelledError, concurrent.futures.CancelledError):
            return
        except Exception:
            return

    def _bind(self, conn) -> None:
        conn.external_tts_coordinator = self

    def _schedule_timeout(self, conn, job: ExternalTTSJob) -> None:
        timeout = max(
            60.0,
            self._number(self._config(conn).get("job_timeout_seconds"), 300.0),
        )
        try:
            conn_ref = weakref.ref(conn)
        except TypeError:
            conn_ref = lambda: conn
        job.timeout_handle = conn.loop.call_later(
            timeout,
            self._timeout_job,
            conn_ref,
            job.job_id,
        )

    def _timeout_job(self, conn_ref, job_id: str) -> None:
        conn = conn_ref()
        if conn is None:
            return
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None or job.status in TERMINAL_STATUSES:
                return
            sentence_id = job.sentence_id
        self.mark_failed(conn, sentence_id, "TTS job timed out")
        if sentence_id == getattr(conn, "sentence_id", None):
            future = conn.loop.create_task(handleAbortMessage(conn))
            future.add_done_callback(self._consume_scheduled_result)

    def _job_for_sentence_locked(self, sentence_id: Optional[str]):
        if not sentence_id:
            return None
        job_id = self._jobs_by_sentence.get(sentence_id)
        return self._jobs.get(job_id) if job_id else None

    @staticmethod
    def _device_key(conn) -> str:
        device_id = str(getattr(conn, "device_id", "") or "").strip().lower()
        return device_id.replace(":", "_")

    @staticmethod
    def _config(conn) -> Dict[str, Any]:
        config = getattr(conn, "config", {}) or {}
        value = config.get("external_tts", {})
        return value if isinstance(value, dict) else {}

    @staticmethod
    def _number(value: Any, default: float) -> float:
        try:
            number = float(value)
        except (TypeError, ValueError):
            return default
        return number if math.isfinite(number) else default


external_tts_coordinator = ExternalTTSCoordinator()
