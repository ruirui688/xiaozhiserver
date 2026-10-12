"""Offline health tests: no supplier calls, private configuration or running server."""

import asyncio
import json
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from core.metering import health
from core.metering.context import snapshot
from core.metering.outbox import UsageOutbox
from core.metering.recorder import begin_call, configure
from core.metering.service import MeteringService, deliver_once


def runtime():
    return {
        "billing_context": {
            "context_id": "context-owned-by-manager", "context_token": "capability-must-stay-private",
            "workspace_id": "workspace-must-not-be-self-reported",
            "models": {"model-a": {"provider": "deepseek", "service_type": "LLM"}},
        },
        "selected_module": {"LLM": "model-a"},
        "api_key": "private-key-must-not-leak",
    }


@pytest.fixture
def observed(tmp_path):
    tracker = health.CollectorHealth(True)
    box = UsageOutbox(str(tmp_path / "usage.sqlite3"), tracker.persistence_failed)
    health.configure(tracker)
    configure(box)
    yield tracker, box
    configure(None)
    health.configure(None)
    box.close()


def test_empty_collector_reports_observations_without_claiming_readiness(observed):
    tracker, box = observed
    payload = tracker.payload(box.instance_id(), box)
    assert payload["managedMode"] is True and payload["queueAvailable"] is True
    assert payload["contextCount"] == payload["activeCount"] == payload["pendingCount"] == 0
    assert payload["lastContextAt"] is payload["lastCallStartedAt"] is payload["lastDeliveryAt"] is None
    assert "status" not in payload and "READY" not in json.dumps(payload)
    assert "LLM/openai" in payload["supportedAdapters"]
    assert "LLM/gemini" not in payload["supportedAdapters"]


def test_health_contains_no_capability_private_config_or_self_reported_owner(observed):
    tracker, box = observed
    config = runtime()
    health.context_received(config)
    begin_call(SimpleNamespace(), "LLM", scope=snapshot(config=config)).finish("SUCCESS")
    payload = tracker.payload(box.instance_id(), box)
    assert payload["contextCount"] == 1
    assert payload["lastContextAt"] and payload["lastCallStartedAt"]
    assert payload["lastDeliveryAt"] is None and payload["pendingCount"] == 1
    encoded = json.dumps(payload)
    for value in (config["api_key"], *[config["billing_context"][k] for k in ("context_token", "context_id", "workspace_id")]):
        assert value not in encoded


def test_missing_scope_is_visible_without_changing_model_execution(observed, caplog):
    tracker, box = observed
    call = begin_call(SimpleNamespace(), "LLM")
    call.usage("input_tokens", 100)
    call.finish("SUCCESS")
    payload = tracker.payload(box.instance_id(), box)
    assert payload["missingContextCalls"] == 1 and payload["lastCallStartedAt"]
    assert payload["pendingCount"] == 0 and payload["contextCount"] == 0
    assert "without usage collection" in caplog.text


def test_health_observer_failure_does_not_break_existing_usage_capture(observed, monkeypatch, caplog):
    tracker, box = observed

    def broken(scope):
        raise RuntimeError("observer-error-with-private-text")

    monkeypatch.setattr(tracker, "call_started", broken)
    begin_call(SimpleNamespace(), "LLM", scope=snapshot(config=runtime())).finish("SUCCESS")
    assert len(box.pending()) == 1
    assert "observer-error-with-private-text" not in caplog.text


def test_config_fetch_failure_is_observed_without_changing_existing_fallback(observed, monkeypatch, caplog):
    from config import config_loader
    tracker, box = observed
    monkeypatch.setattr(config_loader, "get_agent_models", AsyncMock(side_effect=RuntimeError("do-not-log-secret")))
    monkeypatch.setattr(config_loader, "get_correct_words", AsyncMock(return_value=None))
    result = asyncio.run(config_loader.get_private_config_from_api({"selected_module": {}}, "device", "client"))
    assert not result.get("billing_context")
    assert tracker.payload(box.instance_id(), box)["missingContextCount"] == 1
    assert "do-not-log-secret" not in caplog.text


def test_instance_identity_survives_restart_but_not_an_independent_queue(tmp_path):
    path = str(tmp_path / "first.sqlite3")
    first = UsageOutbox(path)
    identity = first.instance_id()
    first.close()
    restarted = UsageOutbox(path)
    independent = UsageOutbox(str(tmp_path / "second.sqlite3"))
    try:
        assert restarted.instance_id() == identity
        assert independent.instance_id() != identity
    finally:
        restarted.close()
        independent.close()


