import asyncio
import json
import stat
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import httpx
import openai
import pytest

from core.metering import begin_call, snapshot, usage_scope
from core.metering.context import bind_provider
from core.metering.openai_stream import metered_stream
from core.metering.outbox import UsageOutbox
from core.metering.recorder import configure
from core.metering.realtime import RealtimeMeter
from core.metering.service import deliver_once
from core.metering import tts_sessions


def runtime(context_id="context-before-switch", purpose="CONVERSATION"):
    return {
        "billing_context": {
            "context_id": context_id, "context_token": "local-test-capability",
            "workspace_id": "workspace-a", "agent_id": "agent-a", "device_id": "database-device-uuid",
            "purpose": purpose,
            "models": {
                "llm-a": {"provider": "deepseek", "service_type": "LLM"},
                "tts-a": {"provider": "volcengine", "service_type": "TTS"},
                "asr-a": {"provider": "volcengine", "service_type": "ASR"},
            },
        },
        "selected_module": {"LLM": "llm-a", "TTS": "tts-a", "ASR": "asr-a"},
    }


@pytest.fixture
def outbox(tmp_path):
    box = UsageOutbox(str(tmp_path / "usage.sqlite3"))
    configure(box)
    yield box
    configure(None)
    box.close()


def scoped_provider(config=None):
    return SimpleNamespace(_billing_scope=snapshot(config=config or runtime(), session_id="session-a"))


def test_frozen_ownership_survives_scene_switch_and_missing_usage_is_unknown(outbox):
    conn = SimpleNamespace(config=runtime(), session_id="original-session", device_id="untrusted-mac")
    provider = SimpleNamespace()
    bind_provider(provider, conn)
    call = begin_call(provider, "LLM")
    conn.config = runtime("new-scene")
    conn.session_id = "new-session"
    call.finish("CANCELLED")
    event, = outbox.pending()
    assert event["contextId"] == "context-before-switch"
    assert event["sessionId"] == "original-session"
    assert event["usage"] == [] and event["usageComplete"] is False
    assert "workspaceId" not in event and "deviceId" not in event


def test_crash_recovery_preserves_observed_usage_without_inventing_completion(tmp_path):
    path = str(tmp_path / "usage.sqlite3")
    box = UsageOutbox(path)
    configure(box)
    call = begin_call(scoped_provider(), "LLM")
    call.usage("prompt_tokens", 8)
    box.close()
    box = UsageOutbox(path)
    configure(box)
    try:
        box.recover()
        event, = box.pending()
        assert event["eventId"] == call.event["eventId"]
        assert event["status"] == "UNKNOWN" and event["usageComplete"] is False
        assert event["usage"][0]["quantity"] == "8"
        assert stat.S_IMODE(__import__("os").stat(path).st_mode) == 0o600
    finally:
        configure(None)
        box.close()


def test_delivery_retries_identical_ids_and_only_acknowledges_sent_events(outbox):
    call = begin_call(scoped_provider(), "LLM")
    call.finish("FAILED")
    sent = []

    async def send(events):
        sent.append(events)
        if len(sent) == 1:
            raise ConnectionError("response lost after insert")
        return {"accepted": [events[0]["eventId"], "not-sent"], "rejected": []}

    async def run():
        with pytest.raises(ConnectionError):
            await deliver_once(outbox, send)
        assert len(outbox.pending()) == 1
        assert await deliver_once(outbox, send)

    asyncio.run(run())
    assert sent[0] == sent[1]
    assert outbox.pending() == []


def test_permanent_rejection_is_retained_without_blocking_later_events(outbox):
    for _ in range(2):
        begin_call(scoped_provider(), "LLM").finish("SUCCESS")
    first, second = outbox.pending()

    async def send(events):
        return {"accepted": [second["eventId"]], "rejected": [
            {"eventId": first["eventId"], "reasonCode": "INVALID_CONTEXT"}]}

    assert asyncio.run(deliver_once(outbox, send))
    assert outbox.pending() == []
    assert outbox.rejected() == [(first["eventId"], "INVALID_CONTEXT")]


def test_no_ack_keeps_queue_and_active_calls_are_not_delivered(outbox):
    call = begin_call(scoped_provider(), "LLM")
    assert outbox.pending() == []
    call.finish("SUCCESS")

    async def send(events):
        return {"acceptedEventIds": [], "duplicateEventIds": []}

    assert asyncio.run(deliver_once(outbox, send)) is False
    assert len(outbox.pending()) == 1


