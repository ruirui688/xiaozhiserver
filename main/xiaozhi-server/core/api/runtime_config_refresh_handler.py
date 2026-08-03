"""Authenticated manager-api bridge for full agent runtime hot reloads."""

import json

from aiohttp import web

from core.api.base_handler import BaseHandler
from core.device_runtime_registry import get_connections_for_agent


class RuntimeConfigRefreshHandler(BaseHandler):
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

        agent_ids = body.get("agentIds")
        if not isinstance(agent_ids, list):
            agent_ids = [body.get("agentId")]
        agent_ids = list(dict.fromkeys(
            str(agent_id or "").strip()
            for agent_id in agent_ids
            if str(agent_id or "").strip()
        ))
        if not agent_ids or len(agent_ids) > 500:
            return self._json_response(
                {"success": False, "message": "agentIds参数无效"}, 400, request
            )

        reason = str(body.get("reason") or "manager_update")[:64]
        online = 0
        scheduled = 0
        seen = set()
        for agent_id in agent_ids:
            for conn in get_connections_for_agent(agent_id):
                identity = id(conn)
                if identity in seen:
                    continue
                seen.add(identity)
                online += 1
                reloader = getattr(conn, "runtime_config_reloader", None)
                if reloader is None:
                    continue
                reloader.request_refresh(reason)
                scheduled += 1

        self.logger.bind(tag=__name__).info(
            "智能体运行配置在线刷新已接收: agentCount={}, online={}, scheduled={}, reason={}",
            len(agent_ids),
            online,
            scheduled,
            reason,
        )
        return self._json_response(
            {
                "success": True,
                "data": {
                    "agentCount": len(agent_ids),
                    "online": online,
                    "scheduled": scheduled,
                },
            },
            request=request,
        )
