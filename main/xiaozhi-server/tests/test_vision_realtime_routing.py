import asyncio
import json
from types import SimpleNamespace

from core.api.vision_handler import VisionHandler


class Field:
    def __init__(self, value, binary=False):
        self.value = value
        self.binary = binary

    async def text(self):
        return self.value

    async def read(self):
        return self.value if self.binary else self.value.encode()


class Reader:
    def __init__(self):
        self.fields = [Field("看到了什么"), Field(b"\x89PNG\r\n\x1a\nbody", True)]

    async def next(self):
        return self.fields.pop(0) if self.fields else None


class Request:
    headers = {
        "Client-Id": "web_test_client",
        "Device-Id": "device-1",
    }

    async def multipart(self):
        return Reader()


def _config():
    return {
        "server": {"auth_key": "test"},
        "selected_module": {"LLM": "rt"},
        "model_config": {
            "rt": {
                "model_type": "LLM",
                "typed_config": {
                    "type": "qwen_omni_realtime",
                    "conversation_mode": "realtime",
                },
            }
        },
    }


def test_online_realtime_connection_not_ready_does_not_fall_back_to_vllm(monkeypatch):
    router = SimpleNamespace(is_realtime=True, session=None)
    monkeypatch.setattr(
        "core.api.vision_handler.get_connection",
        lambda _device_id: SimpleNamespace(conversation_router=router),
    )
    monkeypatch.setattr("core.api.vision_handler.is_valid_image_file", lambda _data: True)
    response = asyncio.run(VisionHandler(_config()).handle_post(Request()))
    assert response.status == 503
    assert "尚未就绪" in json.loads(response.text)["message"]


def test_offline_realtime_model_does_not_fall_back_to_vllm(monkeypatch):
    monkeypatch.setattr("core.api.vision_handler.get_connection", lambda _device_id: None)
    monkeypatch.setattr("core.api.vision_handler.is_valid_image_file", lambda _data: True)
    response = asyncio.run(VisionHandler(_config()).handle_post(Request()))
    assert response.status == 503
    assert "不在线" in json.loads(response.text)["message"]


def test_traditional_model_keeps_existing_vllm_path(monkeypatch):
    config = _config()
    config["selected_module"] = {"LLM": "legacy", "VLLM": "vision"}
    config["model_config"] = {
        "legacy": {
            "model_type": "LLM",
            "typed_config": {"type": "openai"},
        },
        "vision": {
            "model_type": "VLLM",
            "typed_config": {"type": "mock_vision"},
        },
    }
    monkeypatch.setattr("core.api.vision_handler.get_connection", lambda _device_id: None)
    monkeypatch.setattr("core.api.vision_handler.is_valid_image_file", lambda _data: True)
    monkeypatch.setattr(
        "core.api.vision_handler.create_instance",
        lambda *_args: SimpleNamespace(response=lambda *_values: "传统视觉结果"),
    )
    monkeypatch.setattr(
        VisionHandler,
        "_save_image",
        lambda *_args: ("image.png", "https://example.com/image.png"),
    )
    response = asyncio.run(VisionHandler(config).handle_post(Request()))
    assert response.status == 200
    assert json.loads(response.text)["response"] == "传统视觉结果"
