import asyncio
import threading

from config import settings

settings.config_file_valid = True

import core.connection as connection_module
from config.manage_api_client import (
    DEFAULT_MANAGED_MEMORY_QUERY_TIMEOUT_SECONDS,
    ManageApiClient,
    get_managed_memory_query_timeout,
)
from core.connection import ConnectionHandler


class _Logger:
    def __init__(self):
        self.records = []

    def bind(self, **kwargs):
        return self

    def info(self, message):
        self.records.append(("info", message))

    def warning(self, message):
        self.records.append(("warning", message))


class _LoopThread:
    def __init__(self):
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self.loop.run_forever)

    def __enter__(self):
        self.thread.start()
        return self.loop

    def __exit__(self, exc_type, exc_value, traceback):
        async def cancel_pending_tasks():
            current = asyncio.current_task()
            pending = [
                task
                for task in asyncio.all_tasks()
                if task is not current and not task.done()
            ]
            for task in pending:
                task.cancel()
            if pending:
                await asyncio.gather(*pending, return_exceptions=True)

        asyncio.run_coroutine_threadsafe(
            cancel_pending_tasks(), self.loop
        ).result(timeout=1)
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.thread.join(timeout=1)
        self.loop.close()


def _managed_connection(loop):
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.logger = _Logger()
    conn.read_config_from_api = True
    conn.config = {
        "agent_id": "sensitive-agent-id",
        "selected_module": {"Memory": "Memory_managed_memory"},
        "Memory": {"Memory_managed_memory": {"type": "managed_memory"}},
    }
    conn.device_id = "sensitive-device-id"
    conn.loop = loop
    conn.memory = None
    return conn


def _assert_safe_record(conn, expected_status, expected_chars):
    assert len(conn.logger.records) == 1
    _, message = conn.logger.records[0]
    assert f"status={expected_status}" in message
    assert f"context_chars={expected_chars}" in message
    assert "elapsed_ms=" in message
    assert "sensitive-agent-id" not in message
    assert "sensitive-device-id" not in message
    assert "private memory text" not in message


def test_managed_memory_query_timeout_defaults_and_private_override(monkeypatch):
    monkeypatch.setattr(ManageApiClient, "config", {}, raising=False)
    assert (
        get_managed_memory_query_timeout()
        == DEFAULT_MANAGED_MEMORY_QUERY_TIMEOUT_SECONDS
        == 12.0
    )

    ManageApiClient.config = {"memory_query_timeout": "7.5"}
    assert get_managed_memory_query_timeout() == 7.5

    for invalid_value in (None, "invalid", 0, -1, float("inf")):
        ManageApiClient.config = {"memory_query_timeout": invalid_value}
        assert (
            get_managed_memory_query_timeout()
            == DEFAULT_MANAGED_MEMORY_QUERY_TIMEOUT_SECONDS
        )


def test_managed_memory_query_logs_success_with_context_without_content(monkeypatch):
    async def query_memory(*args, **kwargs):
        return "private memory text"

    monkeypatch.setattr(connection_module, "query_managed_memory", query_memory)
    monkeypatch.setattr(connection_module, "get_managed_memory_query_timeout", lambda: 1)

    with _LoopThread() as loop:
        conn = _managed_connection(loop)
        assert conn._query_memory_context("private question") == "private memory text"

    _assert_safe_record(conn, "success_with_context", len("private memory text"))


def test_managed_memory_query_distinguishes_empty_and_failed(monkeypatch):
    responses = iter(("", None))

    async def query_memory(*args, **kwargs):
        return next(responses)

    monkeypatch.setattr(connection_module, "query_managed_memory", query_memory)
    monkeypatch.setattr(connection_module, "get_managed_memory_query_timeout", lambda: 1)

    with _LoopThread() as loop:
        empty_conn = _managed_connection(loop)
        assert empty_conn._query_memory_context("private question") is None
        failed_conn = _managed_connection(loop)
        assert failed_conn._query_memory_context("private question") is None

    _assert_safe_record(empty_conn, "success_empty", 0)
    _assert_safe_record(failed_conn, "failed", 0)


def test_managed_memory_query_logs_timeout_without_sensitive_values(monkeypatch):
    async def slow_query(*args, **kwargs):
        await asyncio.sleep(1)
        return "private memory text"

    monkeypatch.setattr(connection_module, "query_managed_memory", slow_query)
    monkeypatch.setattr(
        connection_module, "get_managed_memory_query_timeout", lambda: 0.01
    )

    with _LoopThread() as loop:
        conn = _managed_connection(loop)
        assert conn._query_memory_context("private question") is None

    _assert_safe_record(conn, "timeout", 0)
