import asyncio
import io
import json
import wave

from aiohttp import web

from config import settings

settings.config_file_valid = True

from core.utils.dialogue import Dialogue, Message
from core.handle.reportHandle import _memory_event_content
from core.providers.asr.base import ASRProviderBase
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
        "sample_seconds": 1.0,
        "result_wait_ms": 300,
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


async def _prefetches_only_first_second_once(monkeypatch):
    provider = _voiceprint_provider(monkeypatch)
    requests = []

    async def identify(audio_data, session_id):
        requests.append((audio_data, session_id))
        return VoiceprintMatch("voice-1", "用户A", 0.88)

    monkeypatch.setattr(provider, "identify_speaker", identify)

    provider.feed_audio_frame(b"1" * 16000, True, "pcm", "session-a")
    assert requests == []
    provider.feed_audio_frame(b"2" * 20000, True, "pcm", "session-a")
    provider.feed_audio_frame(b"3" * 32000, True, "pcm", "session-a")
    await asyncio.sleep(0)

    assert len(requests) == 1
    wav_data, session_id = requests[0]
    assert session_id == "session-a"
    with wave.open(io.BytesIO(wav_data), "rb") as wav_file:
        assert wav_file.getnchannels() == 1
        assert wav_file.getsampwidth() == 2
        assert wav_file.getframerate() == 16000
        assert wav_file.getnframes() == 16000
        assert wav_file.readframes(16000) == b"1" * 16000 + b"2" * 16000

    result = await provider.resolve_utterance_result()
    assert result == VoiceprintMatch("voice-1", "用户A", 0.88)
    await provider.close()


def test_voiceprint_prefetch_uses_only_first_second_and_calls_once(monkeypatch):
    asyncio.run(_prefetches_only_first_second_once(monkeypatch))


async def _short_utterance_skips_voiceprint(monkeypatch):
    provider = _voiceprint_provider(monkeypatch)
    calls = 0

    async def identify(_audio_data, _session_id):
        nonlocal calls
        calls += 1
        return "用户A"

    monkeypatch.setattr(provider, "identify_speaker", identify)
    provider.feed_audio_frame(b"1" * 31998, True, "pcm", "session-a")

    assert await provider.resolve_utterance_result() is None
    assert calls == 0
    await provider.close()


def test_voiceprint_short_utterance_is_skipped(monkeypatch):
    asyncio.run(_short_utterance_skips_voiceprint(monkeypatch))


async def _voiceprint_result_wait_timeout_is_bounded(monkeypatch):
    provider = _voiceprint_provider(monkeypatch, result_wait_ms=10)
    cancelled = asyncio.Event()

    async def identify(_audio_data, _session_id):
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.set()

    monkeypatch.setattr(provider, "identify_speaker", identify)
    provider.feed_audio_frame(b"1" * 32000, True, "pcm", "session-a")

    assert await provider.resolve_utterance_result() is None
    await asyncio.wait_for(cancelled.wait(), timeout=0.2)
    assert provider._prefetch_task is None
    await provider.close()


def test_voiceprint_result_wait_timeout_cancels_prefetch(monkeypatch):
    asyncio.run(_voiceprint_result_wait_timeout_is_bounded(monkeypatch))


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
    provider.feed_audio_frame(b"1" * 32000, True, "pcm", "session-a")
    assert provider._prefetch_task is None


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


def test_speaker_context_is_injected_only_for_current_recognized_speaker():
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
            }
        ]
    }

    first_turn = dialogue.get_llm_dialogue_with_memory(
        "记忆内容", voiceprint_config, "用户A"
    )
    first_system = "\n".join(
        message["content"] for message in first_turn if message["role"] == "system"
    )
    assert "当前说话人：用户A" in first_system
    assert "用户A：喜欢科技" in first_system

    later_turn = dialogue.get_llm_dialogue_with_memory(
        "记忆内容", voiceprint_config, None
    )
    later_system = "\n".join(
        message["content"] for message in later_turn if message["role"] == "system"
    )
    assert "<speakers_info>" not in later_system
