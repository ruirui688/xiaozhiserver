import asyncio
import io
import json
import wave
from types import SimpleNamespace

from aiohttp import web

from config import settings

settings.config_file_valid = True

from core.utils.dialogue import Dialogue, Message
from core.handle.reportHandle import _memory_event_content
from core.providers.asr.base import ASRProviderBase
from core.utils.turn_metrics import TurnMetrics
from core.utils.voiceprint_provider import VoiceprintMatch, VoiceprintProvider


async def _identify_across_agent_and_legacy_scopes(monkeypatch):
    calls = []

    async def identify(request):
        form = await request.post()
        user_id = form["user_id"]
        calls.append(
            {
                "authorization": request.headers.get("Authorization"),
                "user_id": user_id,
                "speaker_ids": form["speaker_ids"],
            }
        )
        if user_id == "agent-a":
            return web.json_response({"speaker_id": "voice-new", "score": 0.91})
        return web.json_response({"speaker_id": "voice-old", "score": 0.62})

    app = web.Application()
    app.router.add_post("/voiceprint/identify", identify)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    port = site._server.sockets[0].getsockname()[1]

    monkeypatch.setattr(
        VoiceprintProvider,
        "_is_public_target",
        staticmethod(lambda _url: True),
    )
    provider = VoiceprintProvider(
        {
            "url": f"http://127.0.0.1:{port}",
            "token": "secret-token",
            "user_id": "agent-a",
            "similarity_threshold": 0.4,
            "speakers": [
                {
                    "speaker_id": "voice-new",
                    "name": "新用户",
                    "description": "新作用域",
                    "user_id": "agent-a",
                },
                {
                    "speaker_id": "voice-old",
                    "name": "旧用户",
                    "description": "历史作用域",
                    "user_id": "default",
                },
            ],
        }
    )

    try:
        result = await provider.identify_speaker(b"RIFF" + b"0" * 2048, "session-a")
    finally:
        await provider.close()
        await runner.cleanup()

    assert result is not None
    assert result.speaker_id == "voice-new"
    assert result.name == "新用户"
    assert result.score == 0.91
    assert {call["user_id"] for call in calls} == {"agent-a", "default"}
    assert all(call["authorization"] == "Bearer secret-token" for call in calls)


def test_voiceprint_provider_sends_user_scope_and_supports_legacy_records(monkeypatch):
    asyncio.run(_identify_across_agent_and_legacy_scopes(monkeypatch))


def test_voiceprint_provider_supports_legacy_key_url(monkeypatch):
    monkeypatch.setattr(
        VoiceprintProvider,
        "_is_public_target",
        staticmethod(lambda _url: True),
    )
    provider = VoiceprintProvider(
        {
            "url": "https://voiceprint.example.com:8005/voiceprint/health?key=legacy-token",
            "user_id": "agent-a",
            "speakers": ["voice-1,用户A,示例"],
        }
    )

    assert provider.enabled is True
    assert provider.api_key == "legacy-token"
    assert provider.api_url == "https://voiceprint.example.com:8005/voiceprint/identify"


def _voiceprint_provider(monkeypatch, **overrides):
    monkeypatch.setattr(
        VoiceprintProvider,
        "_is_public_target",
        staticmethod(lambda _url: True),
    )
    config = {
        "url": "https://voiceprint.example.com:8005/voiceprint",
        "token": "secret-token",
        "user_id": "agent-a",
        "speakers": [
            {
                "speaker_id": "voice-1",
                "name": "用户A",
                "user_id": "agent-a",
            }
        ],
    }
    config.update(overrides)
    return VoiceprintProvider(config)


