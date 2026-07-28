import asyncio
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

from core.providers.tools.device_mcp.mcp_handler import (
    MCPClient,
    handle_mcp_message,
)
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
                    },
                }
            ]
        },
    }

    asyncio.run(handle_mcp_message(conn, client, payload))

    registered = client.tools["self_robot_stop_speaking"]
    assert registered["terminalTurn"] is True
    assert asyncio.run(client.is_ready()) is True


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
