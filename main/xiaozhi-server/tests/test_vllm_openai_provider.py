import sys
from pathlib import Path
from types import ModuleType, SimpleNamespace


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

fake_openai_module = ModuleType("openai")
fake_openai_module.OpenAI = object
sys.modules.setdefault("openai", fake_openai_module)

fake_logger_module = ModuleType("config.logger")


class FakeLogger:
    def bind(self, **kwargs):
        return self

    def error(self, *args, **kwargs):
        return None


fake_logger_module.setup_logging = lambda: FakeLogger()
sys.modules.setdefault("config.logger", fake_logger_module)

fake_util_module = ModuleType("core.utils.util")
fake_util_module.check_model_key = lambda model_type, api_key: None
sys.modules.setdefault("core.utils.util", fake_util_module)

from core.providers.vllm import openai as openai_vllm


def test_openai_vllm_uses_natural_vision_prompt_and_generation_params(monkeypatch):
    captured = {}

    class FakeCompletions:
        def create(self, **kwargs):
            captured.update(kwargs)
            return SimpleNamespace(
                choices=[
                    SimpleNamespace(
                        message=SimpleNamespace(content="这是一块正在通电的电路板。")
                    )
                ]
            )

    class FakeOpenAI:
        def __init__(self, api_key, base_url):
            captured["api_key"] = api_key
            captured["base_url"] = base_url
            self.chat = SimpleNamespace(
                completions=FakeCompletions()
            )

    monkeypatch.setattr(openai_vllm.openai, "OpenAI", FakeOpenAI)

    provider = openai_vllm.VLLMProvider(
        {
            "model_name": "vision-model",
            "api_key": "sk-test",
            "base_url": "https://example.test/v1",
            "max_tokens": "180",
            "temperature": "0.4",
            "top_p": "0.9",
        }
    )

    result = provider.response("请描述当前画面中的场景、人物、氛围等细节", "aW1hZ2U=")

    assert result == "这是一块正在通电的电路板。"
    assert captured["model"] == "vision-model"
    assert captured["max_tokens"] == 180
    assert captured["temperature"] == 0.4
    assert captured["top_p"] == 0.9
    assert captured["stream"] is False

    messages = captured["messages"]
    assert messages[0]["role"] == "system"
    assert "口语化" in messages[0]["content"]
    assert "不要使用编号" in messages[0]["content"]
    assert "用户的问题：请描述当前画面中的场景、人物、氛围等细节" in messages[1]["content"][0]["text"]
    assert messages[1]["content"][1]["image_url"]["url"] == "data:image/jpeg;base64,aW1hZ2U="
