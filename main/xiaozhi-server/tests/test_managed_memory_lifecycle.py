"""Cross-component memory checkpoints, attribution and prompt selection."""

import asyncio
import json
import time
from types import SimpleNamespace

import pytest
import config.settings

config.settings.config_file_valid = True

from core.providers.memory.managed_memory import managed_memory as provider_module
from core.session.managed_memory_session import bind_managed_memory_session, flush_before_scene_switch
from core.session.conversation_context import reset_conversation_context
from core.session.managed_memory_snapshot import ManagedMemorySnapshot
from core.utils.dialogue import Dialogue, Message
from core.utils.prompt_budget import render_memory_context


def memory_conn():
    log = SimpleNamespace(info=lambda *a, **k: None, warning=lambda *a, **k: None,
                          debug=lambda *a, **k: None, error=lambda *a, **k: None)
    conn = SimpleNamespace(
        config={"agent_id": "agent-old"}, session_id="session-original", device_id="device-a",
        dialogue=Dialogue(), logger=SimpleNamespace(bind=lambda **kw: log), loop=None,
        current_speaker_id="speaker-a", current_speaker="A", _inject_tool_call_fewshot=lambda: None,
    )
    provider = provider_module.MemoryProvider({})
    provider.init_memory("device-a", None, agent_id="agent-old")
    conn.memory = provider
    bind_managed_memory_session(conn, provider)
    return conn


def test_long_session_checkpoints_survive_pruning_reset_and_identity_scrub(monkeypatch):
    submitted = []

    async def process(device, agent, session, messages, segment_id=None):
        submitted.append((device, agent, session, segment_id, messages))
        return 1

    monkeypatch.setattr(provider_module, "process_managed_memory_session", process)

    async def run():
        conn = memory_conn()
        for i in range(260):
            conn.dialogue.put(Message("user", json.dumps({"speaker_id": "speaker-a", "content": f"fact-{i}"})))
            if i % 20 == 19:
                await conn.managed_memory_session.flush()
        assert len(conn.dialogue.dialogue) == 200
        # The final segment must retain identity even after prompt scrubbing.
        conn.dialogue.put(Message("user", json.dumps({"speaker_id": "speaker-a", "content": "correction"})))
        conn.dialogue.clear_voiceprint_metadata()
        reset_conversation_context(conn)
        assert not conn.dialogue.dialogue
        await conn.memory.save_memory([], conn.session_id)

    asyncio.run(run())
    messages = [m for *_, batch in submitted for m in batch]
    assert len(messages) == 261
    assert all(m["speakerId"] == "speaker-a" for m in messages)
    assert "fact-0" in messages[0]["content"]
    assert "correction" in messages[-1]["content"]
    assert len({call[3] for call in submitted}) == len(submitted)


def test_fixed_skill_meeting_and_device_background_never_enter_memory(monkeypatch):
    captured = []

    async def process(*args, **kwargs):
        captured.extend(args[3])
        return 0

    monkeypatch.setattr(provider_module, "process_managed_memory_session", process)
    conn = memory_conn()
    conn.dialogue.put(Message("user", "fixed trigger", memory_eligible=False))
    conn.dialogue.put(Message("assistant", "fixed response", memory_eligible=False))
    conn.meeting_minutes = SimpleNamespace(is_active=lambda: True)
    conn.dialogue.put(Message("user", "private meeting"))
    conn.meeting_minutes = SimpleNamespace(is_active=lambda: False)
    conn.dialogue.put(Message("assistant", "meeting result"))
    conn.dialogue.put(Message("user", "real fact", turn_context={"private": "device background"}))
    asyncio.run(conn.managed_memory_session.flush())
    assert [m["content"] for m in captured] == ["real fact"]


def test_failed_checkpoint_retries_exact_segment_and_preserves_later_correction(monkeypatch):
    calls = []

    async def process(device, agent, session, messages, segment_id=None):
        calls.append((agent, session, segment_id, messages))
        if len(calls) == 1:
            raise TimeoutError()
        return 1

    monkeypatch.setattr(provider_module, "process_managed_memory_session", process)
    conn = memory_conn()
    conn.dialogue.put(Message("user", "old preference"))
    with pytest.raises(TimeoutError):
        asyncio.run(flush_before_scene_switch(conn))
    conn.dialogue.put(Message("user", "corrected preference"))
    # Changing the mutable connection config cannot retarget an old segment.
    conn.config["agent_id"] = "agent-new"
    conn.memory.agent_id = "agent-new"
    asyncio.run(flush_before_scene_switch(conn))
    assert calls[0] == calls[1]
    assert calls[2][0] == "agent-old"
    assert calls[2][2] != calls[1][2]
    assert calls[2][3][0]["content"] == "corrected preference"


def test_private_fact_reserves_budget_and_backslashes_cannot_break_prompt():
    conn = memory_conn()
    snapshot = ManagedMemorySnapshot(conn)
    now = int(time.time() * 1000)
    assert snapshot.install({
        "schema_version": 1, "status": "ready", "agent_id": "agent-old", "device_id": "device-a",
        "revision": "a" * 64, "issued_at_ms": now, "expires_at_ms": now + 30000,
        "shared_context": "\n".join(f"- shared-{i}" for i in range(30)),
        "speaker_contexts": {"speaker-a": r"- private C:\users\A <tag>"},
        "context_max_chars": 65, "context_max_items": 3,
    }, 0)
    context = snapshot.read()
    conn.dialogue.update_system_message("role<context><memory></memory></context>")
    messages = conn.dialogue.get_llm_dialogue_with_memory(context)
    rendered = "\n".join(m["content"] for m in messages)
    assert r"private C:\users\A &lt;tag&gt;" in rendered
    assert "<tag>" not in rendered
    assert len(render_memory_context(context)) <= 65
    assert str(context).splitlines()[0].startswith("- private")
    assert len(str(context).splitlines()) <= 3


