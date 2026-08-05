import asyncio
import json
from types import SimpleNamespace

import config.settings

config.settings.config_file_valid = True

import core.api.session_context_handler as handler_module
from core.api.session_context_handler import SessionContextHandler


class _Dialogue:
    def __init__(self):
        self.cleared = False

    def clear_conversation_history(self, preserve_system=True):
        self.cleared = preserve_system
        return 4


class _Request:
    def __init__(self, body, token="server-secret"):
        self._body = body
        self.headers = {"Authorization": f"Bearer {token}"}

    async def json(self):
        return self._body


def _handler():
    return SessionContextHandler(
        {
            "server": {"auth_key": "server-secret"},
            "manager-api": {"secret": "manager-secret"},
        }
    )


def _connection(agent_id="agent-a", busy=False):
    router = SimpleNamespace(
        is_realtime=True,
        capabilities=SimpleNamespace(context_reset=True),
        session=SimpleNamespace(
            _response_active=False,
            _instruction_turn_active=False,
        ),
    )
    router.request_context_reset = lambda: setattr(router, "reset_requested", True) or True
    async def reset_context_now():
        router.reset_completed = True
        return True

    router.reset_context_now = reset_context_now
    return SimpleNamespace(
        config={"agent_id": agent_id},
        client_is_speaking=busy,
        client_have_voice=False,
        conversation_router=router,
        dialogue=_Dialogue(),
        last_tool_context="old",
        current_speaker="speaker",
        current_speaker_id="speaker-id",
        turn_context=SimpleNamespace(clear=lambda: None),
        game_session_manager=SimpleNamespace(clear_active_games=lambda: None),
        semantic_center=SimpleNamespace(
            state_manager=SimpleNamespace(reset=lambda: None)
        ),
        _inject_tool_call_fewshot=lambda: None,
    )


def test_manual_reset_requires_binding_and_rebuilds_realtime_context(monkeypatch):
    conn = _connection()
    monkeypatch.setattr(
        handler_module,
        "get_connection",
        lambda device_id: conn if device_id == "device-a" else None,
    )

    response = asyncio.run(
        _handler().handle_reset(
            _Request({"deviceId": "device-a", "agentId": "agent-a"})
        )
    )
    payload = json.loads(response.text)

    assert response.status == 200
    assert payload["data"] == {
        "online": True,
        "reset": True,
        "mode": "realtime",
        "removedMessages": 4,
        "upstreamResetScheduled": False,
        "upstreamResetCompleted": True,
    }
    assert conn.dialogue.cleared is True
    assert conn.conversation_router.reset_completed is True


def test_manual_reset_rejects_busy_or_cross_agent_connection(monkeypatch):
    conn = _connection(busy=True)
    monkeypatch.setattr(handler_module, "get_connection", lambda _device_id: conn)

    busy = asyncio.run(
        _handler().handle_reset(
            _Request({"deviceId": "device-a", "agentId": "agent-a"})
        )
    )
    wrong_agent = asyncio.run(
        _handler().handle_reset(
            _Request({"deviceId": "device-a", "agentId": "agent-b"})
        )
    )

    assert busy.status == 409
    assert wrong_agent.status == 403
    assert conn.dialogue.cleared is False


def test_manual_reset_does_not_rebuild_realtime_during_protected_state(monkeypatch):
    conn = _connection()
    conn.meeting_minutes = SimpleNamespace(is_active=lambda: True)
    monkeypatch.setattr(handler_module, "get_connection", lambda _device_id: conn)

    response = asyncio.run(
        _handler().handle_reset(
            _Request({"deviceId": "device-a", "agentId": "agent-a"})
        )
    )

    assert response.status == 409
    assert not hasattr(conn.conversation_router, "reset_completed")
    assert conn.dialogue.cleared is False


def test_manual_reset_rejects_bad_secret_and_offline_device(monkeypatch):
    monkeypatch.setattr(handler_module, "get_connection", lambda _device_id: None)

    unauthorized = asyncio.run(
        _handler().handle_reset(
            _Request(
                {"deviceId": "device-a", "agentId": "agent-a"}, token="wrong"
            )
        )
    )
    offline = asyncio.run(
        _handler().handle_reset(
            _Request({"deviceId": "device-a", "agentId": "agent-a"})
        )
    )

    assert unauthorized.status == 401
    assert offline.status == 409


def test_manual_reset_uses_constant_time_secret_comparison(monkeypatch):
    compared = []

    def compare_digest(left, right):
        compared.append((left, right))
        return left == right

    monkeypatch.setattr(handler_module.hmac, "compare_digest", compare_digest)

    assert _handler()._is_authorized(
        _Request({}, token="manager-secret")
    ) is True
    assert compared == [
        ("manager-secret", "server-secret"),
        ("manager-secret", "manager-secret"),
    ]
