import asyncio
import json
from types import SimpleNamespace

from config import settings

settings.config_file_valid = True

from core import reception
from core.handle.textMessageHandlerRegistry import TextMessageHandlerRegistry


class _WebSocket:
    def __init__(self):
        self.messages = []

    async def send(self, value):
        self.messages.append(json.loads(value))


class _Logger:
    def bind(self, **_kwargs):
        return self

    def warning(self, *_args, **_kwargs):
        pass


def _connection():
    return SimpleNamespace(
        device_id="AA:BB:CC:DD:EE:FF",
        features={"reception_v1": True},
        websocket=_WebSocket(),
        logger=_Logger(),
    )


def _event(count):
    return {
        "type": "reception",
        "event": "group_identified",
        "version": 1,
        "eventId": "event-1",
        "groupSessionId": "group-1",
        "presentCount": count,
        "recognized": [
            {"personId": f"person-{index}", "confidence": 0.9}
            for index in range(1, count + 1)
        ],
    }


def test_four_people_query_one_representative(monkeypatch):
    captured = {}

    async def context(payload):
        captured.update(payload)
        return {
            "companyName": "示例公司",
            "companyIntroText": "我们提供机器人服务。",
            "productsJson": '[{"name":"招牌套餐","price":88}]',
            "orderQrContent": "https://order.example/desk",
            "people": [
                {
                    "personId": "person-1",
                    "personName": "王先生",
                    "customerStage": "LONG_TERM",
                    "appointment": True,
                    "hobbiesJson": '["咖啡"]',
                }
            ]
        }

    monkeypatch.setattr(reception, "get_reception_context", context)
    conn = _connection()
    asyncio.run(reception.handle_reception_event(conn, _event(4)))

    assert captured["macAddress"] == conn.device_id
    assert captured["personIds"] == ["person-1"]
    assert conn.websocket.messages[-1]["welcomeText"] == "王先生，欢迎回来。"
    assert conn.websocket.messages[-1]["appointment"] is True
    assert conn.websocket.messages[-1]["companyIntroText"] == "我们提供机器人服务。"
    assert "王先生，欢迎回来" in conn.websocket.messages[-1]["spokenText"]
    assert "咖啡" in conn.websocket.messages[-1]["spokenText"]
    assert "确认了今天的预约" in conn.websocket.messages[-1]["spokenText"]
    assert conn.websocket.messages[-1]["orderQrAvailable"] is True
    assert conn.websocket.messages[-1]["conversationContext"]["products"][0]["name"] == "招牌套餐"


def test_three_people_query_all(monkeypatch):
    async def context(payload):
        return {
            "people": [
                {"personId": person_id, "personName": person_id, "customerStage": "NEW"}
                for person_id in payload["personIds"]
            ]
        }

    monkeypatch.setattr(reception, "get_reception_context", context)
    conn = _connection()
    asyncio.run(reception.handle_reception_event(conn, _event(3)))

    assert conn.websocket.messages[-1]["selectedPersonIds"] == [
        "person-1", "person-2", "person-3"
    ]


def test_reception_handler_is_registered():
    assert "reception" in TextMessageHandlerRegistry().get_supported_types()


def test_audit_failure_does_not_emit_second_error_after_action(monkeypatch):
    async def context(_payload):
        return {
            "people": [
                {
                    "personId": "person-1",
                    "personName": "王先生",
                    "customerStage": "NEW",
                }
            ]
        }

    async def failed_audit(_payload):
        raise RuntimeError("database unavailable")

    monkeypatch.setattr(reception, "get_reception_context", context)
    monkeypatch.setattr(reception, "report_reception_event", failed_audit)
    conn = _connection()
    asyncio.run(reception.handle_reception_event(conn, _event(1)))

    assert [message["event"] for message in conn.websocket.messages] == ["action"]
