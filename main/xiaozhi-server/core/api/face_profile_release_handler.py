"""Authenticated bridge that announces an explicitly published face-library version."""

import asyncio
import hmac
import json

from aiohttp import web

from core.api.base_handler import BaseHandler
from core.device_face_profiles import notify_face_release_available
from core.device_runtime_registry import get_connections_for_agent


class FaceProfileReleaseHandler(BaseHandler):
    def _json_response(self, body, status=200, request=None):
        response = web.Response(
            text=json.dumps(body, ensure_ascii=False),
            content_type="application/json",
            status=status,
        )
        self._add_cors_headers(response, request)
        return response

    def _is_authorized(self, request):
        auth_header = request.headers.get("Authorization", "")
        if not auth_header.startswith("Bearer "):
            return False
        token = auth_header[7:]
        allowed = {
            self.config.get("server", {}).get("auth_key", ""),
            self.config.get("manager-api", {}).get("secret", ""),
        }
        return bool(token and any(
            candidate and hmac.compare_digest(token, candidate)
            for candidate in allowed
        ))

    async def handle_release(self, request):
        if not self._is_authorized(request):
            return self._json_response({"success": False, "message": "未授权"}, 401, request)
        try:
            body = await request.json()
        except Exception:
            return self._json_response(
                {"success": False, "message": "请求体不是有效JSON"}, 400, request
            )
        agent_ids = body.get("agentIds")
        if not isinstance(agent_ids, list):
            return self._json_response(
                {"success": False, "message": "agentIds参数无效"}, 400, request
            )
        agent_ids = list(dict.fromkeys(
            str(agent_id or "").strip() for agent_id in agent_ids
            if 0 < len(str(agent_id or "").strip()) <= 32
        ))
        if not agent_ids or len(agent_ids) > 500:
            return self._json_response(
                {"success": False, "message": "agentIds参数无效"}, 400, request
            )
        connections = []
        seen = set()
        for agent_id in agent_ids:
            for conn in get_connections_for_agent(agent_id):
                if id(conn) not in seen:
                    seen.add(id(conn))
                    connections.append(conn)
        results = await asyncio.gather(
            *(notify_face_release_available(conn) for conn in connections),
            return_exceptions=True,
        )
        notified = sum(result is True for result in results)
        self.logger.bind(tag=__name__).info(
            "在线设备人脸发布提示已发送: agentCount={}, online={}, notified={}",
            len(agent_ids), len(connections), notified,
        )
        return self._json_response(
            {"success": True, "data": {"online": len(connections), "notified": notified}},
            request=request,
        )
