import asyncio
import sys
from types import ModuleType, SimpleNamespace


fake_logger_module = ModuleType("config.logger")


class FakeLogger:
    def bind(self, **kwargs):
        return self

    def debug(self, *args, **kwargs):
        return None

    def info(self, *args, **kwargs):
        return None

    def warning(self, *args, **kwargs):
        return None

    def error(self, *args, **kwargs):
        return None


fake_logger_module.setup_logging = lambda: FakeLogger()
sys.modules.setdefault("config.logger", fake_logger_module)

from core.providers.tools import unified_tool_handler as handler_module
from core.providers.tools.base import ToolDefinition, ToolType
from core.providers.tools.unified_tool_manager import ToolManager
from plugins_func.register import Action, ActionResponse


def test_unregistered_tool_is_rejected_before_device_display(monkeypatch):
    displayed = []

    async def fake_display_message(conn, message):
        displayed.append(message)

    class FakeToolManager:
        def has_tool(self, tool_name):
            return False

        async def execute_tool(self, tool_name, arguments):
            raise AssertionError("unregistered tool must not reach executor")

    handler = object.__new__(handler_module.UnifiedToolHandler)
    handler.conn = SimpleNamespace(session_id="session-a")
    handler.logger = FakeLogger()
    handler.tool_manager = FakeToolManager()
    monkeypatch.setattr(handler_module, "send_display_message", fake_display_message)

    result = asyncio.run(
        handler.handle_llm_function_call(
            handler.conn,
            {"name": "web_search", "arguments": {"query": "test"}},
        )
    )

    assert result.action == Action.NOTFOUND
    assert displayed == []


def test_device_reminder_gets_stable_trusted_call_id_without_mutating_input(
    monkeypatch,
):
    executed = []

    async def fake_display_message(_conn, _message):
        return None

    class FakeToolManager:
        def has_tool(self, tool_name):
            return tool_name == "device_reminder"

        def get_tool_type(self, tool_name):
            return ToolType.SERVER_PLUGIN if self.has_tool(tool_name) else None

        async def execute_tool(self, tool_name, arguments):
            executed.append((tool_name, arguments))
            return ActionResponse(action=Action.RESPONSE, response="ok")

    handler = object.__new__(handler_module.UnifiedToolHandler)
    handler.conn = SimpleNamespace(session_id="session-a")
    handler.logger = FakeLogger()
    handler.tool_manager = FakeToolManager()
    monkeypatch.setattr(handler_module, "send_display_message", fake_display_message)
    model_arguments = {
        "action": "create",
        "title": "敏感提醒标题",
        "request_id": "model-controlled-id",
    }
    call = {
        "id": "stable-tool-call-id",
        "name": "device_reminder",
        "arguments": model_arguments,
    }

    first = asyncio.run(handler.handle_llm_function_call(handler.conn, call))
    second = asyncio.run(handler.handle_llm_function_call(handler.conn, call))

    assert first.action == second.action == Action.RESPONSE
    expected_request_id = handler_module._derive_internal_request_id(
        "session-a", "stable-tool-call-id"
    )
    assert [arguments["request_id"] for _, arguments in executed] == [
        expected_request_id,
        expected_request_id,
    ]
    assert all(arguments["title"] == "敏感提醒标题" for _, arguments in executed)
    assert model_arguments["request_id"] == "model-controlled-id"


def test_device_reminder_without_valid_call_id_fails_closed(monkeypatch):
    executed = []

    class FakeToolManager:
        def has_tool(self, tool_name):
            return tool_name == "device_reminder"

        def get_tool_type(self, tool_name):
            return ToolType.SERVER_PLUGIN if self.has_tool(tool_name) else None

        async def execute_tool(self, tool_name, arguments):
            executed.append((tool_name, arguments))
            return ActionResponse(action=Action.RESPONSE, response="unexpected")

    handler = object.__new__(handler_module.UnifiedToolHandler)
    handler.conn = SimpleNamespace(session_id="session-a")
    handler.logger = FakeLogger()
    handler.tool_manager = FakeToolManager()

    for invalid_id in (None, "", "   "):
        result = asyncio.run(
            handler.handle_llm_function_call(
                handler.conn,
                {
                    "id": invalid_id,
                    "name": "device_reminder",
                    "arguments": {"action": "list"},
                },
            )
        )
        assert result.action == Action.ERROR

    assert executed == []


def test_device_reminder_request_ids_are_session_scoped_stable_and_bounded():
    short_id = "provider-call-id"
    long_id = "x" * 256
    control_id = "provider\ncall"

    normalized_short = handler_module._derive_internal_request_id(
        "session-a", short_id
    )
    normalized_long = handler_module._derive_internal_request_id(
        "session-a", long_id
    )

    assert normalized_short == handler_module._derive_internal_request_id(
        "session-a", short_id
    )
    assert normalized_short != handler_module._derive_internal_request_id(
        "session-b", short_id
    )
    assert normalized_long == handler_module._derive_internal_request_id(
        "session-a", long_id
    )
    assert normalized_long != handler_module._derive_internal_request_id(
        "session-a", "y" * 256
    )
    assert normalized_long != handler_module._derive_internal_request_id(
        "session-a", control_id
    )
    assert len(normalized_short) <= 128
    assert len(normalized_long) <= 128
    assert len(
        handler_module._derive_internal_request_id("session-a", "z" * 129)
    ) <= 128
    assert "\n" not in handler_module._derive_internal_request_id(
        "session-a", control_id
    )
    assert handler_module._derive_internal_request_id("session-a", "   ") is None
    assert handler_module._derive_internal_request_id("", short_id) is None
    assert handler_module._derive_internal_request_id(None, short_id) is None
    assert handler_module._derive_internal_request_id("   ", short_id) is None
    assert handler_module._derive_internal_request_id(
        "session", "a\u0000bc"
    ) != handler_module._derive_internal_request_id("sessiona", "\u0000bc")


