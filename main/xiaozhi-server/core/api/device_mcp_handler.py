"""
设备端MCP HTTP API。

此API使用原始auth_key鉴权（而非JWT），因为设备MCP调用来自受信内部网络。
- 内部网络请求通过原始auth_key进行简单Bearer token校验
- 身份校验直接比对服务器配置的auth_key和manager-api密钥
- 不签发JWT，不依赖第三方认证服务
这是有意为之的设计，保持了内部网络调用的简洁性。
"""

import json
from aiohttp import web

from core.api.base_handler import BaseHandler
from core.device_runtime_registry import get_connection, registered_keys
from core.providers.tools.device_mcp.mcp_handler import call_mcp_tool
from core.utils.util import sanitize_tool_name

TAG = __name__


class DeviceMcpHandler(BaseHandler):
    def _json_response(self, body: dict, status: int = 200):
        response = web.Response(
            text=json.dumps(body, ensure_ascii=False),
            content_type="application/json",
            status=status,
        )
        self._add_cors_headers(response)
        return response

    def _is_authorized(self, request) -> bool:
        # Design note: HTTP API authentication uses a shared secret (server auth_key
        # or manager-api secret) rather than per-device JWT tokens.  This is because
        # these endpoints are called by the management console (manager-api / web),
        # not by individual devices.  The WebSocket connection uses JWT tokens for
        # per-device auth; the HTTP API is a separate control plane that relies on
        # the backend-to-backend shared secret for simplicity.
        auth_header = request.headers.get("Authorization", "")
        if not auth_header.startswith("Bearer "):
            return False

        token = auth_header[7:]
        server_secret = self.config.get("server", {}).get("auth_key", "")
        manager_secret = self.config.get("manager-api", {}).get("secret", "")
        return bool(token and token in {server_secret, manager_secret})

    def _get_ready_mcp_client(self, device_id: str):
        conn = get_connection(device_id)
        if not conn:
            return None, None, "设备未连接到当前运行时"

        mcp_client = getattr(conn, "mcp_client", None)
        if not mcp_client:
            return conn, None, "设备未启用MCP"

        return conn, mcp_client, None

    async def handle_status(self, request):
        if not self._is_authorized(request):
            return self._json_response({"success": False, "message": "未授权"}, 401)

        device_id = request.query.get("deviceId") or request.query.get("device-id")
        if not device_id:
            return self._json_response({"success": False, "message": "缺少deviceId"}, 400)

        self.logger.bind(tag=TAG).info(
            "查询设备MCP状态: deviceId={}, registeredKeys={}",
            device_id,
            sorted(registered_keys()),
        )
        conn = get_connection(device_id)
        if not conn:
            self.logger.bind(tag=TAG).info(
                "设备MCP状态未命中运行时连接: deviceId={}", device_id
            )
            return self._json_response(
                {
                    "success": True,
                    "data": {
                        "deviceId": device_id,
                        "runtimeConnected": False,
                        "mcpReady": False,
                        "status": "offline",
                        "message": "设备未连接到当前运行时",
                        "toolsCount": 0,
                        "source": "runtime",
                    },
                }
            )

        mcp_client = getattr(conn, "mcp_client", None)
        if not mcp_client:
            self.logger.bind(tag=TAG).info(
                "设备MCP状态命中连接但未启用MCP: deviceId={}, clientId={}",
                device_id,
                conn.headers.get("client-id") if conn.headers else "",
            )
            return self._json_response(
                {
                    "success": True,
                    "data": {
                        "deviceId": device_id,
                        "clientId": conn.headers.get("client-id") if conn.headers else "",
                        "runtimeConnected": True,
                        "mcpReady": False,
                        "status": "online_no_mcp",
                        "message": "设备已连接但未启用MCP",
                        "toolsCount": 0,
                        "source": "runtime",
                    },
                }
            )

        ready = await mcp_client.is_ready()
        async with mcp_client.lock:
            tools_count = len(mcp_client.tools)

        status = "mcp_ready" if ready else "mcp_initializing"
        message = "设备MCP已就绪" if ready else "设备MCP尚未准备就绪"
        self.logger.bind(tag=TAG).info(
            "设备MCP状态命中: deviceId={}, clientId={}, ready={}, toolsCount={}",
            device_id,
            conn.headers.get("client-id") if conn.headers else "",
            ready,
            tools_count,
        )
        return self._json_response(
            {
                "success": True,
                "data": {
                    "deviceId": device_id,
                    "clientId": conn.headers.get("client-id") if conn.headers else "",
                    "runtimeConnected": True,
                    "mcpReady": ready,
                    "status": status,
                    "message": message,
                    "toolsCount": tools_count,
                    "source": "runtime",
                },
            }
        )

    async def handle_tools(self, request):
        if not self._is_authorized(request):
            return self._json_response({"success": False, "message": "未授权"}, 401)

        device_id = request.query.get("deviceId") or request.query.get("device-id")
        if not device_id:
            return self._json_response({"success": False, "message": "缺少deviceId"}, 400)

        self.logger.bind(tag=TAG).info(
            "查询设备MCP工具: deviceId={}, registeredKeys={}",
            device_id,
            sorted(registered_keys()),
        )
        conn, mcp_client, error = self._get_ready_mcp_client(device_id)
        if error:
            self.logger.bind(tag=TAG).info(
                "查询设备MCP工具失败: deviceId={}, reason={}", device_id, error
            )
            return self._json_response({"success": False, "message": error})

        if not await mcp_client.is_ready():
            self.logger.bind(tag=TAG).info(
                "查询设备MCP工具失败: deviceId={}, reason=设备MCP尚未准备就绪",
                device_id,
            )
            return self._json_response({"success": False, "message": "设备MCP尚未准备就绪"})

        tools = []
        async with mcp_client.lock:
            for sanitized_name, tool_data in mcp_client.tools.items():
                tool = dict(tool_data)
                tool["name"] = sanitized_name
                tool["originalName"] = mcp_client.name_mapping.get(
                    sanitized_name, tool_data.get("name", sanitized_name)
                )
                tools.append(tool)

        self.logger.bind(tag=TAG).info(
            "查询设备MCP工具成功: deviceId={}, clientId={}, toolsCount={}",
            device_id,
            conn.headers.get("client-id") if conn.headers else "",
            len(tools),
        )
        return self._json_response(
            {
                "success": True,
                "data": {
                    "deviceId": device_id,
                    "clientId": conn.headers.get("client-id") if conn.headers else "",
                    "runtimeConnected": True,
                    "mcpReady": True,
                    "status": "mcp_ready",
                    "message": "设备MCP已就绪",
                    "toolsCount": len(tools),
                    "source": "runtime",
                    "ready": True,
                    "tools": tools,
                },
            }
        )

    async def handle_call(self, request):
        if not self._is_authorized(request):
            return self._json_response({"success": False, "message": "未授权"}, 401)

        try:
            body = await request.json()
        except Exception:
            return self._json_response({"success": False, "message": "请求体不是有效JSON"}, 400)

        device_id = body.get("deviceId") or body.get("device-id")
        tool_name = body.get("name")
        arguments = body.get("arguments") or {}
        if not device_id or not tool_name:
            return self._json_response({"success": False, "message": "缺少deviceId或工具名称"}, 400)

        conn, mcp_client, error = self._get_ready_mcp_client(device_id)
        if error:
            return self._json_response({"success": False, "message": error})

        normalized_name = tool_name
        if not mcp_client.has_tool(normalized_name):
            sanitized_name = sanitize_tool_name(tool_name)
            if mcp_client.has_tool(sanitized_name):
                normalized_name = sanitized_name

        try:
            result = await call_mcp_tool(
                conn,
                mcp_client,
                normalized_name,
                arguments,
                timeout=int(conn.config.get("device_mcp_tool_call_timeout", 10)),
            )
            return self._json_response({"success": True, "data": result})
        except Exception as e:
            return self._json_response({"success": False, "message": str(e)}, 500)
