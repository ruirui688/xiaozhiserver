"""Exercise real execution boundaries with an in-process supplier and manager."""

import asyncio
import json
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest

from core.metering import admission, snapshot
from core.metering.openai_stream import metered_stream
from core.metering.outbox import UsageOutbox
from core.metering.recorder import begin_call, configure
from core.metering.service import MeteringService, deliver_once


@pytest.fixture
def box(tmp_path):
    outbox = UsageOutbox(str(tmp_path / "usage.sqlite3"))
    configure(outbox)
    yield outbox
    configure(None)
    outbox.close()


def provider(owner="owner-a"):
    scope = snapshot(config={
        "billing_context": {"context_id": owner, "context_token": "test-context-capability",
            "models": {"model-a": {"service_type": "LLM", "provider": "deepseek"}},
            "_platform_managed": True},
        "selected_module": {"LLM": "model-a"},
    })
    return SimpleNamespace(_billing_scope=scope, _platform_managed=True, _iter_stream_with_deadline=iter)


def grant(owner="owner-a", maximum=None):
    return {"reservationId": "reservation-" + owner, "permitToken": "test-settlement-capability",
        "hardExpiresAt": (datetime.now(timezone.utc) + timedelta(seconds=60)).isoformat(),
        "limits": maximum or {"input_tokens": "20000", "output_tokens": "16"},
        "credentials": {"api_key": "private-test-credential-" + owner},
        "runtimeConfig": {"model_name": "approved-model", "base_url": "https://api.deepseek.com/v1"}}


class Stream:
    def __init__(self):
        self.closed = False

    def __iter__(self):
        yield SimpleNamespace(id="provider-request", usage={"prompt_tokens": 8, "completion_tokens": 3})

    def close(self):
        self.closed = True


def client(calls, identity="placeholder"):
    def create(**parameters):
        calls.append((identity, parameters))
        return Stream()
    instance = SimpleNamespace(max_retries=0, chat=SimpleNamespace(completions=SimpleNamespace(create=create)))
    instance.with_options = lambda **options: client(calls, options.get("api_key", identity))
    return instance


def test_missing_context_never_falls_back_to_global_credential(box):
    with pytest.raises(admission.PlatformAdmissionError, match="BILLING_CONTEXT_REQUIRED"):
        begin_call(SimpleNamespace(_platform_managed=True), "LLM", max_usage={"input_tokens": "1"})


def test_denied_budget_makes_no_supplier_request(box, monkeypatch):
    requests = []
    upstream = provider()
    upstream.client = client(requests)
    def deny(*args):
        raise admission.PlatformAdmissionError("PLATFORM_CALL_DENIED")
    monkeypatch.setattr(admission, "_request", deny)
    with pytest.raises(admission.PlatformAdmissionError):
        list(metered_stream(upstream, {"messages": [{"role": "user", "content": "test"}], "max_tokens": 16}))
    assert requests == []
    assert box.pending()[0]["usageComplete"] is False


def test_each_call_uses_its_owner_key_and_server_model_without_mutating_shared_client(box, monkeypatch):
    calls, actions = [], []
    shared_client = client(calls)
    def manager(action, payload):
        actions.append(action)
        if action == "admit":
            return grant(payload["contextId"], payload["maxUsage"])
        return {"started": True}
    monkeypatch.setattr(admission, "_request", manager)
    for owner in ("owner-a", "owner-b"):
        upstream = provider(owner)
        upstream.client = shared_client
        list(metered_stream(upstream, {"model": "stale-model", "messages": [], "max_tokens": 16}))
    assert [key for key, _ in calls] == ["private-test-credential-owner-a", "private-test-credential-owner-b"]
    assert all(params["model"] == "approved-model" and params["max_tokens"] == 16 for _, params in calls)
    assert actions == ["admit", "start", "admit", "start"]
    events = box.pending()
    assert len(events) == 2
    assert all(event["platformStarted"] for event in events)
    encoded = json.dumps(events)
    assert "private-test-credential" not in encoded and "runtimeConfig" not in encoded
    assert all(event["usageComplete"] for event in events)


