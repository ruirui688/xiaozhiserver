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


def test_scene_refresh_targets_device_and_preserves_switch_context(monkeypatch):
    captured = {}
    reloader = SimpleNamespace()

    def request_refresh(reason, scene_switch=None):
        captured.update(reason=reason, scene_switch=scene_switch)

    reloader.request_refresh = request_refresh
    conn = SimpleNamespace(
        runtime_config_reloader=reloader,
        client_is_speaking=True,
        device_id="device-a",
    )
    report_result = AsyncMock(return_value=None)
    monkeypatch.setattr(handler_module, "get_connection", lambda value: conn if value == "device-a" else None)
    monkeypatch.setattr(handler_module, "report_device_scene_switch_result", report_result)
    handler = RuntimeConfigRefreshHandler(
        {"server": {"auth_key": "server-secret"}, "manager-api": {"secret": "manager-secret"}}
    )
    scene_switch = {
        "deviceId": "device-a",
        "fromAgentId": "agent-a",
        "toAgentId": "agent-b",
        "switchRequestId": "request-a",
        "configVersion": 2,
    }

    response = asyncio.run(handler.handle_refresh(_Request({
        "deviceIds": ["device-a"],
        "reason": "device_scene_switched",
        "sceneSwitch": scene_switch,
    })))
    payload = json.loads(response.text)

    assert response.status == 200
    assert payload["data"]["deviceCount"] == 1
    assert payload["data"]["scheduled"] == 1
    assert captured == {"reason": "device_scene_switched", "scene_switch": scene_switch}
    report_result.assert_awaited_once()
    assert report_result.await_args.args[0]["status"] == "SCHEDULED"


def test_activation_refresh_runs_immediately_for_idle_unbound_connection(monkeypatch):
    reloader = SimpleNamespace(
        request_refresh=lambda reason: setattr(reloader, "reason", reason),
        refresh_if_due=AsyncMock(return_value=True),
    )
    conn = SimpleNamespace(
        runtime_config_reloader=reloader,
        client_is_speaking=False,
        device_id="device-a",
    )
    monkeypatch.setattr(
        handler_module, "get_connection", lambda value: conn if value == "device-a" else None
    )
    handler = RuntimeConfigRefreshHandler(
        {"server": {"auth_key": "server-secret"}, "manager-api": {"secret": "manager-secret"}}
    )

    async def run():
        response = await handler.handle_refresh(_Request({
            "deviceIds": ["device-a"],
            "reason": "device_activated",
            "refreshNow": True,
        }))
        await asyncio.sleep(0)
        return response

    response = asyncio.run(run())
    assert response.status == 200
    assert reloader.reason == "device_activated"
    reloader.refresh_if_due.assert_awaited_once_with(
        force=True, reason="device_activated"
    )


def test_immediate_refresh_waits_for_busy_connection_to_become_idle(monkeypatch):
    reloader = SimpleNamespace(
        request_refresh=lambda reason: setattr(reloader, "reason", reason),
        refresh_if_due=AsyncMock(return_value=True),
    )
    conn = SimpleNamespace(
        runtime_config_reloader=reloader,
        client_is_speaking=True,
        closing=False,
        closed=False,
        device_id="device-a",
    )
    monkeypatch.setattr(
        handler_module,
        "get_connection",
        lambda value: conn if value == "device-a" else None,
    )
    handler = RuntimeConfigRefreshHandler(
        {
            "server": {"auth_key": "server-secret"},
            "manager-api": {"secret": "manager-secret"},
        }
    )
    handler.SAFE_REFRESH_POLL_SECONDS = 0

    async def run():
        response = await handler.handle_refresh(
            _Request(
                {
                    "deviceIds": ["device-a"],
                    "reason": "device_speech_routing_updated",
                    "refreshNow": True,
                }
            )
        )
        await asyncio.sleep(0)
        reloader.refresh_if_due.assert_not_awaited()
        conn.client_is_speaking = False
        await asyncio.sleep(0)
        await asyncio.sleep(0)
        return response

    response = asyncio.run(run())

    assert response.status == 200
    reloader.refresh_if_due.assert_awaited_once_with(
        force=True, reason="device_speech_routing_updated"
    )