def test_active_pending_and_quarantined_counts_are_separate(observed):
    tracker, box = observed
    scope = snapshot(config=runtime())
    active = begin_call(SimpleNamespace(), "LLM", scope=scope)
    waiting = begin_call(SimpleNamespace(), "LLM", scope=scope)
    rejected = begin_call(SimpleNamespace(), "LLM", scope=scope)
    waiting.finish("SUCCESS")
    rejected.finish("FAILED")
    box.quarantine({rejected.event["eventId"]: "INVALID_CONTEXT"})
    assert box.counts() == dict(activeCount=1, pendingCount=1, rejectedCount=1)
    box.recover()
    assert box.counts() == dict(activeCount=0, pendingCount=2, rejectedCount=1)
    assert tracker.payload(box.instance_id(), box)["lastDeliveryAt"] is None
    assert any(row["eventId"] == active.event["eventId"] and row["status"] == "UNKNOWN" for row in box.pending())


def test_only_accepted_events_establish_successful_delivery(observed):
    tracker, box = observed
    call = begin_call(SimpleNamespace(), "LLM", scope=snapshot(config=runtime()))
    call.finish("SUCCESS")
    send = AsyncMock(return_value={"accepted": [], "rejected": []})
    assert asyncio.run(deliver_once(box, send, tracker)) is False
    payload = tracker.payload(box.instance_id(), box)
    assert payload["lastDeliveryAt"] is None and payload["lastDeliveryErrorCode"] == "ACK_INCOMPLETE"
    send.return_value = {"accepted": [call.event["eventId"]], "rejected": []}
    assert asyncio.run(deliver_once(box, send, tracker)) is True
    payload = tracker.payload(box.instance_id(), box)
    assert payload["lastDeliveryAt"] and payload["lastDeliveryErrorCode"] is None
    assert payload["pendingCount"] == 0


def test_permanent_rejection_is_not_successful_ingestion(observed):
    tracker, box = observed
    call = begin_call(SimpleNamespace(), "LLM", scope=snapshot(config=runtime()))
    call.finish("SUCCESS")
    send = AsyncMock(return_value={"accepted": [], "rejected": [{"eventId": call.event["eventId"], "reasonCode": "INVALID_CONTEXT"}]})
    assert asyncio.run(deliver_once(box, send, tracker)) is True
    payload = tracker.payload(box.instance_id(), box)
    assert payload["lastDeliveryAt"] is None and payload["rejectedCount"] == 1


def test_persistence_failure_and_unreadable_queue_are_not_healthy(tmp_path):
    tracker = health.CollectorHealth(True)
    box = UsageOutbox(str(tmp_path / "closed.sqlite3"), tracker.persistence_failed)
    identity = box.instance_id()
    box.close()
    with pytest.raises(sqlite3.Error):
        box.save({"eventId": "offline-event"}, active=True)
    payload = tracker.payload(identity, box)
    assert payload["queueAvailable"] is False
    assert payload["persistenceFailureCount"] == 1


def test_concurrent_missing_context_calls_do_not_lose_health_counts(observed):
    tracker, box = observed
    with ThreadPoolExecutor(max_workers=8) as executor:
        list(executor.map(lambda _: begin_call(SimpleNamespace(), "LLM"), range(80)))
    assert tracker.payload(box.instance_id(), box)["missingContextCalls"] == 80


def test_delivery_blockage_does_not_block_health_and_stop_cancels_both_tasks(tmp_path, monkeypatch):
    monkeypatch.setenv("XIAOZHI_BILLING_OUTBOX_PATH", str(tmp_path / "service.sqlite3"))
    service = MeteringService({"read_config_from_api": True})
    reports = []

    async def run():
        health_seen = asyncio.Event()
        delivery_started = asyncio.Event()
        never_finish = asyncio.Event()

        async def post(endpoint, payload):
            if endpoint.endswith("/health"):
                reports.append(payload)
                health_seen.set()
                return {"accepted": True}
            delivery_started.set()
            await never_finish.wait()

        monkeypatch.setattr(service, "_post", post)
        await service.start()
        try:
            begin_call(SimpleNamespace(), "LLM", scope=snapshot(config=runtime())).finish("SUCCESS")
            await asyncio.wait_for(delivery_started.wait(), 2)
            await asyncio.wait_for(health_seen.wait(), 2)
            assert reports[0]["pendingCount"] == 1
            assert reports[0]["lastDeliveryAt"] is None
        finally:
            await service.stop()
            configure(None)
            service.outbox.close()
            service.lock_file.close()
        assert service.task.done() and service.health_task.done()

    asyncio.run(run())


def test_local_mode_does_not_create_network_worker_or_queue(tmp_path, monkeypatch):
    monkeypatch.setenv("XIAOZHI_BILLING_OUTBOX_PATH", str(tmp_path / "unused.sqlite3"))
    service = MeteringService({"read_config_from_api": False})
    asyncio.run(service.start())
    assert service.task is None and service.health_task is None and service.outbox is None
    assert not (tmp_path / "unused.sqlite3").exists()
