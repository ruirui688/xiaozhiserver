import asyncio
import sys
import time
from pathlib import Path
from types import SimpleNamespace

import pytest


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings

settings.config_file_valid = True

from core.providers.asr import doubao_recording


class FakeResponse:
    def __init__(self, payload, *, status=200, delay=0, error=None):
        self.payload = payload
        self.status = status
        self.delay = delay
        self.error = error

    async def __aenter__(self):
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error:
            raise self.error
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        return False

    async def json(self, content_type=None):
        return self.payload


class FakeSession:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        return False

    def post(self, url, **kwargs):
        self.calls.append((url, kwargs))
        return self.responses.pop(0)


def _provider(tmp_path, **overrides):
    config = {
        "api_key": "test-api-key",
        "output_dir": str(tmp_path),
    }
    config.update(overrides)
    return doubao_recording.ASRProvider(config, delete_audio_file=True)


def _artifacts():
    return SimpleNamespace(
        pcm_bytes=b"\x00\x00" * 160,
        file_path="test.wav",
    )


def test_timeout_configuration_defaults_and_overrides(tmp_path):
    provider = _provider(tmp_path)

    assert provider.http_timeout_seconds == 10
    assert provider.query_interval_seconds == 0.2
    assert provider.query_timeout_seconds == 15

    configured = _provider(
        tmp_path,
        http_timeout_seconds="3.5",
        query_interval_seconds="0.05",
        query_timeout_seconds="7",
    )

    assert configured.http_timeout_seconds == 3.5
    assert configured.query_interval_seconds == 0.05
    assert configured.query_timeout_seconds == 7


def test_recording_request_sends_agent_hotword_table_in_corpus(tmp_path):
    provider = _provider(
        tmp_path,
        boosting_table_id="example-hotword-table-id",
        context_text='{"hotwords":[{"word":"示例专有词"}]}',
    )

    payload = provider._build_submit_payload(b"wav", "request-id")

    assert payload["request"]["corpus"] == {
        "boosting_table_id": "example-hotword-table-id",
        "context": '{"hotwords":[{"word":"示例专有词"}]}',
    }
    assert "context" not in payload["request"]


def test_recording_request_omits_empty_hotword_corpus(tmp_path):
    provider = _provider(tmp_path, boosting_table_id="")

    assert "corpus" not in provider._build_submit_payload(b"wav", "request-id")["request"]


@pytest.mark.parametrize(
    "key,value",
    [
        ("http_timeout_seconds", 0),
        ("query_interval_seconds", -1),
        ("query_timeout_seconds", "invalid"),
    ],
)
def test_timeout_configuration_rejects_invalid_values(tmp_path, key, value):
    with pytest.raises(ValueError, match=key):
        _provider(tmp_path, **{key: value})


def test_submit_and_poll_are_async_and_return_text(monkeypatch, tmp_path):
    client_session_options = {}
    session = FakeSession(
        [
            FakeResponse({"code": 0}, delay=0.01),
            FakeResponse({"code": 0}, delay=0.01),
            FakeResponse({"result": {"text": "测试识别结果"}}, delay=0.01),
        ]
    )
    monkeypatch.setattr(
        doubao_recording.aiohttp,
        "ClientSession",
        lambda **kwargs: client_session_options.update(kwargs) or session,
    )
    provider = _provider(
        tmp_path,
        query_interval_seconds=0.01,
        query_timeout_seconds=0.2,
    )

    async def run_with_heartbeat():
        heartbeat_count = 0

        async def heartbeat():
            nonlocal heartbeat_count
            for _ in range(10):
                await asyncio.sleep(0.005)
                heartbeat_count += 1

        result, _ = await asyncio.gather(
            provider.speech_to_text([], "session", artifacts=_artifacts()),
            heartbeat(),
        )
        return result, heartbeat_count

    (text, file_path), heartbeat_count = asyncio.run(run_with_heartbeat())

    assert text == "测试识别结果"
    assert file_path == "test.wav"
    assert heartbeat_count == 10
    assert client_session_options == {"trust_env": True}
    assert len(session.calls) == 3
    assert session.calls[0][0] == provider.submit_url
    assert session.calls[1][0] == provider.query_url
    assert session.calls[0][1]["timeout"].total == 10
    assert 0 < session.calls[1][1]["timeout"].total <= 0.2


