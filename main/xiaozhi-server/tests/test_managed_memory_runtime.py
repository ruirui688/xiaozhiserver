"""Local managed memory leases and fail-closed invalidation."""
import asyncio
import copy
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from config import settings
settings.config_file_valid = True

from core.connection import ConnectionHandler
from core.session.managed_memory_snapshot import ManagedMemorySnapshot, snapshot_for
from core.session.runtime_config import build_managed_runtime_config, runtime_config_fingerprint
from core.session.runtime_config_reloader import RuntimeConfigReloader
import core.session.runtime_config_reloader as reload_module
from core.providers.memory.managed_memory.managed_memory import MemoryProvider


def payload(**overrides):
    now = int(time.time() * 1000)
    return {"schema_version": 1, "status": "ready", "agent_id": "agent-a", "device_id": "device-a",
            "revision": "a" * 64, "issued_at_ms": now, "expires_at_ms": now + 30000,
            "shared_context": "shared facts", "speaker_contexts": {"voice-a": "private A", "voice-b": "private B"},
            **overrides}


def connection():
    config = {"agent_id": "agent-a", "selected_module": {"Memory": "managed"}, "delete_audio": True,
              "Memory": {"managed": {"type": "managed_memory"}}, "managed_memory_snapshot": payload()}
    conn = SimpleNamespace(config=config, common_config={}, device_id="device-a",
                           current_speaker_id=None, read_config_from_api=True, memory=None,
                           headers={"device-id": "device-a"}, closing=False, closed=False,
                           client_is_speaking=False, need_bind=False,
                           logger=SimpleNamespace(bind=lambda **kw: SimpleNamespace(info=lambda *a: None, warning=lambda *a: None, error=lambda *a: None)))
    conn.runtime_config_reloader = RuntimeConfigReloader(conn)
    conn.runtime_config_reloader.mark_applied(config)
    return conn


def test_local_reads_use_shared_and_current_speaker_without_http(monkeypatch):
    import config.manage_api_client as api
    async def forbidden(*args, **kwargs):
        pytest.fail("memory read attempted HTTP")
    monkeypatch.setattr(api, "query_managed_memory", forbidden)
    conn = connection()
    for speaker, expected in [(None, "shared facts"), ("voice-a", "shared facts\n\nprivate A"),
                              ("voice-b", "shared facts\n\nprivate B"), ("unknown", "shared facts"),
                              (None, "shared facts")]:
        conn.current_speaker_id = speaker
        assert ConnectionHandler._query_memory_context(conn, "wake or question") == expected
    assert ConnectionHandler._query_memory_context(conn, "") is None


@pytest.mark.parametrize("change", [
    {"status": "unavailable"}, {"schema_version": True}, {"schema_version": 2},
    {"agent_id": "other"}, {"device_id": "other"}, {"revision": "invalid"},
    {"shared_context": "x" * 4001}, {"speaker_contexts": {"v": "x" * 2001}},
    {"speaker_contexts": {str(i): "" for i in range(17)}},
    {"speaker_contexts": {str(i): "x" * 2000 for i in range(8)}},
    {"expires_at_ms": 0}, {"expires_at_ms": int(time.time() * 1000) + 60000},
])
def test_invalid_payload_clears_old_summary(change):
    state = snapshot_for(connection())
    assert not state.install(payload(**change), state.epoch)
    assert state.read() == ""


def test_expiry_and_config_replacement_fail_closed():
    conn = connection()
    now = time.time()
    tick = [10.0]
    state = ManagedMemorySnapshot(conn, wall_clock=lambda: now, clock=lambda: tick[0])
    assert state.install(payload(issued_at_ms=int(now * 1000), expires_at_ms=int(now * 1000) + 1000), 0)
    tick[0] += 2
    assert state.read() == ""
    assert state.install(payload(), 0)
    conn.config = copy.deepcopy(conn.config)
    assert state.read() == ""


def test_refresh_and_initial_old_response_do_not_revive_memory():
    conn = connection()
    state = snapshot_for(conn)
    old_epoch = state.epoch
    conn.runtime_config_reloader.request_refresh("memory_changed")
    assert state.read() == ""
    assert not state.install(payload(), old_epoch)
    conn.runtime_config_reloader.mark_applied(conn.config)
    assert state.read() == ""
    assert conn.runtime_config_reloader._pending