def test_rotated_credential_resolved_even_on_same_provider_instance(box, monkeypatch):
    credentials = iter(("original", "rotated"))
    calls = []
    upstream = provider()
    upstream.client = client(calls)
    monkeypatch.setattr(admission, "_request", lambda action, payload:
        grant(next(credentials), payload["maxUsage"]) if action == "admit" else {"started": True})
    list(metered_stream(upstream, {"messages": [], "max_tokens": 16}))
    list(metered_stream(upstream, {"messages": [], "max_tokens": 16}))
    assert [key for key, _ in calls] == ["private-test-credential-original", "private-test-credential-rotated"]


def test_expired_start_never_contacts_supplier(box, monkeypatch):
    calls = []
    upstream = provider()
    upstream.client = client(calls)
    def manager(action, payload):
        if action == "admit":
            value = grant(maximum=payload["maxUsage"])
            value["hardExpiresAt"] = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
            return value
        pytest.fail("expired permit must not start")
    monkeypatch.setattr(admission, "_request", manager)
    with pytest.raises(admission.PlatformAdmissionError, match="INVALID_PLATFORM_PERMIT"):
        list(metered_stream(upstream, {"messages": [], "max_tokens": 16}))
    assert not calls


def test_route_change_denied_at_start_does_not_send_upstream(box, monkeypatch):
    calls = []
    upstream = provider()
    upstream.client = client(calls)
    def manager(action, payload):
        if action == "admit":
            return grant(maximum=payload["maxUsage"])
        raise admission.PlatformAdmissionError("ROUTE_CHANGED")
    monkeypatch.setattr(admission, "_request", manager)
    with pytest.raises(admission.PlatformAdmissionError):
        list(metered_stream(upstream, {"messages": [], "max_tokens": 16}))
    assert calls == []
    event, = box.pending()
    assert event["platformStarted"] is True and not event["usageComplete"]


def test_stream_extension_denied_before_next_paid_segment(monkeypatch):
    permit = admission.ExecutionPermit(grant(maximum={"audio_duration": "5000"}))
    permit.consume("audio_duration", 4990)
    def deny(*args):
        raise admission.PlatformAdmissionError("PLATFORM_CALL_DENIED")
    monkeypatch.setattr(admission, "_request", deny)
    with pytest.raises(admission.PlatformAdmissionError):
        permit.consume("audio_duration", 60)
    assert permit.used["audio_duration"] == 4990


def test_confirmed_start_rejection_releases_but_ambiguous_start_stays_held(box, monkeypatch):
    monkeypatch.setattr(admission, "_request", lambda action, payload:
        grant(maximum=payload["maxUsage"]) if action == "admit" else {"started": False})
    call = begin_call(provider(), "LLM", max_usage={"input_tokens": "20", "output_tokens": "16"})
    with pytest.raises(admission.PlatformAdmissionError, match="PLATFORM_START_REJECTED"):
        call.start()
    call.finish("FAILED")
    assert box.pending()[0]["platformStarted"] is False


def test_extra_body_cannot_override_reserved_tokens():
    with pytest.raises(admission.PlatformAdmissionError, match="UNBOUNDED_MODEL_OPTIONS"):
        admission.llm_bounds({"messages": [], "max_tokens": 16, "extra_body": {"max_tokens": 100000}})


def test_one_failed_settlement_does_not_block_independent_receipts(box):
    for index in range(3):
        box.save({"eventId": str(index), "platformSettlement": {"reservationId": str(index)},
                  "platformStarted": True, "status": "UNKNOWN", "usageComplete": False, "usage": []}, active=False)
    service = MeteringService({})
    async def post(endpoint, payload):
        if endpoint.endswith("/settle") and payload["reservationId"] == "0":
            raise ConnectionError("unavailable")
        if endpoint.endswith("/usage"):
            return {"accepted": [event["eventId"] for event in payload["events"]]}
        return {"state": "UNKNOWN"}
    service._post = post
    assert asyncio.run(deliver_once(box, service._send))
    assert [event["eventId"] for event in box.pending()] == ["0"]


def test_failed_batch_does_not_starve_later_queue_records(box):
    for index in range(52):
        box.save({"eventId": str(index)}, active=False)
    assert len(box.delivery_batch()) == 50
    assert [event["eventId"] for event in box.delivery_batch()] == ["50", "51"]
    assert box.delivery_batch()[0]["eventId"] == "0"