def test_batch_response_merge_preserves_restrictive_follow_up_metadata():
    handler = object.__new__(handler_module.UnifiedToolHandler)
    responses = [
        ActionResponse(
            action=Action.REQLLM,
            result="日程数据",
            next_llm_tools_enabled=False,
            next_llm_system_prompt="不要猜测日程。",
        ),
        ActionResponse(
            action=Action.REQLLM,
            result="其他数据",
            next_llm_tools_enabled=None,
            next_llm_system_prompt="只回答当前问题。",
        ),
    ]

    result = handler._combine_responses(responses)

    assert result.action == Action.REQLLM
    assert result.next_llm_tools_enabled is False
    assert result.next_llm_system_prompt == "不要猜测日程。\n\n只回答当前问题。"


def test_device_reminder_rejects_non_server_plugin_source_before_injection():
    executed = []

    class FakeToolManager:
        def has_tool(self, tool_name):
            return tool_name == "device_reminder"

        def get_tool_type(self, _tool_name):
            return ToolType.DEVICE_MCP

        async def execute_tool(self, tool_name, arguments):
            executed.append((tool_name, arguments))
            return ActionResponse(action=Action.RESPONSE, response="unexpected")

    handler = object.__new__(handler_module.UnifiedToolHandler)
    handler.conn = SimpleNamespace(session_id="session-a")
    handler.logger = FakeLogger()
    handler.tool_manager = FakeToolManager()

    result = asyncio.run(
        handler.handle_llm_function_call(
            handler.conn,
            {
                "id": "provider-call-id",
                "name": "device_reminder",
                "arguments": {"action": "create", "request_id": "model-id"},
            },
        )
    )

    assert result.action == Action.ERROR
    assert "来源无效" in result.response
    assert executed == []


def test_disabled_server_plugin_reservation_blocks_all_downstream_aliases():
    executions = []

    class StaticExecutor:
        def __init__(self, definitions):
            self.definitions = definitions

        def get_tools(self):
            return self.definitions

        async def execute(self, _conn, tool_name, arguments):
            executions.append((tool_name, arguments))
            return ActionResponse(action=Action.RESPONSE, response="unexpected")

    def definition(name, schema_name, tool_type):
        return ToolDefinition(
            name=name,
            description={
                "type": "function",
                "function": {
                    "name": schema_name,
                    "description": "test",
                    "parameters": {"type": "object", "properties": {}},
                },
            },
            tool_type=tool_type,
        )

    downstream = {
        ToolType.SERVER_MCP: {
            "device_reminder": definition(
                "device_reminder", "device_reminder", ToolType.SERVER_MCP
            )
        },
        ToolType.DEVICE_IOT: {
            "iot_alias": definition(
                "device_reminder", "iot_alias", ToolType.DEVICE_IOT
            )
        },
        ToolType.DEVICE_MCP: {
            "schema_alias": definition(
                "schema_alias", "device_reminder", ToolType.DEVICE_MCP
            )
        },
        ToolType.MCP_ENDPOINT: {
            "mcp_mcp_device_reminder": definition(
                "mcp_mcp_device_reminder",
                "mcp_mcp_device_reminder",
                ToolType.MCP_ENDPOINT,
            )
        },
    }
    manager = ToolManager(conn=None)
    manager.register_executor(ToolType.SERVER_PLUGIN, StaticExecutor({}))
    for tool_type, definitions in downstream.items():
        manager.register_executor(tool_type, StaticExecutor(definitions))

    assert manager.get_all_tools() == {}
    assert manager.get_function_descriptions() == []
    result = asyncio.run(manager.execute_tool("device_reminder", {"action": "list"}))
    alias_result = asyncio.run(
        manager.execute_tool("mcp_mcp_device_reminder", {"action": "list"})
    )
    assert result.action == Action.NOTFOUND
    assert alias_result.action == Action.NOTFOUND
    assert executions == []


def test_internal_argument_injection_does_not_change_other_tools(monkeypatch):
    executed = []

    async def fake_display_message(_conn, _message):
        return None

    class FakeToolManager:
        def has_tool(self, tool_name):
            return tool_name == "other_tool"

        async def execute_tool(self, tool_name, arguments):
            executed.append((tool_name, arguments))
            return ActionResponse(action=Action.RESPONSE, response="ok")

    handler = object.__new__(handler_module.UnifiedToolHandler)
    handler.conn = object()
    handler.logger = FakeLogger()
    handler.tool_manager = FakeToolManager()
    monkeypatch.setattr(handler_module, "send_display_message", fake_display_message)
    arguments = {"request_id": "model-owned", "value": 7}

    result = asyncio.run(
        handler.handle_llm_function_call(
            handler.conn,
            {"id": "unrelated-call-id", "name": "other_tool", "arguments": arguments},
        )
    )

    assert result.action == Action.RESPONSE
    assert executed == [("other_tool", arguments)]
