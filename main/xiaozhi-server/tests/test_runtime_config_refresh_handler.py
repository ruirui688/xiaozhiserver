import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import config.settings

config.settings.config_file_valid = True

import core.api.runtime_config_refresh_handler as handler_module
from core.api.runtime_config_refresh_handler import RuntimeConfigRefreshHandler


class _Request:
    def __init__(self, body, token="server-secret"):
        self._body = body
        self.headers = {"Authorization": f"Bearer {token}"}

    async def json(self):
        return self._body


def test_refresh_marks_all_online_agent_connections(monkeypatch):
    reloader = SimpleNamespace(
        request_refresh=AsyncMock(),
        refresh_if_due=AsyncMock(return_value=True),
    )
    # request_refresh is intentionally synchronous in production.
    reloader.request_refresh = lambda reason: setattr(reloader, "reason", reason)
    conn = SimpleNamespace(
        runtime_config_reloader=reloader,
        client_is_speaking=False,
        device_id="device-a",
    )
    monkeypatch.setattr(
        handler_module,
        "get_connections_for_agent",
        lambda agent_id: [conn] if agent_id == "agent-a" else [],
    )
    handler = RuntimeConfigRefreshHandler(
        {"server": {"auth_key": "server-secret"}, "manager-api": {"secret": "manager-secret"}}
    )

    async def run():
        return await handler.handle_refresh(
            _Request({"agentIds": ["agent-a"], "reason": "agent_updated"})
        )

    response = asyncio.run(run())
    payload = json.loads(response.text)

    assert response.status == 200
    assert payload["data"]["scheduled"] == 1
    assert reloader.reason == "agent_updated"
    reloader.refresh_if_due.assert_not_awaited()


def test_refresh_rejects_wrong_secret():
    handler = RuntimeConfigRefreshHandler(
        {"server": {"auth_key": "server-secret"}, "manager-api": {"secret": "manager-secret"}}
    )
    response = asyncio.run(handler.handle_refresh(
        _Request({"agentIds": ["agent-a"]}, token="wrong")
    ))
    assert response.status == 401