def test_session_api_propagates_failure_instead_of_claiming_zero_changes(monkeypatch):
    from config.manage_api_client import ManageApiClient, process_managed_memory_session

    async def fail(*args, **kwargs):
        raise TimeoutError()

    monkeypatch.setattr(ManageApiClient, "_instance", SimpleNamespace(_execute_async_request=fail))
    with pytest.raises(TimeoutError):
        asyncio.run(process_managed_memory_session("device", "agent", "session", [{"role": "user", "content": "fact"}]))


@pytest.mark.parametrize("status", ["PENDING", "FAILED"])
def test_durable_receipt_advances_checkpoint_even_before_model_success(monkeypatch, status):
    from config.manage_api_client import ManageApiClient
    calls = []

    async def receive(method, endpoint, **kwargs):
        calls.append((endpoint, kwargs["json"]))
        return {"accepted": True, "taskId": "task-1", "status": status, "factsChanged": 0}

    monkeypatch.setattr(ManageApiClient, "_instance", SimpleNamespace(_execute_async_request=receive))
    conn = memory_conn()
    conn.dialogue.put(Message("user", "first fact"))
    asyncio.run(conn.managed_memory_session.flush())
    conn.dialogue.put(Message("user", "later correction"))
    asyncio.run(conn.managed_memory_session.flush())
    assert [call[1]["segmentId"] for call in calls] == ["0", "1"]
    assert not conn.managed_memory_session._messages


@pytest.mark.parametrize("receipt", [
    {"accepted": False, "taskId": "t", "status": "PENDING", "factsChanged": 0},
    {"accepted": True, "taskId": "", "status": "PENDING", "factsChanged": 0},
    {"accepted": True, "taskId": "t", "status": "UNKNOWN", "factsChanged": 0},
    {"accepted": True, "taskId": "t", "status": "PENDING", "factsChanged": False},
    0,
])
def test_missing_or_invalid_durable_receipt_never_discards_evidence(monkeypatch, receipt):
    from config.manage_api_client import ManageApiClient

    async def receive(*args, **kwargs):
        return receipt

    monkeypatch.setattr(ManageApiClient, "_instance", SimpleNamespace(_execute_async_request=receive))
    conn = memory_conn()
    conn.dialogue.put(Message("user", "unacknowledged fact"))
    with pytest.raises(RuntimeError):
        asyncio.run(conn.managed_memory_session.flush())
    assert conn.managed_memory_session._segment == 0
    assert conn.managed_memory_session._messages[0].content == "unacknowledged fact"


@pytest.mark.parametrize("next_speaker", ["speaker-b", None])
def test_speaker_change_removes_private_assistant_and_tool_history_but_keeps_evidence(next_speaker):
    conn = memory_conn()
    conn.dialogue.put(Message("system", "role<context><memory></memory></context>"))
    conn.dialogue.put(Message("user", json.dumps({"speaker_id": "speaker-a", "content": "my secret"})))
    conn.dialogue.put(Message("assistant", "private recalled answer"))
    conn.dialogue.put(Message("tool", "private tool result"))
    content = json.dumps({"speaker_id": next_speaker, "content": "hello"}) if next_speaker else "hello"
    conn.dialogue.put(Message("user", content))
    prompt = json.dumps(conn.dialogue.get_llm_dialogue_with_memory("shared", current_speaker_id=next_speaker))
    assert "my secret" not in prompt
    assert "private recalled answer" not in prompt
    assert "private tool result" not in prompt
    assert "hello" in prompt
    evidence = conn.managed_memory_session._messages
    assert evidence[0].speaker_id == "speaker-a"
    assert "my secret" in evidence[0].content


def test_internal_control_does_not_impersonate_an_unknown_speaker_or_become_evidence():
    conn = memory_conn()
    conn.dialogue.put(Message("user", json.dumps({"speaker_id": "speaker-a", "content": "question"})))
    conn.dialogue.put(Message("user", "finish using available results", is_internal=True))
    assert len(conn.dialogue.dialogue) == 2
    assert len(conn.managed_memory_session._messages) == 1


def test_chat_report_persists_eligibility_and_identity_for_history_rebuild(monkeypatch):
    from core.handle import reportHandle
    calls, events = [], []

    async def capture(**kwargs):
        calls.append(kwargs)

    async def event(**kwargs):
        events.append(kwargs)

    monkeypatch.setattr(reportHandle, "manage_report", capture)
    monkeypatch.setattr(reportHandle, "report_memory_event", event)
    conn = memory_conn()
    text = json.dumps({"content": "excluded skill", "speaker_id": "speaker-a"})
    asyncio.run(reportHandle.report(conn, 1, text, None, 1000, skip_memory_event=True))
    assert calls[0]["metrics"] == {"memory_eligible": False, "speaker_id": "speaker-a"}
    assert not events
