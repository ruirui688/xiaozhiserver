from types import SimpleNamespace
from unittest.mock import AsyncMock
import asyncio
import queue
import threading

import pytest

import config.settings

config.settings.config_file_valid = True

import core.session.runtime_config_reloader as reloader_module
from core.session.runtime_config import build_managed_runtime_config
from core.session.runtime_config_reloader import RuntimeConfigReloader
from core.session.scene_switch import SceneSwitchIsolation
from core.session.tts_session_options import SessionTTSOptions
from core.utils.dialogue import Dialogue, Message


def test_drain_queue_balances_unfinished_tasks():
    target = queue.Queue()
    target.put(b"one")
    target.put(b"two")

    RuntimeConfigReloader._drain_queue(target)

    assert target.empty()
    assert target.unfinished_tasks == 0


def _model(model_type, model_id, **typed_config):
    return {
        "model_type": model_type,
        "typed_config": {"type": typed_config.pop("type", "mock"), **typed_config},
    }


def test_build_managed_runtime_config_replaces_voice_prompt_and_plugins():
    common = {
        "selected_module": {"TTS": "tts-old", "LLM": "llm-old", "Intent": "intent"},
        "model_config": {
            "tts-old": _model("TTS", "tts-old", voice="old"),
            "llm-old": _model("LLM", "llm-old"),
            "intent": _model("Intent", "intent", type="function_call"),
        },
    }
    private = {
        "agent_id": "agent-a",
        "prompt": "新的智能体模板",
        "selected_module": {"TTS": "tts-new", "LLM": "llm-old", "Intent": "intent"},
        "model_config": {
            "tts-new": _model("TTS", "tts-new", voice="new"),
            "llm-old": _model("LLM", "llm-old"),
            "intent": _model("Intent", "intent", type="function_call"),
        },
        "plugins": {"play_music": "{\"enabled\": true}"},
        "correct_words": ["小治|小智"],
        "speech_routing": {"input": "device", "output": "server"},
    }

    result = build_managed_runtime_config(common, private)

    assert result["selected_module"]["TTS"] == "tts-new"
    assert result["TTS"]["tts-new"]["voice"] == "new"
    assert result["prompt"] == "新的智能体模板"
    assert result["plugins"]["play_music"]["enabled"] is True
    assert result["Intent"]["intent"]["functions"] == ["play_music"]
    assert result["TTS"]["tts-new"]["correct_words"] == ["小治|小智"]
    assert result["speech_routing"] == {"input": "device", "output": "server"}


def test_reloader_rebuilds_only_when_manager_config_changes(monkeypatch):
    common = {"delete_audio": True, "selected_module": {}, "model_config": {}}
    old_private = {
        "agent_id": "agent-a",
        "prompt": "old",
        "plugins": {},
        "speech_routing": {"input": "server", "output": "server"},
    }
    new_private = {
        "agent_id": "agent-a",
        "prompt": "new",
        "plugins": {},
        "speech_routing": {"input": "device", "output": "server"},
    }
    conn = SimpleNamespace(
        closing=False,
        closed=False,
        read_config_from_api=True,
        need_bind=False,
        client_is_speaking=False,
        headers={"device-id": "device-a", "client-id": "client-a"},
        device_id="device-a",
        common_config=common,
        config=build_managed_runtime_config(common, old_private),
        logger=SimpleNamespace(bind=lambda **kwargs: SimpleNamespace(info=lambda *args: None)),
    )
    reloader = RuntimeConfigReloader(conn)
    reloader.mark_applied(old_private)
    reloader._replace_pipeline = AsyncMock()
    monkeypatch.setattr(
        reloader_module,
        "get_private_config_from_api",
        AsyncMock(return_value=new_private),
    )

    async def run():
        changed = await reloader.refresh_if_due(force=True)
        reloader._replace_pipeline.assert_awaited_once()
        candidate = reloader._replace_pipeline.await_args.args[0]
        assert candidate["speech_routing"] == {
            "input": "device",
            "output": "server",
        }
        reloader._replace_pipeline.reset_mock()
        unchanged = await reloader.refresh_if_due(force=True)
        return changed, unchanged

    changed, unchanged = asyncio.run(run())
    assert changed is True
    assert unchanged is False
    reloader._replace_pipeline.assert_not_awaited()


def test_forced_refresh_activates_newly_bound_connection(monkeypatch):
    common = {"delete_audio": True, "selected_module": {}, "model_config": {}}
    private = {"agent_id": "agent-a", "prompt": "bound", "plugins": {}}
    conn = SimpleNamespace(
        closing=False,
        closed=False,
        read_config_from_api=True,
        need_bind=True,
        bind_code="123456",
        bind_completed_event=threading.Event(),
        binding_ready=threading.Event(),
        client_is_speaking=False,
        headers={"device-id": "device-a", "client-id": "client-a"},
        device_id="device-a",
        common_config=common,
        config=build_managed_runtime_config(common, {}),
        logger=SimpleNamespace(bind=lambda **kwargs: SimpleNamespace(info=lambda *args: None)),
    )
    reloader = RuntimeConfigReloader(conn)
    reloader.mark_applied({})
    reloader._replace_pipeline = AsyncMock()
    monkeypatch.setattr(
        reloader_module,
        "get_private_config_from_api",
        AsyncMock(return_value=private),
    )

    changed = asyncio.run(reloader.refresh_if_due(force=True, reason="device_activated"))

    assert changed is True
    assert conn.need_bind is False
    assert conn.bind_code is None
    assert conn.binding_ready.is_set()


