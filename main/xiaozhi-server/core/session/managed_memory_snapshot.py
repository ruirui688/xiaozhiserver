"""Bounded, leased manager-owned memory; reads never perform network I/O."""

import re
import threading
import time

from core.observability.session_telemetry import mark_latency
from core.utils.prompt_budget import select_memory_facts


class ManagedMemorySnapshot:
    MAX_LEASE_MS = 30000
    MAX_SHARED_CHARS = 4000
    MAX_PRIVATE_CHARS = 4000
    MAX_TOTAL_CHARS = 16000
    MAX_SPEAKERS = 16

    def __init__(self, conn, *, wall_clock=time.time, clock=time.monotonic):
        self.conn = conn
        self.wall_clock = wall_clock
        self.clock = clock
        self.lock = threading.Lock()
        self.epoch = 0
        self._clear()

    def _clear(self):
        self.config = None
        self.agent_id = None
        self.device_id = None
        self.deadline = 0
        self.expires_at_ms = 0
        self.shared = ""
        self.speakers = {}
        self.max_chars = 4000
        self.max_items = 30

    def invalidate(self):
        with self.lock:
            self.epoch += 1
            self._clear()
            return self.epoch

    def install(self, payload, expected_epoch):
        with self.lock:
            if self.epoch != expected_epoch:
                return False
            self._clear()
            config = getattr(self.conn, "config", {}) or {}
            if getattr(self.conn, "closing", False) or getattr(self.conn, "closed", False):
                return False
            if (not isinstance(payload, dict) or type(payload.get("schema_version")) is not int
                    or payload.get("schema_version") != 1 or payload.get("status") != "ready"):
                return False
            agent = config.get("agent_id")
            device = getattr(self.conn, "device_id", None)
            if not agent or not device or payload.get("agent_id") != agent or payload.get("device_id") != device:
                return False
            revision = payload.get("revision")
            issued, expires = payload.get("issued_at_ms"), payload.get("expires_at_ms")
            if (not isinstance(revision, str) or not re.fullmatch(r"[a-f0-9]{64}", revision)
                    or type(issued) is not int or type(expires) is not int):
                return False
            now_ms = self.wall_clock() * 1000
            if not 0 < expires - issued <= self.MAX_LEASE_MS or issued > now_ms + 5000 or expires <= now_ms:
                return False
            shared, speakers = payload.get("shared_context"), payload.get("speaker_contexts")
            if (not isinstance(shared, str) or len(shared) > self.MAX_SHARED_CHARS
                    or not isinstance(speakers, dict) or len(speakers) > self.MAX_SPEAKERS):
                return False
            if any(not isinstance(key, str) or not key or len(key) > 128
                   or not isinstance(value, str) or len(value) > self.MAX_PRIVATE_CHARS
                   for key, value in speakers.items()):
                return False
            if len(shared) + sum(map(len, speakers.values())) > self.MAX_TOTAL_CHARS:
                return False
            max_chars = payload.get("context_max_chars", 4000)
            max_items = payload.get("context_max_items", 30)
            if type(max_chars) is not int or not 1 <= max_chars <= 4000:
                return False
            if type(max_items) is not int or not 1 <= max_items <= 30:
                return False
            self.config, self.agent_id, self.device_id = config, agent, device
            self.deadline = self.clock() + min(expires - now_ms, self.MAX_LEASE_MS) / 1000
            self.expires_at_ms = expires
            self.shared, self.speakers = shared, dict(speakers)
            self.max_chars, self.max_items = max_chars, max_items
            return True

    def read(self):
        with self.lock:
            conn = self.conn
            if (getattr(conn, "closing", False) or getattr(conn, "closed", False)
                    or getattr(conn, "need_bind", False) or getattr(conn, "initialization_failed", False)
                    or getattr(conn, "config", None) is not self.config
                    or self.config is None or self.config.get("agent_id") != self.agent_id
                    or getattr(conn, "device_id", None) != self.device_id
                    or self.clock() >= self.deadline
                    or self.wall_clock() * 1000 >= self.expires_at_ms):
                self._clear()
                return ""
            speaker_id = getattr(conn, "current_speaker_id", None)
            private = self.speakers.get(speaker_id, "") if isinstance(speaker_id, str) else ""
            return select_memory_facts(private, self.shared, self.max_chars, self.max_items)

    def renewal_due(self):
        with self.lock:
            return self.clock() >= self.deadline - 5


def snapshot_for(conn):
    snapshot = getattr(conn, "managed_memory_snapshot", None)
    if snapshot is None:
        snapshot = ManagedMemorySnapshot(conn)
        conn.managed_memory_snapshot = snapshot
    return snapshot


def read_managed_memory(conn):
    snapshot = snapshot_for(conn)
    context = snapshot.read()
    reloader = getattr(conn, "runtime_config_reloader", None)
    if reloader is not None and snapshot.renewal_due():
        reloader.schedule_snapshot_refresh()
    mark_latency(conn, "memory.snapshot", status="available" if context else "empty")
    return context or None