def test_extract_result_preserves_utterances_for_meeting_minutes():
    result = doubao_recording.ASRProvider._extract_result(
        {
            "result": {
                "text": "会被分段覆盖",
                "utterances": [
                    {
                        "text": "先讨论计划。",
                        "speaker": "spk_1",
                        "start_time": 0,
                        "end_time": 1200,
                    },
                    {
                        "text": "我来负责验收。",
                        "speaker_id": "spk_2",
                        "start_time": 1300,
                        "end_time": 2400,
                    },
                ],
            }
        }
    )

    assert result["content"] == "先讨论计划。我来负责验收。"
    assert result["utterances"][0]["provider_speaker"] == "spk_1"
    assert result["utterances"][1]["provider_speaker"] == "spk_2"
    assert result["provider_metadata"]["provider"] == "doubao_recording"


def test_query_timeout_is_bounded_without_external_request(monkeypatch, tmp_path):
    session = FakeSession(
        [FakeResponse({"code": 0})] + [FakeResponse({"code": 0}) for _ in range(20)]
    )
    monkeypatch.setattr(
        doubao_recording.aiohttp,
        "ClientSession",
        lambda **kwargs: session,
    )
    provider = _provider(
        tmp_path,
        http_timeout_seconds=1,
        query_interval_seconds=0.005,
        query_timeout_seconds=0.03,
    )

    started = time.monotonic()
    result = asyncio.run(
        provider.speech_to_text([], "session", artifacts=_artifacts())
    )
    elapsed = time.monotonic() - started

    assert result == ("", "test.wav")
    assert elapsed < 0.2
    assert len(session.calls) > 1
    assert all(
        call[1]["timeout"].total <= 0.03 for call in session.calls[1:]
    )


def test_submit_timeout_keeps_event_loop_alive_and_next_round_recovers(
    monkeypatch, tmp_path
):
    timed_out = FakeSession(
        [
            FakeResponse(
                {},
                delay=0.02,
                error=asyncio.TimeoutError(),
            )
        ]
    )
    recovered = FakeSession(
        [FakeResponse({"result": {"text": "恢复成功"}})]
    )
    sessions = iter([timed_out, recovered])
    monkeypatch.setattr(
        doubao_recording.aiohttp,
        "ClientSession",
        lambda **kwargs: next(sessions),
    )
    provider = _provider(tmp_path, http_timeout_seconds=0.03)

    async def scenario():
        heartbeat_count = 0

        async def heartbeat():
            nonlocal heartbeat_count
            for _ in range(12):
                await asyncio.sleep(0.005)
                heartbeat_count += 1

        heartbeat_task = asyncio.create_task(heartbeat())
        started = time.monotonic()
        first = await provider.speech_to_text(
            [], "session-1", artifacts=_artifacts()
        )
        elapsed = time.monotonic() - started
        second = await provider.speech_to_text(
            [], "session-2", artifacts=_artifacts()
        )
        await heartbeat_task
        return first, second, elapsed, heartbeat_count

    first, second, elapsed, heartbeat_count = asyncio.run(scenario())

    assert first == ("", "test.wav")
    assert second == ("恢复成功", "test.wav")
    assert elapsed < 0.1
    assert heartbeat_count == 12


def test_query_pending_timeout_keeps_event_loop_alive_and_next_round_recovers(
    monkeypatch, tmp_path
):
    pending = FakeSession(
        [FakeResponse({"code": 0})]
        + [FakeResponse({"code": 0}) for _ in range(20)]
    )
    recovered = FakeSession(
        [FakeResponse({"result": {"text": "下一轮正常"}})]
    )
    sessions = iter([pending, recovered])
    monkeypatch.setattr(
        doubao_recording.aiohttp,
        "ClientSession",
        lambda **kwargs: next(sessions),
    )
    provider = _provider(
        tmp_path,
        http_timeout_seconds=0.1,
        query_interval_seconds=0.005,
        query_timeout_seconds=0.03,
    )

    async def scenario():
        heartbeat = asyncio.create_task(asyncio.sleep(0.05, result="alive"))
        started = time.monotonic()
        first = await provider.speech_to_text(
            [], "session-1", artifacts=_artifacts()
        )
        elapsed = time.monotonic() - started
        second = await provider.speech_to_text(
            [], "session-2", artifacts=_artifacts()
        )
        return first, second, elapsed, await heartbeat

    first, second, elapsed, heartbeat = asyncio.run(scenario())

    assert first == ("", "test.wav")
    assert second == ("下一轮正常", "test.wav")
    assert elapsed < 0.2
    assert heartbeat == "alive"
