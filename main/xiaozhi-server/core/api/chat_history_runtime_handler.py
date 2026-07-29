"""Authenticated manager-api bridge for chat-history runtime refreshes."""

import json

from aiohttp import web

from core.api.base_handler import BaseHandler
from core.device_runtime_registry import get_connections_for_agent

TAG = __name__


class ChatHistoryRuntimeHandler(BaseHandler):
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

    async def handle_refresh(self, request):
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

        agent_id = str(body.get("agentId") or "").strip()
        value = body.get("chatHistoryConf")
        if not agent_id:
            return self._json_response(
                {"success": False, "message": "缺少agentId"}, 400, request
            )
        if isinstance(value, bool) or not isinstance(value, int) or value not in (0, 1, 2):
            return self._json_response(
                {"success": False, "message": "chatHistoryConf必须为0、1或2"},
                400,
                request,
            )

        connections = get_connections_for_agent(agent_id)
        updated = 0
        for conn in connections:
            report_manager = getattr(conn, "report_manager", None)
            if report_manager is None:
                continue
            if report_manager.update_chat_history_config(conn, value):
                updated += 1

        self.logger.bind(tag=TAG).info(
            "聊天记录配置在线刷新: agentId={}, online={}, updated={}, mode={}",
            agent_id,
            len(connections),
            updated,
            value,
        )
        return self._json_response(
            {
                "success": True,
                "data": {
                    "online": len(connections),
                    "updated": updated,
                    "chatHistoryConf": value,
                },
            },
            request=request,
        )
