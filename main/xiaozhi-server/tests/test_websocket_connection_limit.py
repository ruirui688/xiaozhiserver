import asyncio
from types import SimpleNamespace

import config.settings

config.settings.config_file_valid = True

from core.websocket_server import WebSocketServer, _max_device_connections


class _Logger:
    def bind(self, **_kwargs):
        return self

    def warning(self, *_args, **_kwargs):
        return None


class _WebSocket:
    def __init__(self):
        self.request = SimpleNamespace(
            path="/xiaozhi/v1/",
            headers={"device-id": "device-1"},
        )
        self.closed_with = None

    async def close(self, code=None, reason=None):
        self.closed_with = (code, reason)


def test_max_device_connections_uses_safe_default_and_minimum():
    assert _max_device_connections({}) == 64
    assert _max_device_connections({"max_device_connections": "invalid"}) == 64
    assert _max_device_connections({"max_device_connections": 0}) == 1


def test_authenticated_connection_is_rejected_at_capacity():
    server = object.__new__(WebSocketServer)
    server.active_handlers = {object()}
    server.max_device_connections = 1
    server.audio_preview_hub = SimpleNamespace()
    server.logger = _Logger()

    async def authenticate(_websocket):
        return None

    server._handle_auth = authenticate
    websocket = _WebSocket()

    asyncio.run(server._handle_connection(websocket))

    assert websocket.closed_with == (1013, "server capacity reached")
