"""Commerce discovery must never grant an LLM permission to dispense goods."""

import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from core.api.device_mcp_handler import DeviceMcpHandler
from core.providers.tools.base import ToolDefinition, ToolType
from core.providers.tools.commerce_policy import is_commerce_device_tool
from core.providers.tools.device_mcp.mcp_handler import MCPClient, call_mcp_tool
from core.providers.tools.unified_tool_manager import ToolManager
from plugins_func.register import Action


@pytest.mark.parametrize("name", [
    "self.commerce.fulfillment.submit",
    "self_commerce_fulfillment_submit",
    "mcp_self.commerce.fulfillment.submit",
    "mcp_mcp_self_commerce_fulfillment_submit",
    "self.commerce.display.show",
    "self.commerce.fulfillment.get",
])
def test_reserved_names_and_aliases(name):
    assert is_commerce_device_tool(name)


@pytest.mark.parametrize("name", [
    "self.audio.speaker.set_volume", "self_robot_take_snapshot",
    "get_weather", "commerce_catalog_search", None,
])
def test_unrelated_tools_unchanged(name):
    assert not is_commerce_device_tool(name)


@pytest.mark.parametrize("source", list(ToolType))
def test_all_tool_sources_exclude_commerce_from_model_and_execution(source):
    hidden = "self_commerce_fulfillment_submit"
    allowed = "get_weather"
    definitions = {
        name: ToolDefinition(name, {"type": "function", "function": {
            "name": name, "description": "test", "parameters": {"type": "object"}
        }}, source)
        for name in (hidden, allowed)
    }
    executor = SimpleNamespace(get_tools=lambda: definitions, execute=AsyncMock())
    manager = ToolManager(None)
    manager.register_executor(source, executor)
    assert list(manager.get_all_tools()) == [allowed]
    assert [item["function"]["name"] for item in manager.get_function_descriptions()] == [allowed]
    result = asyncio.run(manager.execute_tool(hidden, {"paid": True}))
    assert result.action == Action.ERROR
    executor.execute.assert_not_called()


def test_schema_alias_cannot_publish_reserved_name():
    tool = ToolDefinition("alias", {"function": {
        "name": "self.commerce.fulfillment.submit"
    }}, ToolType.SERVER_MCP)
    manager = ToolManager(None)
    manager.register_executor(ToolType.SERVER_MCP, SimpleNamespace(get_tools=lambda: {"alias": tool}))
    assert manager.get_all_tools() == {}


@pytest.mark.parametrize("alias", [False, True])
def test_device_transport_rejects_before_sending_or_registering_future(alias):
    async def run():
        client = MCPClient()
        await client.add_tool({
            "name": "self.commerce.fulfillment.submit",
            "description": "Dispense", "inputSchema": {"type": "object"},
        })
        await client.set_ready(True)
        name = "self_commerce_fulfillment_submit"
        if alias:
            client.name_mapping["alias"] = "self.commerce.fulfillment.submit"
            name = "alias"
        conn = SimpleNamespace(websocket=SimpleNamespace(send=AsyncMock()))
        with pytest.raises(PermissionError):
            await call_mcp_tool(conn, client, name, {"paid": True})
        conn.websocket.send.assert_not_called()
        assert client.call_results == {}
        # Keep discovery intact for integration diagnostics.
        assert client.has_tool("self_commerce_fulfillment_submit")
    asyncio.run(run())


def test_generic_http_call_cannot_authorize_commerce_with_shared_secret():
    handler = object.__new__(DeviceMcpHandler)
    handler._is_authorized = lambda request: True
    handler._json_response = lambda body, status=200: (status, body)
    request = SimpleNamespace(json=AsyncMock(return_value={
        "deviceId": "robot-1", "name": "self.commerce.fulfillment.submit",
        "arguments": {"paid": True, "orderId": "model-selected-order"},
    }))
    status, body = asyncio.run(handler.handle_call(request))
    assert status == 403
    assert body["success"] is False
