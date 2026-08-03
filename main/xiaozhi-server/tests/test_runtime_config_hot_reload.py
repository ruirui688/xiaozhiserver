from types import SimpleNamespace
from unittest.mock import AsyncMock
import asyncio
import threading

import pytest

import config.settings

config.settings.config_file_valid = True

import core.session.runtime_config_reloader as reloader_module
from core.session.runtime_config import build_managed_runtime_config
from core.session.runtime_config_reloader import RuntimeConfigReloader


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
    }

    result = build_managed_runtime_config(common, private)

    assert result["selected_module"]["TTS"] == "tts-new"
    assert result["TTS"]["tts-new"]["voice"] == "new"
    assert result["prompt"] == "新的智能体模板"
    assert result["plugins"]["play_music"]["enabled"] is True
    assert result["Intent"]["intent"]["functions"] == ["play_music"]
    assert result["TTS"]["tts-new"]["correct_words"] == ["小治|小智"]


def test_reloader_rebuilds_only_when_manager_config_changes(monkeypatch):
    common = {"delete_audio": True, "selected_module": {}, "model_config": {}}
    old_private = {"agent_id": "agent-a", "prompt": "old", "plugins": {}}
    new_private = {"agent_id": "agent-a", "prompt": "new", "plugins": {}}
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
        reloader._replace_pipeline.reset_mock()
        unchanged = await reloader.refresh_if_due(force=True)
        return changed, unchanged

    changed, unchanged = asyncio.run(run())
    assert changed is True
    assert unchanged is False
    reloader._replace_pipeline.assert_not_awaited()


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
