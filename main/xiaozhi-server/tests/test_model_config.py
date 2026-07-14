import sys
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.utils.model_config import (
    apply_legacy_runtime_view,
    get_runtime_config,
    has_runtime_config,
    normalize_runtime_config,
)
from core.utils.util import check_asr_update, check_vad_update
from core.connection import ConnectionHandler


def test_apply_legacy_runtime_view_normalizes_lowercase_api_modules():
    config = {
        "selected_module": {
            "asr": "asr-model-id",
            "tts": "tts-model-id",
            "llm": "llm-model-id",
            "memory": "memory-model-id",
            "intent": "intent-model-id",
        },
        "model_config": {
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


def test_normalize_runtime_config_rebuilds_stale_legacy_view_from_model_config():
    config = {
        "selected_module": {"ASR": "new-asr"},
        "model_config": {
            "new-asr": {
                "model_type": "asr",
                "typed_config": {"type": "openai", "api_key": "new"},
            }
        },
        "ASR": {"old-asr": {"type": "sherpa_onnx_local"}},
    }

    normalize_runtime_config(config)

    assert config["model_config"]["new-asr"]["model_type"] == "ASR"
    assert list(config["ASR"].keys()) == ["new-asr"]
    assert config["ASR"]["new-asr"]["api_key"] == "new"


def test_normalize_runtime_config_builds_model_config_from_legacy_config():
    config = {
        "selected_module": {"asr": "ASR_Local", "memory": "Memory_nomem"},
        "model_config": {},
        "ASR": {"ASR_Local": {"type": "sherpa_onnx_local", "model_dir": "models/asr"}},
        "Memory": {"Memory_nomem": {"type": "nomem"}},
    }

    normalize_runtime_config(config)

    assert config["selected_module"]["ASR"] == "ASR_Local"
    assert config["model_config"]["ASR_Local"]["model_type"] == "ASR"
    assert config["model_config"]["Memory_nomem"]["model_type"] == "Memory"
    assert config["ASR"]["ASR_Local"]["model_dir"] == "models/asr"


def test_update_checks_use_model_config_runtime_types_without_legacy_maps():
    before_config = {
        "selected_module": {"vad": "vad-local", "asr": "asr-openai"},
        "model_config": {
            "vad-local": {"model_type": "vad", "typed_config": {"type": "silero"}},
            "asr-openai": {"model_type": "asr", "typed_config": {"type": "openai"}},
        },
    }
    same_config = {
        "selected_module": {"VAD": "vad-local", "ASR": "asr-openai"},
        "model_config": {
            "vad-local": {"model_type": "VAD", "typed_config": {"type": "silero"}},
            "asr-openai": {"model_type": "ASR", "typed_config": {"type": "openai"}},
        },
    }
    changed_asr = {
        "selected_module": {"ASR": "asr-openai"},
        "model_config": {
            "asr-openai": {"model_type": "ASR", "typed_config": {"type": "doubao"}}
        },
    }

    assert check_vad_update(before_config, same_config) is False
    assert check_asr_update(before_config, same_config) is False
    assert check_asr_update(before_config, changed_asr) is True


def test_connection_private_config_merge_accepts_lowercase_model_config_payload():
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.config = {"selected_module": {}, "model_config": {}}
    conn.correct_word_replacements = {}
    conn.max_output_size = 0
    conn.chat_history_conf = 0

    flags = conn._apply_private_runtime_config(
        {
            "selected_module": {
                "asr": "asr-openai",
                "tts": "tts-edge",
                "llm": "llm-openai",
            },
            "model_config": {
                "asr-openai": {
                    "model_type": "asr",
                    "typed_config": {"type": "openai", "api_key": "asr-key"},
                },
                "tts-edge": {
                    "model_type": "tts",
                    "typed_config": {"type": "edge", "voice": "zh-CN-XiaoxiaoNeural"},
                },
                "llm-openai": {
                    "model_type": "llm",
                    "typed_config": {"type": "openai", "model_name": "qwen"},
                },
            },
        }
    )

    assert flags == (True, True, False, False, False)
    assert conn.config["selected_module"]["ASR"] == "asr-openai"
    assert conn.config["ASR"]["asr-openai"]["api_key"] == "asr-key"
    assert conn.config["TTS"]["tts-edge"]["voice"] == "zh-CN-XiaoxiaoNeural"
    assert conn.config["LLM"]["llm-openai"]["model_name"] == "qwen"


def test_connection_empty_private_config_keeps_existing_runtime_config():
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.config = {
        "selected_module": {"ASR": "asr-common"},
        "model_config": {
            "asr-common": {
                "model_type": "ASR",
                "typed_config": {"type": "openai", "api_key": "common-key"},
            }
        },
    }
    normalize_runtime_config(conn.config)
    conn.correct_word_replacements = {}
    conn.max_output_size = 0
    conn.chat_history_conf = 0

    flags = conn._apply_private_runtime_config({})

    assert flags == (False, False, False, False, False)
    assert conn.config["selected_module"]["ASR"] == "asr-common"
    assert conn.config["ASR"]["asr-common"]["api_key"] == "common-key"


def test_connection_private_model_config_merges_without_dropping_common_models():
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.config = {
        "selected_module": {"ASR": "asr-common"},
        "model_config": {
            "asr-common": {
                "model_type": "ASR",
                "typed_config": {"type": "openai", "api_key": "common-key"},
            }
        },
    }
    normalize_runtime_config(conn.config)
    conn.correct_word_replacements = {}
    conn.max_output_size = 0
    conn.chat_history_conf = 0

    flags = conn._apply_private_runtime_config(
        {
            "selected_module": {"llm": "llm-private"},
            "model_config": {
                "llm-private": {
                    "model_type": "llm",
                    "typed_config": {"type": "openai", "model_name": "private"},
                }
            },
        }
    )

    assert flags == (True, False, False, False, False)
    assert conn.config["selected_module"]["ASR"] == "asr-common"
    assert conn.config["selected_module"]["LLM"] == "llm-private"
    assert conn.config["ASR"]["asr-common"]["api_key"] == "common-key"
    assert conn.config["LLM"]["llm-private"]["model_name"] == "private"


def test_connection_private_config_marks_selected_slm_for_initialization():
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.config = {"selected_module": {}, "model_config": {}}
    conn.correct_word_replacements = {}
    conn.max_output_size = 0
    conn.chat_history_conf = 0

    flags = conn._apply_private_runtime_config(
        {
            "selected_module": {"slm": "slm-control"},
            "model_config": {
                "slm-control": {
                    "model_type": "slm",
                    "typed_config": {
                        "type": "openai",
                        "model_name": "small-control-model",
                    },
                }
            },
        }
    )

    assert flags == (False, False, False, False, True)
    assert conn.config["selected_module"]["SLM"] == "slm-control"
    assert conn.config["SLM"]["slm-control"]["model_name"] == "small-control-model"