async def _handle_voice_stop_identifies_with_full_utterance(monkeypatch):
    class DummyASR(ASRProviderBase):
        async def speech_to_text(self, *args, **kwargs):
            return "你好", None

        async def speech_to_text_wrapper(self, *args, **kwargs):
            return "你好", None

    class DummyVoiceprint:
        def __init__(self):
            self.requests = []

        async def identify_speaker(self, audio_data, session_id):
            self.requests.append((audio_data, session_id))
            return VoiceprintMatch("voice-1", "用户A", 0.88)

        def reset_utterance(self):
            return None

    async def fake_start_to_chat(_conn, _text):
        return False

    monkeypatch.setattr("core.providers.asr.base.startToChat", fake_start_to_chat)

    voiceprint = DummyVoiceprint()
    conn = SimpleNamespace(
        session_id="session-a",
        audio_format="pcm",
        voiceprint_provider=voiceprint,
        voiceprint_enrollment=None,
        turn_metrics=TurnMetrics(),
    )
    audio_frames = [b"1" * 16000, b"2" * 16000, b"3" * 8000]

    await DummyASR().handle_voice_stop(conn, audio_frames)

    assert len(voiceprint.requests) == 1
    wav_data, session_id = voiceprint.requests[0]
    assert session_id == "session-a"
    with wave.open(io.BytesIO(wav_data), "rb") as wav_file:
        assert wav_file.getnchannels() == 1
        assert wav_file.getsampwidth() == 2
        assert wav_file.getframerate() == 16000
        assert wav_file.readframes(wav_file.getnframes()) == b"".join(audio_frames)

    voiceprint_metrics = conn.turn_metrics.build_user_metrics()["stages"]["voiceprint"]
    assert voiceprint_metrics["enabled"] is True
    assert voiceprint_metrics["speaker_id"] == "voice-1"
    assert voiceprint_metrics["total_ms"] is not None


def test_voiceprint_identifies_with_full_utterance_at_voice_stop(monkeypatch):
    asyncio.run(_handle_voice_stop_identifies_with_full_utterance(monkeypatch))


async def _http_session_is_reused_and_closed(monkeypatch):
    provider = _voiceprint_provider(monkeypatch)
    first = await provider._get_http_session()
    second = await provider._get_http_session()

    assert first is second
    assert first.closed is False
    await provider.close()
    assert first.closed is True
    assert provider._http_session is None


def test_voiceprint_http_session_is_reused_and_closed(monkeypatch):
    asyncio.run(_http_session_is_reused_and_closed(monkeypatch))


def test_voiceprint_without_bound_speakers_never_enables(monkeypatch):
    provider = _voiceprint_provider(monkeypatch, speakers=[])

    assert provider.enabled is False


def test_voiceprint_identity_is_preserved_for_chat_history_and_memory_event():
    enhanced = ASRProviderBase._build_enhanced_text(
        None, "我喜欢骑车", "用户A", "voice-1", 0.91234
    )

    payload = json.loads(enhanced)
    assert payload == {
        "speaker": "用户A",
        "content": "我喜欢骑车",
        "speaker_id": "voice-1",
        "speaker_score": 0.9123,
    }
    memory_text, memory_json, speaker_id = _memory_event_content(1, enhanced)
    assert memory_text == "我喜欢骑车"
    assert memory_json == payload
    assert speaker_id == "voice-1"


def test_speaker_context_marks_current_recognized_speaker_as_current_turn_user():
    dialogue = Dialogue()
    dialogue.put(
        Message(
            role="system",
            content="静态规则\n<context>当前时间：{{current_time}}</context>\n<memory></memory>",
        )
    )
    voiceprint_config = {
        "speakers": [
            {
                "speaker_id": "voice-1",
                "name": "用户A",
                "description": "喜欢科技",
                "user_id": "agent-a",
            },
            {
                "speaker_id": "voice-2",
                "name": "用户A",
                "description": "喜欢音乐",
                "user_id": "agent-a",
            },
        ]
    }

    first_turn = dialogue.get_llm_dialogue_with_memory(
        "记忆内容", voiceprint_config, "用户A", "voice-1"
    )
    first_system = "\n".join(
        message["content"] for message in first_turn if message["role"] == "system"
    )
    assert "当前说话人：用户A" in first_system
    assert "刚刚说话的人、正在和你说话的人都指向上述说话人" in first_system
    assert "用户A：喜欢科技" in first_system
    assert "用户A：喜欢音乐" not in first_system
    assert "不要因为每轮收到身份信息就机械地称呼姓名" in first_system

    later_turn = dialogue.get_llm_dialogue_with_memory(
        "记忆内容", voiceprint_config, "用户A", "voice-1"
    )
    later_system = "\n".join(
        message["content"] for message in later_turn if message["role"] == "system"
    )
    assert "当前说话人：用户A" in later_system
    assert "不要把历史对话、记忆或描述中的其他人名当成当前说话人" in later_system
    assert "不要主动提及声纹识别过程、置信度或标签" in later_system
