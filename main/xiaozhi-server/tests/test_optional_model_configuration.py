import sys
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.utils import modules_initialize
from core.utils.modules_initialize import initialize_asr, initialize_modules


class _Logger:
    def bind(self, **kwargs):
        return self

    def info(self, message):
        pass


def test_initialize_modules_allows_empty_selected_module():
    modules = initialize_modules(
        _Logger(),
        {"selected_module": {}},
        init_vad=True,
        init_asr=True,
        init_llm=True,
        init_tts=True,
        init_memory=True,
        init_intent=True,
    )

    assert modules == {}


def test_initialize_asr_returns_none_when_unconfigured():
    assert initialize_asr({"selected_module": {}, "ASR": {}}) is None


def test_initialize_modules_creates_selected_slm(monkeypatch):
    created = object()
    captured = {}

    def fake_create_instance(model_type, config):
        captured.update({"model_type": model_type, "config": config})
        return created

    monkeypatch.setattr(
        modules_initialize.llm,
        "create_instance",
        fake_create_instance,
    )
    config = {
        "selected_module": {"SLM": "slm-control"},
        "model_config": {
            "slm-control": {
                "model_type": "SLM",
                "typed_config": {
                    "type": "openai",
                    "model_name": "small-control-model",
                },
            }
        },
    }

    modules = initialize_modules(_Logger(), config, init_slm=True)

    assert modules["slm"] is created
    assert captured == {
        "model_type": "openai",
        "config": {"type": "openai", "model_name": "small-control-model"},
    }
