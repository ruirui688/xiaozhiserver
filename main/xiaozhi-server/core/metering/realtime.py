"""Realtime usage remains live after audio playback has finished or been aborted."""

from collections import deque

from .context import current_scope, snapshot
from .recorder import begin_call
from .volcengine import realtime_usage


class RealtimeMeter:
    def __init__(self, provider, conn=None):
        self.provider = provider
        self.scope = snapshot(conn) if conn is not None else current_scope.get()
        self.pending = None
        self.calls = {}
        self.finished = deque(maxlen=256)

    def submitted(self, event):
        if event.get("type") == "response.cancel":
            for call in self.calls.values():
                call.status = "CANCELLED"
            return
        if event.get("type") not in {"input_audio_buffer.append", "response.create", "conversation.item.create"}:
            return
        if self.pending is None:
            self.pending = begin_call(self.provider, "REALTIME", scope=self.scope)

    def received(self, event):
        kind = event.get("type", "")
        if kind in {"error", "transport.closed"}:
            self.close("FAILED" if kind == "error" else "UNKNOWN")
            return
        response = event.get("response") or {}
        if not isinstance(response, dict):
            response = {}
        response_id = event.get("response_id") or response.get("id")
        if not kind.startswith("response.") or not response_id or response_id in self.finished:
            return
        call = self.calls.get(response_id)
        if call is None:
            call = self.pending or begin_call(self.provider, "REALTIME", scope=self.scope)
            self.pending = None
            self.calls[response_id] = call
            call.request_id(response_id)
        realtime_usage(call, response)
        realtime_usage(call, event)
        if kind in {"response.canceled", "response.cancelled"}:
            call.status = "CANCELLED"
        if kind in {"response.done", "response.failed"}:
            state = str(response.get("status") or "").lower()
            status = "CANCELLED" if call.status == "CANCELLED" or state in {"cancelled", "canceled"} else (
                "FAILED" if kind == "response.failed" or state == "failed" else "SUCCESS")
            call.finish(status)
            self.calls.pop(response_id, None)
            self.finished.append(response_id)

    def close(self, status="UNKNOWN"):
        if self.pending:
            self.pending.finish(status)
            self.pending = None
        for call in self.calls.values():
            call.finish("CANCELLED" if call.status == "CANCELLED" else status)
        self.calls.clear()


class LegacyRealtimeMeter:
    """The legacy binary API exposes session accounting, not response identities."""

    def __init__(self, provider, conn=None):
        self.provider = provider
        self.scope = snapshot(conn) if conn is not None else current_scope.get()
        self.call = None

    def submitted(self, event_id, payload, session_id):
        if event_id == 100:
            if self.call:
                self.call.finish("UNKNOWN")
            self.call = begin_call(self.provider, "REALTIME", scope=self.scope, request_id=session_id)
        elif event_id == 200 and self.call and isinstance(payload, (bytes, bytearray)):
            self.call.usage("submitted_audio_duration", len(payload) / 32,
                            "milliseconds", "OBSERVED", add=True)

    def received(self, event):
        if self.call:
            realtime_usage(self.call, event.get("payload"))
            if event.get("event") in {152, 153}:
                self.call.finish("SUCCESS" if event["event"] == 152 else "FAILED")
                self.call = None

    def close(self):
        if self.call:
            self.call.finish("UNKNOWN")
            self.call = None
