import asyncio
import base64
import json
from datetime import datetime
from typing import Any, Dict

from config.manage_api_client import (
    download_device_face_profile,
    download_published_face_profile,
    get_device_face_profiles,
    report_device_face_recognition,
    sync_agent_face_profiles,
)

TAG = __name__
MAX_PROFILE_COUNT = 100
MAX_IMAGE_BYTES = 768 * 1024
MAX_TOTAL_BYTES = 12 * 1024 * 1024


def face_protocol_version(conn) -> int:
    features = getattr(conn, "features", None) or {}
    feature = features.get("face_profiles")
    if isinstance(feature, dict):
        try:
            return max(0, int(feature.get("version", 1)))
        except (TypeError, ValueError):
            return 1
    if features.get("face_profiles_v2") is True:
        return 2
    return 1 if feature is True else 0


def schedule_face_profile_sync(conn) -> None:
    """绑定完成后只宣告 v2 可同步；旧协议继续兼容一次全量同步。"""
    if face_protocol_version(conn) <= 0 or not getattr(conn, "read_config_from_api", False):
        return
    existing = getattr(conn, "_face_profile_sync_task", None)
    if existing is not None and not existing.done():
        return
    conn._face_profile_sync_task = asyncio.create_task(
        _wait_until_ready(conn),
        name=f"face-profile-sync-{getattr(conn, 'session_id', 'unknown')}",
    )


async def _wait_until_ready(conn) -> None:
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
    if face_protocol_version(conn) >= 2:
        await _send_json(
            conn,
            {
                "type": "face_profiles",
                "event": "sync_ready",
                "protocolVersion": 2,
                "agentId": str((getattr(conn, "config", {}) or {}).get("agent_id") or ""),
            },
        )
        return
    await sync_face_profiles(conn)


