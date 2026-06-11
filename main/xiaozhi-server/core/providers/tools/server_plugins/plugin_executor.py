"""服务端插件工具执行器"""

from typing import Dict, Any, TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
from ..base import ToolType, ToolDefinition, ToolExecutor
from plugins_func.register import all_function_registry, Action, ActionResponse


class ServerPluginExecutor(ToolExecutor):
    """服务端插件工具执行器"""

    def __init__(self, conn: "ConnectionHandler"):
        self.conn = conn
        self.config = conn.config

    async def execute(
        self, conn: "ConnectionHandler", tool_name: str, arguments: Dict[str, Any]
    ) -> ActionResponse:
        """执行服务端插件工具"""
        func_item = all_function_registry.get(tool_name)
        if not func_item:
            declarative_response = self._execute_declarative_interaction_plugin(
                tool_name, arguments
            )
            if declarative_response:
                return declarative_response
            return ActionResponse(
                action=Action.NOTFOUND, response=f"插件函数 {tool_name} 不存在"
            )

        try:
            # 根据工具类型决定如何调用
            if hasattr(func_item, "type"):
                func_type = func_item.type
                if func_type.code in [4, 5]:  # SYSTEM_CTL, IOT_CTL (需要conn参数)
                    result = func_item.func(conn, **arguments)
                elif func_type.code == 2:  # WAIT
                    result = func_item.func(**arguments)
                elif func_type.code == 3:  # CHANGE_SYS_PROMPT
                    result = func_item.func(conn, **arguments)
                else:
                    result = func_item.func(**arguments)
            else:
                # 默认不传conn参数
                result = func_item.func(**arguments)

            return result

        except Exception as e:
            return ActionResponse(
                action=Action.ERROR,
                response=str(e),
            )

    def get_tools(self) -> Dict[str, ToolDefinition]:
        """获取所有注册的服务端插件工具"""
        tools = {}

        # 获取必要的函数
        necessary_functions = ["handle_exit_intent", "get_lunar"]

        # 获取配置中的函数
        config_functions = self.config["Intent"][
            self.config["selected_module"]["Intent"]
        ].get("functions", [])

        # 转换为列表
        if not isinstance(config_functions, list):
            try:
                config_functions = list(config_functions)
            except TypeError:
                config_functions = []

        # 合并所有需要的函数
        all_required_functions = list(set(necessary_functions + config_functions))

        for func_name in all_required_functions:
            func_item = all_function_registry.get(func_name)
            if func_item:
                # 从函数注册中获取描述
                fun_description = (
                    self.config.get("plugins", {})
                    .get(func_name, {})
                    .get("description", "")
                )
                if fun_description is not None and len(fun_description) > 0:
                    if "function" in func_item.description and isinstance(
                        func_item.description["function"], dict
                    ):
                        func_item.description["function"][
                            "description"
                        ] = fun_description

                # 新闻插件：根据配置更新新闻源参数描述
                if func_name == "get_news_from_newsnow":
                    self._init_news_source_description(func_item, func_name)

                tools[func_name] = ToolDefinition(
                    name=func_name,
                    description=func_item.description,
                    tool_type=ToolType.SERVER_PLUGIN,
                )
                continue

            declarative_tool = self._build_declarative_interaction_tool(func_name)
            if declarative_tool:
                tools[func_name] = declarative_tool

        return tools

    def has_tool(self, tool_name: str) -> bool:
        """检查是否有指定的服务端插件工具"""
        return (
            tool_name in all_function_registry
            or self._is_declarative_interaction_plugin(tool_name)
        )

    def _init_news_source_description(self, func_item, func_name):
        """根据连接配置初始化新闻工具的参数描述"""
        news_sources = (
            self.config.get("plugins", {})
            .get(func_name, {})
            .get("news_sources", "")
        )
        if not news_sources:
            news_sources = "澎湃新闻;百度热搜;财联社"
        sources_str = news_sources.replace(";", "、")
        try:
            func_item.description["function"]["parameters"]["properties"]["source"][
                "description"
            ] = f"新闻源的标准中文名称，例如{sources_str}等。可选参数，如果不提供则使用默认新闻源"
        except (KeyError, TypeError):
            pass

    def _get_plugin_config(self, func_name):
        """获取单个插件配置，兼容异常配置格式。"""
        plugin_config = self.config.get("plugins", {}).get(func_name, {})
        return plugin_config if isinstance(plugin_config, dict) else {}

    def _is_declarative_interaction_plugin(self, func_name):
        """判断是否为后台声明式互动插件。

        custom_plugin 是历史字段名，只表示该插件没有 Python 执行函数，
        由后台声明玩法规则，再交给 LLM 继续生成回复。
        """
        plugin_config = self._get_plugin_config(func_name)
        custom_flag = plugin_config.get("custom_plugin")
        if isinstance(custom_flag, bool):
            return custom_flag
        if isinstance(custom_flag, str):
            return custom_flag.lower() == "true"
        return False

    def _build_declarative_interaction_tool(self, func_name):
        """把声明式互动插件转换成 OpenAI function tool 描述。"""
        plugin_config = self._get_plugin_config(func_name)
        if not self._is_declarative_interaction_plugin(func_name):
            return None

        description = plugin_config.get("description") or plugin_config.get(
            "instructions"
        )
        if not description:
            description = f"声明式互动功能插件：{func_name}"

        parameters = plugin_config.get("parameters_schema")
        if not isinstance(parameters, dict):
            parameters = {
                "type": "object",
                "properties": {
                    "user_message": {
                        "type": "string",
                        "description": "用户当前触发这个功能插件时说的话",
                    },
                    "context": {
                        "type": "string",
                        "description": "可选的对话上下文或额外提示",
                    },
                },
                "required": ["user_message"],
            }

        tool_desc = {
            "type": "function",
            "function": {
                "name": func_name,
                "description": description,
                "parameters": parameters,
            },
        }
        return ToolDefinition(
            name=func_name,
            description=tool_desc,
            tool_type=ToolType.SERVER_PLUGIN,
        )

    def _execute_declarative_interaction_plugin(self, tool_name, arguments):
        """执行声明式互动插件。

        声明式互动插件不运行用户上传代码，只把后台配置的玩法说明交给 LLM 继续生成回复。
        """
        if not self._is_declarative_interaction_plugin(tool_name):
            return None

        plugin_config = self._get_plugin_config(tool_name)
        instructions = plugin_config.get("instructions") or plugin_config.get(
            "description", ""
        )
        opening_message = plugin_config.get("opening_message", "")

        result = (
            f"声明式互动功能插件 {tool_name} 已触发。\n"
            f"玩法说明：{instructions}\n"
            f"开场白：{opening_message}\n"
            f"工具参数：{arguments}\n"
            "请根据玩法说明和当前对话上下文，用自然口语继续和用户互动。"
        )
        return ActionResponse(action=Action.REQLLM, result=result)
