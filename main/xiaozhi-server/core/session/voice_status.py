"""Opt-in voice readiness and bounded, non-billable failure reporting.

All mutable state belongs to the device event loop. Provider threads submit work
to that loop; no per-frame tasks, external requests or synthesized error audio.
"""

import asyncio
import json
import time

from core.metering.admission import DENIAL_CODES, PlatformAdmissionError


class VoiceStatus:
    INITIALIZATION_TIMEOUT = 18.0
    RETRY_SECONDS = 5.0

    def __init__(self, conn):
        self.conn = conn
        self.enabled = False
        self._monitor = None
        self._send_lock = asyncio.Lock()
        self._failed = False
        self._asr_blocked_until = 0.0
        self._asr_denied = False
        self._last_error = None
        self._last_error_at = 0.0
        self._readiness_changed = asyncio.Event()
        self._loop = None
        self._watched_future = None
        self._ready_announced = False
        self._ready_invalidation_counts = None
        self._invalidation_task = None

    def negotiate(self, features):
        if isinstance(features, dict) and type(features.get("voice_status")) is int and features["voice_status"] == 1:
            self.enabled = True
            advertised = self.conn.welcome_msg.get("features")
            advertised = dict(advertised) if isinstance(advertised, dict) else {}
            advertised["voice_status"] = 1
            self.conn.welcome_msg["features"] = advertised

    async def start(self):
        if not self.enabled or self._monitor is not None or self._failed:
            return
        self._loop = asyncio.get_running_loop()
        await self._send({"status": "initializing"})
        self._monitor = asyncio.create_task(self._watch_initialization())

    def _closing(self):
        return bool(getattr(self.conn, "closing", False) or getattr(self.conn, "closed", False))

    async def _watch_initialization(self):
        deadline = time.monotonic() + self.INITIALIZATION_TIMEOUT
        while not self._closing() and not self._failed:
            self._readiness_changed.clear()
            if getattr(self.conn, "initialization_failed", False):
                await self.error("INITIALIZATION_FAILED", stage="initialization", retryable=False)
                return
            if getattr(self.conn, "need_bind", False):
                await self.error("CAPABILITY_UNAVAILABLE", stage="initialization", retryable=False)
                return
            if self._is_ready():
                if await self._send({"status": "ready"}) is False:
                    continue  # Readiness changed while waiting for the send lock.
                return
            future = getattr(self.conn, "_components_initialize_future", None)
            if future is not None and future.done():
                await self.error("CAPABILITY_UNAVAILABLE", stage="initialization", retryable=False)
                return
            if future is not None and future is not self._watched_future:
                self._watched_future = future
                future.add_done_callback(lambda _future: self.notify_readiness_changed())
            try:
                await asyncio.wait_for(self._readiness_changed.wait(), timeout=max(0, deadline - time.monotonic()))
            except asyncio.TimeoutError:
                await self.error("INITIALIZATION_TIMEOUT", stage="initialization", retryable=False)
                return

    def notify_readiness_changed(self):
        loop = self._loop
        if loop is not None and not loop.is_closed():
            try:
                loop.call_soon_threadsafe(self._on_readiness_changed)
            except RuntimeError:
                pass  # The device loop may close concurrently with a worker.

    def _on_readiness_changed(self):
        if self._closing():
            return
        self._readiness_changed.set()
        if (not self.enabled or self._failed or not self._ready_announced
                or self._invalidation_task is not None):
            return
        failed = getattr(self.conn, "initialization_failed", False)
        invalidated = (not self._is_ready()
                       or self._invalidation_counts() != self._ready_invalidation_counts)
        if failed or invalidated:
            # An App without a pause/resume handshake must end this capture.
            # Generation counts retain even a clear/set between queued callbacks.
            self._asr_blocked_until = float("inf")
            self._invalidation_task = asyncio.create_task(self.error(
                "INITIALIZATION_FAILED" if failed else "VOICE_SESSION_INVALIDATED",
                stage="initialization" if failed else "session", retryable=not failed))

    def _is_ready(self):
        return all(getattr(self.conn, name, None) is not None and getattr(self.conn, name).is_set()
                   for name in ("audio_input_ready", "conversation_ready"))

    def _invalidation_counts(self):
        return tuple(getattr(getattr(self.conn, name, None), "invalidation_count", 0)
                     for name in ("audio_input_ready", "conversation_ready"))

    def allows_asr(self):
        return not self._closing() and time.monotonic() >= self._asr_blocked_until

    async def asr_failed(self, error, *, provider=None):
        # A superseded stream must not poison a newly selected provider.
        if provider is not None and getattr(self.conn, "asr", provider) is not provider:
            return
        if self._closing():
            return
        if self._asr_denied:
            return
        code = "ASR_UNAVAILABLE"
        retryable = True
        if isinstance(error, PlatformAdmissionError):
            code = error.code if error.code in DENIAL_CODES else "PLATFORM_SERVICE_UNAVAILABLE"
            retryable = code in {"CHANNEL_CONCURRENCY_LIMIT", "PLATFORM_SERVICE_UNAVAILABLE"}
        self._asr_denied = not retryable
        # A negotiated client ends capture and requires an explicit new call.
        # Legacy devices cool down transient errors; permanent denials need reconnect.
        self._asr_blocked_until = max(self._asr_blocked_until,
            float("inf") if self.enabled or not retryable else time.monotonic() + self.RETRY_SECONDS)
        reset = getattr(self.conn, "reset_audio_states", None)
        if callable(reset) and (self.enabled or not retryable):
            reset()
        context = getattr(self.conn, "turn_context", None)
        if context is not None:
            context.clear()
        await self.error(code, stage="asr", retryable=retryable)

    async def error(self, code, *, stage, retryable):
        if self._closing():
            return
        now = time.monotonic()
        key = (stage, code)
        if ((self._last_error == key and not retryable)
                or (self._last_error is not None and retryable and now - self._last_error_at < self.RETRY_SECONDS)):
            return
        self._last_error, self._last_error_at = key, now
        self._failed = True
        if self.enabled:
            self._asr_blocked_until = float("inf")
        self.notify_readiness_changed()
        message = {
            "ALLOWANCE_EXHAUSTED": "服务额度不足，请补充额度后重新开始通话。",
            "WORKSPACE_ALLOWANCE_LIMIT": "工作空间服务额度已达上限。",
            "TEAM_ALLOWANCE_LIMIT": "团队服务额度已达上限。",
            "PAYER_FROZEN": "服务账户当前不可用，请联系管理员。",
            "INITIALIZATION_FAILED": "语音服务初始化失败，请检查模型配置。",
            "CAPABILITY_UNAVAILABLE": "语音能力未配置完整，请检查模型配置。",
            "INITIALIZATION_TIMEOUT": "语音服务准备超时，请稍后重新开始通话。",
            "VOICE_SESSION_INVALIDATED": "语音服务已更新，本次通话已结束，请重新开始通话。",
        }.get(code, "语音服务暂不可用，请稍后重新开始通话。")
        await self._send({"status": "error", "code": code, "stage": stage,
                          "message": message, "retryable": retryable,
                          "retry_after_ms": int(self.RETRY_SECONDS * 1000) if retryable else 0})

    async def _send(self, payload):
        async with self._send_lock:
            if self._closing() or (payload["status"] != "error" and self._failed):
                return
            if not self.enabled and payload["status"] != "error":
                return
            message = {"type": "voice_status" if self.enabled else "error", "version": 1,
                       "session_id": getattr(self.conn, "session_id", ""), **payload}
            websocket = getattr(self.conn, "websocket", None)
            if websocket is None:
                return
            publishing_ready = payload["status"] == "ready"
            if publishing_ready:
                counts = self._invalidation_counts()
                if not self._is_ready() or getattr(self.conn, "initialization_failed", False):
                    return False
                self._ready_invalidation_counts = counts
                self._ready_announced = True
            try:
                await asyncio.wait_for(websocket.send(json.dumps(message, ensure_ascii=False)), timeout=1.0)
            except asyncio.CancelledError:
                raise
            except Exception:
                # Send failure is not another voice failure; do not recurse/retry.
                return
            finally:
                if publishing_ready:
                    self._on_readiness_changed()

    def close(self):
        for task in (self._monitor, self._invalidation_task):
            if task is not None and not task.done():
                loop = task.get_loop()
                if not loop.is_closed():
                    try:
                        loop.call_soon_threadsafe(task.cancel)
                    except RuntimeError:
                        pass


def voice_status(conn):
    status = getattr(conn, "voice_status", None)
    if status is None:
        status = VoiceStatus(conn)
        conn.voice_status = status
    return status


async def report_asr_failure(conn, error, *, provider=None):
    await voice_status(conn).asr_failed(error, provider=provider)
