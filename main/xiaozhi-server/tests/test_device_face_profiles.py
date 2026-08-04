import asyncio
import json
from types import SimpleNamespace

from config import settings

settings.config_file_valid = True

from core import device_face_profiles
from core.handle.textMessageHandlerRegistry import TextMessageHandlerRegistry


class _Logger:
    def bind(self, **_kwargs):
        return self

    def info(self, *_args, **_kwargs):
        pass

    def warning(self, *_args, **_kwargs):
        pass


class _WebSocket:
    def __init__(self):
        self.messages = []

    async def send(self, value):
        self.messages.append(json.loads(value))


def _connection():
    return SimpleNamespace(
        device_id="AA:BB:CC:DD:EE:FF",
        websocket=_WebSocket(),
        logger=_Logger(),
        features={"face_profiles": True},
        read_config_from_api=True,
        closing=False,
        closed=False,
        need_bind=False,
        initialization_failed=False,
    )


def test_sync_sends_manifest_bounded_images_and_completion(monkeypatch):
    async def manifest(_mac):
        return {
            "syncVersion": 7,
            "profiles": [
                {
                    "personId": "person1",
                    "name": "访客一",
                    "faceVersion": 2,
                    "faceSha256": "abc",
                    "contentType": "image/jpeg",
                    "recognitionAction": "SPEAK_TEXT",
                }
            ],
        }

    async def image(_mac, _person):
        return b"jpeg-bytes"

    monkeypatch.setattr(device_face_profiles, "get_device_face_profiles", manifest)
    monkeypatch.setattr(device_face_profiles, "download_device_face_profile", image)
    conn = _connection()

    asyncio.run(device_face_profiles.sync_face_profiles(conn))

    assert [item["event"] for item in conn.websocket.messages] == ["manifest", "upsert", "complete"]
    assert conn.websocket.messages[1]["personId"] == "person1"
    assert "imageBase64" in conn.websocket.messages[1]
    assert conn.websocket.messages[2]["profileCount"] == 1


def test_recognition_uses_authenticated_connection_device_id(monkeypatch):
    captured = {}

    async def report(payload):
        captured.update(payload)
        return {
            "personName": "访客一",
            "actionType": "SPEAK_TEXT",
            "text": "欢迎回来",
            "duplicate": False,
        }

    monkeypatch.setattr(device_face_profiles, "report_device_face_recognition", report)
    conn = _connection()

    asyncio.run(
        device_face_profiles.handle_face_recognition_event(
            conn,
            {
                "type": "face_recognition",
                "eventId": "event-1",
                "personId": "person1",
                "confidence": 0.92,
                "macAddress": "FORGED",
            },
        )
    )

    assert captured["macAddress"] == conn.device_id
    assert conn.websocket.messages[-1]["event"] == "action"
    assert conn.websocket.messages[-1]["text"] == "欢迎回来"


def test_face_recognition_handler_is_registered():
    assert "face_recognition" in TextMessageHandlerRegistry().get_supported_types()
