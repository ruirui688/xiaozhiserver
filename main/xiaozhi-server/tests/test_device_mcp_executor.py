import asyncio
import json
import sys
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.providers.tools.device_mcp import mcp_executor
from core.providers.tools.device_mcp.mcp_executor import DeviceMCPExecutor
from plugins_func.register import Action


class ReadyMCPClient:
    async def is_ready(self):
        return True


class Conn:
    def __init__(self):
        self.mcp_client = ReadyMCPClient()
        self.config = {}


def test_device_volume_timeout_does_not_claim_success(monkeypatch):
    async def timeout_call(*args, **kwargs):
        raise TimeoutError("工具调用请求超时")

    monkeypatch.setattr(mcp_executor, "call_mcp_tool", timeout_call)

    result = asyncio.run(
        DeviceMCPExecutor(Conn()).execute(
            Conn(),
            "self_audio_speaker_set_volume",
            {"volume": 60},
        )
    )

    assert result.action == Action.REQLLM
    payload = json.loads(result.result)
    assert payload["tool"] == "self_audio_speaker_set_volume"
    assert payload["arguments"] == {"volume": 60}
    assert payload["transport_result_received"] is False
    assert payload["state_verified"] is False
    assert payload["error"] == "timeout"


def test_device_volume_success_returns_structured_result_for_llm(monkeypatch):
    async def ok_call(*args, **kwargs):
        return "OK"

    monkeypatch.setattr(mcp_executor, "call_mcp_tool", ok_call)

    result = asyncio.run(
        DeviceMCPExecutor(Conn()).execute(
            Conn(),
            "self_audio_speaker_set_volume",
            {"volume": 60},
        )
    )

    assert result.action == Action.REQLLM
    payload = json.loads(result.result)
    assert payload["tool"] == "self_audio_speaker_set_volume"
    assert payload["arguments"] == {"volume": 60}
    assert payload["transport_result_received"] is True
    assert payload["state_verified"] is False
    assert payload["raw_result"] == "OK"
