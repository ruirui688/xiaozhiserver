"""Request-local, payload-free timings for three manager API read endpoints."""

import time
import uuid

from core.observability.session_telemetry import (
    current_latency_metadata, current_latency_owner, latency_span, mark_latency,
)


READ_STAGES = {
    "/config/agent-models": "models",
    "/config/correct-words": "correct_words",
    "/config/memory/query": "memory",
}
TRACE_STAGES = {
    "connection.connect_tcp": "tcp_connect",
    "connection.start_tls": "tls",
    "http11.send_request_headers": "send_headers",
    "http11.send_request_body": "send_body",
    "http11.receive_response_headers": "receive_headers",
    "http11.receive_response_body": "receive_body",
    "http2.send_request_headers": "send_headers",
    "http2.send_request_body": "send_body",
    "http2.receive_response_headers": "receive_headers",
    "http2.receive_response_body": "receive_body",
}


class RequestObservation:
    def __init__(self, stage):
        self.owner = current_latency_owner()
        self.metadata = current_latency_metadata()
        self.metadata["request_id"] = uuid.uuid4().hex
        self.stage = "api." + stage
        telemetry = getattr(self.owner, "session_telemetry", None)
        self.clock = telemetry.metrics._monotonic_clock if telemetry else time.monotonic
        self.pending = {}

    @classmethod
    def for_request(cls, method, endpoint):
        if (method == "POST" and endpoint in READ_STAGES
                and getattr(current_latency_owner(), "session_telemetry", None) is not None):
            return cls(READ_STAGES[endpoint])
        return None

    def options(self, kwargs):
        """Never mutate pooled client headers, caller dictionaries, or body."""
        result = dict(kwargs)
        headers = {k: v for k, v in dict(result.get("headers") or {}).items()
                   if k.lower() != "x-xiaozhi-trace-id"}
        headers["X-Xiaozhi-Trace-Id"] = self.metadata["request_id"]
        result["headers"] = headers
        extensions = dict(result.get("extensions") or {})
        # Existing tracing must retain its behavior if a caller supplies it.
        prior = extensions.get("trace")
        if prior is None:
            extensions["trace"] = self.trace
        else:
            async def trace(event, info):
                await self.trace(event, info)
                await prior(event, info)
            extensions["trace"] = trace
        result["extensions"] = extensions
        return result

    def span(self, suffix, **metadata):
        return latency_span(self.owner, self.stage + "." + suffix,
                            **self.metadata, **metadata)

    async def trace(self, event, info):
        # Deliberately never inspect info: it contains URLs, headers and bodies.
        try:
            prefix, status = event.rsplit(".", 1)
            stage = TRACE_STAGES.get(prefix)
            if stage is None or status not in {"started", "complete", "failed"}:
                return
            now = self.clock()
            if status == "started":
                self.pending[prefix] = now
                mark_latency(self.owner, self.stage + "." + stage + ".start",
                             **self.metadata)
            else:
                started = self.pending.pop(prefix, None)
                if started is not None:
                    mark_latency(self.owner, self.stage + "." + stage + ".end",
                                 elapsed_ms=round((now - started) * 1000, 3),
                                 status="completed" if status == "complete" else "interrupted",
                                 **self.metadata)
        except Exception:
            # Diagnostics cannot fail the actual request or leak exception text.
            pass

    def finish(self):
        for prefix, started in list(self.pending.items()):
            mark_latency(self.owner, self.stage + "." + TRACE_STAGES[prefix] + ".end",
                         elapsed_ms=round((self.clock() - started) * 1000, 3),
                         status="interrupted", **self.metadata)
        self.pending.clear()
