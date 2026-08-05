"""Authenticated manager-api bridge for manual session context resets."""

import hmac
import json

from aiohttp import web

from core.api.base_handler import BaseHandler
from core.device_runtime_registry import get_connection
from core.session.conversation_context import (
    ConversationContextResetError,
    context_reset_protected_reason,
    reset_conversation_context,
)


TAG = __name__


class SessionContextHandler(BaseHandler):
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
        return bool(token) and any(
            secret and hmac.compare_digest(token, str(secret))
            for secret in (server_secret, manager_secret)
        )

    async def handle_reset(self, request):
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

        device_id = str(body.get("deviceId") or "").strip()
        agent_id = str(body.get("agentId") or "").strip()
        if not device_id or len(device_id) > 128 or not agent_id or len(agent_id) > 64:
            return self._json_response(
                {"success": False, "message": "deviceId/agentId参数无效"},
                400,
                request,
            )

        conn = get_connection(device_id)
        if conn is None:
            return self._json_response(
                {"success": False, "message": "设备当前不在线"}, 409, request
            )
        bound_agent_id = str(
            (getattr(conn, "config", {}) or {}).get("agent_id") or ""
        ).strip()
        if bound_agent_id != agent_id:
            self.logger.bind(tag=TAG).warning(
                "拒绝跨智能体会话上下文控制: deviceId={}, requestedAgentId={}",
                device_id,
                agent_id,
            )
            return self._json_response(
                {"success": False, "message": "设备与智能体绑定关系不匹配"},
                403,
                request,
            )

        router = getattr(conn, "conversation_router", None)
        session = getattr(router, "session", None)
        if (
            getattr(conn, "client_is_speaking", False)
            or getattr(conn, "client_have_voice", False)
            or getattr(session, "_response_active", False)
            or getattr(session, "_instruction_turn_active", False)
        ):
            return self._json_response(
                {"success": False, "message": "设备正在对话，请在本轮结束后重试"},
                409,
                request,
            )
        protected_reason = context_reset_protected_reason(conn)
        if protected_reason:
            return self._json_response(
                {"success": False, "message": protected_reason}, 409, request
            )

        upstream_reset_completed = False
        try:
            if router is not None and router.is_realtime:
                upstream_reset_completed = await router.reset_context_now()
                if not upstream_reset_completed:
                    raise ConversationContextResetError(
                        "Realtime 上游会话未能完成重建，请稍后重试。"
                    )
            result = reset_conversation_context(
                conn, request_upstream_reset=False
            )
        except ConversationContextResetError as exc:
            return self._json_response(
                {"success": False, "message": str(exc)}, 409, request
            )
        except Exception as error:
            self.logger.bind(tag=TAG).warning(
                "设备短期上下文重建失败: deviceId={}, agentId={}, errorType={}",
                device_id,
                agent_id,
                type(error).__name__,
            )
            return self._json_response(
                {"success": False, "message": "Realtime 上游会话重建失败，请稍后重试"},
                502,
                request,
            )

        mode = "realtime" if router is not None and router.is_realtime else "pipeline"
        self.logger.bind(tag=TAG).info(
            "设备短期上下文已手动清空: deviceId={}, agentId={}, mode={}, removedMessages={}, upstreamResetScheduled={}",
            device_id,
            agent_id,
            mode,
            result.removed_messages,
            result.upstream_reset_scheduled,
        )
        return self._json_response(
            {
                "success": True,
                "data": {
                    "online": True,
                    "reset": True,
                    "mode": mode,
                    "removedMessages": result.removed_messages,
                    "upstreamResetScheduled": result.upstream_reset_scheduled,
                    "upstreamResetCompleted": upstream_reset_completed,
                },
            },
            request=request,
        )
