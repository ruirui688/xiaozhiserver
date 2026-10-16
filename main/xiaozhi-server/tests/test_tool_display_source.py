"""Legacy display compatibility without mislabelling tool progress as ASR."""

import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

from core.handle.sendAudioHandle import send_display_message


def test_tool_progress_has_explicit_source_without_starting_audio():
    websocket = SimpleNamespace(send=AsyncMock())
    conn = SimpleNamespace(websocket=websocket, session_id="test-session")
    asyncio.run(send_display_message(conn, "% device_reminder"))
    websocket.send.assert_awaited_once()
    assert json.loads(websocket.send.call_args.args[0]) == {
        "type": "stt", "source": "tool", "text": "% device_reminder",
        "session_id": "test-session",
    }
