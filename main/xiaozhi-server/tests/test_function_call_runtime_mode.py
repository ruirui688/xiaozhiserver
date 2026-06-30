import sys
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.connection import ConnectionHandler
from core.providers.intent.function_call.function_call import IntentProvider as FunctionCallIntent
from plugins_func.register import Action, ActionResponse


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


def test_reqlm_tool_result_can_continue_tool_chain():
    conn = _conn(NativeFunctionCallLLM())
    calls = []

    def fake_chat(query, depth=0, allow_tools=True):
        calls.append({"query": query, "depth": depth, "allow_tools": allow_tools})

    conn.chat = fake_chat
    conn.client_abort = False
    conn.abort_generation = 0
    conn.sentence_id = "sentence-1"

    result = ActionResponse(
        action=Action.REQLLM,
        result='{"audio_speaker":{"volume":100}}',
    )
    tool_call_data = {
        "id": "call-1",
        "name": "self_get_device_status",
        "arguments": "{}",
    }

    conn._handle_function_result(
        [(result, tool_call_data)],
        depth=0,
        sentence_id="sentence-1",
        abort_generation=0,
    )

    assert calls == [{"query": None, "depth": 1, "allow_tools": True}]
