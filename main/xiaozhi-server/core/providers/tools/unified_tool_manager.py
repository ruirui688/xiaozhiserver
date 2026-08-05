"""统一工具管理器"""

import asyncio

from typing import Dict, List, Optional, Any
from config.logger import setup_logging
from plugins_func.register import Action, ActionResponse
from .base import ToolType, ToolDefinition, ToolExecutor
from core.utils.prompt_budget import compact_tool_schemas


class ToolManager:
    """统一工具管理器，管理所有类型的工具"""

    def __init__(self, conn):
        self.conn = conn
        self.logger = setup_logging()
        self.executors: Dict[ToolType, ToolExecutor] = {}
        self._cached_tools: Optional[Dict[str, ToolDefinition]] = None
        self._cached_function_descriptions: Optional[List[Dict[str, Any]]] = None

    def register_executor(self, tool_type: ToolType, executor: ToolExecutor):
        """注册工具执行器"""
        self.executors[tool_type] = executor
        self._invalidate_cache()
        self.logger.debug(f"注册工具执行器: {tool_type.value}")

    def _invalidate_cache(self):
        """使缓存失效"""
        self._cached_tools = None
        self._cached_function_descriptions = None

    def get_all_tools(self) -> Dict[str, ToolDefinition]:
        """获取所有工具定义"""
        if self._cached_tools is not None:
            return self._cached_tools

        all_tools = {}
        for tool_type, executor in self.executors.items():
            try:
                tools = executor.get_tools()
                for name, definition in tools.items():
                    if name in all_tools:
                        existing_type = all_tools[name].tool_type.value
                        incoming_type = tool_type.value
                        self.logger.warning(
                            f"工具名称冲突: {name} (已有: {existing_type}, "
                            f"跳过: {incoming_type}) — 保留先注册的 {existing_type}"
                        )
                        continue  # 保留先注册的工具（SERVER_PLUGIN优先）
                    all_tools[name] = definition
            except Exception as e:
                self.logger.error(
                    f"获取{tool_type.value}工具时出错: errorType={type(e).__name__}"
                )

        self._cached_tools = all_tools
        return all_tools

    def get_function_descriptions(self) -> List[Dict[str, Any]]:
        """获取所有工具的函数描述（OpenAI格式）"""
        if self._cached_function_descriptions is not None:
            return self._cached_function_descriptions

        descriptions = []
        tools = self.get_all_tools()
        for tool_definition in tools.values():
            descriptions.append(tool_definition.description)

        descriptions = compact_tool_schemas(descriptions)
        self._cached_function_descriptions = descriptions
        return descriptions

    def has_tool(self, tool_name: str) -> bool:
        """检查是否存在指定工具"""
        tools = self.get_all_tools()
        return tool_name in tools

    def get_tool_type(self, tool_name: str) -> Optional[ToolType]:
        """获取工具类型"""
        tools = self.get_all_tools()
        tool_def = tools.get(tool_name)
        return tool_def.tool_type if tool_def else None

    async def execute_tool(
        self, tool_name: str, arguments: Dict[str, Any]
    ) -> ActionResponse:
        """执行工具调用"""
        try:
            # 查找工具类型
            tool_type = self.get_tool_type(tool_name)
            if not tool_type:
                return ActionResponse(
                    action=Action.NOTFOUND,
                    response=f"工具 {tool_name} 不存在",
                )

            # 获取对应的执行器
            executor = self.executors.get(tool_type)
            if not executor:
                return ActionResponse(
                    action=Action.ERROR,
                    response=f"工具类型 {tool_type.value} 的执行器未注册",
                )

            # 执行工具
            self.logger.info(f"执行工具: {tool_name}")
            result = await executor.execute(self.conn, tool_name, arguments)
            self.logger.debug(f"工具执行完成: {tool_name}")
            return result

        except Exception as e:
            self.logger.error(
                f"执行工具 {tool_name} 时出错: errorType={type(e).__name__}"
            )
            return ActionResponse(action=Action.ERROR, response=str(e))

    def get_supported_tool_names(self) -> List[str]:
        """获取所有支持的工具名称"""
        tools = self.get_all_tools()
        return list(tools.keys())

    def refresh_tools(self):
        """刷新工具缓存"""
        self._invalidate_cache()
        self.logger.debug("工具缓存已刷新")
        router = getattr(self.conn, "conversation_router", None)
        loop = getattr(self.conn, "loop", None)
        if (
            router is None
            or not router.is_realtime
            or router.session is None
            or loop is None
        ):
            return

        async def sync_realtime_tools():
            try:
                await router.refresh_tools()
            except Exception as error:
                self.logger.warning(
                    f"同步Realtime工具描述失败: errorType={type(error).__name__}"
                )

        try:
            running_loop = asyncio.get_running_loop()
        except RuntimeError:
            running_loop = None
        if running_loop is loop:
            loop.create_task(sync_realtime_tools())
        else:
            asyncio.run_coroutine_threadsafe(sync_realtime_tools(), loop)

    def get_tool_statistics(self) -> Dict[str, int]:
        """获取工具统计信息"""
        stats = {}
        for tool_type, executor in self.executors.items():
            try:
                tools = executor.get_tools()
                stats[tool_type.value] = len(tools)
            except Exception as e:
                self.logger.error(f"获取{tool_type.value}工具统计时出错: {e}")
                stats[tool_type.value] = 0
        return stats
