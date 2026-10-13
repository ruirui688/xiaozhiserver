"""统一工具处理器"""

import hashlib
import json
from typing import Dict, List, Any, Optional
from config.logger import setup_logging
from core.utils.util import is_placeholder_value
from core.providers.tools.home_assistant_config import is_home_assistant_runtime_enabled
from plugins_func.loadplugins import auto_import_modules

from .base import ToolType
from plugins_func.register import Action, ActionResponse
from .unified_tool_manager import ToolManager
from .server_plugins import ServerPluginExecutor
from .server_mcp import ServerMCPExecutor
from .device_iot import DeviceIoTExecutor
from .device_mcp import DeviceMCPExecutor
from .mcp_endpoint import MCPEndpointExecutor
from core.handle.sendAudioHandle import send_display_message
from core.game import GameSessionManager
from .server_plugins.policy import DEVICE_REMINDER_TOOL_NAME


_MAX_INTERNAL_REQUEST_ID_LENGTH = 128
_INTERNAL_REQUEST_ID_NAMESPACE = "device-reminder:tool-call:v1"


class UnifiedToolHandler:
    """统一工具处理器"""

    def __init__(self, conn):
        self.conn = conn
        self.config = conn.config
        self.logger = setup_logging()

        # 创建工具管理器
        self.tool_manager = ToolManager(conn)

        # 创建各类执行器
        self.server_plugin_executor = ServerPluginExecutor(conn)
        self.server_mcp_executor = ServerMCPExecutor(conn)
        self.device_iot_executor = DeviceIoTExecutor(conn)
        self.device_mcp_executor = DeviceMCPExecutor(conn)
        self.mcp_endpoint_executor = MCPEndpointExecutor(conn)

        # 注册执行器
        self.tool_manager.register_executor(
            ToolType.SERVER_PLUGIN, self.server_plugin_executor
        )
        self.tool_manager.register_executor(
            ToolType.SERVER_MCP, self.server_mcp_executor
        )
        self.tool_manager.register_executor(
            ToolType.DEVICE_IOT, self.device_iot_executor
        )
        self.tool_manager.register_executor(
            ToolType.DEVICE_MCP, self.device_mcp_executor
        )
        self.tool_manager.register_executor(
            ToolType.MCP_ENDPOINT, self.mcp_endpoint_executor
        )

        # 初始化标志
        self.finish_init = False

    async def _initialize(self):
        """异步初始化"""
        try:
            # 自动导入插件模块
            auto_import_modules("plugins_func.functions")

            # 初始化服务端MCP
            await self.server_mcp_executor.initialize()

            # 初始化MCP接入点
            await self._initialize_mcp_endpoint()

            # 初始化Home Assistant（仅当服务端已配置时）
            if self._is_home_assistant_configured():
                self._initialize_home_assistant()

            self.finish_init = True
            self.logger.debug("统一工具处理器初始化完成")

            # 输出当前支持的所有工具列表
            self.current_support_functions()

        except Exception as e:
            self.logger.error(
                f"统一工具处理器初始化失败: errorType={type(e).__name__}"
            )

    async def _initialize_mcp_endpoint(self):
        """初始化MCP接入点"""
        try:
            from .mcp_endpoint import connect_mcp_endpoint

            # 从配置中获取MCP接入点URL
            mcp_endpoint_url = self.config.get("mcp_endpoint", "")

            if (
                mcp_endpoint_url
                and not is_placeholder_value(mcp_endpoint_url)
                and mcp_endpoint_url != "null"
            ):
                self.logger.info("正在初始化MCP接入点（地址已脱敏）")
                mcp_endpoint_client = await connect_mcp_endpoint(
                    mcp_endpoint_url, self.conn
                )

                if mcp_endpoint_client:
                    # 将MCP接入点客户端保存到连接对象中
                    self.conn.mcp_endpoint_client = mcp_endpoint_client
                    self.logger.info("MCP接入点初始化成功")
                else:
                    self.logger.warning("MCP接入点初始化失败")

        except Exception as e:
            self.logger.error(
                f"初始化MCP接入点失败: errorType={type(e).__name__}"
            )

    def _is_home_assistant_configured(self) -> bool:
        """Return True only when Home Assistant is explicitly configured."""
        return is_home_assistant_runtime_enabled(self.conn)

    def _initialize_home_assistant(self):
        """初始化Home Assistant提示词"""
        try:
            from plugins_func.functions.hass_init import append_devices_to_prompt

            append_devices_to_prompt(self.conn)
        except ImportError:
            pass  # 忽略导入错误
        except Exception as e:
            self.logger.error(f"初始化Home Assistant失败: {e}")

    def get_functions(self) -> List[Dict[str, Any]]:
        """获取所有工具的函数描述"""
        return self.tool_manager.get_function_descriptions()

    def current_support_functions(self) -> List[str]:
        """获取当前支持的函数名称列表"""
        func_names = self.tool_manager.get_supported_tool_names()
        self.logger.info(f"当前支持的函数列表: {func_names}")
        return func_names

    def upload_functions_desc(self):
        """刷新函数描述列表"""
        self.tool_manager.refresh_tools()
        self.logger.info("函数描述列表已刷新")

    def has_tool(self, tool_name: str) -> bool:
        """检查是否有指定工具"""
        return self.tool_manager.has_tool(tool_name)

    @staticmethod
    def _should_display_tool_call(tool_name: str) -> bool:
        return not (
            tool_name in GameSessionManager.GUESS_MY_WORD_TOOLS
            or tool_name in GameSessionManager.USER_DESCRIBES_TOOLS
            or tool_name in GameSessionManager.IDIOM_CHAIN_TOOLS
        )

    async def handle_llm_function_call(
        self, conn, function_call_data: Dict[str, Any]
    ) -> Optional[ActionResponse]:
        """处理LLM函数调用"""
        try:
            # 处理多函数调用
            if "function_calls" in function_call_data:
                responses = []
                for call in function_call_data["function_calls"]:
                    arguments, error = self._prepare_internal_arguments(
                        conn, call["name"], call.get("arguments", {}), call
                    )
                    if error is not None:
                        responses.append(error)
                        continue
                    result = await self.tool_manager.execute_tool(
                        call["name"], arguments
                    )
                    responses.append(result)
                return self._combine_responses(responses)

            # 处理单函数调用
            function_name = function_call_data["name"]
            arguments = function_call_data.get("arguments", {})

            # 如果arguments是字符串，尝试解析为JSON
            if isinstance(arguments, str):
                try:
                    arguments = json.loads(arguments) if arguments else {}
                except json.JSONDecodeError as error:
                    self.logger.error(
                        f"无法解析函数参数: errorType={type(error).__name__}"
                    )
                    return ActionResponse(
                        action=Action.ERROR,
                        response="无法解析函数参数",
                    )

            self.logger.debug(f"调用函数: {function_name}")

            # 当前注册表是执行权限的事实来源。先拒绝幻觉或过期调用，
            # 避免设备先显示一个实际上不存在的工具正在执行。
            if not self.has_tool(function_name):
                self.logger.warning(f"拒绝未注册工具调用: {function_name}")
                return ActionResponse(
                    action=Action.NOTFOUND,
                    response=f"工具 {function_name} 不存在",
                )

            arguments, error = self._prepare_internal_arguments(
                conn, function_name, arguments, function_call_data
            )
            if error is not None:
                return error

            # 发送工具调用显示消息到设备
            if self._should_display_tool_call(function_name):
                try:
                    await send_display_message(self.conn, f"% {function_name}")
                except Exception as e:
                    self.logger.warning(f"发送工具调用显示消息失败: {e}")

            # 执行工具调用
            result = await self.tool_manager.execute_tool(function_name, arguments)
            return result

        except Exception as e:
            self.logger.error(
                f"处理function call错误: errorType={type(e).__name__}"
            )
            return ActionResponse(action=Action.ERROR, response=str(e))

    def _prepare_internal_arguments(
        self,
        conn,
        function_name: str,
        arguments: Any,
        function_call_data: Dict[str, Any],
    ):
        """Inject trusted runtime-only arguments without changing model schemas."""
        if function_name != DEVICE_REMINDER_TOOL_NAME:
            return arguments, None

        if self.tool_manager.get_tool_type(function_name) != ToolType.SERVER_PLUGIN:
            self.logger.warning("拒绝非Server Plugin来源的设备提醒调用")
            return arguments, ActionResponse(
                action=Action.ERROR,
                response="设备提醒工具来源无效，已拒绝执行。",
            )

        if isinstance(arguments, str):
            try:
                arguments = json.loads(arguments) if arguments else {}
            except json.JSONDecodeError:
                return arguments, ActionResponse(
                    action=Action.ERROR,
                    response="提醒参数格式无效，请重试。",
                )
        if not isinstance(arguments, dict):
            return arguments, ActionResponse(
                action=Action.ERROR,
                response="提醒参数格式无效，请重试。",
            )

        request_id = _derive_internal_request_id(
            getattr(conn, "session_id", None),
            function_call_data.get("id"),
        )
        if request_id is None:
            self.logger.warning("拒绝缺少有效工具调用标识的设备提醒")
            return arguments, ActionResponse(
                action=Action.ERROR,
                response="提醒调用缺少有效标识，请重试。",
            )

        prepared = dict(arguments)
        prepared["request_id"] = request_id
        return prepared, None

    async def invoke(
        self, tool_name: str, arguments: Optional[Dict[str, Any]] = None, source: str = "direct"
    ) -> ActionResponse:
        """Direct tool invocation for router/policy controlled paths."""
        arguments = arguments or {}
        self.logger.debug(f"直接调用工具: {tool_name}, source={source}")
        if self._should_display_tool_call(tool_name):
            try:
                await send_display_message(self.conn, f"% {tool_name}")
            except Exception as e:
                self.logger.warning(f"发送工具调用显示消息失败: {e}")
        return await self.tool_manager.execute_tool(tool_name, arguments)

    def _combine_responses(self, responses: List[ActionResponse]) -> ActionResponse:
        """合并多个函数调用的响应"""
        if not responses:
            return ActionResponse(action=Action.NONE, response="无响应")

        next_llm_tools_enabled = None
        if any(
            getattr(response, "next_llm_tools_enabled", None) is False
            for response in responses
        ):
            next_llm_tools_enabled = False
        elif any(
            getattr(response, "next_llm_tools_enabled", None) is True
            for response in responses
        ):
            next_llm_tools_enabled = True

        system_prompts = []
        for response in responses:
            prompt = getattr(response, "next_llm_system_prompt", None)
            prompt_text = str(prompt).strip() if prompt else ""
            if prompt_text and prompt_text not in system_prompts:
                system_prompts.append(prompt_text)

        merged_metadata = {
            "next_llm_tools_enabled": next_llm_tools_enabled,
            "next_llm_system_prompt": "\n\n".join(system_prompts) or None,
        }

        # 如果有任何错误，返回第一个错误
        for response in responses:
            if response.action == Action.ERROR:
                return ActionResponse(
                    action=response.action,
                    result=response.result,
                    response=response.response,
                    **merged_metadata,
                )

        # 合并所有成功的响应
        contents = []
        responses_text = []

        for response in responses:
            if response.result:
                contents.append(str(response.result))
            if response.response:
                responses_text.append(str(response.response))

        # 确定最终的动作类型
        final_action = Action.RESPONSE
        for response in responses:
            if response.action == Action.REQLLM:
                final_action = Action.REQLLM
                break

        return ActionResponse(
            action=final_action,
            result="; ".join(contents) if contents else None,
            response="; ".join(responses_text) if responses_text else None,
            **merged_metadata,
        )

    async def register_iot_tools(self, descriptors: List[Dict[str, Any]]):
        """注册IoT设备工具"""
        self.device_iot_executor.register_iot_tools(descriptors)
        self.tool_manager.refresh_tools()
        self.logger.info(f"注册了{len(descriptors)}个IoT设备的工具")

    def get_tool_statistics(self) -> Dict[str, int]:
        """获取工具统计信息"""
        return self.tool_manager.get_tool_statistics()

    async def cleanup(self):
        """清理资源"""
        try:
            await self.server_mcp_executor.cleanup()

            # 清理MCP接入点连接
            if (
                hasattr(self.conn, "mcp_endpoint_client")
                and self.conn.mcp_endpoint_client
            ):
                await self.conn.mcp_endpoint_client.close()

            if hasattr(self.conn, "mcp_client") and self.conn.mcp_client:
                await self.conn.mcp_client.reject_all_call_results(
                    ConnectionError("连接已关闭，取消等待中的设备MCP调用")
                )

            self.logger.info("工具处理器清理完成")
        except Exception as e:
            self.logger.error(f"工具处理器清理失败: {e}")


def _derive_internal_request_id(session_id: Any, provider_call_id: Any) -> Optional[str]:
    if not isinstance(session_id, str) or not session_id.strip():
        return None
    if not isinstance(provider_call_id, str) or not provider_call_id.strip():
        return None
    digest_builder = hashlib.sha256()
    for field in (
        _INTERNAL_REQUEST_ID_NAMESPACE,
        session_id,
        provider_call_id,
    ):
        field_bytes = field.encode("utf-8")
        digest_builder.update(len(field_bytes).to_bytes(8, "big"))
        digest_builder.update(field_bytes)
    digest = digest_builder.hexdigest()
    request_id = f"{_INTERNAL_REQUEST_ID_NAMESPACE}:{digest}"
    if len(request_id) > _MAX_INTERNAL_REQUEST_ID_LENGTH:
        raise AssertionError("internal reminder request ID exceeds its contract")
    return request_id