@pytest.mark.parametrize("endpoint", ["http://api.deepseek.com/v1", "https://attacker.invalid/v1", "https://api.deepseek.com@attacker.invalid", "https://api.deepseek.com/v1?key=private"])
def test_unapproved_credential_destination_is_rejected(endpoint):
    value = grant()
    value["runtimeConfig"]["base_url"] = endpoint
    with pytest.raises(admission.PlatformAdmissionError, match="UNAPPROVED_MODEL_ENDPOINT"):
        admission.ExecutionPermit(value).endpoint("base_url")


def test_pending_settlement_retains_queue_and_retries_without_upstream_secret(box, monkeypatch):
    monkeypatch.setattr(admission, "_request", lambda action, payload: grant(maximum=payload["maxUsage"]) if action == "admit" else {"started": True})
    call = begin_call(provider(), "LLM", max_usage={"input_tokens": "20", "output_tokens": "16"})
    call.start()
    call.usage("input_tokens", 8, complete=True)
    call.usage("output_tokens", 3, complete=True)
    call.finish("SUCCESS")
    service = MeteringService({})
    posts = []
    async def post(endpoint, payload):
        posts.append((endpoint, payload))
        if len(posts) == 1:
            raise ConnectionError("receipt lost")
        if endpoint.endswith("/usage"):
            assert "platformSettlement" not in payload["events"][0]
            return {"accepted": [call.event["eventId"]]}
        return {"settled": True}
    service._post = post
    with pytest.raises(ConnectionError):
        asyncio.run(deliver_once(box, service._send))
    assert len(box.pending()) == 1
    asyncio.run(deliver_once(box, service._send))
    assert box.pending() == []
    assert posts[0] == posts[1]
    assert "private-test-credential" not in json.dumps(posts)


def test_never_started_reservation_releases_instead_of_claiming_zero_supplier_usage(box, monkeypatch):
    monkeypatch.setattr(admission, "_request", lambda action, payload: grant(maximum=payload["maxUsage"]))
    call = begin_call(provider(), "LLM", max_usage={"input_tokens": "20", "output_tokens": "16"})
    call.finish("FAILED")
    service = MeteringService({})
    endpoints = []
    async def post(endpoint, payload):
        endpoints.append(endpoint)
        return {"accepted": [call.event["eventId"]]} if endpoint.endswith("/usage") else {"released": True}
    service._post = post
    asyncio.run(deliver_once(box, service._send))
    assert endpoints == ["/config/platform/release", "/config/billing/usage"]


def test_uninstrumented_provider_is_blocked_for_managed_config():
    with pytest.raises(admission.PlatformAdmissionError, match="MODEL_NOT_ADMITTED"):
        admission.validate_adapter("LLM", "coze", {"_platform_managed": True})


def test_local_asr_remains_available_without_opening_remote_asr_bypass():
    for adapter in ("fun_local", "vosk", "sherpa_onnx_local"):
        admission.validate_adapter("ASR", adapter, {"_platform_managed": True})
    with pytest.raises(admission.PlatformAdmissionError, match="MODEL_NOT_ADMITTED"):
        admission.validate_adapter("ASR", "fun_server", {"_platform_managed": True})


def test_stream_asr_benchmark_is_denied_before_websocket_dispatch(box, monkeypatch):
    from core.benchmark import asr_executor
    from unittest.mock import Mock
    asr = SimpleNamespace(_platform_managed=True)
    connect = Mock(side_effect=AssertionError("supplier must not be contacted"))
    monkeypatch.setattr(asr_executor.websockets, "connect", connect)
    with pytest.raises(admission.PlatformAdmissionError, match="BILLING_CONTEXT_REQUIRED"):
        asyncio.run(asr_executor._recognize_doubao_stream(asr, b"\x00" * 320, 0))
    connect.assert_not_called()


def test_direct_duplex_benchmark_cannot_bypass_managed_adapter_admission():
    from core.benchmark.runner import _run_model
    with pytest.raises(admission.PlatformAdmissionError, match="MODEL_NOT_ADMITTED"):
        asyncio.run(_run_model({"model_type": "LLM", "provider_type": "doubao_duplex",
                               "test_mode": "realtime_audio", "config": {
                                   "_platform_managed": True, "conversation_mode": "realtime"}}, 1))
