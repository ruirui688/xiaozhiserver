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

from core.providers.llm.openai import openai as openai_llm


class FakeStream:
    def __init__(self, chunks=None, error=None):
        self._chunks = iter(chunks or [])
        self._error = error
        self.closed = False

    def __iter__(self):
        return self

    def __next__(self):
        if self._error is not None:
            error = self._error
            self._error = None
            raise error
        return next(self._chunks)

    def close(self):
        self.closed = True


def _chunk(content="", tool_calls=None):
    return SimpleNamespace(
        choices=[
            SimpleNamespace(
                delta=SimpleNamespace(content=content, tool_calls=tool_calls)
            )
        ],
        usage=None,
    )


def _provider(monkeypatch, stream, **overrides):
    captured = {}

    class FakeCompletions:
        def create(self, **kwargs):
            captured["request"] = kwargs
            return stream

    class FakeOpenAI:
        def __init__(self, api_key, base_url, timeout):
            captured["api_key"] = api_key
            captured["base_url"] = base_url
            captured["timeout"] = timeout
            self.chat = SimpleNamespace(completions=FakeCompletions())

    monkeypatch.setattr(openai_llm.openai, "OpenAI", FakeOpenAI)
    config = {
        "model_name": "test-model",
        "api_key": "test-key",
        "base_url": "https://example.test/v1",
    }
    config.update(overrides)
    return openai_llm.LLMProvider(config), captured


def _dialogue():
    return [{"role": "user", "content": "你好"}]


def test_default_http_timeouts_bound_stream_without_first_chunk(monkeypatch):
    stream = FakeStream(error=TimeoutError("read timed out"))
    provider, captured = _provider(monkeypatch, stream)

    timeout = captured["timeout"]
    assert timeout.pool == 2.0
    assert timeout.connect == 3.0
    assert timeout.write == 5.0
    assert timeout.read == 15.0
    assert provider.stream_total_timeout_seconds == 30.0

    with pytest.raises(TimeoutError, match="read timed out"):
        list(provider.response("session", _dialogue()))

    assert stream.closed is True


def test_timeout_configuration_can_override_each_budget(monkeypatch):
    provider, captured = _provider(
        monkeypatch,
        FakeStream(),
        timeout={"pool": 1, "connect": 2, "write": 3, "read": 4},
        stream_total_timeout_seconds="5.5",
    )

    timeout = captured["timeout"]
    assert timeout.pool == 1
    assert timeout.connect == 2
    assert timeout.write == 3
    assert timeout.read == 4
    assert provider.stream_total_timeout_seconds == 5.5


def test_continuous_text_stream_exceeding_total_budget_is_closed(monkeypatch):
    stream = FakeStream([_chunk("第一段"), _chunk("第二段")])
    provider, _ = _provider(
        monkeypatch,
        stream,
        stream_total_timeout_seconds=1,
    )
    clock = iter([0.0, 0.1, 0.2, 0.8, 1.1])
    monkeypatch.setattr(openai_llm.time, "monotonic", lambda: next(clock))

    responses = provider.response("session", _dialogue())
    assert next(responses) == "第一段"
    with pytest.raises(TimeoutError, match="LLM流式响应超过总时限（1.0s）"):
        next(responses)

    assert stream.closed is True


def test_function_stream_uses_same_total_budget(monkeypatch):
    stream = FakeStream(
        [
            _chunk("", [SimpleNamespace(id="call-1")]),
            _chunk("", [SimpleNamespace(id="call-2")]),
        ]
    )
    provider, _ = _provider(
        monkeypatch,
        stream,
        stream_total_timeout_seconds=1,
    )
    clock = iter([0.0, 0.1, 0.2, 0.8, 1.1])
    monkeypatch.setattr(openai_llm.time, "monotonic", lambda: next(clock))

    responses = provider.response_with_functions(
        "session",
        _dialogue(),
        functions=[{"type": "function", "function": {"name": "test"}}],
    )
    content, tool_calls = next(responses)
    assert content == ""
    assert tool_calls[0].id == "call-1"
    with pytest.raises(TimeoutError, match="LLM流式响应超过总时限（1.0s）"):
        next(responses)

    assert stream.closed is True


def test_normal_stream_finishes_within_total_budget(monkeypatch):
    stream = FakeStream([_chunk("你"), _chunk("好")])
    provider, _ = _provider(
        monkeypatch,
        stream,
        stream_total_timeout_seconds=1,
    )
    clock = iter([0.0, 0.1, 0.2, 0.3, 0.4, 0.5])
    monkeypatch.setattr(openai_llm.time, "monotonic", lambda: next(clock))

    assert list(provider.response("session", _dialogue())) == ["你", "好"]
    assert stream.closed is True


def test_read_timeout_in_executor_keeps_heartbeat_and_next_round_recovers(
    monkeypatch,
):
    class SlowTimeoutStream(FakeStream):
        def __next__(self):
            time.sleep(0.03)
            raise TimeoutError("simulated first-token timeout")

    failed = SlowTimeoutStream()
    recovered = FakeStream([_chunk("恢复成功")])
    provider, _ = _provider(
        monkeypatch,
        failed,
        timeout={"pool": 0.01, "connect": 0.01, "write": 0.01, "read": 0.03},
        stream_total_timeout_seconds=0.1,
    )
    streams = iter([failed, recovered])
    provider.client.chat.completions.create = lambda **kwargs: next(streams)

    async def scenario():
        heartbeat_count = 0

        async def heartbeat():
            nonlocal heartbeat_count
            for _ in range(12):
                await asyncio.sleep(0.005)
                heartbeat_count += 1

        heartbeat_task = asyncio.create_task(heartbeat())
        with pytest.raises(TimeoutError, match="first-token timeout"):
            await asyncio.to_thread(
                lambda: list(provider.response("session-1", _dialogue()))
            )
        second = await asyncio.to_thread(
            lambda: list(provider.response("session-2", _dialogue()))
        )
        await heartbeat_task
        return second, heartbeat_count

    second, heartbeat_count = asyncio.run(scenario())

    assert second == ["恢复成功"]
    assert heartbeat_count == 12
    assert failed.closed is True
    assert recovered.closed is True


def test_mid_stream_read_timeout_closes_stream_and_next_round_recovers(monkeypatch):
    class MidStreamTimeoutStream(FakeStream):
        def __init__(self):
            super().__init__()
            self.index = 0

        def __next__(self):
            self.index += 1
            if self.index == 1:
                return _chunk("第一段")
            time.sleep(0.02)
            raise TimeoutError("simulated mid-stream timeout")

    failed = MidStreamTimeoutStream()
    recovered = FakeStream([_chunk("下一轮正常")])
    provider, _ = _provider(
        monkeypatch,
        failed,
        timeout={"pool": 0.01, "connect": 0.01, "write": 0.01, "read": 0.02},
        stream_total_timeout_seconds=0.1,
    )
    streams = iter([failed, recovered])
    provider.client.chat.completions.create = lambda **kwargs: next(streams)

    responses = provider.response("session-1", _dialogue())
    assert next(responses) == "第一段"
    with pytest.raises(TimeoutError, match="mid-stream timeout"):
        next(responses)

    assert list(provider.response("session-2", _dialogue())) == ["下一轮正常"]
    assert failed.closed is True
    assert recovered.closed is True
