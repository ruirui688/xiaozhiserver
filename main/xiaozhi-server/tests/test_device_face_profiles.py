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
                    "description": "画电路板的",
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
    assert conn.websocket.messages[1]["description"] == "画电路板的"
    assert "imageBase64" in conn.websocket.messages[1]
    assert conn.websocket.messages[2]["profileCount"] == 1


def test_recognition_uses_authenticated_connection_device_id(monkeypatch):
    captured = {}

    async def report(payload):
        captured.update(payload)
        return {
            "personName": "访客一",
            "description": "画电路板的",
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
    assert conn.websocket.messages[-1]["description"] == "画电路板的"


def test_face_recognition_handler_is_registered():
    assert "face_recognition" in TextMessageHandlerRegistry().get_supported_types()


def test_v2_matching_release_does_not_download_images(monkeypatch):
    async def sync(_payload):
        return {"agentId": "agent-a", "releaseVersion": 9, "unchanged": True}

    async def unexpected_download(*_args):
        raise AssertionError("matching version must not download images")

    monkeypatch.setattr(device_face_profiles, "sync_agent_face_profiles", sync)
    monkeypatch.setattr(device_face_profiles, "download_published_face_profile", unexpected_download)
    conn = _connection()
    conn.features = {"face_profiles": {"version": 2}}

    asyncio.run(device_face_profiles.handle_face_profile_message(conn, {
        "type": "face_profiles",
        "event": "sync_request",
        "agentId": "agent-a",
        "releaseVersion": 9,
        "profiles": [{
            "personId": "person1",
            "name": "小明",
            "groupName": "访客",
            "description": "画电路板的",
            "recognitionAction": "NONE",
            "faceVersion": 2,
            "faceSha256": "a" * 64,
        }],
    }))

    assert [item["event"] for item in conn.websocket.messages] == ["unchanged"]


def test_v2_sends_only_prepared_delta_and_atomic_completion(monkeypatch):
    async def sync(payload):
        assert payload["macAddress"] == "AA:BB:CC:DD:EE:FF"
        return {
            "agentId": "agent-a",
            "releaseVersion": 10,
            "unchanged": False,
            "upserts": [{
                "personId": "person2",
                "assetId": "asset2",
                "name": "访客二",
                "description": "测试背景",
                "faceVersion": 3,
                "faceSha256": "b" * 64,
                "contentType": "image/jpeg",
                "recognitionAction": "NONE",
            }],
            "deletes": ["person-old"],
        }

    async def image(_mac, person_id, asset_id):
        assert (person_id, asset_id) == ("person2", "asset2")
        return b"new-jpeg"

    monkeypatch.setattr(device_face_profiles, "sync_agent_face_profiles", sync)
    monkeypatch.setattr(device_face_profiles, "download_published_face_profile", image)
    conn = _connection()
    conn.features = {"face_profiles_v2": True}

    asyncio.run(device_face_profiles.handle_face_profile_message(conn, {
        "type": "face_profiles",
        "event": "sync_request",
        "agentId": "agent-a",
        "releaseVersion": 9,
        "profiles": [{"personId": "person-old", "faceVersion": 1, "faceSha256": "c" * 64}],
    }))

    assert [item["event"] for item in conn.websocket.messages] == ["sync_plan", "upsert", "complete"]
    assert conn.websocket.messages[0]["deletes"] == ["person-old"]
    assert conn.websocket.messages[-1]["releaseVersion"] == 10
    assert "imageBase64" in conn.websocket.messages[1]
    assert conn.websocket.messages[1]["description"] == "测试背景"


def test_face_profile_v2_handler_is_registered():
    assert "face_profiles" in TextMessageHandlerRegistry().get_supported_types()
