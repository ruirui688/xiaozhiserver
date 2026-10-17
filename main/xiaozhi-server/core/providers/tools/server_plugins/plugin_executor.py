"""服务端插件工具执行器"""

import asyncio
import copy
import json
from typing import Dict, Any, TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
from ..base import ToolType, ToolDefinition, ToolExecutor
from plugins_func.register import all_function_registry, Action, ActionResponse
from plugins_func.register import ToolType as PluginBehavior
from core.game import GameSessionManager
from core.game.interactions import (
    END_INTERACTION_TOOL, activate_interaction, available_interactions,
    configured_function_names, end_interaction_tool_description,
    interaction_tool_description, reset_active_interaction,
)
from core.meeting.config import is_meeting_mode_enabled
from .policy import DEVICE_REMINDER_TOOL_NAME, RETIRED_TOOL_NAMES


_REALTIME_REMINDER_UNAVAILABLE = (
    "当前Realtime会话暂不支持设备日程提醒，提醒未创建。"
)


class ServerPluginExecutor(ToolExecutor):
    """服务端插件工具执行器"""

    def __init__(self, conn: "ConnectionHandler"):
        self.conn = conn

    @property
    def config(self):
        return self.conn.config

    async def execute(
        self, conn: "ConnectionHandler", tool_name: str, arguments: Dict[str, Any]
    ) -> ActionResponse:
        """执行服务端插件工具"""
        if tool_name == DEVICE_REMINDER_TOOL_NAME and self._is_realtime(conn):
            return ActionResponse(
                action=Action.ERROR,
                response=_REALTIME_REMINDER_UNAVAILABLE,
            )
        if tool_name in RETIRED_TOOL_NAMES or tool_name not in self.get_tools():
            return ActionResponse(action=Action.NOTFOUND, response="插件函数不存在")
        if tool_name == END_INTERACTION_TOOL:
            reset_active_interaction(conn)
            return ActionResponse(action=Action.REQLLM, result='{"activity_status":"ended"}')
        if tool_name in available_interactions(conn):
            return self._execute_declarative_interaction_plugin(tool_name, arguments)

        func_item = all_function_registry.get(tool_name)
        if not func_item:
            game_response = self._execute_stateful_game_plugin(tool_name, arguments)
            if game_response:
                return game_response
            declarative_response = self._execute_declarative_interaction_plugin(
                tool_name, arguments
            )
            if declarative_response:
                return declarative_response
            return ActionResponse(
                action=Action.NOTFOUND, response=f"插件函数 {tool_name} 不存在"
            )

        try:
            result = await asyncio.to_thread(
                self._execute_registered_function, conn, func_item, arguments
            )
            if asyncio.iscoroutine(result):
                result = await result

            return result

        except Exception as e:
            return ActionResponse(
                action=Action.ERROR,
                response=str(e),
            )

    @staticmethod
    def _execute_registered_function(
        conn: "ConnectionHandler", func_item, arguments: Dict[str, Any]
    ) -> ActionResponse:
        # 根据工具类型决定如何调用。插件函数通常是同步 I/O，放到线程里避免阻塞事件循环。
        if hasattr(func_item, "type"):
            func_type = func_item.type
            if func_type.code in (PluginBehavior.SYSTEM_CTL.code, PluginBehavior.IOT_CTL.code):  # SYSTEM_CTL, IOT_CTL (需要conn参数)
                return func_item.func(conn, **arguments)
            if func_type.code == PluginBehavior.WAIT.code:  # WAIT
                return func_item.func(**arguments)
            if func_type.code == PluginBehavior.CHANGE_SYS_PROMPT.code:  # CHANGE_SYS_PROMPT
                return func_item.func(conn, **arguments)
            return func_item.func(**arguments)

        # 默认不传conn参数
        return func_item.func(**arguments)

    def get_tools(self) -> Dict[str, ToolDefinition]:
        """获取所有注册的服务端插件工具"""
        tools = {}

        # 获取必要的函数
        necessary_functions = [
            "handle_exit_intent",
            "get_lunar",
            "reset_conversation_context",
        ]
        if is_meeting_mode_enabled(self.conn):
            necessary_functions.append("meeting_minutes")

        config_functions = list(configured_function_names(self.config))
        interactions = available_interactions(self.conn)

        # 合并所有需要的函数
        all_required_functions = sorted(
            set(
                necessary_functions
                + config_functions
                + list(GameSessionManager.GUESS_MY_WORD_TOOLS)
                + list(GameSessionManager.USER_DESCRIBES_TOOLS)
                + list(GameSessionManager.IDIOM_CHAIN_TOOLS)
            )
        )

        for func_name in all_required_functions:
            if (
                func_name == DEVICE_REMINDER_TOOL_NAME
                and self._is_realtime(self.conn)
            ):
                continue
            if func_name in interactions:
                tools[func_name] = ToolDefinition(
                    name=func_name,
                    description=interaction_tool_description(
                        func_name, interactions[func_name], self._get_tool_prompt(func_name),
                    ),
                    tool_type=ToolType.SERVER_PLUGIN,
                )
                continue
            if func_name in GameSessionManager.FREEFORM_GAME_TOOLS or self._is_declarative_interaction_plugin(func_name):
                continue
            if func_name == "meeting_minutes" and not is_meeting_mode_enabled(
                self.conn
            ):
                continue
            if func_name in RETIRED_TOOL_NAMES:
                continue
            if (
                func_name == "meeting_minutes"
                and getattr(self.conn, "intent_type", None) != "function_call"
            ):
                continue
            func_item = all_function_registry.get(func_name)
            if func_item:
                tool_description = copy.deepcopy(func_item.description)
                # 资源中心目录提示词是全局覆盖；旧的角色插件 description
                # 仅作为兼容回退。业务参数仍只从 plugins 读取。
                fun_description = self._get_tool_prompt(func_name)
                if fun_description:
                    if "function" in tool_description and isinstance(
                        tool_description["function"], dict
                    ):
                        tool_description["function"]["description"] = fun_description

                # 新闻插件：根据配置更新新闻源参数描述
                if func_name == "get_news_from_newsnow":
                    self._init_news_source_description(tool_description, func_name)
                elif func_name == "get_news_from_chinanews":
                    self._init_chinanews_description(tool_description)
                elif func_name == "play_music":
                    self._init_music_description(tool_description)
                elif func_name == "web_search":
                    self._init_web_search_description(tool_description)
                elif func_name == "feishu_assistant":
                    self._init_feishu_description(tool_description)

                tools[func_name] = ToolDefinition(
                    name=func_name,
                    description=tool_description,
                    tool_type=ToolType.SERVER_PLUGIN,
                )
                continue

            if self._is_stateful_game_plugin(func_name):
                continue

            declarative_tool = self._build_declarative_interaction_tool(func_name)
            if declarative_tool:
                tools[func_name] = declarative_tool

        if interactions:
            tools[END_INTERACTION_TOOL] = ToolDefinition(
                name=END_INTERACTION_TOOL, description=end_interaction_tool_description(
                    self._get_tool_prompt(END_INTERACTION_TOOL),
                ),
                tool_type=ToolType.SERVER_PLUGIN,
            )
        return tools

    @staticmethod
    def _is_realtime(conn) -> bool:
        router = getattr(conn, "conversation_router", None)
        return bool(router is not None and getattr(router, "is_realtime", False))

    def has_tool(self, tool_name: str) -> bool:
        """检查是否有指定的服务端插件工具"""
        return tool_name in self.get_tools()

    def _init_news_source_description(self, tool_description, func_name):
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
            tool_description["function"]["parameters"]["properties"]["source"][
                "description"
            ] = f"新闻源的标准中文名称，例如{sources_str}等。可选参数，如果不提供则使用默认新闻源"
        except (KeyError, TypeError):
            pass

    def _init_music_description(self, tool_description):
        """Keep routing stable; the current library is supplied once by system context."""
        guidance = (
            " 仅当用户明确要求播放音乐时调用；询问曲库时直接依据本轮曲库上下文回答，不调用播放工具。"
        )
        try:
            function_desc = tool_description["function"]
            base_desc = str(function_desc.get("description") or "")
            if guidance.strip() not in base_desc:
                function_desc["description"] = guidance.strip() + " " + base_desc
        except (KeyError, TypeError):
            pass

    def _init_chinanews_description(self, tool_description):
        """Expose the actual feed contract even with an older catalog prompt."""
        from plugins_func.functions.get_news_from_chinanews import get_supported_categories

        categories = get_supported_categories(
            self._get_plugin_config("get_news_from_chinanews")
        )
        guidance = (
            "实际能力约束优先：本工具仅取配置新闻源的一条新闻，不能提供热点排行、"
            "多条热点汇总或任意主题检索。指定类别只能使用category枚举中的值，"
            "不支持的类别不得替换成社会新闻；不指定类别才使用默认新闻源。"
        )
        if (
            "web_search" in configured_function_names(self.config)
            and "web_search" in all_function_registry
            and not self._is_declarative_interaction_plugin("web_search")
        ):
            guidance += "热点汇总、指定主题或实时榜单应选择当前可用的web_search检索。"
        else:
            guidance += "当前未启用网页搜索；超出本工具能力时说明限制，不得声称已检索或编造新闻。"
        try:
            function_desc = tool_description["function"]
            function_desc["description"] = guidance + " " + str(
                function_desc.get("description") or ""
            )
            properties = function_desc["parameters"]["properties"]
            if not categories:
                # An empty enum is rejected by some model APIs. With only a
                # default feed, omit the optional selector altogether.
                properties.pop("category", None)
                return
            category_schema = properties["category"]
            category_schema["enum"] = categories
            category_schema["description"] = (
                "可选，仅允许当前已配置的类别：" + "、".join(categories)
                + "。未指定类别时省略此参数，使用默认源；不能将不支持的类别改写成默认类别。"
            )
        except (KeyError, TypeError):
            pass

    def _init_web_search_description(self, tool_description):
        """Keep web search routing precise even when backend config overrides description."""
        guidance = (
            " 仅用于用户要求的联网检索或最新外部事实，不用于本地知识库、播放或设备控制。"
            "相对时间依据本轮system中的北京时间；查询保留原话的相对时间、绝对日期、对象及事实目标，"
            "不得改成泛泛资料或补充未确认的对象、地点、日期。"
            "时效检索同时设置time_range：今天为day，最近或最新通常为week，"
            "用户明确范围优先，可选day/week/month/year；历史查询或无时效要求时省略。"
        )
        try:
            function_desc = tool_description["function"]
            base_desc = str(function_desc.get("description") or "")
            if guidance.strip() not in base_desc:
                function_desc["description"] = guidance.strip() + " " + base_desc
        except (KeyError, TypeError):
            pass

    def _init_feishu_description(self, tool_description):
        plugin_config = self._get_plugin_config("feishu_assistant")
        allowed_actions = [
            action
            for action in (
                "send_message",
                "list_messages",
                "list_tasks",
                "create_task",
                "create_calendar_event",
            )
            if plugin_config.get(action) is True
        ]
        try:
            action_schema = tool_description["function"]["parameters"]["properties"]["action"]
            action_schema["enum"] = allowed_actions
            action_schema["description"] = (
                "当前智能体允许的飞书动作：" + "、".join(allowed_actions)
            )
        except (KeyError, TypeError):
            pass

    def _get_plugin_config(self, func_name):
        """获取单个插件配置，兼容异常配置格式。"""
        plugins = self.config.get("plugins", {}) or {}
        plugin_config = plugins.get(func_name, {}) if isinstance(plugins, dict) else {}
        return plugin_config if isinstance(plugin_config, dict) else {}

    def _get_tool_prompt(self, func_name):
        """读取目录级工具提示词，并兼容旧的角色级 description。"""
        overrides = self.config.get("tool_prompt_overrides", {}) or {}
        if isinstance(overrides, dict):
            prompt = overrides.get(func_name)
            if isinstance(prompt, str) and prompt.strip():
                return prompt.strip()

        legacy_prompt = self._get_plugin_config(func_name).get("description")
        if isinstance(legacy_prompt, str) and legacy_prompt.strip():
            return legacy_prompt.strip()
        return ""

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

    def _is_stateful_game_plugin(self, func_name):
        manager = getattr(self.conn, "game_session_manager", None)
        if manager and (
            manager.is_guess_my_word_tool(func_name)
            or manager.is_user_describes_tool(func_name)
            or manager.is_idiom_chain_tool(func_name)
        ):
            return True
        return (
            func_name in GameSessionManager.GUESS_MY_WORD_TOOLS
            or func_name in GameSessionManager.USER_DESCRIBES_TOOLS
            or func_name in GameSessionManager.IDIOM_CHAIN_TOOLS
        )

    def _build_declarative_interaction_tool(self, func_name):
        config = available_interactions(self.conn).get(func_name)
        if config is None:
            return None
        return ToolDefinition(
            name=func_name, description=interaction_tool_description(
                func_name, config, self._get_tool_prompt(func_name),
            ),
            tool_type=ToolType.SERVER_PLUGIN,
        )

    def _execute_stateful_game_plugin(self, tool_name, arguments):
        if not self._is_stateful_game_plugin(tool_name):
            return None
        manager = getattr(self.conn, "game_session_manager", None)
        if manager is None:
            return None
        user_message = ""
        if isinstance(arguments, dict):
            user_message = arguments.get("user_message") or arguments.get("query") or ""
        if manager.is_idiom_chain_tool(tool_name):
            response = manager.handle_idiom_chain(tool_name, user_message)
        elif manager.is_user_describes_tool(tool_name):
            response = manager.handle_user_describes_guess(tool_name, user_message)
        else:
            response = manager.handle_guess_my_word(tool_name, user_message)
        return ActionResponse(action=Action.RESPONSE, response=response)

    def _execute_declarative_interaction_plugin(self, tool_name, arguments):
        # Arguments never become rules or duplicate user history. Selection is
        # authorized again at execution, including in-place configuration changes.
        if not activate_interaction(self.conn, tool_name):
            return ActionResponse(action=Action.NOTFOUND, response="活动未启用或当前模型不支持")
        return ActionResponse(
            action=Action.REQLLM,
            result=json.dumps({"activity_status": "active", "activity": tool_name},
                              ensure_ascii=False),
        )
