import asyncio
import io
import json
import time
import wave
from types import SimpleNamespace

from config import settings

settings.config_file_valid = True

import core.utils.voiceprint_enrollment as enrollment_module
import core.utils.voiceprint_runtime as voiceprint_runtime_module
from core.api.voiceprint_enrollment_handler import VoiceprintEnrollmentHandler
from core.handle.receiveAudioHandle import startToChat
from core.providers.asr.base import ASRProviderBase
from core.providers.vad.silero import VADProvider
from core.utils.voiceprint_enrollment import VoiceprintEnrollmentCollector
from core.utils.voiceprint_runtime import refresh_voiceprint_provider


class _Logger:
    def bind(self, **kwargs):
        return self

    def debug(self, *args, **kwargs):
        pass

    def info(self, *args, **kwargs):
        pass

    def warning(self, *args, **kwargs):
        pass

    def error(self, *args, **kwargs):
        pass


def _conn(**overrides):
    values = {
        "device_id": "device-a",
        "session_id": "session-a",
        "audio_format": "pcm",
        "config": {"agent_id": "agent-a"},
        "logger": _Logger(),
        "voiceprint_provider": None,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


async def _collector_uploads_chat_history_sample(monkeypatch):
    calls = []

    async def fake_report(**kwargs):
        calls.append(kwargs)
        return {"id": "audio-1"}

    monkeypatch.setattr(enrollment_module, "report_chat_history", fake_report)
    collector = VoiceprintEnrollmentCollector()
    status = collector.start(
        {
            "requestId": "req-a",
            "agentId": "agent-a",
            "sourceName": "测试用户",
            "targetSamples": 1,
            "minDurationSeconds": 0.5,
        }
    )
    assert status["active"] is True

    pcm = b"\x01\x02" * 16000
    consumed = await collector.collect_utterance(_conn(), [pcm], "pcm")

    assert consumed is True
    assert collector.status.active is False
    assert collector.status.state == "completed"
    assert collector.status.sample_count == 1
    assert len(calls) == 1

    payload = json.loads(calls[0]["content"])
    assert payload["type"] == "voiceprint_enrollment_sample"
    assert payload["requestId"] == "req-a"
    assert payload["agentId"] == "agent-a"
    assert payload["sampleIndex"] == 1
    with wave.open(io.BytesIO(calls[0]["audio"])) as wav_file:
        assert wav_file.getframerate() == 16000
        assert wav_file.getnchannels() == 1
        assert wav_file.getsampwidth() == 2
        assert wav_file.getnframes() == 16000


def test_voiceprint_enrollment_uploads_chat_history_sample(monkeypatch):
    asyncio.run(_collector_uploads_chat_history_sample(monkeypatch))


async def _collector_rejects_short_sample(monkeypatch):
    calls = []

    async def fake_report(**kwargs):
        calls.append(kwargs)

    monkeypatch.setattr(enrollment_module, "report_chat_history", fake_report)
    collector = VoiceprintEnrollmentCollector()
    collector.start({"targetSamples": 1, "minDurationSeconds": 3})

    assert await collector.collect_utterance(_conn(), [b"\x01\x02" * 16000], "pcm")
    assert calls == []
    assert collector.status.active is True
    assert collector.status.sample_count == 0
    assert collector.status.samples[0].error == "sample_too_short"


def test_voiceprint_enrollment_rejects_short_sample(monkeypatch):
    asyncio.run(_collector_rejects_short_sample(monkeypatch))


def test_voiceprint_enrollment_requires_at_least_two_seconds_of_silence():
    collector = VoiceprintEnrollmentCollector()

    status = collector.start({"completionSilenceSeconds": 0.2})

    assert status["completionSilenceSeconds"] == 2.0


def test_silero_vad_uses_enrollment_specific_silence_threshold():
    provider = object.__new__(VADProvider)
    provider.silence_threshold_ms = 200
    collector = VoiceprintEnrollmentCollector()
    collector.start({"completionSilenceSeconds": 2.0})
    conn = _conn(voiceprint_enrollment=collector)

    assert provider._effective_silence_threshold_ms(conn) == 2000

    collector.stop()
    assert provider._effective_silence_threshold_ms(conn) == 200


async def _collector_rejects_sample_before_two_seconds_of_silence(monkeypatch):
    calls = []

    async def fake_report(**kwargs):
        calls.append(kwargs)

    monkeypatch.setattr(enrollment_module, "report_chat_history", fake_report)
    collector = VoiceprintEnrollmentCollector()
    collector.start({"targetSamples": 1, "minDurationSeconds": 0.5})
    conn = _conn(vad_last_voice_time=time.time() * 1000 - 1000)

    assert await collector.collect_utterance(conn, [b"\x01\x02" * 16000], "pcm")
    assert calls == []
    assert collector.status.active is True
    assert collector.status.sample_count == 0
    assert collector.status.samples[0].error == "completion_silence_too_short"


def test_collector_rejects_sample_before_two_seconds_of_silence(monkeypatch):
    asyncio.run(_collector_rejects_sample_before_two_seconds_of_silence(monkeypatch))


async def _collector_notifies_device_experience_events(monkeypatch):
    async def fake_report(**kwargs):
        return {"ok": True}

    notifications = []

    def notify(event, status=None, prompt=None):
        notifications.append((event, status["sampleCount"], prompt))

    monkeypatch.setattr(enrollment_module, "report_chat_history", fake_report)
    collector = VoiceprintEnrollmentCollector()
    collector.start({"targetSamples": 2, "minDurationSeconds": 0.1})
    conn = _conn(notify_voiceprint_enrollment_event=notify)
    pcm = b"\x01\x02" * 16000

    assert await collector.collect_utterance(conn, [pcm], "pcm")
    assert await collector.collect_utterance(conn, [pcm], "pcm")

    assert notifications[0][0] == "sample_collected"
    assert notifications[0][1] == 1
    assert "还需要 1 段" in notifications[0][2]
    assert notifications[1][0] == "completed"
    assert notifications[1][1] == 2


def test_voiceprint_enrollment_notifies_device_experience_events(monkeypatch):
    asyncio.run(_collector_notifies_device_experience_events(monkeypatch))


class _ASR(ASRProviderBase):
    def __init__(self):
        super().__init__()
        self.speech_to_text_calls = 0

    async def speech_to_text(self, opus_data, session_id, audio_format="opus", artifacts=None):
        self.speech_to_text_calls += 1
        return "should not run", None


async def _handle_voice_stop_bypasses_asr_when_enrolling(monkeypatch):
    collector = VoiceprintEnrollmentCollector()
    collector.start({"targetSamples": 1, "minDurationSeconds": 0.1})
    collected = []

    async def fake_collect(conn, audio_frames, audio_format):
        collected.append((audio_frames, audio_format))
        collector.status.active = False
        collector.status.state = "completed"
        return True

    monkeypatch.setattr(collector, "collect_utterance", fake_collect)
    conn = _conn(voiceprint_enrollment=collector)
    asr = _ASR()

    await asr.handle_voice_stop(conn, [b"pcm"],)

    assert asr.speech_to_text_calls == 0
    assert collected == [([b"pcm"], "pcm")]


def test_handle_voice_stop_bypasses_asr_when_enrolling(monkeypatch):
    asyncio.run(_handle_voice_stop_bypasses_asr_when_enrolling(monkeypatch))


async def _start_to_chat_ignores_text_when_enrolling():
    collector = VoiceprintEnrollmentCollector()
    collector.start({"targetSamples": 1, "minDurationSeconds": 0.1})
    conn = _conn(voiceprint_enrollment=collector)

    accepted = await startToChat(conn, "这句话不应该进入普通对话")

    assert accepted is False


def test_start_to_chat_ignores_text_when_enrolling():
    asyncio.run(_start_to_chat_ignores_text_when_enrolling())


def test_voiceprint_enrollment_handler_starts_runtime_connection(monkeypatch):
    conn = SimpleNamespace(
        start_voiceprint_enrollment=lambda body: {"active": True, "requestId": body["requestId"]}
    )
    monkeypatch.setattr(
        "core.api.voiceprint_enrollment_handler.get_connection",
        lambda device_id: conn if device_id == "device-a" else None,
    )
    handler = VoiceprintEnrollmentHandler(
        {
            "server": {"auth_key": "secret"},
            "manager-api": {"secret": "manager-secret"},
        }
    )

    class _Request:
        headers = {"Authorization": "Bearer secret"}

        async def json(self):
            return {"deviceId": "device-a", "requestId": "req-a"}

    response = asyncio.run(handler.handle_start(_Request()))
    assert response.status == 200
    payload = json.loads(response.text)
    assert payload["success"] is True
    assert payload["data"]["requestId"] == "req-a"


def test_voiceprint_enrollment_handler_finishes_runtime_connection(monkeypatch):
    calls = []

    async def finish_voiceprint_enrollment(reason):
        calls.append(reason)
        return {"active": False, "requestId": "req-a", "reason": reason}

    conn = SimpleNamespace(finish_voiceprint_enrollment=finish_voiceprint_enrollment)
    monkeypatch.setattr(
        "core.api.voiceprint_enrollment_handler.get_connection",
        lambda device_id: conn if device_id == "device-a" else None,
    )
    handler = VoiceprintEnrollmentHandler(
        {
            "server": {"auth_key": "secret"},
            "manager-api": {"secret": "manager-secret"},
        }
    )

    class _Request:
        headers = {"Authorization": "Bearer secret"}
        query = {}

        async def json(self):
            return {"deviceId": "device-a", "reason": "manual_stop"}

    response = asyncio.run(handler.handle_stop(_Request()))
    assert response.status == 200
    payload = json.loads(response.text)
    assert payload["success"] is True
    assert payload["data"]["reason"] == "manual_stop"
    assert calls == ["manual_stop"]


async def _refresh_replaces_online_voiceprint_provider(monkeypatch):
    class _Provider:
        def __init__(self, config):
            self.enabled = True
            self.speaker_map = {item["speaker_id"]: item for item in config["speakers"]}
            self.closed = False

        async def close(self):
            self.closed = True

    async def get_agent_models(device_id, client_id, selected_module):
        assert device_id == "device-a"
        assert client_id == "client-a"
        return {
            "voiceprint": {
                "url": "https://voiceprint.example.com/voiceprint",
                "token": "test-token",
                "user_id": "agent-a",
                "speakers": [{"speaker_id": "voice-new", "name": "用户A"}],
            }
        }

    monkeypatch.setattr(voiceprint_runtime_module, "get_agent_models", get_agent_models)
    monkeypatch.setattr(voiceprint_runtime_module, "VoiceprintProvider", _Provider)
    old_provider = _Provider({"speakers": [{"speaker_id": "voice-old", "name": "旧用户"}]})
    conn = _conn(
        read_config_from_api=True,
        closing=False,
        headers={"device-id": "device-a", "client-id": "client-a"},
        config={"agent_id": "agent-a", "selected_module": {}},
        voiceprint_provider=old_provider,
    )

    result = await refresh_voiceprint_provider(conn)

    assert result == {"enabled": True, "speakerCount": 1}
    assert conn.voiceprint_provider is not old_provider
    assert "voice-new" in conn.voiceprint_provider.speaker_map
    assert old_provider.closed is True


def test_refresh_replaces_online_voiceprint_provider(monkeypatch):
    asyncio.run(_refresh_replaces_online_voiceprint_provider(monkeypatch))


def test_voiceprint_enrollment_handler_refreshes_runtime_provider(monkeypatch):
    conn = SimpleNamespace()

    async def refresh(_conn):
        assert _conn is conn
        return {"enabled": True, "speakerCount": 1}

    monkeypatch.setattr(
        "core.api.voiceprint_enrollment_handler.get_connection",
        lambda device_id: conn if device_id == "device-a" else None,
    )
    monkeypatch.setattr(
        "core.api.voiceprint_enrollment_handler.refresh_voiceprint_provider",
        refresh,
    )
    handler = VoiceprintEnrollmentHandler(
        {
            "server": {"auth_key": "secret"},
            "manager-api": {"secret": "manager-secret"},
        }
    )

    class _Request:
        headers = {"Authorization": "Bearer secret"}

        async def json(self):
            return {"deviceId": "device-a"}

    response = asyncio.run(handler.handle_refresh(_Request()))
    payload = json.loads(response.text)

    assert response.status == 200
    assert payload["data"] == {"enabled": True, "speakerCount": 1}
