import asyncio
import json
import re
from types import SimpleNamespace

import httpx
import pytest

from config.manage_api_client import ManageApiClient
from config.request_observation import RequestObservation
from core.observability.session_telemetry import (
    SessionTelemetry, current_latency_owner, current_latency_metadata, latency_span,
)


class Sink:
    def __init__(self):
        self.logs = []

    def bind(self, **kwargs):
        return self

    def info(self, value):
        self.logs.append(json.loads(value))


def owner(name):
    sink = Sink()
    return SimpleNamespace(session_telemetry=SessionTelemetry(name, sink, "test")), sink


def test_concurrent_requests_correlate_without_payload_or_header_mutation(monkeypatch):
    async def run():
        requests = []

        class Client:
            async def request(self, method, endpoint, **kwargs):
                requests.append(kwargs)
                trace = kwargs["extensions"]["trace"]
                await trace("connection.connect_tcp.started", {"secret": "never-log"})
                await asyncio.sleep(0)
                await trace("connection.connect_tcp.complete", {})
                await trace("http11.receive_response_headers.started", {})
                await trace("http11.receive_response_headers.complete", {})
                await trace("http11.receive_response_body.started", {})
                await trace("http11.receive_response_body.complete", {})
                return httpx.Response(200, json={"code": 0, "data": {}},
                                      request=httpx.Request(method, "https://example.invalid"))

        async def ensure(cls):
            return Client()

        monkeypatch.setattr(ManageApiClient, "_ensure_async_client", classmethod(ensure))
        headers = {"Authorization": "never-log"}
        observers = [owner("a"), owner("b")]

        async def request(item):
            conn, sink = item
            with latency_span(conn, "memory.query", turn_id=conn.session_telemetry.session_id):
                await ManageApiClient._async_request("POST", "/config/memory/query",
                                                    headers=headers, json={"query": "never-log"})
            assert current_latency_owner() is None

        await asyncio.gather(*(request(item) for item in observers))
        ids = [item["headers"]["X-Xiaozhi-Trace-Id"] for item in requests]
        assert len(set(ids)) == 2 and all(re.fullmatch("[a-f0-9]{32}", x) for x in ids)
        assert headers == {"Authorization": "never-log"}
        for conn, sink in observers:
            events = [e for e in sink.logs if e["stage"].startswith("api.")]
            assert events and len({e["request_id"] for e in events}) == 1
            assert all(e["turn_id"] == conn.session_telemetry.session_id for e in events)
            assert "never-log" not in json.dumps(sink.logs)
            assert any(e["stage"] == "api.memory.receive_body.end" for e in events)

    asyncio.run(run())


@pytest.mark.parametrize("error", [httpx.ReadTimeout("never-log"), asyncio.CancelledError()])
def test_failure_and_cancel_close_spans_and_restore_context(monkeypatch, error):
    async def run():
        class Client:
            async def request(self, method, endpoint, **kwargs):
                await kwargs["extensions"]["trace"]("http11.receive_response_body.started", {})
                raise error

        async def ensure(cls):
            return Client()

        monkeypatch.setattr(ManageApiClient, "_ensure_async_client", classmethod(ensure))
        conn, sink = owner("failure")
        with pytest.raises(type(error)):
            with latency_span(conn, "memory.query"):
                await ManageApiClient._async_request("POST", "/config/memory/query")
        assert current_latency_owner() is None
        ends = [e for e in sink.logs if e["stage"] in {"api.memory.total.end", "api.memory.receive_body.end"}]
        assert len(ends) == 2 and all(e["status"] == "interrupted" for e in ends)
        assert "never-log" not in json.dumps(sink.logs)

    asyncio.run(run())


def test_request_allowlist_and_missing_context():
    assert RequestObservation.for_request("POST", "/config/memory/query") is None
    conn, _ = owner("scope")
    with latency_span(conn, "config.models"):
        for endpoint in ["https://example.invalid/config/memory/query", "/config/memory/query?x=1", "/config/other"]:
            assert RequestObservation.for_request("POST", endpoint) is None
        assert RequestObservation.for_request("GET", "/config/memory/query") is None
        assert RequestObservation.for_request("POST", "/config/agent-models") is not None


