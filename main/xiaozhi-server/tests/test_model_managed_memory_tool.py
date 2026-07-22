import asyncio
import sys
from types import ModuleType
from types import SimpleNamespace


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

from config.manage_api_client import (
    ManageApiClient,
    forget_managed_memory,
    remember_managed_memory,
)
from core.providers.tools.server_plugins.plugin_executor import ServerPluginExecutor
from plugins_func.functions import manage_memory as memory_tool_module
from plugins_func.register import Action


class FakeLoop:
    def is_running(self):
        return True


class ImmediateFuture:
    def __init__(self, coroutine):
        self.coroutine = coroutine

    def result(self, timeout=None):
        return asyncio.run(self.coroutine)


def managed_config(memory_type="managed_memory"):
    return {
        "agent_id": "agent-1",
        "selected_module": {"Memory": "Memory_active"},
        "Memory": {"Memory_active": {"type": memory_type}},
    }


def test_manage_memory_tool_is_exposed_only_for_managed_memory():
    managed = SimpleNamespace(config=managed_config(), intent_type="function_call")
    disabled = SimpleNamespace(config=managed_config("nomem"), intent_type="function_call")

    assert "manage_memory" in ServerPluginExecutor(managed).get_tools()
    assert "manage_memory" not in ServerPluginExecutor(disabled).get_tools()


def test_tool_description_requires_proactive_cross_session_judgment_without_business_rules():
    description = memory_tool_module.MANAGE_MEMORY_FUNCTION_DESC["function"]["description"]

    assert "周文杰" not in description
    assert "高瑞瑞" not in description
    assert "空调" not in description
    assert "每轮对话都先判断" in description
    assert "不需要等待用户明确要求记住" in description
    assert "一次性请求" in description
    assert "服务端不会扫描聊天" in description


def test_main_llm_tool_forwards_private_model_decision_and_current_speaker(monkeypatch):
    captured = {}

    async def fake_remember(mac_address, agent_id, text, **kwargs):
        captured.update(
            mac_address=mac_address,
            agent_id=agent_id,
            text=text,
            **kwargs,
        )
        return True

    monkeypatch.setattr(memory_tool_module, "remember_managed_memory", fake_remember)
    monkeypatch.setattr(
        memory_tool_module.asyncio,
        "run_coroutine_threadsafe",
        lambda coroutine, loop: ImmediateFuture(coroutine),
    )
    conn = SimpleNamespace(
        config=managed_config(),
        device_id="AA:BB:CC",
        current_speaker_id="voice-1",
        current_speaker="用户A",
        loop=FakeLoop(),
        logger=FakeLogger(),
    )

    result = memory_tool_module.manage_memory(
        conn,
        operation="remember",
        text="模型自主生成的记忆正文",
        visibility="private",
        category="模型自主分类",
    )

    assert result.action == Action.REQLLM
    assert captured == {
        "mac_address": "AA:BB:CC",
        "agent_id": "agent-1",
        "text": "模型自主生成的记忆正文",
        "category": "模型自主分类",
        "speaker_id": "voice-1",
        "speaker_name": "用户A",
        "shared": False,
    }


def test_main_llm_can_make_memory_shared_without_business_specific_matching(monkeypatch):
    captured = {}

    async def fake_remember(mac_address, agent_id, text, **kwargs):
        captured.update(text=text, **kwargs)
        return True

    monkeypatch.setattr(memory_tool_module, "remember_managed_memory", fake_remember)
    monkeypatch.setattr(
        memory_tool_module.asyncio,
        "run_coroutine_threadsafe",
        lambda coroutine, loop: ImmediateFuture(coroutine),
    )
    conn = SimpleNamespace(
        config=managed_config(),
        device_id="AA:BB:CC",
        current_speaker_id="voice-1",
        current_speaker="用户A",
        loop=FakeLoop(),
        logger=FakeLogger(),
    )

    result = memory_tool_module.manage_memory(
        conn,
        operation="remember",
        text="模型判断应在后续多人物对话中使用的内容",
        visibility="shared",
    )

    assert result.action == Action.REQLLM
    assert captured["shared"] is True
    assert captured["speaker_id"] == "voice-1"


def test_manage_api_memory_actions_use_server_endpoints():
    calls = []

    class FakeClient:
        async def _execute_async_request(self, method, endpoint, **kwargs):
            calls.append((method, endpoint, kwargs["json"]))
            return None

    original = ManageApiClient._instance
    ManageApiClient._instance = FakeClient()
    try:
        assert asyncio.run(
            remember_managed_memory(
                "AA:BB:CC",
                "agent-1",
                "正文",
                category="分类",
                speaker_id="voice-1",
                speaker_name="用户A",
                shared=True,
            )
        )
        assert asyncio.run(
            forget_managed_memory(
                "AA:BB:CC",
                "agent-1",
                "忘记目标",
                reason="模型决定",
                speaker_id="voice-1",
                speaker_name="用户A",
                shared=False,
            )
        )
    finally:
        ManageApiClient._instance = original

    assert calls[0][0:2] == ("POST", "/config/memory/remember")
    assert calls[0][2]["speakerId"] == "voice-1"
    assert calls[0][2]["shared"] is True
    assert calls[1][0:2] == ("POST", "/config/memory/forget")
    assert calls[1][2]["reason"] == "模型决定"
    assert calls[1][2]["shared"] is False
    assert "patternType" not in calls[1][2]
