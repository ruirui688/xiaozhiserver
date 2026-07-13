import asyncio
import sys
from types import ModuleType


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
from plugins_func.register import Action


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
    handler.conn = object()
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
