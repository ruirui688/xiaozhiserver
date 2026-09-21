import asyncio
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from config import settings
settings.config_file_valid = True

from core.connection import ConnectionHandler
from core.handle.receiveAudioHandle import startToChat
from core.session.runtime_readiness import RuntimeReadiness
from test_wake_events import runtime, detect


def prepare_fast(runtime, monkeypatch):
    readiness = runtime.runtime_readiness
    runtime.conversation_ready.clear()
    readiness.binding_ready.set()
    readiness.tts_ready.set()
    runtime.tts = Mock()
    runtime.prompt = "current role and mandatory system rules"
    monkeypatch.setattr(readiness, "schedule_flush", lambda: None)
    assert readiness.publish_wake_ready()
    return readiness


@pytest.mark.parametrize("asr_first", [False, True])
def test_fast_wake_pair_replies_once_before_context_ready(runtime, monkeypatch, asr_first):
    ready = prepare_fast(runtime, monkeypatch)

    async def run():
        if asr_first:
            assert await startToChat(runtime, "你好，小智。")
        await detect(runtime)
        if not asr_first:
            assert not await startToChat(runtime, "你好，小智。")

    asyncio.run(run())
    assert not ready.conversation_ready.is_set()
    assert runtime.executor.submit.call_count == 1
    assert runtime.reset_audio_calls == 0


def test_immediate_asr_instruction_waits_without_being_lost(runtime, monkeypatch):
    ready = prepare_fast(runtime, monkeypatch)

    async def run():
        await detect(runtime)
        task = asyncio.create_task(startToChat(runtime, "你好小智，打开摄像头"))
        await asyncio.sleep(0)
        assert not task.done()
        assert runtime.executor.submit.call_count == 1
        runtime.semantic_center.handle_user_text.assert_not_called()
        ready.conversation_ready.set()
        assert await asyncio.wait_for(task, 1)

    asyncio.run(run())
    assert runtime.executor.submit.call_count == 2
    assert "打开摄像头" in runtime.executor.submit.call_args.args[1]
    assert runtime.reset_audio_calls == 0


def test_deferred_queue_releases_only_wake_then_real_instruction(runtime, monkeypatch):
    import core.handle.textHandler.listenMessageHandler as listen
    ready = prepare_fast(runtime, monkeypatch)

    async def dispatch(conn, payload):
        import json
        await listen.ListenTextMessageHandler().handle(conn, json.loads(payload))

    monkeypatch.setattr("core.handle.textHandle.handleTextMessage", dispatch)
    for text in ("你好小智，打开摄像头", "你好小智"):
        ready.defer_listen_detect({"type": "listen", "state": "detect", "text": text})

    async def run():
        assert await ready.flush_deferred_listen_detects() == 1
        assert len(ready.deferred_listen_detects) == 1
        assert runtime.executor.submit.call_count == 1
        ready.conversation_ready.set()
        assert await ready.flush_deferred_listen_detects() == 1

    asyncio.run(run())
    assert runtime.executor.submit.call_count == 2


@pytest.mark.parametrize("flag", ["closing", "closed", "initialization_failed", "need_bind"])
def test_fast_gate_never_bypasses_terminal_or_binding_state(runtime, monkeypatch, flag):
    ready = prepare_fast(runtime, monkeypatch)
    setattr(runtime, flag, True)
    assert not ready.accepts("wake")
    assert not ready.publish_wake_ready()
    assert not asyncio.run(ready.wait_for_input("wake", timeout_seconds=0))
    runtime.executor.submit.assert_not_called()


def test_fast_gate_is_invalidated_by_role_replacement_and_failure(runtime, monkeypatch):
    ready = prepare_fast(runtime, monkeypatch)
    old = runtime.config
    runtime.config = dict(old, agent_id="different-role")
    assert not ready.accepts("wake")
    assert not ready.publish_wake_ready(old)
    ready.mark_initialization_failed(RuntimeError("test"))
    assert not ready.wake_ready.is_set()


def test_wait_timeout_has_no_reply_side_effects(runtime, monkeypatch):
    ready = prepare_fast(runtime, monkeypatch)
    assert not asyncio.run(ready.wait_for_input("text", timeout_seconds=0))
    runtime.executor.submit.assert_not_called()


def test_waiting_asr_abandons_old_role_after_config_changes(runtime, monkeypatch):
    ready = prepare_fast(runtime, monkeypatch)

    async def run():
        task = asyncio.create_task(startToChat(runtime, "打开摄像头"))
        await asyncio.sleep(0)
        runtime.config = dict(runtime.config, agent_id="new-role")
        ready.conversation_ready.set()
        assert not await asyncio.wait_for(task, 1)

    asyncio.run(run())
    runtime.executor.submit.assert_not_called()


def test_minimal_prompt_renders_current_role_without_fetching_context():
    manager = Mock()
    manager.build_enhanced_prompt.return_value = "role + mandatory system rules"
    conn = SimpleNamespace(
        config={"prompt": "authoritative current role"}, prompt_manager=manager,
        closing=False, closed=False, client_ip="", device_id="device-a",
        features={}, intent_type="nointent", change_system_prompt=Mock(), logger=Mock(),
    )
    ConnectionHandler._init_prompt_enhancement(conn, refresh_context=False)
    manager.update_context_info.assert_not_called()
    assert manager.build_enhanced_prompt.call_args.args[0] == "authoritative current role"
    conn.change_system_prompt.assert_called_once_with("role + mandatory system rules")


def test_wake_greeting_disabled_remains_silent_while_context_pending(runtime, monkeypatch):
    prepare_fast(runtime, monkeypatch)
    runtime.config["enable_greeting"] = False
    assert not asyncio.run(startToChat(runtime, "你好小智"))
    runtime.executor.submit.assert_not_called()
