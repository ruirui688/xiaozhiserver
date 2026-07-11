import asyncio
import json
from types import SimpleNamespace

from core.handle.helloHandle import handleHelloMessage


class FakeLogger:
    def bind(self, **_kwargs):
        return self

    def info(self, *_args, **_kwargs):
        return None

    def debug(self, *_args, **_kwargs):
        return None


class FakeWebSocket:
    def __init__(self):
        self.sent = []

    async def send(self, payload):
        self.sent.append(json.loads(payload))


def test_hello_records_persistent_websocket_feature():
    async def scenario():
        websocket = FakeWebSocket()
        conn = SimpleNamespace(
            websocket=websocket,
            logger=FakeLogger(),
            headers={"device-id": "device", "client-id": "client"},
            welcome_msg={"type": "hello"},
            sample_rate=24000,
            features=None,
            audio_format="opus",
        )

        await handleHelloMessage(
            conn,
            {"features": {"persistent_websocket": True, "mcp": False}},
        )

        assert conn.features["persistent_websocket"] is True
        assert websocket.sent == [{"type": "hello"}]

    asyncio.run(scenario())


def test_hello_accepts_empty_feature_map_for_legacy_defaults():
    async def scenario():
        conn = SimpleNamespace(
            websocket=FakeWebSocket(),
            logger=FakeLogger(),
            headers={},
            welcome_msg={"type": "hello"},
            sample_rate=24000,
            features=None,
            audio_format="opus",
        )

        await handleHelloMessage(conn, {"features": {}})

        assert conn.features == {}

    asyncio.run(scenario())