def test_deactivate_closes_connection_when_old_pipeline_cleanup_fails():
    conn = SimpleNamespace(
        stop_event=threading.Event(),
        func_handler=SimpleNamespace(
            cleanup=AsyncMock(side_effect=RuntimeError("cleanup failed"))
        ),
        tts=None,
        asr=None,
        voiceprint_provider=None,
        vad=None,
        asr_priority_thread=None,
        close=AsyncMock(),
    )
    reloader = RuntimeConfigReloader(conn)

    async def run():
        with pytest.raises(RuntimeError, match="cleanup failed"):
            await reloader._deactivate_pipeline()

    asyncio.run(run())
    assert conn.stop_event.is_set()
    conn.close.assert_awaited_once()


def test_reloader_allows_realtime_pipeline_without_legacy_audio_modules(monkeypatch):
    config = {
        "selected_module": {"LLM": "rt"},
        "model_config": {
            "rt": {
                "model_type": "LLM",
                "typed_config": {
                    "type": "qwen_omni_realtime",
                    "conversation_mode": "realtime",
                },
            }
        },
    }
    monkeypatch.setattr(
        reloader_module,
        "initialize_modules",
        lambda *_args, **_kwargs: {"memory": object()},
    )
    conn = SimpleNamespace(logger=SimpleNamespace())
    modules = asyncio.run(RuntimeConfigReloader(conn)._build_modules(config))
    assert set(modules) == {"memory"}


def test_deactivate_closes_realtime_router():
    router = SimpleNamespace(close=AsyncMock())
    conn = SimpleNamespace(
        stop_event=threading.Event(),
        func_handler=None,
        conversation_router=router,
        tts=None,
        asr=None,
        voiceprint_provider=None,
        vad=None,
        asr_priority_thread=None,
        closing=False,
        closed=False,
        asr_audio_queue=None,
        reset_audio_states=lambda: None,
        tts_ready=threading.Event(),
        conversation_ready=threading.Event(),
        audio_input_ready=threading.Event(),
        _modules_ready=threading.Event(),
        logger=SimpleNamespace(
            bind=lambda **kwargs: SimpleNamespace(error=lambda *args: None)
        ),
    )
    asyncio.run(RuntimeConfigReloader(conn)._deactivate_pipeline())
    router.close.assert_awaited_once()


def test_activate_realtime_pipeline_publishes_readiness_after_router(monkeypatch):
    calls = []

    class Router:
        def __init__(self, conn):
            self.conn = conn

        async def initialize(self):
            calls.append("router_ready")
            return True

        async def close(self):
            pass

    monkeypatch.setattr(reloader_module, "ConversationRouter", Router)
    config = {
        "prompt": "test",
        "selected_module": {"LLM": "rt"},
        "model_config": {
            "rt": {
                "model_type": "LLM",
                "typed_config": {
                    "type": "qwen_omni_realtime",
                    "conversation_mode": "realtime",
                },
            }
        },
        "dialogue": {},
    }
    dialogue = SimpleNamespace(
        clear_voiceprint_metadata=lambda: None,
        clear_temporary_messages=lambda: None,
        max_history_turns=0,
    )
    conn = SimpleNamespace(
        config={},
        logger=SimpleNamespace(
            bind=lambda **_kwargs: SimpleNamespace(
                debug=lambda *_args: None,
                info=lambda *_args: None,
                warning=lambda *_args: None,
                error=lambda *_args: None,
            )
        ),
        func_handler=None,
        mcp_endpoint_client=None,
        prompt_manager=None,
        dialogue=dialogue,
        semantic_center=None,
        report_manager=None,
        correct_word_replacements={},
        tts_session_options=SimpleNamespace(),
        tts_ready=threading.Event(),
        conversation_ready=threading.Event(),
        audio_input_ready=threading.Event(),
        _modules_ready=threading.Event(),
        _initialize_memory=lambda: None,
        _initialize_intent=lambda: None,
        _init_prompt_enhancement=lambda: None,
        _inject_tool_call_fewshot=lambda: None,
        _init_voiceprint_enrollment_from_config=lambda: None,
    )
    asyncio.run(RuntimeConfigReloader(conn)._activate_pipeline(config, {}))
    assert calls == ["router_ready"]
    assert conn.tts is None and conn.asr is None and conn.llm is None
    assert conn.tts_ready.is_set()
    assert conn.conversation_ready.is_set()
    assert conn.audio_input_ready.is_set()


def test_scene_switch_isolation_resets_and_can_restore_session_state():
    dialogue = Dialogue()
    dialogue.put(Message(role="user", content="old scene secret"))
    options = SessionTTSOptions()
    options.restore({"voice": "old-voice"})
    conn = SimpleNamespace(
        session_id="old-session",
        dialogue=dialogue,
        game_session_manager=object(),
        meeting_minutes=object(),
        turn_context=object(),
        reply_lifecycle=object(),
        voiceprint_enrollment=object(),
        tts_session_options=options,
        current_speaker="Alice",
        current_speaker_id="speaker-a",
        last_tool_context={"tool": "old"},
        sentence_id="sentence-a",
        calling=True,
        _tts_protocol_sentence_id="sentence-a",
        _tts_protocol_start_sent=True,
        _tts_protocol_stop_sent=False,
    )

    snapshot = SceneSwitchIsolation.begin(conn)

    assert conn.session_id != "old-session"
    assert conn.dialogue.dialogue == []
    assert conn.current_speaker is None
    assert conn.last_tool_context is None
    assert conn.tts_session_options.snapshot() == {}

    SceneSwitchIsolation.rollback(conn, snapshot)
    assert conn.session_id == "old-session"
    assert conn.dialogue.dialogue[0].content == "old scene secret"
    assert conn.current_speaker == "Alice"
    assert conn.tts_session_options.snapshot() == {"voice": "old-voice"}
    assert conn._tts_protocol_sentence_id == "sentence-a"
    assert conn._tts_protocol_start_sent is True
    assert conn._tts_protocol_stop_sent is False
