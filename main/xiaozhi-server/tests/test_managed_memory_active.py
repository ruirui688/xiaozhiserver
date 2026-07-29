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
original_logger_module = sys.modules.get("config.logger")
sys.modules["config.logger"] = fake_logger_module

from config.manage_api_client import ManageApiClient, process_managed_memory_session
from core.providers.memory.managed_memory import managed_memory as provider_module
from core.providers.tools.server_plugins.plugin_executor import ServerPluginExecutor

if original_logger_module is None:
    del sys.modules["config.logger"]
else:
    sys.modules["config.logger"] = original_logger_module


def managed_config(memory_type="managed_memory"):
    return {
        "agent_id": "agent-1",
        "selected_module": {
            "Memory": "Memory_active",
            "Intent": "Intent_function_call",
        },
        "Memory": {"Memory_active": {"type": memory_type}},
        "Intent": {"Intent_function_call": {"functions": ["manage_memory"]}},
    }


def test_manage_memory_tool_is_never_exposed_even_when_stale_config_remains():
    conn = SimpleNamespace(config=managed_config(), intent_type="function_call")
    executor = ServerPluginExecutor(conn)

    assert "manage_memory" not in executor.get_tools()
    assert executor.has_tool("manage_memory") is False


def test_managed_memory_provider_submits_complete_non_temporary_dialogue(monkeypatch):
    captured = {}

    async def fake_process(mac_address, agent_id, session_id, messages):
        captured.update(
            mac_address=mac_address,
            agent_id=agent_id,
            session_id=session_id,
            messages=messages,
        )
        return 2

    monkeypatch.setattr(provider_module, "process_managed_memory_session", fake_process)
    provider = provider_module.MemoryProvider({"type": "managed_memory"})
    provider.init_memory("AA:BB:CC", llm=None, agent_id="agent-1")
    dialogue = [
        SimpleNamespace(role="system", content="系统提示", is_temporary=False),
        SimpleNamespace(
            role="user",
            content='{"content":"我喜欢跑步","speaker":"用户A","speaker_id":"voice-1"}',
            is_temporary=False,
        ),
        SimpleNamespace(role="assistant", content="记住了你的爱好。", is_temporary=False),
        SimpleNamespace(role="user", content="临时示例", is_temporary=True),
    ]

    changed = asyncio.run(provider.save_memory(dialogue, "session-1"))

    assert changed == 2
    assert captured == {
        "mac_address": "AA:BB:CC",
        "agent_id": "agent-1",
        "session_id": "session-1",
        "messages": [
            {
                "role": "user",
                "content": '{"content":"我喜欢跑步","speaker":"用户A","speaker_id":"voice-1"}',
            },
            {"role": "assistant", "content": "记住了你的爱好。"},
        ],
    }


def test_process_managed_memory_session_uses_server_endpoint():
    calls = []

    class FakeClient:
        async def _execute_async_request(self, method, endpoint, **kwargs):
            calls.append((method, endpoint, kwargs["json"]))
            return 3

    original = ManageApiClient._instance
    ManageApiClient._instance = FakeClient()
    try:
        changed = asyncio.run(
            process_managed_memory_session(
                "AA:BB:CC",
                "agent-1",
                "session-1",
                [{"role": "user", "content": "我喜欢跑步"}],
            )
        )
    finally:
        ManageApiClient._instance = original

    assert changed == 3
    assert calls == [
        (
            "POST",
            "/config/memory/process-session",
            {
                "macAddress": "AA:BB:CC",
                "agentId": "agent-1",
                "sessionId": "session-1",
                "messages": [{"role": "user", "content": "我喜欢跑步"}],
            },
        )
    ]
