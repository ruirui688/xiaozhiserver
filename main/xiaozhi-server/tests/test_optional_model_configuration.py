import sys
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

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
