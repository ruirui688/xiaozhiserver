import asyncio
from types import SimpleNamespace

import pytest

from core.game import GameSessionManager
from core.semantic.semantic_center import SemanticCenter
from core.session import SessionTTSOptions, TTSOptionError, TurnContextBuffer
from core.providers.tools.server_plugins.plugin_executor import ServerPluginExecutor
from core.utils.dialogue import Dialogue, Message
from plugins_func.functions.session_controls import (
    reset_conversation_context,
    set_session_voice,
)
from plugins_func.register import Action


class AdjustableTTS:
    TTS_PARAM_CONFIG = [
        ("ttsRate", "speed", 0.5, 2.0, 1.0, lambda value: round(value, 2)),
        ("ttsVolume", "volume", 0, 100, 50, int),
    ]

    def __init__(self):
        self.voice = "voice-a"
        self.speed = 1.0
        self.volume = 50


class FixedTTS:
    pass


def _voice_conn(tts=None, catalog=None):
    return SimpleNamespace(
        tts=tts or AdjustableTTS(),
        tts_session_options=SessionTTSOptions(),
        config={
            "selected_module": {"TTS": "TTS_demo"},
            "tts_voice_catalog": catalog or [],
        },
    )


def test_session_tts_options_apply_only_to_current_connection():
    first = _voice_conn()
    second = _voice_conn()

    result = set_session_voice(first, voice="voice-b", speed=50, volume=-50)

    assert result.action == Action.RESPONSE
    assert first.tts.voice == "voice-b"
    assert first.tts.speed == 1.5
    assert first.tts.volume == 25
    assert second.tts.voice == "voice-a"
    assert second.tts.speed == 1.0
    assert first.config["selected_module"]["TTS"] == "TTS_demo"


def test_session_control_tools_are_default_and_voice_schema_uses_catalog():
    conn = _voice_conn(catalog=[{"id": "voice-a", "name": "音色 A"}])
    conn.config.update({"Intent": {}, "plugins": {}})
    conn.intent_type = "function_call"

    tools = ServerPluginExecutor(conn).get_tools()

    assert "set_session_voice" in tools
    assert "reset_conversation_context" in tools
    schema = tools["set_session_voice"].description["function"]["parameters"]
    assert schema["properties"]["voice"]["enum"] == ["voice-a"]
    assert "pitch" not in schema["properties"]


def test_session_tts_options_validate_range_and_provider_capabilities_atomically():
    options = SessionTTSOptions()
    tts = AdjustableTTS()

    with pytest.raises(TTSOptionError, match="-100 到 100"):
        options.apply(tts, speed=101)
    with pytest.raises(TTSOptionError, match="不支持调整pitch"):
        options.apply(tts, speed=50, pitch=20)

    assert tts.speed == 1.0
    assert options.snapshot() == {}


def test_session_voice_rejects_unknown_catalog_entry():
    conn = _voice_conn(catalog=[{"id": "voice-a", "name": "音色 A"}])

    result = set_session_voice(conn, voice="voice-not-available")

    assert result.action == Action.RESPONSE
    assert "不在当前语音服务" in result.response
    assert conn.tts.voice == "voice-a"


def test_realtime_session_voice_routes_to_active_adapter():
    requested = []

    class Router:
        is_realtime = True
        capabilities = SimpleNamespace(
            session_voice=True,
            native_function_calling=True,
            custom_voice_ids=False,
        )

        def voice_catalog(self):
            return [{"id": "Serena", "name": "Serena"}]

        def request_voice_change(self, voice):
            requested.append(voice)
            return True

    conn = _voice_conn()
    conn.conversation_router = Router()

    result = set_session_voice(conn, voice="Serena")

    assert result.action == Action.RESPONSE
    assert requested == ["Serena"]
    assert conn.tts.voice == "voice-a"


def test_realtime_session_voice_rejects_unsupported_parameters():
    conn = _voice_conn()
    conn.conversation_router = SimpleNamespace(
        is_realtime=True,
        capabilities=SimpleNamespace(
            session_voice=True,
            native_function_calling=True,
            custom_voice_ids=False,
        ),
    )

    result = set_session_voice(conn, voice="Serena", speed=10)

    assert "只支持切换音色" in result.response


def test_session_tts_options_reapply_only_for_same_model():
    options = SessionTTSOptions()
    first = AdjustableTTS()
    options.apply(first, provider_identity="TTS_a", speed=50)

    replacement = AdjustableTTS()
    options.reapply(replacement, provider_identity="TTS_a")
    assert replacement.speed == 1.5

    other_model = AdjustableTTS()
    options.reapply(other_model, provider_identity="TTS_b")
    assert other_model.speed == 1.0
    assert options.snapshot() == {}