def test_nested_context_and_thread_handoff():
    async def run():
        a, _ = owner("a")
        b, _ = owner("b")
        loop = asyncio.get_running_loop()

        async def inspect():
            assert current_latency_owner() is a
            assert current_latency_metadata()["turn_id"] == "turn"

        def worker():
            with latency_span(a, "memory.query", turn_id="turn"):
                asyncio.run_coroutine_threadsafe(inspect(), loop).result(timeout=2)
                with latency_span(b, "other"):
                    assert "turn_id" not in current_latency_metadata()
                assert current_latency_owner() is a
            assert current_latency_owner() is None

        await asyncio.to_thread(worker)
        assert current_latency_owner() is None

    asyncio.run(run())


def test_retry_attempts_get_distinct_ids(monkeypatch):
    async def run():
        ids = []

        class Client:
            async def request(self, method, endpoint, **kwargs):
                ids.append(kwargs["headers"]["X-Xiaozhi-Trace-Id"])
                if len(ids) == 1:
                    raise httpx.ReadTimeout("never-log")
                return httpx.Response(200, json={"code": 0, "data": {}},
                                      request=httpx.Request(method, "https://example.invalid"))

        async def ensure(cls):
            return Client()

        monkeypatch.setattr(ManageApiClient, "_ensure_async_client", classmethod(ensure))
        monkeypatch.setattr(ManageApiClient, "max_retries", 1, raising=False)
        monkeypatch.setattr(ManageApiClient, "retry_delay", 0, raising=False)
        conn, sink = owner("retry")
        with latency_span(conn, "config.models"):
            await ManageApiClient._execute_async_request("POST", "/config/agent-models")
        assert len(set(ids)) == 2
        assert {e["request_id"] for e in sink.logs if "request_id" in e} == set(ids)
        assert current_latency_owner() is None

    asyncio.run(run())


def test_options_preserve_prior_trace_and_sink_failure_is_harmless():
    async def run():
        conn, sink = owner("safe")
        prior_calls = []

        async def prior(event, info):
            prior_calls.append((event, info))

        original = {"headers": {"x-Xiaozhi-Trace-ID": "old"}, "extensions": {"trace": prior}}
        with latency_span(conn, "memory.query"):
            observation = RequestObservation.for_request("POST", "/config/memory/query")
            options = observation.options(original)
        assert original["headers"] == {"x-Xiaozhi-Trace-ID": "old"}
        assert original["extensions"]["trace"] is prior
        assert list(options["headers"]) == ["X-Xiaozhi-Trace-Id"]
        def fail(value):
            raise ValueError("never-log")
        sink.info = fail
        info = {"body": "never-log"}
        await options["extensions"]["trace"]("connection.connect_tcp.started", info)
        await options["extensions"]["trace"]("connection.connect_tcp.complete", info)
        assert prior_calls == [("connection.connect_tcp.started", info),
                               ("connection.connect_tcp.complete", info)]
        assert "never-log" not in json.dumps(sink.logs)

    asyncio.run(run())


def test_httpx_real_loopback_trace_events(monkeypatch):
    async def run():
        received = []
        finished = asyncio.Event()

        async def serve(reader, writer):
            try:
                received.append(await reader.readuntil(b"\r\n\r\n"))
                body = b'{"code":0,"data":{"ok":true}}'
                writer.write(b"HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: "
                             + str(len(body)).encode() + b"\r\n\r\n" + body)
                await writer.drain()
            finally:
                writer.close()
                await writer.wait_closed()
                finished.set()

        server = await asyncio.start_server(serve, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{port}",
                                         trust_env=False, timeout=2) as client:
                async def ensure(cls):
                    return client
                monkeypatch.setattr(ManageApiClient, "_ensure_async_client", classmethod(ensure))
                conn, sink = owner("loopback")
                with latency_span(conn, "config.models"):
                    result = await ManageApiClient._async_request("POST", "/config/agent-models")
                await asyncio.wait_for(finished.wait(), 2)
                assert result == {"ok": True}
                events = [e for e in sink.logs if e["stage"].startswith("api.")]
                stages = {e["stage"] for e in events}
                assert {"api.models.tcp_connect.end", "api.models.receive_headers.end",
                        "api.models.receive_body.end", "api.models.total.end"} <= stages
                ids = {e["request_id"] for e in events}
                assert len(ids) == 1
                assert (b"X-Xiaozhi-Trace-Id: " + ids.pop().encode()) in received[0]
        finally:
            server.close()
            await server.wait_closed()

    asyncio.run(run())
