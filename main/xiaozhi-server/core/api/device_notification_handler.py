"""Authenticated manager-api bridge for proactive device announcements."""

import hmac
import json
import re

from aiohttp import web

from core.api.base_handler import BaseHandler
from core.device_runtime_registry import get_connection, get_connections_for_agent
from core.spoken_notifications import (
    configure_notification_limits,
    enqueue_spoken_notification,
)

TAG = __name__


class DeviceNotificationHandler(BaseHandler):
    def __init__(self, config: dict):
        super().__init__(config)
        configure_notification_limits(config)

    def _json_response(self, body: dict, status: int = 200, request=None):
        response = web.Response(
            text=json.dumps(body, ensure_ascii=False),
            content_type="application/json",
            status=status,
        )
        self._add_cors_headers(response, request)
        return response

    def _is_authorized(self, request) -> bool:
        auth_header = request.headers.get("Authorization", "")
        if not auth_header.startswith("Bearer "):
            return False
        token = auth_header[7:]
        server_secret = self.config.get("server", {}).get("auth_key", "")
        manager_secret = self.config.get("manager-api", {}).get("secret", "")
        token_bytes = token.encode("utf-8")
        matches = [
            bool(secret)
            and hmac.compare_digest(token_bytes, str(secret).encode("utf-8"))
            for secret in (server_secret, manager_secret)
        ]
        return bool(token) and any(matches)

    async def handle_deliver(self, request):
        """Deliver one idempotent reminder to one authenticated device connection."""
        if not self._is_authorized(request):
            return self._json_response(
                {"success": False, "message": "未授权"}, 401, request
            )
        try:
            body = await request.json()
        except Exception:
            return self._json_response(
                {"success": False, "message": "请求体不是有效JSON"}, 400, request
            )

        if not isinstance(body, dict):
            return self._json_response(
                {"success": False, "message": "请求体必须是JSON对象"}, 400, request
            )
        device_value = body.get("deviceId")
        agent_value = body.get("agentId")
        request_value = body.get("requestId")
        content_value = body.get("content")
        if not all(
            isinstance(value, str)
            for value in (device_value, agent_value, request_value, content_value)
        ):
            return self._json_response(
                {"success": False, "message": "投递字段类型无效"}, 400, request
            )
        device_id = device_value.strip()
        agent_id = agent_value.strip()
        request_id = request_value.strip()
        content = content_value.strip()
        if not (
            _valid_metadata(device_id, 128)
            and _valid_metadata(agent_id, 64)
            and _valid_metadata(request_id, 128)
            and content
            and len(content) <= 500
        ):
            return self._json_response(
                {"success": False, "message": "投递参数无效"}, 400, request
            )

        conn = get_connection(device_id)
        if conn is None:
            return self._delivery_response(
                "offline", device_id, agent_id, request_id, request=request
            )
        connected_device_id = str(getattr(conn, "device_id", "") or "").strip()
        if not _same_device_identity(device_id, connected_device_id):
            self.logger.bind(tag=TAG).warning(
                "拒绝设备别名冲突的提醒投递: requestedDeviceId={}, "
                "connectedDeviceId={}, agentId={}, requestId={}",
                device_id,
                connected_device_id,
                agent_id,
                request_id,
            )
            return self._json_response(
                {"success": False, "message": "设备连接身份不匹配"},
                409,
                request,
            )
        bound_agent_id = str(
            (getattr(conn, "config", {}) or {}).get("agent_id") or ""
        ).strip()
        if not hmac.compare_digest(
            bound_agent_id.encode("utf-8"), agent_id.encode("utf-8")
        ):
            self.logger.bind(tag=TAG).warning(
                "拒绝跨智能体设备提醒投递: deviceId={}, agentId={}, requestId={}",
                device_id,
                agent_id,
                request_id,
            )
            return self._json_response(
                {"success": False, "message": "设备与智能体绑定关系不匹配"},
                403,
                request,
            )

        try:
            accepted, reason = enqueue_spoken_notification(
                conn, content, request_id=request_id
            )
        except Exception as error:
            self.logger.bind(tag=TAG).warning(
                "设备提醒入队失败: deviceId={}, agentId={}, requestId={}, errorType={}",
                device_id,
                agent_id,
                request_id,
                type(error).__name__,
            )
            return self._delivery_response(
                "queue_error",
                device_id,
                agent_id,
                request_id,
                request=request,
            )

        status = _delivery_status(accepted, reason)
        return self._delivery_response(
            status,
            device_id,
            agent_id,
            request_id,
            request=request,
        )

    def _delivery_response(
        self,
        status,
        device_id,
        agent_id,
        request_id,
        *,
        request=None,
    ):
        self.logger.bind(tag=TAG).info(
            "设备提醒投递结果: deviceId={}, agentId={}, requestId={}, status={}",
            device_id,
            agent_id,
            request_id,
            status,
        )
        data = {"status": status}
        if status == "overloaded":
            data["retryAfterSeconds"] = 15
        return self._json_response(
            {"success": True, "data": data}, request=request
        )

    async def handle_broadcast(self, request):
        if not self._is_authorized(request):
            return self._json_response({"success": False, "message": "未授权"}, 401, request)
        try:
            body = await request.json()
        except Exception:
            return self._json_response({"success": False, "message": "请求体不是有效JSON"}, 400, request)

        agent_id = str(body.get("agentId") or "").strip()
        content = str(body.get("content") or "").strip()
        if not agent_id or not content:
            return self._json_response(
                {"success": False, "message": "缺少agentId或content"}, 400, request
            )

        announcement = f"飞书群消息：{content}"
        queued = 0
        skipped = {}
        for conn in get_connections_for_agent(agent_id):
            accepted, reason = enqueue_spoken_notification(conn, announcement)
            if accepted:
                queued += 1
            else:
                skipped[reason] = skipped.get(reason, 0) + 1

        self.logger.bind(tag=TAG).info(
            "飞书群消息设备播报: agentId={}, online={}, queued={}, skipped={}",
            agent_id,
            queued + sum(skipped.values()),
            queued,
            skipped,
        )
        return self._json_response(
            {
                "success": True,
                "data": {
                    "online": queued + sum(skipped.values()),
                    "queued": queued,
                    "skipped": skipped,
                },
            },
            request=request,
        )

    async def handle_options(self, request):
        response = web.Response(status=204)
        self._add_cors_headers(response, request)
        return response


_CONTROL_CHARACTERS = re.compile(r"[\x00-\x1f\x7f]")


def _valid_metadata(value: str, max_length: int) -> bool:
    return (
        bool(value)
        and len(value) <= max_length
        and not _CONTROL_CHARACTERS.search(value)
    )


def _same_device_identity(requested: str, connected: str) -> bool:
    """Compare canonical device IDs without accepting client-id aliases."""
    requested_bytes = requested.strip().lower().replace("_", ":").encode("utf-8")
    connected_bytes = connected.strip().lower().replace("_", ":").encode("utf-8")
    return bool(requested_bytes) and hmac.compare_digest(
        requested_bytes,
        connected_bytes,
    )


def _delivery_status(accepted: bool, reason: str) -> str:
    if accepted and reason == "duplicate":
        return "duplicate"
    if accepted:
        return "queued"
    return reason or "queue_error"