def test_fixed_tts_rejects_unsupported_voice_and_parameters():
    options = SessionTTSOptions()
    with pytest.raises(TTSOptionError, match="不支持运行时切换音色"):
        options.apply(FixedTTS(), voice="voice-a")
    with pytest.raises(TTSOptionError, match="不支持调整speed"):
        options.apply(FixedTTS(), speed=10)


def test_huoshan_session_percentages_update_next_upstream_session():
    from core.providers.tts.huoshan_double_stream import TTSProvider

    provider = object.__new__(TTSProvider)
    provider.voice = "voice-a"
    provider.audio_params = {"speech_rate": 0, "loudness_rate": 0}
    provider.additions = {"post_process": {"pitch": 0}}

    SessionTTSOptions().apply(provider, speed=100, volume=-100, pitch=50)

    assert provider.audio_params == {"speech_rate": 100, "loudness_rate": -50}
    assert provider.additions["post_process"]["pitch"] == 6


def test_siliconflow_request_uses_session_speed_and_gain(monkeypatch):
    from core.providers.tts.siliconflow import TTSProvider

    captured = {}

    def fake_request(method, url, json, headers):
        captured.update(json)
        return SimpleNamespace(content=b"audio")

    monkeypatch.setattr("core.providers.tts.siliconflow.requests.request", fake_request)
    provider = TTSProvider(
        {
            "model": "demo",
            "access_token": "token",
            "voice": "voice-a",
            "response_format": "mp3",
        },
        True,
    )
    SessionTTSOptions().apply(provider, speed=50, volume=-50)

    assert asyncio.run(provider.text_to_speak("测试", None)) == b"audio"
    assert captured["speed"] == 2.5
    assert captured["gain"] == -5


def _reset_conn():
    dialogue = Dialogue()
    dialogue.put(Message(role="system", content="system prompt"))
    dialogue.put(Message(role="user", content="old user"))
    dialogue.put(Message(role="assistant", content="old assistant"))
    dialogue.put(Message(role="tool", content="old tool", tool_call_id="tool-1"))
    game = GameSessionManager()
    game.sessions["demo"] = {"active": True}
    semantic = SemanticCenter()
    semantic.state_manager.transition("game", "demo")
    semantic.state_manager.record_intent("game.start")
    turn_context = TurnContextBuffer()
    turn_context.replace({"screen": "old"})
    conn = SimpleNamespace(
        dialogue=dialogue,
        last_tool_context="music",
        current_speaker="用户甲",
        current_speaker_id="speaker-1",
        turn_context=turn_context,
        game_session_manager=game,
        semantic_center=semantic,
        meeting_minutes=SimpleNamespace(is_active=lambda: False),
        voiceprint_enrollment=SimpleNamespace(status=SimpleNamespace(active=False)),
        calling=False,
    )
    conn._inject_tool_call_fewshot = lambda: conn.dialogue.put(
        Message(role="user", content="fewshot", is_temporary=True)
    )
    return conn


def test_reset_conversation_context_preserves_system_and_resets_soft_state():
    conn = _reset_conn()

    result = reset_conversation_context(conn)

    assert result.action == Action.RESPONSE
    assert [(message.role, message.content) for message in conn.dialogue.dialogue] == [
        ("system", "system prompt"),
        ("user", "fewshot"),
    ]
    assert conn.last_tool_context is None
    assert conn.current_speaker is None
    assert conn.current_speaker_id is None
    assert conn.turn_context.take() is None
    assert not conn.game_session_manager.has_active_game()
    state = conn.semantic_center.state_manager
    assert state.state == "idle"
    assert state.current_skill is None
    assert state.last_intent is None


def test_reset_conversation_context_requests_realtime_upstream_rebuild():
    conn = _reset_conn()
    requested = []
    conn.conversation_router = SimpleNamespace(
        is_realtime=True,
        capabilities=SimpleNamespace(context_reset=True),
        request_context_reset=lambda: requested.append("reset") or True,
    )

    result = reset_conversation_context(conn)

    assert result.action == Action.RESPONSE
    assert requested == ["reset"]
    assert not any(message.content == "old user" for message in conn.dialogue.dialogue)


@pytest.mark.parametrize("protected", ["meeting", "voiceprint", "calling"])
def test_reset_conversation_context_rejects_protected_modes(protected):
    conn = _reset_conn()
    if protected == "meeting":
        conn.meeting_minutes = SimpleNamespace(is_active=lambda: True)
    elif protected == "voiceprint":
        conn.voiceprint_enrollment.status.active = True
    else:
        conn.calling = True

    result = reset_conversation_context(conn)

    assert result.action == Action.RESPONSE
    assert "暂时不能清空上下文" in result.response
    assert any(message.content == "old user" for message in conn.dialogue.dialogue)