def test_metrics_reject_non_numeric_negative_and_sensitive_strings(outbox):
    call = begin_call(scoped_provider(), "TTS")
    for value in ("private spoken text", float("nan"), float("inf"), -1, None):
        call.usage("billable_characters", value, "characters")
    call.usage("submitted_characters", 12, "characters", "OBSERVED")
    call.finish("SUCCESS")
    event, = outbox.pending()
    assert event["usage"] == [{"metric": "submitted_characters", "quantity": "12", "unit": "characters", "source": "OBSERVED"}]
    assert "private spoken text" not in json.dumps(event)
    assert event["usageComplete"] is False


class Stream:
    def __init__(self, chunks):
        self.chunks = iter(chunks)
        self.closed = False

    def __iter__(self): return self
    def __next__(self): return next(self.chunks)
    def close(self): self.closed = True


def provider_with_streams(streams, *, retries=0):
    streams = iter(streams)

    def create(**kwargs):
        stream = next(streams)
        if isinstance(stream, Exception):
            raise stream
        return stream

    client = SimpleNamespace(max_retries=retries, chat=SimpleNamespace(completions=SimpleNamespace(create=create)))
    client.with_options = lambda **kwargs: client
    provider = scoped_provider()
    provider.client = client
    provider._iter_stream_with_deadline = iter
    return provider


def chunk(request, prompt=None, completion=None):
    usage = None if prompt is None else {"prompt_tokens": prompt, "completion_tokens": completion, "total_tokens": prompt + completion}
    return SimpleNamespace(id=request, usage=usage)


def test_interleaved_calls_on_shared_provider_keep_their_own_usage(outbox):
    first, second = Stream([chunk("one"), chunk("one", 2, 3)]), Stream([chunk("two", 10, 20)])
    provider = provider_with_streams([first, second])
    a, b = metered_stream(provider, {}), metered_stream(provider, {})
    next(a)
    list(b)
    list(a)
    events = {event["providerRequestId"]: event for event in outbox.pending()}
    assert {item["metric"]: item["quantity"] for item in events["one"]["usage"]}["total_tokens"] == "5"
    assert {item["metric"]: item["quantity"] for item in events["two"]["usage"]}["total_tokens"] == "30"
    assert all(event["usageComplete"] for event in events.values())
    assert first.closed and second.closed


def test_generator_close_records_cancellation_before_final_usage(outbox):
    stream = Stream([chunk("interrupted"), chunk("interrupted", 4, 5)])
    response = metered_stream(provider_with_streams([stream]), {})
    next(response)
    response.close()
    event, = outbox.pending()
    assert event["status"] == "CANCELLED" and event["usage"] == []
    assert event["usageComplete"] is False and stream.closed


def test_real_retry_is_separate_attempt_of_same_logical_call(outbox, monkeypatch):
    from core.metering import openai_stream
    monkeypatch.setattr(openai_stream.time, "sleep", lambda delay: None)
    failure = openai.APIConnectionError(request=httpx.Request("POST", "https://example.invalid"))
    provider = provider_with_streams([failure, Stream([chunk("retry", 6, 2)])], retries=1)
    list(metered_stream(provider, {}))
    first, second = outbox.pending()
    assert first["logicalCallId"] == second["logicalCallId"]
    assert first["attemptId"] != second["attemptId"]
    assert first["status"] == "FAILED" and second["status"] == "SUCCESS"
    assert first["usage"] == []


def test_threaded_calls_use_distinct_frozen_contexts(outbox):
    def invoke(index):
        with usage_scope(snapshot(config=runtime(f"owner-{index}"), purpose="INTENT")):
            call = begin_call(SimpleNamespace(), "LLM")
            call.usage("prompt_tokens", index)
            call.finish("SUCCESS")
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(invoke, range(8)))
    events = outbox.pending()
    assert len({event["eventId"] for event in events}) == 8
    assert {event["contextId"] for event in events} == {f"owner-{index}" for index in range(8)}
    assert {event["purpose"] for event in events} == {"INTENT"}


def test_old_tts_session_usage_survives_new_playback_session(outbox):
    provider = scoped_provider()
    old = tts_sessions.start(provider, "old-sentence")
    new = tts_sessions.start(provider, "new-sentence")
    provider._active_session_id = "new-sentence"
    tts_sessions.received(provider, "old-sentence", b'{"usage":{"text_words":21}}', "SUCCESS")
    event, = outbox.pending()
    assert event["sentenceId"] == "old-sentence" and event["usageComplete"] is True
    assert event["usage"][0]["quantity"] == "21"
    assert old.finished and not new.finished
    tts_sessions.close(provider)


