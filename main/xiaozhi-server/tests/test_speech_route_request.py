import asyncio
import json
from types import SimpleNamespace

import pytest

from config import settings

settings.config_file_valid = True

import core.handle.textHandler.speechRouteRequestMessageHandler as route_handler_module
from core.handle.textHandler.speechRouteRequestMessageHandler import (
    SpeechRouteRequestTextMessageHandler,
)
from core.handle.textMessageHandlerRegistry import TextMessageHandlerRegistry


class _Logger:
    def bind(self, **_kwargs):
        return self

    def warning(self, *_args, **_kwargs):
        pass


class _WebSocket:
    def __init__(self):
        self.messages = []

    async def send(self, payload):
        self.messages.append(json.loads(payload))


def _connection(**overrides):
    values = {
        "device_id": "AA:BB:CC:DD:EE:FF",
        "session_id": "session-1",
        "read_config_from_api": True,
        "websocket": _WebSocket(),
        "logger": _Logger(),
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def test_route_request_handler_is_registered():
    handler = TextMessageHandlerRegistry().get_handler("speech_route_request")

    assert isinstance(handler, SpeechRouteRequestTextMessageHandler)


def test_route_request_persists_only_authenticated_connection_device(monkeypatch):
    recorded = []

    async def persist(device_id, policy):
        recorded.append((device_id, policy))

    monkeypatch.setattr(
        route_handler_module, "update_device_speech_input_policy", persist
    )
    conn = _connection()

    asyncio.run(
        SpeechRouteRequestTextMessageHandler().handle(
            conn,
            {
                "type": "speech_route_request",
                "request_id": "route-1",
                "inputPolicy": "device",
                "deviceId": "attacker-controlled-device",
            },
        )
    )

    assert recorded == [("AA:BB:CC:DD:EE:FF", "device")]
    assert conn.websocket.messages == [
        {
            "type": "speech_route_request_ack",
            "session_id": "session-1",
            "request_id": "route-1",
            "inputPolicy": "device",
            "accepted": True,
            "pending": True,
        }
    ]


@pytest.mark.parametrize(
    ("message", "reason"),
    [
        ({"inputPolicy": "fallback"}, "invalid_input_policy"),
        (
            {"inputPolicy": "auto", "request_id": "contains spaces"},
            "invalid_request_id",
        ),
    ],
)
def test_route_request_rejects_invalid_bounded_fields_without_persisting(
    monkeypatch, message, reason
):
    async def unexpected(*_args):
        raise AssertionError("persistence must not be called")

    monkeypatch.setattr(
        route_handler_module, "update_device_speech_input_policy", unexpected
    )
    conn = _connection()

    asyncio.run(SpeechRouteRequestTextMessageHandler().handle(conn, message))

    assert conn.websocket.messages[-1]["accepted"] is False
    assert conn.websocket.messages[-1]["pending"] is False
    assert conn.websocket.messages[-1]["reason"] == reason


def test_route_request_requires_manager_owned_runtime_configuration(monkeypatch):
    async def unexpected(*_args):
        raise AssertionError("persistence must not be called")

    monkeypatch.setattr(
        route_handler_module, "update_device_speech_input_policy", unexpected
    )
    conn = _connection(read_config_from_api=False)

    asyncio.run(
        SpeechRouteRequestTextMessageHandler().handle(
            conn, {"inputPolicy": "server", "request_id": "route-2"}
        )
    )

    assert conn.websocket.messages[-1]["reason"] == "route_management_unavailable"


def test_route_request_is_rate_limited_per_authenticated_connection(monkeypatch):
    recorded = []

    async def persist(device_id, policy):
        recorded.append((device_id, policy))

    monkeypatch.setattr(
        route_handler_module, "update_device_speech_input_policy", persist
    )
    conn = _connection()
    handler = SpeechRouteRequestTextMessageHandler()

    async def send_requests():
        for index in range(5):
            await handler.handle(
                conn,
                {"inputPolicy": "auto", "request_id": f"route-{index}"},
            )

    asyncio.run(send_requests())

    assert len(recorded) == 4
    assert conn.websocket.messages[-1]["accepted"] is False
    assert conn.websocket.messages[-1]["reason"] == "rate_limited"
