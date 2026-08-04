"""Authenticated manager-api bridge for agent and device runtime hot reloads."""

import asyncio
import json

from aiohttp import web

from core.api.base_handler import BaseHandler
from config.manage_api_client import report_device_scene_switch_result
from core.device_runtime_registry import get_connection, get_connections_for_agent


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
        device_ids = body.get("deviceIds")
        if not isinstance(device_ids, list):
            device_ids = [body.get("deviceId")]
        device_ids = list(dict.fromkeys(
            str(device_id or "").strip()
            for device_id in device_ids
            if str(device_id or "").strip()
        ))
        if len(agent_ids) > 500 or len(device_ids) > 500 or not (
            agent_ids or device_ids
        ):
            return self._json_response(
                {"success": False, "message": "agentIds/deviceIds参数无效"},
                400,
                request,
            )

        reason = str(body.get("reason") or "manager_update")[:64]
        scene_switch = self._normalize_scene_switch(body.get("sceneSwitch"))
        if body.get("sceneSwitch") is not None and scene_switch is None:
            return self._json_response(
                {"success": False, "message": "sceneSwitch参数无效"}, 400, request
            )
        if scene_switch is not None and scene_switch["deviceId"] not in device_ids:
            return self._json_response(
                {"success": False, "message": "sceneSwitch设备与刷新目标不一致"},
                400,
                request,
            )

        online = 0
        scheduled = 0
        seen = set()
        connections = []
        for device_id in device_ids:
            conn = get_connection(device_id)
            if conn is not None:
                connections.append(conn)
        for agent_id in agent_ids:
            for conn in get_connections_for_agent(agent_id):
                connections.append(conn)
        for conn in connections:
            identity = id(conn)
            if identity in seen:
                continue
            seen.add(identity)
            online += 1
            reloader = getattr(conn, "runtime_config_reloader", None)
            if reloader is None:
                continue
            if scene_switch is not None:
                reloader.request_refresh(reason, scene_switch=scene_switch)
            else:
                reloader.request_refresh(reason)
            scheduled += 1
            if scene_switch is not None and not getattr(
                conn, "client_is_speaking", False
            ):
                asyncio.create_task(
                    self._run_scene_refresh(reloader),
                    name="scene-switch-{}".format(
                        scene_switch["switchRequestId"][:32]
                    ),
                )

        if scene_switch is not None:
            await self._report_scene_status(
                scene_switch, "SCHEDULED" if scheduled else "APPLIED"
            )

        self.logger.bind(tag=__name__).info(
            "运行配置在线刷新已接收: agentCount={}, deviceCount={}, online={}, scheduled={}, reason={}",
            len(agent_ids),
            len(device_ids),
            online,
            scheduled,
            reason,
        )
        return self._json_response(
            {
                "success": True,
                "data": {
                    "agentCount": len(agent_ids),
                    "deviceCount": len(device_ids),
                    "online": online,
                    "scheduled": scheduled,
                },
            },
            request=request,
        )

    async def _run_scene_refresh(self, reloader):
        try:
            await reloader.refresh_if_due(force=True, reason="scene_switch")
        except Exception as error:
            self.logger.bind(tag=__name__).warning(
                "在线设备场景刷新失败: errorType={}", type(error).__name__
            )

    async def _report_scene_status(self, scene_switch, status):
        payload = {
            "deviceId": scene_switch["deviceId"],
            "switchRequestId": scene_switch["switchRequestId"],
            "agentId": scene_switch["toAgentId"],
            "configVersion": scene_switch["configVersion"],
            "status": status,
        }
        try:
            await report_device_scene_switch_result(payload)
        except Exception as error:
            self.logger.bind(tag=__name__).warning(
                "场景切换调度状态回传失败: status={}, errorType={}",
                status,
                type(error).__name__,
            )

    @staticmethod
    def _normalize_scene_switch(value):
        if not isinstance(value, dict):
            return None
        normalized = {
            "deviceId": str(value.get("deviceId") or "").strip(),
            "fromAgentId": str(value.get("fromAgentId") or "").strip(),
            "toAgentId": str(value.get("toAgentId") or "").strip(),
            "switchRequestId": str(value.get("switchRequestId") or "").strip(),
        }
        try:
            normalized["configVersion"] = int(value.get("configVersion"))
        except (TypeError, ValueError):
            return None
        if not all(
            normalized[key]
            for key in ("deviceId", "toAgentId", "switchRequestId")
        ) or normalized["configVersion"] < 0:
            return None
        return normalized
