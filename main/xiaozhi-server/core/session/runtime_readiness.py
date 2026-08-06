"""Capability-scoped readiness gates and deferred listen messages."""

from __future__ import annotations

import copy
import json
import threading
import time
from collections import deque


class RuntimeReadiness:
    """Own readiness events without adding another responsibility to connection.py."""

    def __init__(self, connection, *, detect_limit=8, detect_ttl_seconds=10.0):
        self.connection = connection
        self.binding_ready = threading.Event()
        self.tts_ready = threading.Event()
        self.conversation_ready = threading.Event()
        self.audio_input_ready = threading.Event()
        self.deferred_listen_detects = deque(maxlen=detect_limit)
        self.detect_ttl_seconds = float(detect_ttl_seconds)

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
            }
        )
        conn.logger.bind(tag=__name__).info(
            "listen.detect已延迟到对话能力就绪: "
            f"queued={len(self.deferred_listen_detects)}, ttl_seconds={ttl:g}"
        )
        return True

    async def flush_deferred_listen_detects(self):
        conn = self.connection
        if (
            not self.conversation_ready.is_set()
            or conn.closing
            or conn.closed
            or conn.initialization_failed
        ):
            return 0
        now = time.monotonic()
        pending = list(self.deferred_listen_detects)
        self.deferred_listen_detects.clear()
        replayed = 0
        from core.handle.textHandle import handleTextMessage

        for item in pending:
            if conn.closing or conn.closed or conn.initialization_failed:
                break
            if item["expires_at"] <= now:
                continue
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
            self.connection.logger.bind(tag=__name__).warning(
                f"已清空延迟listen.detect: count={count}, reason={reason}"
            )

    def mark_initialization_failed(self, error):
        conn = self.connection
        conn.initialization_failed = True
        conn.initialization_error = str(error)
        self.tts_ready.clear()
        self.conversation_ready.clear()
        self.audio_input_ready.clear()
        legacy_ready = getattr(conn, "_modules_ready", None)
        if legacy_ready is not None:
            legacy_ready.clear()
        self.clear_deferred_listen_detects(type(error).__name__)
