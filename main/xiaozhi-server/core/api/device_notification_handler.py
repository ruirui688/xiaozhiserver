"""Authenticated manager-api bridge for proactive device announcements."""

import json

from aiohttp import web

from core.api.base_handler import BaseHandler
from core.device_runtime_registry import get_connections_for_agent
from core.spoken_notifications import enqueue_spoken_notification

TAG = __name__


class DeviceNotificationHandler(BaseHandler):
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
