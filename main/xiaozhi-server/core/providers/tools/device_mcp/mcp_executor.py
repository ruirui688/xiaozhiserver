"""设备端MCP工具执行器"""

import json
from typing import Dict, Any, TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
from ..base import ToolType, ToolDefinition, ToolExecutor
from plugins_func.register import Action, ActionResponse
from .mcp_handler import call_mcp_tool


class DeviceMCPExecutor(ToolExecutor):
    """设备端MCP工具执行器"""

    FAST_RESPONSE_CONTROL_TOOLS = {
        "self_audio_speaker_set_volume",
        "self_audio_speaker_volume_up",
        "self_audio_speaker_volume_down",
        "self_screen_set_brightness",
        "self_screen_set_theme",
        "self_update_device",
    }

    def __init__(self, conn):
        self.conn = conn

    @staticmethod
    def _json_result(payload: Dict[str, Any]) -> str:
        return json.dumps(payload, ensure_ascii=False)

    def _build_control_result(
        self,
        tool_name: str,
        arguments: Dict[str, Any],
        *,
        raw_result: Any = None,
        error: str = None,
        transport_result_received: bool = False,
    ) -> str:
        return self._json_result(
            {
                "tool": tool_name,
                "arguments": arguments or {},
                "transport": "device_mcp",
                "transport_result_received": transport_result_received,
                "state_verified": False,
                "raw_result": raw_result,
                "error": error,
            }
        )

    async def execute(
        self, conn: "ConnectionHandler", tool_name: str, arguments: Dict[str, Any]
    ) -> ActionResponse:
        """执行设备端MCP工具"""
        if not hasattr(conn, "mcp_client") or not conn.mcp_client:
            return ActionResponse(
                action=Action.ERROR,
                response="设备端MCP客户端未初始化",
            )

        if not await conn.mcp_client.is_ready():
            return ActionResponse(
                action=Action.ERROR,
                response="设备端MCP客户端未准备就绪",
            )

        try:
            args_str = json.dumps(arguments) if arguments else "{}"

            # 调用设备端MCP工具
            result = await call_mcp_tool(
                conn,
                conn.mcp_client,
                tool_name,
                args_str,
                timeout=self._get_tool_timeout(conn, tool_name),
            )

            resultJson = None
            if isinstance(result, str):
                try:
                    resultJson = json.loads(result)
                except Exception as e:
                    pass

            # 视觉大模型不经过二次LLM处理
            if (
                resultJson is not None
                and isinstance(resultJson, dict)
                and "action" in resultJson
            ):
                action_name = resultJson.get("action")
                action = Action.__members__.get(action_name) if action_name else None
                if action is None:
                    self.logger.warning(
                        f"Device returned unknown action: {action_name}, treating as ERROR"
                    )
                    action = Action.ERROR
                result_for_history = json.dumps(resultJson, ensure_ascii=False)
                return ActionResponse(
                    action=action,
                    result=result_for_history,
                    response=resultJson.get("response", ""),
                )

            if tool_name in self.FAST_RESPONSE_CONTROL_TOOLS:
                return ActionResponse(
                    action=Action.REQLLM,
                    result=self._build_control_result(
                        tool_name,
                        arguments,
                        raw_result=result,
                        transport_result_received=True,
                    ),
                )

            return ActionResponse(action=Action.REQLLM, result=str(result))

        except ValueError as e:
            return ActionResponse(action=Action.NOTFOUND, response=str(e))
        except TimeoutError:
            if tool_name in self.FAST_RESPONSE_CONTROL_TOOLS:
                return ActionResponse(
                    action=Action.REQLLM,
                    result=self._build_control_result(
                        tool_name,
                        arguments,
                        error="timeout",
                        transport_result_received=False,
                    ),
                )
            return ActionResponse(action=Action.ERROR, response="设备端工具响应超时")
        except Exception as e:
            return ActionResponse(action=Action.ERROR, response=str(e))

    def _get_tool_timeout(self, conn: "ConnectionHandler", tool_name: str) -> int:
        if tool_name in self.FAST_RESPONSE_CONTROL_TOOLS:
            try:
                return int(conn.config.get("device_mcp_control_timeout", 3))
            except (TypeError, ValueError):
                return 3
        try:
            return int(conn.config.get("device_mcp_tool_call_timeout", 10))
        except (TypeError, ValueError):
            return 10

    def get_tools(self) -> Dict[str, ToolDefinition]:
        """获取所有设备端MCP工具"""
        if not hasattr(self.conn, "mcp_client") or not self.conn.mcp_client:
            return {}

        tools = {}
        mcp_tools = self.conn.mcp_client.get_available_tools()

        for tool in mcp_tools:
            func_def = tool.get("function", {})
            tool_name = func_def.get("name", "")

            if tool_name:
                tools[tool_name] = ToolDefinition(
                    name=tool_name, description=tool, tool_type=ToolType.DEVICE_MCP
                )

        return tools

    def has_tool(self, tool_name: str) -> bool:
        """检查是否有指定的设备端MCP工具"""
        if not hasattr(self.conn, "mcp_client") or not self.conn.mcp_client:
            return False

        return self.conn.mcp_client.has_tool(tool_name)