def test_snapshot_only_refresh_does_not_rebuild_pipeline(monkeypatch):
    conn = connection()
    reloader = conn.runtime_config_reloader
    new = copy.deepcopy(conn.config)
    new["managed_memory_snapshot"] = payload(shared_context="changed")
    monkeypatch.setattr(reload_module, "get_private_config_from_api", AsyncMock(return_value=new))
    reloader._replace_pipeline = AsyncMock()
    reloader.request_refresh("memory_changed")
    assert asyncio.run(reloader.refresh_if_due(force=True)) is False
    reloader._replace_pipeline.assert_not_awaited()
    assert snapshot_for(conn).read() == "changed"


def test_refresh_failure_and_inflight_notification_leave_memory_empty(monkeypatch):
    conn = connection()
    reloader = conn.runtime_config_reloader
    monkeypatch.setattr(reload_module, "get_private_config_from_api", AsyncMock(side_effect=TimeoutError()))
    with pytest.raises(TimeoutError):
        asyncio.run(reloader.refresh_if_due(force=True))
    assert snapshot_for(conn).read() == ""
    async def fetch(*args):
        reloader.request_refresh("memory_changed")
        return conn.config
    monkeypatch.setattr(reload_module, "get_private_config_from_api", fetch)
    assert asyncio.run(reloader.refresh_if_due(force=True)) is False
    assert snapshot_for(conn).read() == ""
    assert reloader._pending


def test_missing_authoritative_snapshot_never_uses_common_cache():
    common = {"managed_memory_snapshot": payload()}
    assert "managed_memory_snapshot" not in build_managed_runtime_config(common, {})
    assert runtime_config_fingerprint(common) == runtime_config_fingerprint({})


def test_provider_reads_local_and_nomem_is_empty():
    conn = connection()
    provider = MemoryProvider({})
    provider.snapshot_reader = snapshot_for(conn).read
    assert asyncio.run(provider.query_memory("question")) == "shared facts"
    provider.snapshot_reader = None
    assert asyncio.run(provider.query_memory("question")) == ""
    conn.config["Memory"]["managed"]["type"] = "nomem"
    assert ConnectionHandler._query_memory_context(conn, "question") is None


@pytest.mark.parametrize("flag", ["closing", "closed", "need_bind", "initialization_failed"])
def test_unavailable_session_never_reads_snapshot(flag):
    conn = connection()
    setattr(conn, flag, True)
    assert snapshot_for(conn).read() == ""


def test_continuous_session_renews_off_reply_path_singleflight(monkeypatch):
    async def run():
        conn = connection()
        conn.loop = asyncio.get_running_loop()
        reloader = conn.runtime_config_reloader
        state = snapshot_for(conn)
        state.deadline = 0
        entered, release = asyncio.Event(), asyncio.Event()
        calls = []
        async def fetch(*args):
            calls.append(args)
            entered.set()
            await release.wait()
            return {**conn.config, "managed_memory_snapshot": payload(shared_context="renewed")}
        monkeypatch.setattr(reload_module, "get_private_config_from_api", fetch)
        reloader._replace_pipeline = AsyncMock()
        # No listen.start and no wait for the config request in the reply path.
        for _ in range(10):
            assert ConnectionHandler._query_memory_context(conn, "question") is None
        await asyncio.wait_for(entered.wait(), 1)
        assert len(calls) == 1
        release.set()
        await reloader._snapshot_refresh_task
        assert ConnectionHandler._query_memory_context(conn, "question") == "renewed"
        reloader._replace_pipeline.assert_not_awaited()
    asyncio.run(run())


def test_background_renewal_failure_backoff_and_close_cancel(monkeypatch):
    async def run():
        conn = connection()
        conn.loop = asyncio.get_running_loop()
        reloader = conn.runtime_config_reloader
        fetch = AsyncMock(side_effect=TimeoutError())
        monkeypatch.setattr(reload_module, "get_private_config_from_api", fetch)
        reloader._start_snapshot_refresh()
        await reloader._snapshot_refresh_task
        reloader._start_snapshot_refresh()
        assert fetch.await_count == 1
        assert snapshot_for(conn).read() == ""
        entered = asyncio.Event()
        async def slow(*args):
            entered.set()
            await asyncio.Future()
        monkeypatch.setattr(reload_module, "get_private_config_from_api", slow)
        reloader._snapshot_refresh_at = 0
        reloader._start_snapshot_refresh()
        await entered.wait()
        conn.closing = True
        reloader.cancel_snapshot_refresh()
        with pytest.raises(asyncio.CancelledError):
            await reloader._snapshot_refresh_task
        assert snapshot_for(conn).read() == ""
    asyncio.run(run())