def test_realtime_audio_done_is_not_usage_terminal_and_late_usage_is_retained(outbox):
    conn = SimpleNamespace(config=runtime(), session_id="old-session")
    meter = RealtimeMeter(SimpleNamespace(), conn)
    meter.submitted({"type": "response.create"})
    meter.received({"type": "response.output_audio.done", "response_id": "response-old"})
    assert outbox.pending() == []
    conn.config = runtime("new-owner")
    meter.received({"type": "response.done", "response": {"id": "response-old", "usage": {"input_tokens": 7, "output_tokens": 3, "total_tokens": 10}}})
    event, = outbox.pending()
    assert event["contextId"] == "context-before-switch" and event["usageComplete"] is True
    assert event["serviceType"] == "REALTIME"
    meter.received({"type": "response.done", "response_id": "response-old"})
    assert len(outbox.pending()) == 1


def test_cancellation_ack_keeps_late_supplier_usage(outbox):
    meter = RealtimeMeter(SimpleNamespace(), SimpleNamespace(config=runtime(), session_id="session"))
    meter.received({"type": "response.created", "response": {"id": "cancelled-response"}})
    meter.received({"type": "response.canceled", "response_id": "cancelled-response"})
    assert outbox.pending() == []
    meter.received({"type": "response.done", "response": {"id": "cancelled-response", "usage": {
        "input_tokens": 20, "output_tokens": 2, "total_tokens": 22}}})
    event, = outbox.pending()
    assert event["status"] == "CANCELLED" and event["usageComplete"] is True


def test_slm_benchmark_uses_java_normalized_llm_context(outbox, monkeypatch):
    from core.benchmark import runner

    async def run_model(model, *args):
        begin_call(SimpleNamespace(), "LLM").finish("SUCCESS")
        return {"ok": True}

    monkeypatch.setattr(runner, "_run_model", run_model)
    asyncio.run(runner._run_metered_model({"id": "llm-a", "model_type": "SLM"},
                                        billing_context=runtime(purpose="BENCHMARK")["billing_context"]))
    event, = outbox.pending()
    assert event["modelId"] == "llm-a" and event["purpose"] == "BENCHMARK"


def test_duplex_benchmark_waits_for_usage_after_audio_terminal(outbox):
    from core.benchmark.doubao_duplex import run_duplex_samples

    class Transport:
        def __init__(self, config): self.event_task = None
        async def connect(self, handler): self.handler = handler
        async def configure(self, *args): return None
        async def send_audio(self, pcm):
            self._billing_meter.submitted({"type": "input_audio_buffer.append"})
            await self.handler({"type": "conversation.item.input_audio_transcription.completed", "transcript": "测试"})
            import base64
            await self.handler({"type": "response.output_audio.delta", "response_id": "probe-response",
                                "delta": base64.b64encode(b"\0" * 1000).decode()})
        async def mute(self, **kwargs):
            await self.handler({"type": "response.output_audio.done", "response_id": "probe-response"})
            async def later():
                await asyncio.sleep(0.01)
                await self.handler({"type": "response.done", "response_id": "probe-response", "usage": {
                    "input_tokens": 9, "output_tokens": 1, "total_tokens": 10}})
            self.event_task = asyncio.create_task(later())
        async def close(self):
            if self.event_task:
                self.event_task.cancel()
                await asyncio.gather(self.event_task, return_exceptions=True)

    with usage_scope(snapshot(config=runtime(purpose="MODEL_TEST"))):
        result = asyncio.run(run_duplex_samples({}, 1, transport_factory=Transport))
    event, = outbox.pending()
    assert result[0]["success"] is True
    assert event["purpose"] == "MODEL_TEST" and event["usageComplete"] is True
    assert event["usage"][0]["unit"] == "token"


def test_platform_context_purpose_cannot_become_normal_conversation(outbox):
    provider = scoped_provider(runtime(purpose="MODEL_TEST"))
    call = begin_call(provider, "LLM")
    call.finish("SUCCESS")
    event, = outbox.pending()
    assert event["purpose"] == "MODEL_TEST"


def test_missing_managed_context_does_not_inherit_other_tenant():
    from core.session.runtime_config import build_managed_runtime_config
    assert "billing_context" not in build_managed_runtime_config(runtime(), {})


def test_new_capability_does_not_force_pipeline_reload_but_owner_change_does():
    from core.session.runtime_config import runtime_config_fingerprint
    first, second = runtime(), runtime("new-context-id")
    second["billing_context"]["context_token"] = "new-capability"
    assert runtime_config_fingerprint(first) == runtime_config_fingerprint(second)
    second["billing_context"]["workspace_id"] = "different-owner"
    assert runtime_config_fingerprint(first) != runtime_config_fingerprint(second)


def test_known_context_fails_closed_if_storage_is_not_ready():
    configure(None)
    with pytest.raises(RuntimeError, match="outbox"):
        begin_call(scoped_provider(), "LLM")
