import asyncio
import json
import queue
import threading
from types import SimpleNamespace

import config.settings

config.settings.config_file_valid = True

import core.api.device_notification_handler as handler_module
from core.api.device_notification_handler import DeviceNotificationHandler


class _Request:
    def __init__(self, body, token="server-secret"):
        self._body = body
        self.headers = {"Authorization": f"Bearer {token}"}

    async def json(self):
        return self._body


class _TTS:
    def __init__(self):
        self.tts_text_queue = queue.Queue()
        self.tts_audio_queue = queue.Queue()
        self.stored = []

    def store_tts_text(self, sentence_id, text):
        self.stored.append((sentence_id, text))

    def tts_one_sentence(self, *_args, **_kwargs):
        return None


class _Logger:
    def __init__(self):
        self.calls = []

    def bind(self, **_kwargs):
        return self

    def info(self, message, *args):
        self.calls.append(("info", message, args))

    def warning(self, message, *args):
        self.calls.append(("warning", message, args))


def _handler():
    handler = DeviceNotificationHandler(
        {
            "server": {"auth_key": "server-secret"},
            "manager-api": {"secret": "manager-secret"},
        }
    )
    handler.logger = _Logger()
    return handler


def _connection(agent_id="agent-a", **overrides):
    values = {
        "device_id": "AA:BB:CC:DD:EE:20",
        "config": {"agent_id": agent_id},
        "tts": _TTS(),
        "meeting_minutes": None,
        "voiceprint_enrollment": None,
        "client_is_speaking": False,
        "client_have_voice": False,
        "client_abort": False,
        "_abort_lock": threading.Lock(),
        "sentence_id": "",
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def _body(**overrides):
    value = {
        "deviceId": "AA:BB:CC:DD:EE:20",
        "agentId": "agent-a",
        "requestId": "reminder-delivery-20",
        "content": "现在两点了，该开会了。",
    }
    value.update(overrides)
    return value


def _payload(response):
    return json.loads(response.text)


def test_deliver_targets_one_bound_device_and_is_idempotent(monkeypatch):
    conn = _connection()
    looked_up = []

    def get_connection(device_id):
        looked_up.append(device_id)
        return conn

    monkeypatch.setattr(handler_module, "get_connection", get_connection)
    handler = _handler()

    first = asyncio.run(handler.handle_deliver(_Request(_body())))
    duplicate = asyncio.run(handler.handle_deliver(_Request(_body())))

    assert first.status == 200
    assert _payload(first)["data"] == {"status": "queued"}
    assert _payload(duplicate)["data"] == {"status": "duplicate"}
    assert looked_up == ["AA:BB:CC:DD:EE:20", "AA:BB:CC:DD:EE:20"]
    assert len(conn.tts.stored) == 1
    assert conn.tts.stored[0][1] == "现在两点了，该开会了。"
    assert all("现在两点" not in str(call) for call in handler.logger.calls)


def test_deliver_rejects_client_alias_collision_before_queueing(monkeypatch):
    wrong_conn = _connection(device_id="11:22:33:44:55:66")
    monkeypatch.setattr(
        handler_module,
        "get_connection",
        lambda _device_id: wrong_conn,
    )

    response = asyncio.run(_handler().handle_deliver(_Request(_body())))

    assert response.status == 409
    assert _payload(response) == {
        "success": False,
        "message": "设备连接身份不匹配",
    }
    assert wrong_conn.tts.stored == []


def test_deliver_accepts_case_and_colon_underscore_device_equivalence(monkeypatch):
    conn = _connection(device_id="AA:BB:CC:DD:EE:20")
    monkeypatch.setattr(handler_module, "get_connection", lambda _device_id: conn)

    response = asyncio.run(
        _handler().handle_deliver(
            _Request(
                _body(
                    deviceId="aa_bb_cc_dd_ee_20",
                    requestId="normalized-device-id",
                )
            )
        )
    )

    assert response.status == 200
    assert _payload(response)["data"] == {"status": "queued"}
    assert len(conn.tts.stored) == 1


def test_deliver_returns_offline_and_exact_retryable_statuses(monkeypatch):
    handler = _handler()
    monkeypatch.setattr(handler_module, "get_connection", lambda _device_id: None)

    offline = asyncio.run(handler.handle_deliver(_Request(_body())))
    assert _payload(offline)["data"] == {"status": "offline"}

    conn = _connection(client_have_voice=True)
    monkeypatch.setattr(handler_module, "get_connection", lambda _device_id: conn)
    busy = asyncio.run(
        handler.handle_deliver(
            _Request(_body(requestId="reminder-delivery-busy"))
        )
    )
    assert _payload(busy)["data"] == {"status": "device_busy"}

    conn.client_have_voice = False
    conn.meeting_minutes = SimpleNamespace(is_active=lambda: True)
    meeting = asyncio.run(
        handler.handle_deliver(
            _Request(_body(requestId="reminder-delivery-meeting"))
        )
    )
    assert _payload(meeting)["data"] == {"status": "meeting_active"}

    conn.meeting_minutes = None
    conn.tts = None
    unavailable = asyncio.run(
        handler.handle_deliver(
            _Request(_body(requestId="reminder-delivery-tts"))
        )
    )
    assert _payload(unavailable)["data"] == {"status": "tts_unavailable"}


def test_deliver_rejects_cross_agent_invalid_body_and_bad_secret(monkeypatch):
    monkeypatch.setattr(
        handler_module, "get_connection", lambda _device_id: _connection("agent-other")
    )
    handler = _handler()

    cross_agent = asyncio.run(handler.handle_deliver(_Request(_body())))
    invalid = asyncio.run(
        handler.handle_deliver(_Request(_body(content="x" * 501)))
    )
    missing_required = asyncio.run(
        handler.handle_deliver(_Request(_body(requestId=None, content=None)))
    )
    unauthorized = asyncio.run(
        handler.handle_deliver(_Request(_body(), token="wrong"))
    )

    assert cross_agent.status == 403
    assert invalid.status == 400
    assert missing_required.status == 400
    assert unauthorized.status == 401


def test_deliver_auth_uses_constant_time_comparison(monkeypatch):
    compared = []

    def compare_digest(left, right):
        compared.append((left, right))
        return left == right

    monkeypatch.setattr(handler_module.hmac, "compare_digest", compare_digest)

    assert _handler()._is_authorized(
        _Request({}, token="manager-secret")
    ) is True
    assert compared == [
        (b"manager-secret", b"server-secret"),
        (b"manager-secret", b"manager-secret"),
    ]


def test_deliver_rejects_non_ascii_bearer_token_without_error():
    response = asyncio.run(
        _handler().handle_deliver(_Request(_body(), token="错误令牌🔑"))
    )

    assert response.status == 401
    assert _payload(response) == {"success": False, "message": "未授权"}


def test_deliver_rejects_non_ascii_cross_agent_identity_without_error(monkeypatch):
    monkeypatch.setattr(
        handler_module, "get_connection", lambda _device_id: _connection("agent-a")
    )

    response = asyncio.run(
        _handler().handle_deliver(_Request(_body(agentId="错误智能体")))
    )

    assert response.status == 403
    assert _payload(response) == {
        "success": False,
        "message": "设备与智能体绑定关系不匹配",
    }