def test_background_config_change_defers_pipeline_and_retains_no_old_memory(monkeypatch):
    async def run():
        conn = connection()
        conn.loop = asyncio.get_running_loop()
        reloader = conn.runtime_config_reloader
        monkeypatch.setattr(reload_module, "get_private_config_from_api",
                            AsyncMock(return_value={**conn.config, "agent_id": "new-agent"}))
        reloader._replace_pipeline = AsyncMock()
        await reloader._refresh_snapshot_only()
        assert reloader._pending
        assert snapshot_for(conn).read() == ""
        reloader._replace_pipeline.assert_not_awaited()
    asyncio.run(run())


def test_two_connections_concurrent_renewal_remains_isolated(monkeypatch):
    async def run():
        a, b = connection(), connection()
        b.device_id = "device-b"
        b.headers = {"device-id": "device-b"}
        b.config["managed_memory_snapshot"] = payload(device_id="device-b", shared_context="B")
        b.runtime_config_reloader.mark_applied(b.config)
        both_entered, release = asyncio.Event(), asyncio.Event()
        calls = []
        async def fetch(config, device, client):
            calls.append(device)
            if len(calls) == 2:
                both_entered.set()
            await release.wait()
            return {**config, "managed_memory_snapshot": payload(device_id=device, shared_context=device)}
        monkeypatch.setattr(reload_module, "get_private_config_from_api", fetch)
        for conn in (a, b):
            conn.loop = asyncio.get_running_loop()
            conn.runtime_config_reloader._start_snapshot_refresh()
        await asyncio.wait_for(both_entered.wait(), 1)
        release.set()
        await asyncio.gather(*(c.runtime_config_reloader._snapshot_refresh_task for c in (a, b)))
        assert snapshot_for(a).read() == "device-a"
        assert snapshot_for(b).read() == "device-b"
        a.runtime_config_reloader.request_refresh("memory_changed")
        assert snapshot_for(a).read() == ""
        assert snapshot_for(b).read() == "device-b"
    asyncio.run(run())


def test_provider_activation_rollback_cannot_restore_forgotten_snapshot(monkeypatch):
    conn = connection()
    reloader = conn.runtime_config_reloader
    old = copy.deepcopy(conn.config)
    candidate = {**old, "prompt": "changed"}
    conn.speech_runtime = SimpleNamespace(route=object(), resolve_candidate=lambda *a, **kw: object())
    reloader._build_modules = AsyncMock(return_value={})
    reloader._deactivate_pipeline = AsyncMock()
    activations = []
    async def activate(config, *args):
        conn.config = copy.deepcopy(config)
        activations.append(config)
        if len(activations) == 1:
            raise RuntimeError("new provider failed")
    reloader._activate_pipeline = activate
    monkeypatch.setattr(reload_module, "get_private_config_from_api", AsyncMock(return_value=candidate))
    with pytest.raises(RuntimeError, match="new provider failed"):
        asyncio.run(reloader.refresh_if_due(force=True))
    assert len(activations) == 2
    assert conn.config == old
    assert snapshot_for(conn).read() == ""


def test_third_party_memory_keeps_own_provider_without_managed_query(monkeypatch):
    import concurrent.futures
    import config.manage_api_client as api
    def forbidden(*args, **kwargs):
        pytest.fail("third-party provider attempted managed HTTP")
    monkeypatch.setattr(api, "query_managed_memory", forbidden)
    def submit(coro, loop):
        future = concurrent.futures.Future()
        future.set_result(asyncio.run(coro))
        return future
    monkeypatch.setattr(asyncio, "run_coroutine_threadsafe", submit)
    conn = connection()
    conn.config["Memory"]["managed"]["type"] = "powermem"
    conn.memory = SimpleNamespace(query_memory=AsyncMock(return_value="third-party facts"))
    conn.loop = object()
    assert ConnectionHandler._query_memory_context(conn, "question") == "third-party facts"
    conn.memory.query_memory.assert_awaited_once_with("question")


def test_legacy_query_timeout_config_remains_compatible(monkeypatch):
    from config.manage_api_client import (
        ManageApiClient, get_managed_memory_query_timeout,
        DEFAULT_MANAGED_MEMORY_QUERY_TIMEOUT_SECONDS,
    )
    monkeypatch.setattr(ManageApiClient, "config", {}, raising=False)
    assert get_managed_memory_query_timeout() == DEFAULT_MANAGED_MEMORY_QUERY_TIMEOUT_SECONDS == 12.0
    ManageApiClient.config = {"memory_query_timeout": "7.5"}
    assert get_managed_memory_query_timeout() == 7.5
    for invalid in (None, "invalid", 0, -1, float("inf")):
        ManageApiClient.config = {"memory_query_timeout": invalid}
        assert get_managed_memory_query_timeout() == DEFAULT_MANAGED_MEMORY_QUERY_TIMEOUT_SECONDS
