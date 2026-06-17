#!/usr/bin/env python3
"""Run no-code HTTP tools as an MCP endpoint tool server.

Environment:
  MCP_ENDPOINT_URL   ws://.../mcp_endpoint/mcp/?token=...
  MANAGER_API_URL    http://127.0.0.1:8002/xiaozhi
  MANAGER_API_SECRET server.secret for /config APIs
  AGENT_ID           agent id whose tool config should be loaded
"""

import asyncio
import json
import os
import re
import signal
from typing import Any, Dict, List

import httpx
import websockets


SERVER_INFO = {"name": "xiaozhi-no-code-http-tools", "version": "0.1.0"}
PLACEHOLDER = re.compile(r"\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}")


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _require_env(name: str) -> str:
    value = _env(name)
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


def render_template(value: Any, args: Dict[str, Any]) -> Any:
    if isinstance(value, str):
        return PLACEHOLDER.sub(lambda m: str(args.get(m.group(1), "")), value)
    if isinstance(value, dict):
        return {k: render_template(v, args) for k, v in value.items()}
    if isinstance(value, list):
        return [render_template(v, args) for v in value]
    return value


def build_input_schema(tool: Dict[str, Any]) -> Dict[str, Any]:
    properties = {}
    required = []
    for param in tool.get("parameters") or []:
        name = str(param.get("name") or "").strip()
        if not name:
            continue
        param_type = param.get("type") or "string"
        properties[name] = {
            "type": param_type,
            "description": param.get("description") or "",
        }
        if param.get("required"):
            required.append(name)
    schema = {"type": "object", "properties": properties}
    if required:
        schema["required"] = required
    return schema


def tool_to_mcp(tool: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "name": tool["name"],
        "description": tool.get("description") or tool["name"],
        "inputSchema": build_input_schema(tool),
    }


def success_response(request_id: Any, result: Any) -> Dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def error_response(request_id: Any, code: int, message: str) -> Dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def tool_result(request_id: Any, text: str, is_error: bool = False) -> Dict[str, Any]:
    result = {
        "content": [{"type": "text", "text": text}],
        "isError": is_error,
    }
    if is_error:
        result["error"] = text
    return success_response(request_id, result)


class NoCodeMcpToolServer:
    def __init__(self) -> None:
        self.endpoint_url = _require_env("MCP_ENDPOINT_URL")
        self.manager_api_url = _require_env("MANAGER_API_URL").rstrip("/")
        self.manager_api_secret = _require_env("MANAGER_API_SECRET")
        self.agent_id = _require_env("AGENT_ID")
        self.reload_interval = int(_env("MCP_TOOL_RELOAD_SECONDS", "30"))
        self.tools: Dict[str, Dict[str, Any]] = {}
        self.stop_event = asyncio.Event()

    async def load_tools(self) -> None:
        url = f"{self.manager_api_url}/config/mcp/no-code-tools/{self.agent_id}"
        headers = {"Authorization": f"Bearer {self.manager_api_secret}"}
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.get(url, headers=headers)
            response.raise_for_status()
            payload = response.json()
        if payload.get("code") != 0:
            raise RuntimeError(payload.get("msg") or "manager-api returned an error")
        tools = payload.get("data") or []
        enabled_tools = {}
        for tool in tools:
            name = str(tool.get("name") or "").strip()
            if not name or tool.get("enabled") is False:
                continue
            enabled_tools[name] = tool
        self.tools = enabled_tools
        print(f"Loaded {len(self.tools)} no-code MCP tools for agent {self.agent_id}")

    async def refresh_loop(self) -> None:
        while not self.stop_event.is_set():
            try:
                await self.load_tools()
            except Exception as exc:
                print(f"Tool config refresh failed: {exc}")
            try:
                await asyncio.wait_for(self.stop_event.wait(), timeout=self.reload_interval)
            except asyncio.TimeoutError:
                pass

    async def call_tool(self, request_id: Any, params: Dict[str, Any]) -> Dict[str, Any]:
        name = params.get("name")
        args = params.get("arguments") or {}
        tool = self.tools.get(name)
        if not tool:
            return tool_result(request_id, f"Unknown tool: {name}", True)

        method = (tool.get("method") or "GET").upper()
        url = render_template(tool.get("url") or "", args)
        headers = render_template(tool.get("headers") or {}, args)
        query_params = render_template(tool.get("queryParams") or {}, args)
        timeout = int(tool.get("timeoutSeconds") or 10)
        body_template = tool.get("bodyTemplate") or ""

        json_body = None
        content = None
        if method not in ("GET", "DELETE") and body_template.strip():
            rendered_body = render_template(body_template, args)
            try:
                json_body = json.loads(rendered_body)
            except json.JSONDecodeError:
                content = rendered_body
                headers.setdefault("Content-Type", "text/plain; charset=utf-8")

        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                response = await client.request(
                    method,
                    url,
                    headers=headers,
                    params=query_params,
                    json=json_body,
                    content=content,
                )
            text = response.text
            if len(text) > 8000:
                text = text[:8000] + "\n...<truncated>"
            prefix = f"HTTP {response.status_code}"
            if response.is_error:
                return tool_result(request_id, f"{prefix}: {text}", True)
            return tool_result(request_id, text or prefix)
        except Exception as exc:
            return tool_result(request_id, f"HTTP request failed: {exc}", True)

    async def handle_message(self, websocket, raw_message: str) -> None:
        try:
            message = json.loads(raw_message)
        except json.JSONDecodeError:
            return

        method = message.get("method")
        request_id = message.get("id")

        if method == "initialize":
            await websocket.send(json.dumps(success_response(request_id, {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": SERVER_INFO,
            }), ensure_ascii=False))
            return

        if method == "notifications/initialized":
            return

        if method == "tools/list":
            tools = [tool_to_mcp(tool) for tool in self.tools.values()]
            await websocket.send(json.dumps(success_response(request_id, {"tools": tools}), ensure_ascii=False))
            return

        if method == "tools/call":
            response = await self.call_tool(request_id, message.get("params") or {})
            await websocket.send(json.dumps(response, ensure_ascii=False))
            return

        if request_id is not None:
            await websocket.send(json.dumps(error_response(request_id, -32601, f"Method not found: {method}")))

    async def run_once(self) -> None:
        await self.load_tools()
        async with websockets.connect(self.endpoint_url, ping_interval=20, ping_timeout=20) as websocket:
            print(f"Connected MCP tool server: {self.endpoint_url}")
            async for message in websocket:
                await self.handle_message(websocket, message)

    async def run_forever(self) -> None:
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, self.stop_event.set)
        refresh_task = asyncio.create_task(self.refresh_loop())
        try:
            while not self.stop_event.is_set():
                try:
                    await self.run_once()
                except Exception as exc:
                    print(f"MCP tool server disconnected: {exc}")
                    try:
                        await asyncio.wait_for(self.stop_event.wait(), timeout=3)
                    except asyncio.TimeoutError:
                        pass
        finally:
            self.stop_event.set()
            refresh_task.cancel()


async def main() -> None:
    server = NoCodeMcpToolServer()
    print("Starting no-code MCP tool bridge")
    try:
        await server.run_forever()
    finally:
        print("Stopped no-code MCP tool bridge")


if __name__ == "__main__":
    asyncio.run(main())
