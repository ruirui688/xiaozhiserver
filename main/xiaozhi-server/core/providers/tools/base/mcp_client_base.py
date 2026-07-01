"""MCP客户端基类定义，包含MCPClient和MCPEndpointClient的公共字段和方法"""

import asyncio
import re
from concurrent.futures import Future
from core.utils.util import sanitize_tool_name
from config.logger import setup_logging

TAG = __name__
logger = setup_logging()


class BaseMCPClient:
    """MCP客户端基类，提供MCP客户端的公共属性和方法"""

    def __init__(self):
        self.tools = {}  # sanitized_name -> tool_data
        self.name_mapping = {}
        self.ready = False
        self.call_results = {}  # To store Futures for tool call responses
        self.next_id = 1
        self.lock = asyncio.Lock()
        self._cached_available_tools = None  # Cache for get_available_tools

    def has_tool(self, name: str) -> bool:
        return name in self.tools

    def get_available_tools(self) -> list:
        # Check if the cache is valid
        if self._cached_available_tools is not None:
            return self._cached_available_tools

        # If cache is not valid, regenerate the list
        result = []
        for tool_name, tool_data in self.tools.items():
            function_def = {
                "name": tool_name,
                "description": tool_data["description"],
                "parameters": {
                    "type": tool_data["inputSchema"].get("type", "object"),
                    "properties": tool_data["inputSchema"].get("properties", {}),
                    "required": tool_data["inputSchema"].get("required", []),
                },
            }
            result.append({"type": "function", "function": function_def})

        self._cached_available_tools = result  # Store the generated list in cache
        return result

    async def is_ready(self) -> bool:
        async with self.lock:
            return self.ready

    async def set_ready(self, status: bool):
        async with self.lock:
            self.ready = status

    async def add_tool(self, tool_data: dict):
        async with self.lock:
            sanitized_name = sanitize_tool_name(tool_data["name"])
            self.tools[sanitized_name] = tool_data
            self.name_mapping[sanitized_name] = tool_data["name"]
            self._cached_available_tools = (
                None  # Invalidate the cache when a tool is added
            )

    async def get_next_id(self) -> int:
        async with self.lock:
            current_id = self.next_id
            self.next_id += 1
            return current_id

    async def register_call_result_future(self, id: int, future: Future):
        async with self.lock:
            self.call_results[id] = future

    async def resolve_call_result(self, id: int, result: any):
        async with self.lock:
            if id in self.call_results:
                future = self.call_results.pop(id)
                if not future.done():
                    future.set_result(result)

    async def reject_call_result(self, id: int, exception: Exception):
        async with self.lock:
            if id in self.call_results:
                future = self.call_results.pop(id)
                if not future.done():
                    future.set_exception(exception)

    async def cleanup_call_result(self, id: int):
        async with self.lock:
            if id in self.call_results:
                self.call_results.pop(id)

    async def reject_all_call_results(self, exception: Exception):
        async with self.lock:
            pending_results = list(self.call_results.values())
            self.call_results.clear()
        for future in pending_results:
            if not future.done():
                future.set_exception(exception)

    @property
    def tool_count(self) -> int:
        """获取工具数量"""
        return len(self.tools)

    def _sanitize_descriptions(self):
        """使用词边界正则替换所有工具描述中的工具名称（原始名称 -> 清理后名称）"""
        for tool_data in self.tools.values():
            if "description" in tool_data:
                description = tool_data["description"]
                for sanitized_name, original_name in self.name_mapping.items():
                    description = re.sub(
                        r'\b' + re.escape(original_name) + r'\b',
                        sanitized_name,
                        description,
                    )
                tool_data["description"] = description
        # Invalidate cache since descriptions have changed
        self._cached_available_tools = None
