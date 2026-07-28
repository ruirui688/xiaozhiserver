import asyncio
import json
import sys
import threading
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
from core.providers.tools.terminal_turn import (
    TERMINAL_TURN_BEHAVIOR,
    finish_terminal_turn,
    has_terminal_turn_annotation,
    select_terminal_tool_calls,
)
from core.utils import textUtils


def test_terminal_annotation_requires_explicit_protocol_value():
    assert has_terminal_turn_annotation(
        {"turnBehavior": TERMINAL_TURN_BEHAVIOR}
    )
    assert not has_terminal_turn_annotation({"turnBehavior": "reply"})
    assert not has_terminal_turn_annotation("terminate_silently")


def test_device_tool_discovery_preserves_terminal_capability():
    client = MCPClient()
    conn = SimpleNamespace(headers={}, func_handler=None)
    payload = {
        "id": 2,
        "result": {
            "tools": [
                {
                    "name": "self.robot.stop_speaking",
                    "description": "Model-decided terminal control.",
                    "inputSchema": {
                        "type": "object",
                        "properties": {"request_id": {"type": "string"}},
                        "required": ["request_id"],
                    },
                    "annotations": {
                        "turnBehavior": TERMINAL_TURN_BEHAVIOR,
                        "serverGenerated": ["request_id"],
                    },
                }
            ]
        },
    }

    asyncio.run(handle_mcp_message(conn, client, payload))

    registered = client.tools["self_robot_stop_speaking"]
    assert registered["terminalTurn"] is True
    assert registered["server_generated"] == ["request_id"]
    assert registered["inputSchema"] == {
        "type": "object",
        "properties": {},
        "required": [],
    }
    visible_schema = client.get_available_tools()[0]["function"]["parameters"]
    assert "request_id" not in visible_schema["properties"]
    assert "request_id" not in visible_schema["required"]
    assert asyncio.run(client.is_ready()) is True


def test_server_managed_request_id_uses_json_rpc_id_and_ignores_model_args():
    async def run_call():
        client = MCPClient()
        client.next_id = 123
        await client.add_tool(
            {
                "name": "self.robot.stop_speaking",
                "description": "Model-decided terminal control.",
                "inputSchema": {
                    "type": "object",
                    "properties": {},
                    "required": [],
                },
                "server_generated": ["request_id"],
            }
        )
        await client.set_ready(True)

        class ReplyingWebSocket:
            def __init__(self):
                self.messages = []

            async def send(self, message):
                self.messages.append(json.loads(message))
                payload = self.messages[-1]["payload"]
                await client.resolve_call_result(
                    payload["id"],
                    {"content": [{"type": "text", "text": "OK"}]},
                )

        websocket = ReplyingWebSocket()
        conn = SimpleNamespace(features={"mcp": True}, websocket=websocket)
        result = await call_mcp_tool(
            conn,
            client,
            "self_robot_stop_speaking",
            '{"request_id":"model-id","text":"hallucinated","content":"x"}',
        )
        return result, websocket.messages

    result, messages = asyncio.run(run_call())

    assert result == "OK"
    payload = messages[0]["payload"]
    assert payload["id"] == 123
    assert payload["params"]["arguments"] == {"request_id": "mcp-123"}


def test_unannotated_device_tool_keeps_model_visible_schema_and_arguments():
    client = MCPClient()
    conn = SimpleNamespace(headers={}, func_handler=None)
    payload = {
        "id": 2,
        "result": {
            "tools": [
                {
                    "name": "self.robot.perform_gesture",
                    "description": "Perform a gesture.",
                    "inputSchema": {
                        "type": "object",
                        "properties": {"motion": {"type": "string"}},
                        "required": ["motion"],
                    },
                }
            ]
        },
    }

    asyncio.run(handle_mcp_message(conn, client, payload))

    registered = client.tools["self_robot_perform_gesture"]
    assert registered["server_generated"] == []
    assert registered["inputSchema"]["properties"] == {
        "motion": {"type": "string"}
    }
    assert registered["inputSchema"]["required"] == ["motion"]


def test_terminal_selection_uses_registered_metadata_not_user_text():
    class Handler:
        @staticmethod
        def is_terminal_turn_tool(name):
            return name == "terminal_control"

    calls = [
        {"name": "ordinary_tool", "arguments": "{}"},
        {"name": "terminal_control", "arguments": "{}"},
    ]

    assert select_terminal_tool_calls(Handler(), calls) == [calls[1]]


def test_terminal_completion_aborts_generation_exactly_once(monkeypatch):
    conn = SimpleNamespace(
        _abort_lock=threading.Lock(),
        client_abort=False,
        abort_generation=4,
    )
    calls = []

    async def fake_abort(target):
        calls.append(target.abort_generation)
        target.client_abort = True
        target.abort_generation += 1

    monkeypatch.setattr(
        "core.handle.abortHandle.handleAbortMessage",
        fake_abort,
    )

    first = asyncio.run(finish_terminal_turn(conn, 4))
    duplicate = asyncio.run(finish_terminal_turn(conn, 4))

    assert first is True
    assert duplicate is False
    assert calls == [4]
    assert conn.abort_generation == 5


def test_stale_emotion_is_not_sent_after_generation_changes():
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
