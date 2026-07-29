import asyncio
import json
import sys
from types import ModuleType, SimpleNamespace


class FakeLogger:
    def bind(self, **_kwargs):
        return self

    def debug(self, *_args, **_kwargs):
        return None

    def info(self, *_args, **_kwargs):
        return None

    def warning(self, *_args, **_kwargs):
        return None

    def error(self, *_args, **_kwargs):
        return None


fake_logger_module = ModuleType("config.logger")
fake_logger_module.setup_logging = lambda: FakeLogger()
fake_logger_module.build_module_string = lambda *_args, **_kwargs: "test"
fake_logger_module.create_connection_logger = (
    lambda *_args, **_kwargs: FakeLogger()
)
sys.modules.setdefault("config.logger", fake_logger_module)

from core.providers.tools.device_mcp.mcp_client import MCPClient
from core.providers.tools.device_mcp.mcp_handler import call_mcp_tool, handle_mcp_message
from core.utils import textUtils


def test_device_tool_discovery_keeps_schema_and_ignores_custom_annotations():
    client = MCPClient()
    conn = SimpleNamespace(headers={}, func_handler=None)
    payload = {
        "id": 2,
        "result": {
            "tools": [
                {
                    "name": "self.robot.take_snapshot",
                    "description": "Take a snapshot.",
                    "inputSchema": {
                        "type": "object",
                        "properties": {"request_id": {"type": "string"}},
                        "required": ["request_id"],
                    },
                    "annotations": {
                        "turnBehavior": "terminate_silently",
                        "serverGenerated": ["request_id"],
                    },
                }
            ]
        },
    }

    asyncio.run(handle_mcp_message(conn, client, payload))

    registered = client.tools["self_robot_take_snapshot"]
    assert "terminalTurn" not in registered
    assert "server_generated" not in registered
    assert registered["inputSchema"] == {
        "type": "object",
        "properties": {"request_id": {"type": "string"}},
        "required": ["request_id"],
    }
    assert asyncio.run(client.is_ready()) is True


def test_device_tool_call_forwards_model_arguments_without_server_injection():
    async def run_call():
        client = MCPClient()
        client.next_id = 123
        await client.add_tool(
            {
                "name": "self.robot.take_snapshot",
                "description": "Take a snapshot.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"request_id": {"type": "string"}},
                    "required": ["request_id"],
                },
            }
        )
        await client.set_ready(True)

        class ReplyingWebSocket:
            def __init__(self):
                self.messages = []

            async def send(self, message):
                self.messages.append(json.loads(message))
                request = self.messages[-1]["payload"]
                await client.resolve_call_result(
                    request["id"],
                    {"content": [{"type": "text", "text": "OK"}]},
                )

        websocket = ReplyingWebSocket()
        conn = SimpleNamespace(features={"mcp": True}, websocket=websocket)
        result = await call_mcp_tool(
            conn,
            client,
            "self_robot_take_snapshot",
            '{"request_id":"model-request"}',
        )
        return result, websocket.messages

    result, messages = asyncio.run(run_call())

    assert result == "OK"
    request = messages[0]["payload"]
    assert request["id"] == 123
    assert request["params"]["arguments"] == {
        "request_id": "model-request"
    }


def test_stale_emotion_is_not_sent_after_abort_generation_changes():
    class WebSocket:
        def __init__(self):
            self.messages = []

        async def send(self, message):
            self.messages.append(message)

    websocket = WebSocket()
    conn = SimpleNamespace(
        client_abort=True,
        abort_generation=6,
        sentence_id="sentence-2",
        websocket=websocket,
        logger=FakeLogger(),
    )

    asyncio.run(
        textUtils.get_emotion(
            conn,
            "ordinary response",
            abort_generation=5,
            sentence_id="sentence-1",
        )
    )

    assert websocket.messages == []