async def sync_face_profiles(conn) -> None:
    """旧 v1 兼容：设备未实现本地版本协商时仍执行一次全量同步。"""
    mac_address = str(getattr(conn, "device_id", "") or "").strip()
    if not mac_address:
        return
    try:
        manifest = await get_device_face_profiles(mac_address) or {}
        profiles = manifest.get("profiles") if isinstance(manifest.get("profiles"), list) else []
        profiles = profiles[:50]
        await _send_json(
            conn,
            {
                "type": "face_profiles",
                "event": "manifest",
                "syncVersion": manifest.get("syncVersion", 0),
                "profiles": [_public_profile(item) for item in profiles if isinstance(item, dict)],
            },
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
            await _send_json(
                conn,
                {
                    "type": "face_profiles",
                    "event": "upsert",
                    **_public_profile(item),
                    "imageBase64": base64.b64encode(image).decode("ascii"),
                },
            )
            sent_count += 1
        await _send_json(
            conn,
            {
                "type": "face_profiles",
                "event": "complete",
                "syncVersion": manifest.get("syncVersion", 0),
                "profileCount": sent_count,
            },
        )
        conn.logger.bind(tag=TAG).info(
            "旧协议设备人脸资源同步完成: deviceId={}, profileCount={}, totalBytes={}".format(
                mac_address, sent_count, total_bytes
            )
        )
    except Exception as error:
        conn.logger.bind(tag=TAG).warning(
            "旧协议设备人脸资源同步失败: deviceId={}, errorType={}".format(
                mac_address, type(error).__name__
            )
        )
        await _send_status(conn, "sync_failed", error_code="server_unavailable")


async def handle_face_profile_message(conn, message: Dict[str, Any]) -> None:
    if face_protocol_version(conn) < 2:
        await _send_status(conn, "sync_failed", error_code="protocol_not_supported")
        return
    if message.get("event") == "applied":
        conn.logger.bind(tag=TAG).info(
            "设备人脸增量已持久化: deviceId={}, releaseVersion={}".format(
                getattr(conn, "device_id", ""), message.get("releaseVersion")
            )
        )
        return
    if message.get("event") != "sync_request":
        await _send_status(conn, "sync_failed", error_code="invalid_event")
        return
    mac_address = str(getattr(conn, "device_id", "") or "").strip()
    if not mac_address:
        await _send_status(conn, "sync_failed", error_code="device_not_ready")
        return
    cached_profiles = _normalize_cached_profiles(message.get("profiles"))
    if cached_profiles is None:
        await _send_status(conn, "sync_failed", error_code="invalid_inventory")
        return
    try:
        release_version = max(0, int(message.get("releaseVersion", 0)))
    except (TypeError, ValueError):
        await _send_status(conn, "sync_failed", error_code="invalid_release_version")
        return
    payload = {
        "macAddress": mac_address,
        "cachedAgentId": str(message.get("agentId") or "")[:32] or None,
        "releaseVersion": release_version,
        "profiles": cached_profiles,
    }
    try:
        plan = await sync_agent_face_profiles(payload) or {}
        if plan.get("unchanged") is True:
            await _send_json(
                conn,
                {
                    "type": "face_profiles",
                    "event": "unchanged",
                    "protocolVersion": 2,
                    "agentId": plan.get("agentId"),
                    "releaseVersion": plan.get("releaseVersion", 0),
                },
            )
            return
        prepared, total_bytes = await _prepare_upserts(mac_address, plan.get("upserts"))
        deletes = [
            str(person_id) for person_id in (plan.get("deletes") or [])
            if _valid_identifier(str(person_id))
        ][:MAX_PROFILE_COUNT]
        await _send_json(
            conn,
            {
                "type": "face_profiles",
                "event": "sync_plan",
                "protocolVersion": 2,
                "agentId": plan.get("agentId"),
                "releaseVersion": plan.get("releaseVersion", 0),
                "upserts": [_public_profile(item) for item, _image in prepared],
                "deletes": deletes,
            },
        )
        for item, image in prepared:
            await _send_json(
                conn,
                {
                    "type": "face_profiles",
                    "event": "upsert",
                    "protocolVersion": 2,
                    **_public_profile(item),
                    "assetId": item.get("assetId"),
                    "imageBase64": base64.b64encode(image).decode("ascii"),
                },
            )
        await _send_json(
            conn,
            {
                "type": "face_profiles",
                "event": "complete",
                "protocolVersion": 2,
                "agentId": plan.get("agentId"),
                "releaseVersion": plan.get("releaseVersion", 0),
                "upsertCount": len(prepared),
                "deleteCount": len(deletes),
            },
        )
        conn.logger.bind(tag=TAG).info(
            "设备人脸增量已发送: deviceId={}, upserts={}, deletes={}, totalBytes={}".format(
                mac_address, len(prepared), len(deletes), total_bytes
            )
        )
    except Exception as error:
        conn.logger.bind(tag=TAG).warning(
            "设备人脸增量同步失败: deviceId={}, errorType={}".format(
                mac_address, type(error).__name__
            )
        )
        await _send_status(conn, "sync_failed", error_code="server_unavailable")


async def notify_face_release_available(conn) -> bool:
    if face_protocol_version(conn) < 2:
        return False
    try:
        await _send_json(
            conn,
            {
                "type": "face_profiles",
                "event": "sync_available",
                "protocolVersion": 2,
                "agentId": str((getattr(conn, "config", {}) or {}).get("agent_id") or ""),
            },
        )
        return True
    except Exception:
        return False


async def _prepare_upserts(mac_address: str, raw_upserts):
    upserts = raw_upserts if isinstance(raw_upserts, list) else []
    prepared = []
    total_bytes = 0
    for item in upserts[:MAX_PROFILE_COUNT]:
        if not isinstance(item, dict):
            continue
        person_id = str(item.get("personId") or "").strip()
        asset_id = str(item.get("assetId") or "").strip()
        if not _valid_identifier(person_id) or not _valid_identifier(asset_id):
            raise ValueError("invalid profile identity")
        image = await download_published_face_profile(mac_address, person_id, asset_id)
        if not image or len(image) > MAX_IMAGE_BYTES:
            raise ValueError("invalid profile image")
        total_bytes += len(image)
        if total_bytes > MAX_TOTAL_BYTES:
            raise ValueError("profile payload too large")
        prepared.append((item, image))
    return prepared, total_bytes


def _normalize_cached_profiles(value):
    if value is None:
        return []
    if not isinstance(value, list) or len(value) > MAX_PROFILE_COUNT:
        return None
    result = []
    seen = set()
    for item in value:
        if not isinstance(item, dict):
            return None
        person_id = str(item.get("personId") or "").strip()
        sha256 = str(item.get("faceSha256") or "").strip().lower()
        try:
            version = max(0, int(item.get("faceVersion", 0)))
        except (TypeError, ValueError):
            return None
        if not _valid_identifier(person_id) or person_id in seen:
            return None
        if sha256 and (len(sha256) != 64 or any(char not in "0123456789abcdef" for char in sha256)):
            return None
        seen.add(person_id)
        result.append(
            {
                "personId": person_id,
                "name": _optional_text(item.get("name"), 30),
                "groupName": _optional_text(item.get("groupName"), 60),
                "description": _optional_text(item.get("description"), 200),
                "recognitionAction": _optional_text(item.get("recognitionAction"), 32),
                "faceVersion": version,
                "faceSha256": sha256 or None,
            }
        )
    return result


async def handle_face_recognition_event(conn, message: Dict[str, Any]) -> None:
    if face_protocol_version(conn) <= 0:
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
        await _send_json(
            conn,
            {
                "type": "face_recognition",
                "event": "action",
                "eventId": event_id,
                "personId": person_id,
                "personName": action.get("personName"),
                "description": action.get("description"),
                "actionType": action.get("actionType", "NONE"),
                "text": action.get("text"),
                "duplicate": bool(action.get("duplicate", False)),
            },
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
        "description": item.get("description"),
        "faceVersion": item.get("faceVersion", 0),
        "faceSha256": item.get("faceSha256"),
        "contentType": item.get("contentType", "image/jpeg"),
        "recognitionAction": item.get("recognitionAction", "NONE"),
    }


def _valid_identifier(value: str) -> bool:
    return 1 <= len(value) <= 64 and all(char.isalnum() or char in "_-" for char in value)


def _optional_text(value: Any, max_length: int):
    """Normalize optional cached metadata without trusting arbitrary payloads."""
    if value is None:
        return None
    if not isinstance(value, str):
        return None
    value = value.strip()
    return value[:max_length] if value else None


async def _send_json(conn, payload: Dict[str, Any]) -> None:
    await conn.websocket.send(json.dumps(payload, ensure_ascii=False))


async def _send_status(conn, event: str, *, error_code: str) -> None:
    try:
        await _send_json(conn, {"type": "face_profiles", "event": event, "errorCode": error_code})
    except Exception:
        return


async def _send_recognition_error(conn, message: Dict[str, Any], error_code: str) -> None:
    try:
        await _send_json(
            conn,
            {
                "type": "face_recognition",
                "event": "error",
                "eventId": message.get("eventId"),
                "errorCode": error_code,
            },
        )
    except Exception:
        return
