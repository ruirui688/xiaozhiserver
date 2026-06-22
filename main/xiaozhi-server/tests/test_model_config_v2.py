import sys
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.utils.model_config_v2 import (
    apply_legacy_runtime_view,
    get_runtime_config,
    has_runtime_config,
)


def test_apply_legacy_runtime_view_normalizes_lowercase_api_modules():
    config = {
        "selected_module": {
            "asr": "asr-model-id",
            "tts": "tts-model-id",
            "llm": "llm-model-id",
            "memory": "memory-model-id",
            "intent": "intent-model-id",
        },
        "model_config_v2": {
            "asr-model-id": {
                "model_type": "asr",
                "typed_config": {"type": "openai", "api_key": "test"},
            },
            "tts-model-id": {
                "model_type": "tts",
                "typed_config": {"type": "edge", "voice": "zh-CN-XiaoxiaoNeural"},
            },
            "llm-model-id": {
                "model_type": "llm",
                "typed_config": {"type": "openai", "model_name": "test-llm"},
            },
            "memory-model-id": {
                "model_type": "memory",
                "typed_config": {"type": "nomem"},
            },
            "intent-model-id": {
                "model_type": "intent",
                "typed_config": {"type": "Intent_nointent"},
            },
        },
    }

    apply_legacy_runtime_view(config)

    assert config["selected_module"]["ASR"] == "asr-model-id"
    assert config["selected_module"]["TTS"] == "tts-model-id"
    assert config["selected_module"]["LLM"] == "llm-model-id"
    assert config["selected_module"]["Memory"] == "memory-model-id"
    assert config["selected_module"]["Intent"] == "intent-model-id"
    assert config["ASR"]["asr-model-id"]["type"] == "openai"
    assert config["TTS"]["tts-model-id"]["voice"] == "zh-CN-XiaoxiaoNeural"
    assert config["LLM"]["llm-model-id"]["model_name"] == "test-llm"
    assert has_runtime_config(config, "ASR", "asr-model-id")
    assert get_runtime_config(config, "LLM", "llm-model-id")["type"] == "openai"

