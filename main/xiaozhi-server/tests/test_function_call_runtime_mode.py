import sys
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.connection import ConnectionHandler
from core.providers.intent.function_call.function_call import IntentProvider as FunctionCallIntent


class NativeFunctionCallLLM:
    supports_native_function_call = True


class TextOnlyLLM:
    supports_native_function_call = False


def _config():
    return {
        "exit_commands": [],
        "Intent": {
            "function_call": {
                "type": "function_call",
                "functions": ["self_audio_speaker_set_volume"],
            },
            "intent_llm": {
                "type": "intent_llm",
                "llm": "",
                "functions": [],
            },
        },
        "selected_module": {
            "Intent": "function_call",
            "LLM": "TextOnlyLLM",
        },
        "LLM": {},
    }


def _conn(llm):
    return ConnectionHandler(
        _config(),
        _vad=None,
        _asr=None,
        _llm=llm,
        _memory=None,
        _intent=FunctionCallIntent({}),
    )


def _conn_without_intent_llm_config(llm):
    config = _config()
    config["Intent"].pop("intent_llm")
    return ConnectionHandler(
        config,
        _vad=None,
        _asr=None,
        _llm=llm,
        _memory=None,
        _intent=FunctionCallIntent({}),
    )


def test_function_call_mode_kept_for_native_provider():
    conn = _conn(NativeFunctionCallLLM())

    conn._initialize_intent()

    assert conn.configured_intent_type == "function_call"
    assert conn.intent_type == "function_call"
    assert isinstance(conn.intent, FunctionCallIntent)


def test_function_call_mode_degrades_to_intent_llm_for_text_only_provider():
    conn = _conn(TextOnlyLLM())

    conn._initialize_intent()

    assert conn.configured_intent_type == "function_call"
    assert conn.intent_type == "intent_llm"
    assert conn.intent.__class__.__module__.endswith("intent_llm.intent_llm")
    assert conn.config["Intent"]["intent_llm"]["functions"] == [
        "self_audio_speaker_set_volume"
    ]


def test_function_call_degrade_creates_default_intent_llm_config():
    conn = _conn_without_intent_llm_config(TextOnlyLLM())

    conn._initialize_intent()

    assert conn.intent_type == "intent_llm"
    assert conn.config["Intent"]["intent_llm"]["type"] == "intent_llm"
    assert conn.config["Intent"]["intent_llm"]["functions"] == [
        "self_audio_speaker_set_volume"
    ]
