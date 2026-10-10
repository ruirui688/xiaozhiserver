"""Realtime memory expiry/revocation must discard upstream history."""

import asyncio
import time
from types import SimpleNamespace

import pytest
import config.settings

config.settings.config_file_valid = True

from core.conversation.router import ConversationRouter
from core.session.managed_memory_snapshot import snapshot_for
from core.session.realtime_memory import append_realtime_memory


def setup_connection(adapter="qwen-omni-realtime"):
    logger = SimpleNamespace(info=lambda *a, **k: None, warning=lambda *a, **k: None)
    conn = SimpleNamespace(
        config={"agent_id": "agent", "selected_module": {"Memory": "mem", "LLM": "rt"},
                "Memory": {"mem": {"type": "managed_memory"}},
                "LLM": {"rt": {"type": adapter, "conversation_mode": "realtime"}}},
        device_id="device", prompt="role", current_speaker_id=None,
        func_handler=SimpleNamespace(get_functions=lambda: [], get_tool_statistics=lambda: {}),
        logger=SimpleNamespace(bind=lambda **kw: logger),
        audio_input_ready=asyncio.Event(), conversation_ready=asyncio.Event(),
    )
    return conn


def install(conn, text):
    now = int(time.time() * 1000)
    state = snapshot_for(conn)
    assert state.install({"schema_version": 1, "status": "ready", "agent_id": "agent", "device_id": "device",
                          "revision": "a" * 64, "issued_at_ms": now, "expires_at_ms": now + 30000,
                          "shared_context": text, "speaker_contexts": {}}, state.epoch)


@pytest.mark.parametrize("adapter", ["qwen-omni-realtime", "doubao-realtime", "doubao-duplex"])
def test_realtime_uses_same_snapshot_and_rebuilds_on_forget_and_expiry(adapter):
    created = []

    class Session:
        def __init__(self, conn, _config):
            self.conn, self.closed = conn, False
            created.append(self)

        async def initialize(self):
            self.prompt = append_realtime_memory(self.conn, "role")

        async def close(self):
            self.closed = True

        async def handle_text(self, text):
            self.text = text
            return True

    async def run():
        conn = setup_connection(adapter)
        install(conn, "- likes tea <untrusted>")
        router = ConversationRouter(conn, session_factory=Session)
        await router.initialize()
        assert "likes tea &lt;untrusted&gt;" in created[0].prompt
        install(conn, "- likes coffee")
        await router.handle_text("what do I like?")
        assert created[0].closed
        assert "likes coffee" in created[1].prompt
        assert "likes tea" not in created[1].prompt
        snapshot_for(conn).invalidate()
        await router.handle_text("forget that")
        assert created[1].closed
        assert "likes coffee" not in created[2].prompt
        install(conn, "- temporary fact")
        await router.handle_text("next")
        snapshot_for(conn).deadline = 0
        await router.handle_text("expired")
        assert created[-2].closed
        assert "temporary fact" not in created[-1].prompt
        await router.close()
        assert all(item.closed for item in created)

    asyncio.run(run())


def test_realtime_refresh_failure_closes_old_session_and_rejects_new_input():
    created = []

    class Session:
        def __init__(self, conn, _config):
            self.conn, self.closed = conn, False
            created.append(self)

        async def initialize(self):
            if len(created) > 1:
                raise ConnectionError("offline")
            append_realtime_memory(self.conn, "role")

        async def close(self):
            self.closed = True

    async def run():
        conn = setup_connection()
        install(conn, "- private fact")
        router = ConversationRouter(conn, session_factory=Session)
        await router.initialize()
        snapshot_for(conn).invalidate()
        with pytest.raises(ConnectionError):
            await router.handle_text("next")
        assert router.session is None
        assert all(item.closed for item in created)
        assert not conn.audio_input_ready.is_set()
        with pytest.raises(RuntimeError, match="memory_refresh_failed"):
            await router.handle_text("retry")
        await router.close()

    asyncio.run(run())
