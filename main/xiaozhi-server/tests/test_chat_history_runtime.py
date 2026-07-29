import asyncio
import json
from types import SimpleNamespace

from config import settings

settings.config_file_valid = True

from core.api.chat_history_runtime_handler import ChatHistoryRuntimeHandler


class _ReportManager:
    def __init__(self):
        self.values = []

    def update_chat_history_config(self, conn, value):
        self.values.append((conn, value))
        return True


class _Request:
    def __init__(self, body, token="secret"):
        self._body = body
        self.headers = {"Authorization": f"Bearer {token}"}

    async def json(self):
        return self._body


def _handler():
    return ChatHistoryRuntimeHandler(
        {
            "server": {"auth_key": "secret"},
            "manager-api": {"secret": "manager-secret"},
        }
    )


def test_refresh_updates_all_online_connections_for_agent(monkeypatch):
    first_manager = _ReportManager()
    second_manager = _ReportManager()
    connections = [
        SimpleNamespace(report_manager=first_manager),
        SimpleNamespace(report_manager=second_manager),
    ]
    monkeypatch.setattr(
        "core.api.chat_history_runtime_handler.get_connections_for_agent",
        lambda agent_id: connections if agent_id == "agent-a" else [],
    )

    response = asyncio.run(
        _handler().handle_refresh(
            _Request({"agentId": "agent-a", "chatHistoryConf": 2})
        )
    )
    payload = json.loads(response.text)

    assert response.status == 200
    assert payload["data"] == {
        "online": 2,
        "updated": 2,
        "chatHistoryConf": 2,
    }
    assert first_manager.values == [(connections[0], 2)]
    assert second_manager.values == [(connections[1], 2)]


def test_refresh_rejects_unauthorized_or_invalid_mode(monkeypatch):
    monkeypatch.setattr(
        "core.api.chat_history_runtime_handler.get_connections_for_agent",
        lambda _agent_id: [],
    )

    unauthorized = asyncio.run(
        _handler().handle_refresh(
            _Request({"agentId": "agent-a", "chatHistoryConf": 1}, token="wrong")
        )
    )
    invalid = asyncio.run(
        _handler().handle_refresh(
            _Request({"agentId": "agent-a", "chatHistoryConf": 3})
        )
    )

    assert unauthorized.status == 401
    assert invalid.status == 400
