import json

from aiohttp import web

from core.api.base_handler import BaseHandler
from core.device_runtime_registry import get_connection, registered_keys
from core.utils.voiceprint_runtime import refresh_voiceprint_provider

TAG = __name__


class VoiceprintEnrollmentHandler(BaseHandler):
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
        return bool(token and token in {server_secret, manager_secret})

    def _connection(self, device_id: str):
        conn = get_connection(device_id)
        if conn is None:
            self.logger.bind(tag=TAG).info(
                "声纹采集未命中运行时连接: deviceId={}, registeredKeys={}",
                device_id,
                sorted(registered_keys()),
            )
        return conn

    async def handle_start(self, request):
        if not self._is_authorized(request):
            return self._json_response({"success": False, "message": "未授权"}, 401, request)
        try:
            body = await request.json()
        except Exception:
            return self._json_response({"success": False, "message": "请求体不是有效JSON"}, 400, request)

        device_id = body.get("deviceId") or body.get("device-id")
        if not device_id:
            return self._json_response({"success": False, "message": "缺少deviceId"}, 400, request)

        conn = self._connection(device_id)
        if conn is None:
            return self._json_response({"success": False, "message": "设备未连接到当前运行时"}, 404, request)
        status = conn.start_voiceprint_enrollment(body)
        return self._json_response({"success": True, "data": status}, request=request)

    async def handle_stop(self, request):
        if not self._is_authorized(request):
            return self._json_response({"success": False, "message": "未授权"}, 401, request)
        try:
            body = await request.json()
        except Exception:
            body = {}
        device_id = body.get("deviceId") or body.get("device-id") or request.query.get("deviceId")
        if not device_id:
            return self._json_response({"success": False, "message": "缺少deviceId"}, 400, request)
        conn = self._connection(device_id)
        if conn is None:
            return self._json_response({"success": False, "message": "设备未连接到当前运行时"}, 404, request)
        request_id = str(body.get("requestId") or body.get("request_id") or "").strip()
        if request_id:
            current_status = conn.get_voiceprint_enrollment_status()
            if current_status.get("active") and current_status.get("requestId") != request_id:
                return self._json_response(
                    {"success": False, "message": "声纹采集任务已被更新"}, 409, request
                )
        status = await conn.finish_voiceprint_enrollment(body.get("reason") or "http_stop")
        return self._json_response({"success": True, "data": status}, request=request)

    async def handle_status(self, request):
        if not self._is_authorized(request):
            return self._json_response({"success": False, "message": "未授权"}, 401, request)
        device_id = request.query.get("deviceId") or request.query.get("device-id")
        if not device_id:
            return self._json_response({"success": False, "message": "缺少deviceId"}, 400, request)
        conn = self._connection(device_id)
        if conn is None:
            return self._json_response({"success": False, "message": "设备未连接到当前运行时"}, 404, request)
        return self._json_response(
            {"success": True, "data": conn.get_voiceprint_enrollment_status()},
            request=request,
        )

    async def handle_refresh(self, request):
        if not self._is_authorized(request):
            return self._json_response({"success": False, "message": "未授权"}, 401, request)
        try:
            body = await request.json()
        except Exception:
            return self._json_response({"success": False, "message": "请求体不是有效JSON"}, 400, request)

        device_id = body.get("deviceId") or body.get("device-id")
        if not device_id:
            return self._json_response({"success": False, "message": "缺少deviceId"}, 400, request)
        conn = self._connection(device_id)
        if conn is None:
            return self._json_response({"success": False, "message": "设备未连接到当前运行时"}, 404, request)
        try:
            result = await refresh_voiceprint_provider(conn)
        except Exception as error:
            self.logger.bind(tag=TAG).warning(
                "在线声纹配置刷新失败: deviceId={}, errorType={}",
                device_id,
                type(error).__name__,
            )
            return self._json_response(
                {"success": False, "message": "在线声纹配置刷新失败"},
                503,
                request,
            )
        return self._json_response({"success": True, "data": result}, request=request)
