import asyncio
import sys
import time
from pathlib import Path

import pytest


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings

settings.config_file_valid = True

from core.providers.asr import doubao_stream


class FakeWebSocket:
    def __init__(self, *, recv_delay=1, responses=None):
        self.recv_delay = recv_delay
        self.responses = list(responses or [])
        self.closed = False
        self.sent = []

    async def send(self, payload):
        self.sent.append(payload)

    async def recv(self):
        if self.responses:
            response = self.responses.pop(0)
            if isinstance(response, BaseException):
                raise response
            return response
        await asyncio.sleep(self.recv_delay)
        return b""

    async def close(self):
        self.closed = True


class FakeConnection:
    def __init__(self):
        self.client_listen_mode = "auto"
        self.asr_audio = []
        self.client_have_voice = False
        self.client_voice_stop = False
        self.stop_event = type("StopEvent", (), {"is_set": lambda self: False})()
        self.reset_count = 0

    def reset_audio_states(self):
        self.reset_count += 1


def _provider(tmp_path, **overrides):
    config = {
        "api_key": "test-api-key",
        "output_dir": str(tmp_path),
    }
    config.update(overrides)
    return doubao_stream.ASRProvider(config, delete_audio_file=True)


def test_stream_timeout_configuration_defaults_and_overrides(tmp_path):
    provider = _provider(tmp_path)
    assert provider.init_timeout_seconds == 10
    assert provider.response_timeout_seconds == 15

    configured = _provider(
        tmp_path,
        init_timeout_seconds="0.25",
        response_timeout_seconds="1.5",
    )
    assert configured.init_timeout_seconds == 0.25
    assert configured.response_timeout_seconds == 1.5


@pytest.mark.parametrize(
    "key,value",
    [
        ("init_timeout_seconds", 0),
        ("response_timeout_seconds", -1),
        ("response_timeout_seconds", "invalid"),
    ],
)
def test_stream_timeout_configuration_rejects_invalid_values(
    tmp_path, key, value
):
    with pytest.raises(ValueError, match=key):
        _provider(tmp_path, **{key: value})


def test_initialization_timeout_closes_websocket_and_resets_state(
    monkeypatch, tmp_path
):
    websocket = FakeWebSocket()

    async def fake_connect(*args, **kwargs):
        return websocket

    monkeypatch.setattr(doubao_stream.websockets, "connect", fake_connect)
    provider = _provider(tmp_path, init_timeout_seconds=0.01)
    conn = FakeConnection()
    conn.asr = provider

    asyncio.run(provider.receive_audio(conn, b"invalid-opus", True))

    assert websocket.closed is True
    assert provider.asr_ws is None
    assert provider.is_processing is False
    assert provider._is_stopping is False
    assert conn.reset_count == 1


def test_response_timeout_closes_websocket_and_resets_audio_state(tmp_path):
    websocket = FakeWebSocket()
    provider = _provider(tmp_path, response_timeout_seconds=0.01)
    provider.asr_ws = websocket
    provider.is_processing = True
    conn = FakeConnection()

    asyncio.run(provider._forward_asr_results(conn))

    assert websocket.closed is True
    assert provider.asr_ws is None
    assert provider.is_processing is False
    assert provider._is_stopping is False
    assert conn.reset_count == 1


def test_initialization_half_open_keeps_heartbeat_and_next_connection_starts(
    monkeypatch, tmp_path
):
    first = FakeWebSocket(recv_delay=1)
    second = FakeWebSocket(responses=[b"init", b"final"])
    sockets = iter([first, second])

    async def fake_connect(*args, **kwargs):
        return next(sockets)

    monkeypatch.setattr(doubao_stream.websockets, "connect", fake_connect)
    provider = _provider(
        tmp_path,
        init_timeout_seconds=0.02,
        response_timeout_seconds=0.05,
    )
    provider.parse_response = lambda response: (
        {"code": 1000}
        if response == b"init"
        else {"payload_msg": {"error": "simulated completion"}}
    )
    provider.decoder = type("Decoder", (), {"decode": lambda *args: b""})()
    conn = FakeConnection()
    conn.asr = provider

    async def scenario():
        heartbeat_count = 0

        async def heartbeat():
            nonlocal heartbeat_count
            for _ in range(10):
                await asyncio.sleep(0.005)
                heartbeat_count += 1

        heartbeat_task = asyncio.create_task(heartbeat())
        started = time.monotonic()
        await provider.receive_audio(conn, b"audio", True)
        first_elapsed = time.monotonic() - started
        await provider.receive_audio(conn, b"audio", True)
        await asyncio.wait_for(provider.forward_task, timeout=0.1)
        await heartbeat_task
        return first_elapsed, heartbeat_count

    elapsed, heartbeat_count = asyncio.run(scenario())

    assert elapsed < 0.1
    assert heartbeat_count == 10
    assert first.closed is True
    assert second.closed is True
    assert provider.asr_ws is None
    assert provider.is_processing is False
    assert conn.reset_count == 2


def test_result_half_open_keeps_heartbeat_and_next_forwarder_starts(tmp_path):
    first = FakeWebSocket(recv_delay=1)
    second = FakeWebSocket(responses=[b"final"])
    provider = _provider(tmp_path, response_timeout_seconds=0.02)
    provider.parse_response = lambda response: {
        "payload_msg": {"error": "simulated completion"}
    }
    provider.asr_ws = first
    provider.is_processing = True
    conn = FakeConnection()

    async def scenario():
        heartbeat = asyncio.create_task(asyncio.sleep(0.05, result="alive"))
        started = time.monotonic()
        await provider._forward_asr_results(conn)
        first_elapsed = time.monotonic() - started

        provider.asr_ws = second
        provider.is_processing = True
        await provider._forward_asr_results(conn)
        return first_elapsed, await heartbeat

    elapsed, heartbeat = asyncio.run(scenario())

    assert elapsed < 0.1
    assert heartbeat == "alive"
    assert first.closed is True
    assert second.closed is True
    assert provider.asr_ws is None
    assert provider.is_processing is False
    assert conn.reset_count == 2
