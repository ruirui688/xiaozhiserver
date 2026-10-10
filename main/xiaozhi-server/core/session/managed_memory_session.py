"""Bounded immutable evidence and acknowledged checkpoints for managed memory.

The LLM's mutable/pruned Dialogue is deliberately not the persistence source.
Only successful server acknowledgements advance the checkpoint. This buffer is
not a durable queue; original reported events remain the recovery source after
an ungraceful process exit.
"""

import asyncio
import json
import threading
from dataclasses import dataclass


@dataclass(frozen=True)
class MemoryEvidence:
    role: str
    content: str
    uniq_id: str
    speaker_id: str = ""
    is_temporary: bool = False
    memory_eligible: bool = True


class MemorySessionCapacityError(RuntimeError):
    """The unacknowledged buffer is full; never silently evict evidence."""


class ManagedMemorySession:
    BATCH_MESSAGES = 40
    BATCH_CHARS = 16000
    MAX_MESSAGES = 200
    MAX_CHARS = 160000

    def __init__(self, conn, provider):
        self.conn = conn
        self.provider = provider
        self.agent_id = provider.agent_id
        self.device_id = provider.role_id
        self.session_id = conn.session_id
        self._messages = []
        self._chars = 0
        self._lock = threading.Lock()
        self._flush_lock = threading.Lock()
        self._task = None
        self._segment = 0
        self._inflight = None
        self._excluded_turn = False
        self.last_error = None
        self._idle_timer = None
        self.enabled = True

    def append(self, message):
        if not self.enabled:
            return
        if (getattr(self.conn, "config", {}) or {}).get("memory_policy", {}).get("maintenanceMode") == "OFF":
            return
        if getattr(message, "is_temporary", False) or getattr(message, "is_internal", False):
            return
        if message.role not in {"user", "assistant", "tool"}:
            return
        if message.role == "user":
            meeting = getattr(self.conn, "meeting_minutes", None)
            self._excluded_turn = bool(
                not getattr(message, "memory_eligible", True)
                or meeting is not None and meeting.is_active()
            )
        if self._excluded_turn or not getattr(message, "memory_eligible", True):
            return
        content = getattr(message, "memory_content", message.content)
        if not content:
            return
        # Never truncate structured speaker metadata or silently change meaning.
        if len(content) > 8000:
            raise MemorySessionCapacityError("memory_message_too_large")
        speaker = ""
        if message.role == "user":
            try:
                payload = json.loads(content)
                if isinstance(payload, dict):
                    speaker = str(payload.get("speaker_id") or "")
            except (ValueError, TypeError):
                pass
        evidence = MemoryEvidence(message.role, str(content), message.uniq_id, speaker)
        with self._lock:
            if len(self._messages) >= self.MAX_MESSAGES or self._chars + len(content) > self.MAX_CHARS:
                self.last_error = "memory_buffer_full"
                raise MemorySessionCapacityError(self.last_error)
            self._messages.append(evidence)
            self._chars += len(content)
            due = len(self._messages) >= self.BATCH_MESSAGES or self._chars >= self.BATCH_CHARS
        if due:
            self.schedule_flush()
        else:
            loop = getattr(self.conn, "loop", None)
            if loop is not None and not loop.is_closed():
                loop.call_soon_threadsafe(self._schedule_interval_flush)

    def _schedule_interval_flush(self):
        if self._idle_timer is None and not getattr(self.conn, "closing", False):
            self._idle_timer = asyncio.get_running_loop().call_later(30, self._interval_flush)

    def _interval_flush(self):
        self._idle_timer = None
        self._start_flush()

    def schedule_flush(self):
        loop = getattr(self.conn, "loop", None)
        if loop is None or loop.is_closed():
            return
        loop.call_soon_threadsafe(self._start_flush)

    def _start_flush(self):
        if not self.enabled or getattr(self.conn, "closing", False) or getattr(self.conn, "closed", False):
            return
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self.flush())
            self._task.add_done_callback(self._finished)

    def _finished(self, task):
        if task.cancelled():
            return
        error = task.exception()
        if error is not None:
            self.last_error = type(error).__name__
            self.conn.logger.bind(tag=__name__).warning(
                "记忆分段提交失败，保留未确认消息: errorType={}", self.last_error
            )
            if not getattr(self.conn, "closing", False) and not getattr(self.conn, "closed", False):
                asyncio.get_running_loop().call_later(10, self._start_flush)

    def cancel_scheduled_flush(self):
        timer, self._idle_timer = self._idle_timer, None
        if timer is not None:
            timer.cancel()

    async def flush(self):
        if not self.enabled:
            return 0
        # close processing may run on its own loop; a threading lock also
        # serializes it with the background checkpoint task on the main loop.
        acquired = False
        while not acquired:
            acquired = self._flush_lock.acquire(blocking=False)
            if not acquired:
                await asyncio.sleep(0.01)
        try:
            changed = 0
            while True:
                if not self.enabled:
                    return changed
                with self._lock:
                    if self._inflight is None:
                        batch, chars = [], 0
                        for message in self._messages:
                            if batch and (len(batch) >= self.BATCH_MESSAGES or chars + len(message.content) > self.BATCH_CHARS):
                                break
                            batch.append(message)
                            chars += len(message.content)
                        if batch:
                            self._inflight = (batch, chars)
                    else:
                        batch, chars = self._inflight
                    segment = str(self._segment)
                if not batch:
                    self.cancel_scheduled_flush()
                    return changed
                changed += await self.provider.submit_messages(
                    batch, self.session_id, segment_id=segment,
                    agent_id=self.agent_id, device_id=self.device_id,
                )
                with self._lock:
                    del self._messages[:len(batch)]
                    self._chars -= chars
                    self._segment += 1
                    self._inflight = None
                    self.last_error = None
        finally:
            self._flush_lock.release()


def bind_managed_memory_session(conn, provider):
    previous = getattr(conn, "managed_memory_session", None)
    if (previous is None or previous.agent_id != provider.agent_id
            or previous.session_id != conn.session_id):
        previous = ManagedMemorySession(conn, provider)
        conn.managed_memory_session = previous
        # Identity-preserving copies captured by Message at construction.
        for message in conn.dialogue.snapshot_for_memory():
            previous.append(message)
    provider.session = previous
    previous.enabled = (conn.config.get("memory_policy") or {}).get("maintenanceMode") != "OFF"
    conn.dialogue.memory_sink = previous.append if previous.enabled else None
    conn.dialogue.memory_checkpoint = previous.schedule_flush if previous.enabled else None
    return previous


def detach_managed_memory_session(conn):
    dialogue = getattr(conn, "dialogue", None)
    if dialogue is not None:
        dialogue.memory_sink = None
        dialogue.memory_checkpoint = None
    session = getattr(conn, "managed_memory_session", None)
    if session is not None:
        session.enabled = False
        session.cancel_scheduled_flush()


def checkpoint_before_context_reset(conn):
    session = getattr(conn, "managed_memory_session", None)
    if session is not None:
        session.schedule_flush()


async def flush_before_scene_switch(conn):
    session = getattr(conn, "managed_memory_session", None)
    if session is not None:
        await session.flush()
