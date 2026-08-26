import asyncio
import json
from types import SimpleNamespace

import config.settings

config.settings.config_file_valid = True

import core.api.face_profile_release_handler as handler_module
from core.api.face_profile_release_handler import FaceProfileReleaseHandler


class _Request:
    def __init__(self, body, token="server-secret"):
        self._body = body
        self.headers = {"Authorization": f"Bearer {token}"}

    async def json(self):
        return self._body


def test_release_only_announces_to_online_v2_connections(monkeypatch):
    conn = SimpleNamespace(device_id="device-a")
    monkeypatch.setattr(
        handler_module,
        "get_connections_for_agent",
        lambda agent_id: [conn] if agent_id == "agent-a" else [],
    )

    async def notify(value):
        return value is conn

    monkeypatch.setattr(handler_module, "notify_face_release_available", notify)
    handler = FaceProfileReleaseHandler({
        "server": {"auth_key": "server-secret"},
        "manager-api": {"secret": "manager-secret"},
    })

    response = asyncio.run(handler.handle_release(_Request({"agentIds": ["agent-a"]})))
    payload = json.loads(response.text)

    assert response.status == 200
    assert payload["data"] == {"online": 1, "notified": 1}


def test_release_rejects_wrong_secret():
    handler = FaceProfileReleaseHandler({
        "server": {"auth_key": "server-secret"},
        "manager-api": {"secret": "manager-secret"},
    })
    response = asyncio.run(handler.handle_release(
        _Request({"agentIds": ["agent-a"]}, token="wrong")
    ))
    assert response.status == 401
