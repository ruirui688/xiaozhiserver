"""Capability-scoped readiness gates and deferred listen messages."""

from __future__ import annotations

import copy
import asyncio
import json
import threading
import time
from collections import deque
from core.observability.session_telemetry import mark_latency


class RuntimeReadiness:
    """Own readiness events without adding another responsibility to connection.py."""

    def __init__(self, connection, *, detect_limit=8, detect_ttl_seconds=10.0):
        self.connection = connection
        self.binding_ready = threading.Event()
        self.tts_ready = threading.Event()
        self.conversation_ready = threading.Event()
        self.wake_ready = threading.Event()
        self._wake_config = None
        self.audio_input_ready = threading.Event()
        self.deferred_listen_detects = deque(maxlen=detect_limit)
        self.detect_ttl_seconds = float(detect_ttl_seconds)

    def accepts(self, kind):
        conn = self.connection
        if any(getattr(conn, flag, False) for flag in
               ("closing", "closed", "initialization_failed", "need_bind")):
            return False
        if self.conversation_ready.is_set():
            return True
        return bool(
            kind == "wake" and self.wake_ready.is_set()
            and self._wake_config is getattr(conn, "config", None)
            and self.binding_ready.is_set() and self.tts_ready.is_set()
            and getattr(conn, "llm", None) is not None
            and getattr(conn, "tts", None) is not None
        )

    def accepts_text(self, text):
        from core.session.wake_events import classify_input
        if self.conversation_ready.is_set():
            return self.accepts("text")
        return self.accepts(classify_input(getattr(self.connection, "config", {}), str(text or "")))

    def publish_wake_ready(self, expected_config=None):
        """Called only after the authoritative, locally rendered system prompt."""
        conn = self.connection
        if ((expected_config is not None and conn.config is not expected_config)
                or not self.binding_ready.is_set() or not self.tts_ready.is_set()
                or not getattr(conn, "prompt", None)
                or getattr(conn, "llm", None) is None
                or any(getattr(conn, flag, False) for flag in
                       ("closing", "closed", "need_bind", "initialization_failed"))):
            return False
        self._wake_config = conn.config
        self.wake_ready.set()
        mark_latency(conn, "init.wake_ready")
        self.schedule_flush()
        return True

    def clear_wake_ready(self):
        self.wake_ready.clear()
        self._wake_config = None

    def schedule_flush(self):
        conn = self.connection
        if getattr(conn, "closing", False) or getattr(conn, "closed", False):
            return
        flush = getattr(conn, "flush_deferred_listen_detects", self.flush_deferred_listen_detects)
        future = asyncio.run_coroutine_threadsafe(flush(), conn.loop)
        def completed(result):
            if not result.cancelled() and result.exception() is not None:
                conn.logger.bind(tag=__name__).warning("延迟输入回放失败: errorType={}", type(result.exception()).__name__)
        future.add_done_callback(completed)

    async def wait_for_input(self, kind, timeout_seconds=None):
        """ASR retains its result while full readiness is pending; no side effects."""
        conn = self.connection
        config = getattr(conn, "config", None)
        timeout = self.detect_ttl_seconds if timeout_seconds is None else timeout_seconds
        deadline = time.monotonic() + timeout
        while not self.accepts(kind):
            if (any(getattr(conn, flag, False) for flag in
                    ("closing", "closed", "initialization_failed", "need_bind"))
                    or getattr(conn, "config", None) is not config):
                mark_latency(conn, "input.wait_rejected")
                return False
            if time.monotonic() >= deadline:
                mark_latency(conn, "input.wait_expired")
                return False
            await asyncio.sleep(0.01)
        return getattr(conn, "config", None) is config

    def defer_listen_detect(self, message, ttl_seconds=None):
        conn = self.connection
        if conn.closing or conn.closed or conn.initialization_failed:
            return False
        text = str(message.get("text") or "")
        dedupe_key = (
            f"device_asr:{message.get('turn_id')}"
            if message.get("source") == "device_asr" and message.get("turn_id")
            else f"text:{text}"
        )
        if any(
            item.get("dedupe_key") == dedupe_key
            for item in self.deferred_listen_detects
        ):
            return False
        ttl = max(
            0.1,
            float(
                self.detect_ttl_seconds
                if ttl_seconds is None
                else ttl_seconds
            ),
        )
        self.deferred_listen_detects.append(
            {
                "text": text,
                "dedupe_key": dedupe_key,
                "message": copy.deepcopy(message),
                "expires_at": time.monotonic() + ttl,
                "queued_at": time.monotonic(),
            }
        )
        mark_latency(conn, "detect.queued")
        conn.logger.bind(tag=__name__).info(
            "listen.detect已延迟到对话能力就绪: "
            f"queued={len(self.deferred_listen_detects)}, ttl_seconds={ttl:g}"
        )
        return True

    async def flush_deferred_listen_detects(self):
        conn = self.connection
        if (
            not (self.conversation_ready.is_set() or self.wake_ready.is_set())
            or conn.closing
            or conn.closed
            or conn.initialization_failed
        ):
            return 0
        now = time.monotonic()
        pending = list(self.deferred_listen_detects)
        self.deferred_listen_detects.clear()
        eligible = []
        for item in pending:
            if item["expires_at"] <= now or self.accepts_text(item["text"]):
                eligible.append(item)
            else:
                self.deferred_listen_detects.append(item)
        replayed = 0
        from core.handle.textHandle import handleTextMessage

        for item in eligible:
            if conn.closing or conn.closed or conn.initialization_failed:
                break
            if item["expires_at"] <= now:
                mark_latency(conn, "detect.expired", elapsed_ms=round((time.monotonic() - item["queued_at"]) * 1000, 3))
                continue
            mark_latency(conn, "detect.released", elapsed_ms=round((time.monotonic() - item["queued_at"]) * 1000, 3))
            await handleTextMessage(
                conn, json.dumps(item["message"], ensure_ascii=False)
            )
            replayed += 1
        if replayed:
            conn.logger.bind(tag=__name__).info(
                f"已回放初始化期间的listen.detect: count={replayed}"
            )
        return replayed

    def clear_deferred_listen_detects(self, reason):
        count = len(self.deferred_listen_detects)
        self.deferred_listen_detects.clear()
        if count:
            mark_latency(self.connection, "detect.cleared")
            self.connection.logger.bind(tag=__name__).warning(
                f"已清空延迟listen.detect: count={count}, reason={reason}"
            )

    def mark_initialization_failed(self, error):
        conn = self.connection
        conn.initialization_failed = True
        self.clear_wake_ready()
        mark_latency(conn, "init.failed")
        conn.initialization_error = str(error)
        self.tts_ready.clear()
        self.conversation_ready.clear()
        self.audio_input_ready.clear()
        legacy_ready = getattr(conn, "_modules_ready", None)
        if legacy_ready is not None:
            legacy_ready.clear()
        self.clear_deferred_listen_detects(type(error).__name__)
