import asyncio
import io
import json
import wave
from types import SimpleNamespace

from config import settings

settings.config_file_valid = True

import core.utils.voiceprint_enrollment as enrollment_module
from core.api.voiceprint_enrollment_handler import VoiceprintEnrollmentHandler
from core.providers.asr.base import ASRProviderBase
from core.utils.voiceprint_enrollment import VoiceprintEnrollmentCollector


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
