import asyncio
import base64
import json
from datetime import datetime
from typing import Any, Dict

from config.manage_api_client import (
    download_device_face_profile,
    get_device_face_profiles,
    report_device_face_recognition,
)

TAG = __name__
MAX_PROFILE_COUNT = 50
MAX_IMAGE_BYTES = 768 * 1024
MAX_TOTAL_BYTES = 12 * 1024 * 1024


def schedule_face_profile_sync(conn) -> None:
    """在 hello 声明能力后安排同步，等待后台完成设备绑定判定。"""
    features = getattr(conn, "features", None) or {}
    if not features.get("face_profiles") or not getattr(conn, "read_config_from_api", False):
        return
    existing = getattr(conn, "_face_profile_sync_task", None)
    if existing is not None and not existing.done():
        return
    conn._face_profile_sync_task = asyncio.create_task(
        _wait_and_sync(conn), name=f"face-profile-sync-{getattr(conn, 'session_id', 'unknown')}"
    )


async def _wait_and_sync(conn) -> None:
    for _ in range(100):
        if getattr(conn, "closing", False) or getattr(conn, "closed", False):
            return
        binding_ready = getattr(conn, "binding_ready", None)
        if binding_ready is not None and binding_ready.is_set():
            break
        await asyncio.sleep(0.1)
    else:
        await _send_status(conn, "sync_failed", error_code="binding_timeout")
        return
    if getattr(conn, "need_bind", True) or getattr(conn, "initialization_failed", False):
        return
    await sync_face_profiles(conn)


async def sync_face_profiles(conn) -> None:
    mac_address = str(getattr(conn, "device_id", "") or "").strip()
    if not mac_address:
        return
    try:
        manifest = await get_device_face_profiles(mac_address) or {}
        raw_profiles = manifest.get("profiles")
        profiles = raw_profiles if isinstance(raw_profiles, list) else []
        profiles = profiles[:MAX_PROFILE_COUNT]
        public_profiles = [_public_profile(item) for item in profiles if isinstance(item, dict)]
        await conn.websocket.send(
            json.dumps(
                {
                    "type": "face_profiles",
                    "event": "manifest",
                    "syncVersion": manifest.get("syncVersion", 0),
                    "profiles": public_profiles,
                },
                ensure_ascii=False,
            )
        )

        total_bytes = 0
        sent_count = 0
        for item in profiles:
            if not isinstance(item, dict):
                continue
            person_id = str(item.get("personId") or "").strip()
            if not _valid_identifier(person_id):
                continue
            image = await download_device_face_profile(mac_address, person_id)
            if not image or len(image) > MAX_IMAGE_BYTES:
                continue
            total_bytes += len(image)
            if total_bytes > MAX_TOTAL_BYTES:
                break
            await conn.websocket.send(
                json.dumps(
                    {
                        "type": "face_profiles",
                        "event": "upsert",
                        **_public_profile(item),
                        "imageBase64": base64.b64encode(image).decode("ascii"),
                    },
                    ensure_ascii=False,
                )
            )
            sent_count += 1
        await conn.websocket.send(
            json.dumps(
                {
                    "type": "face_profiles",
                    "event": "complete",
                    "syncVersion": manifest.get("syncVersion", 0),
                    "profileCount": sent_count,
                },
                ensure_ascii=False,
            )
        )
        conn.logger.bind(tag=TAG).info(
            "设备人脸资源同步完成: deviceId={}, profileCount={}, totalBytes={}".format(
                mac_address, sent_count, total_bytes
            )
        )
    except Exception as error:
        conn.logger.bind(tag=TAG).warning(
            "设备人脸资源同步失败: deviceId={}, errorType={}".format(
                mac_address, type(error).__name__
            )
        )
        await _send_status(conn, "sync_failed", error_code="server_unavailable")


async def handle_face_recognition_event(conn, message: Dict[str, Any]) -> None:
    features = getattr(conn, "features", None) or {}
    if not features.get("face_profiles"):
        await _send_recognition_error(conn, message, "feature_not_enabled")
        return
    event_id = str(message.get("eventId") or "").strip()
    person_id = str(message.get("personId") or "").strip()
    if not (1 <= len(event_id) <= 128) or not _valid_identifier(person_id):
        await _send_recognition_error(conn, message, "invalid_request")
        return
    try:
        confidence = float(message.get("confidence"))
    except (TypeError, ValueError):
        await _send_recognition_error(conn, message, "invalid_confidence")
        return
    if not 0.0 <= confidence <= 1.0:
        await _send_recognition_error(conn, message, "invalid_confidence")
        return
    captured_at = message.get("capturedAt")
    if captured_at is not None and not isinstance(captured_at, str):
        await _send_recognition_error(conn, message, "invalid_captured_at")
        return
    if isinstance(captured_at, str) and len(captured_at) > 40:
        await _send_recognition_error(conn, message, "invalid_captured_at")
        return

    payload = {
        "macAddress": str(getattr(conn, "device_id", "") or ""),
        "eventId": event_id,
        "personId": person_id,
        "confidence": confidence,
        "occurredAt": captured_at or datetime.now().astimezone().isoformat(timespec="milliseconds"),
    }
    try:
        action = await report_device_face_recognition(payload) or {}
        await conn.websocket.send(
            json.dumps(
                {
                    "type": "face_recognition",
                    "event": "action",
                    "eventId": event_id,
                    "personId": person_id,
                    "personName": action.get("personName"),
                    "actionType": action.get("actionType", "NONE"),
                    "text": action.get("text"),
                    "duplicate": bool(action.get("duplicate", False)),
                },
                ensure_ascii=False,
            )
        )
        conn.logger.bind(tag=TAG).info(
            "设备人脸识别事件已处理: deviceId={}, eventId={}, personId={}, actionType={}".format(
                payload["macAddress"], event_id, person_id, action.get("actionType", "NONE")
            )
        )
    except Exception as error:
        conn.logger.bind(tag=TAG).warning(
            "设备人脸识别事件上报失败: deviceId={}, eventId={}, errorType={}".format(
                payload["macAddress"], event_id, type(error).__name__
            )
        )
        await _send_recognition_error(conn, message, "server_unavailable")


def _public_profile(item: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "personId": item.get("personId"),
        "name": item.get("name"),
        "groupName": item.get("groupName"),
        "faceVersion": item.get("faceVersion", 0),
        "faceSha256": item.get("faceSha256"),
        "contentType": item.get("contentType", "image/jpeg"),
        "recognitionAction": item.get("recognitionAction", "NONE"),
    }


def _valid_identifier(value: str) -> bool:
    return 1 <= len(value) <= 64 and all(char.isalnum() or char in "_-" for char in value)


async def _send_status(conn, event: str, *, error_code: str) -> None:
    try:
        await conn.websocket.send(
            json.dumps(
                {"type": "face_profiles", "event": event, "errorCode": error_code},
                ensure_ascii=False,
            )
        )
    except Exception:
        return


async def _send_recognition_error(conn, message: Dict[str, Any], error_code: str) -> None:
    try:
        await conn.websocket.send(
            json.dumps(
                {
                    "type": "face_recognition",
                    "event": "error",
                    "eventId": message.get("eventId"),
                    "errorCode": error_code,
                },
                ensure_ascii=False,
            )
        )
    except Exception:
        return
