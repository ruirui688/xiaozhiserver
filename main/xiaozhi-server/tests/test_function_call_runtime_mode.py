import asyncio
import concurrent.futures
import queue
import sys
from pathlib import Path
from types import ModuleType, SimpleNamespace


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))


class FakeLogger:
    def bind(self, **_kwargs):
        return self

    def debug(self, *_args, **_kwargs):
        return None

    def info(self, *_args, **_kwargs):
        return None

    def warning(self, *_args, **_kwargs):
        return None

    def error(self, *_args, **_kwargs):
        return None


fake_logger_module = ModuleType("config.logger")
fake_logger_module.setup_logging = lambda: FakeLogger()
fake_logger_module.build_module_string = lambda *_args, **_kwargs: "test"
fake_logger_module.create_connection_logger = (
    lambda *_args, **_kwargs: FakeLogger()
)
sys.modules.setdefault("config.logger", fake_logger_module)

from core.connection import ConnectionHandler
from core.providers.intent.function_call.function_call import IntentProvider as FunctionCallIntent
from core.providers.tts.dto.dto import ContentType
from plugins_func.register import Action, ActionResponse


class NativeFunctionCallLLM:
    supports_native_function_call = True


class TextOnlyLLM:
    supports_native_function_call = False


class CapturingFunctionCallLLM:
    supports_native_function_call = True
    supports_tool_choice = True

    def __init__(self, responses):
        self.responses = responses
        self.calls = []

    def response_with_functions(self, session_id, dialogue, functions, **kwargs):
        self.calls.append(
            {
                "session_id": session_id,
                "dialogue": dialogue,
                "functions": functions,
                "options": kwargs,
            }
        )
        return iter(self.responses)

    def response(self, session_id, dialogue):
        raise AssertionError("function_call mode must use response_with_functions")


class CapturingTTS:
    def __init__(self):
        self.tts_text_queue = queue.Queue()
        self.stored_text = []

    def store_tts_text(self, sentence_id, text):
        self.stored_text.append(text)

    def queued_text(self):
        items = list(self.tts_text_queue.queue)
        return [
            item.content_detail
            for item in items
            if item.content_type == ContentType.TEXT and item.content_detail
        ]


class CapturingToolHandler:
    def __init__(self, functions, terminal_names=()):
        self.functions = functions
        self.calls = []
        self.terminal_names = set(terminal_names)

    def get_functions(self):
        return list(self.functions)

    def has_tool(self, name):
        return any(
            item.get("function", {}).get("name") == name for item in self.functions
        )

    def is_terminal_turn_tool(self, name):
        return name in self.terminal_names

    async def handle_llm_function_call(self, conn, tool_call_data):
        self.calls.append(tool_call_data)
        return ActionResponse(action=Action.RESPONSE, response="执行完成")


def _config():
    return {
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


def _tool(name):
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": f"{name} description",
            "parameters": {"type": "object", "properties": {}},
        },
    }


def _chat_conn(llm, tool_handler):
    conn = _conn(llm)
    conn.intent_type = "function_call"
    conn.configured_intent_type = "function_call"
    conn.func_handler = tool_handler
    conn.tts = CapturingTTS()
    conn.features = {"emoji": False}
    conn.read_config_from_api = False
    conn.memory = None
    conn.loop = None
    return conn


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


def test_function_call_chat_passes_full_tool_schema_to_main_llm():
    tools = [_tool("play_music"), _tool("self_audio_speaker_set_volume")]
    llm = CapturingFunctionCallLLM([("直接回复", None)])
    conn = _chat_conn(llm, CapturingToolHandler(tools))

    assert conn.chat("那你放个歌") is True

    assert len(llm.calls) == 1
    function_names = [
        item.get("function", {}).get("name") for item in llm.calls[0]["functions"]
    ]
    assert function_names == [
        "play_music",
        "self_audio_speaker_set_volume",
        "direct_answer",
    ]
    assert conn.tts.queued_text() == ["直接回复"]
    assert conn.tts.stored_text == ["直接回复"]
    assert llm.calls[0]["options"] == {}


def test_terminal_tool_preempts_direct_answer_and_other_tools(monkeypatch):
    terminal_call = SimpleNamespace(
        index=0,
        id="stop-call-1",
        function=SimpleNamespace(
            name="self_robot_stop_speaking",
            arguments='{"request_id":"turn-1-stop-1"}',
        ),
    )
    direct_answer_call = SimpleNamespace(
        index=1,
        id="direct-answer-1",
        function=SimpleNamespace(
            name="direct_answer",
            arguments='{"response":"好的，我安静了。"}',
        ),
    )
    gesture_call = SimpleNamespace(
        index=2,
        id="gesture-call-1",
        function=SimpleNamespace(
            name="perform_gesture",
            arguments='{"motion_name":"wave"}',
        ),
    )
    finished = []

    async def fake_finish(conn, expected_abort_generation):
        finished.append(expected_abort_generation)
        conn.client_abort = True
        conn.abort_generation += 1
        return True

    def run_immediately(coro, _loop):
        future = concurrent.futures.Future()
        try:
            future.set_result(asyncio.run(coro))
        except Exception as error:
            future.set_exception(error)
        return future

    monkeypatch.setattr(
        "core.connection.asyncio.run_coroutine_threadsafe",
        run_immediately,
    )
    monkeypatch.setattr(
        "core.connection.finish_terminal_turn",
        fake_finish,
    )
    tools = [
        _tool("self_robot_stop_speaking"),
        _tool("perform_gesture"),
    ]
    handler = CapturingToolHandler(
        tools,
        terminal_names={"self_robot_stop_speaking"},
    )
    llm = CapturingFunctionCallLLM(
        [(None, [terminal_call, direct_answer_call, gesture_call])]
    )
    conn = _chat_conn(llm, handler)

    assert conn.chat("请停止当前输出") is None

    assert [call["name"] for call in handler.calls] == [
        "self_robot_stop_speaking"
    ]
    assert conn.tts.queued_text() == []
    assert conn.tts.stored_text == []
    assert finished == [0]


def test_active_meeting_forces_main_llm_to_choose_meeting_tool():
    llm = CapturingFunctionCallLLM([("", None)])
    conn = _chat_conn(llm, CapturingToolHandler([_tool("meeting_minutes")]))
    conn.meeting_minutes.start("发布评审")
    conn.meeting_minutes.prepare_turn(None, "退出会议模式。", "张敏")

    assert conn.chat("退出会议模式。") is True

    assert llm.calls[0]["options"] == {
        "tool_choice": "required"
    }
    assert conn.meeting_minutes.build_transcript()[0]["text"] == "退出会议模式。"


def test_function_call_chat_blocks_dsml_protocol_text_from_tts_and_history():
    dsml = (
        "<｜｜DSML｜｜tool_calls>\n"
        "<｜｜DSML｜｜invoke name=\"play_music\">\n"
        "<｜｜DSML｜｜parameter name=\"query\" string=\"true\">随机</｜｜DSML｜｜parameter>"
    )
    tool_handler = CapturingToolHandler([_tool("play_music")])
    llm = CapturingFunctionCallLLM([(dsml, None)])
    conn = _chat_conn(llm, tool_handler)

    assert conn.chat("那你放个歌") is True

    queued_text = "".join(conn.tts.queued_text())
    stored_text = "".join(conn.tts.stored_text)
    history_text = "\n".join(
        message.content or "" for message in conn.dialogue.dialogue
    )
    assert "DSML" not in queued_text
    assert "DSML" not in stored_text
    assert "DSML" not in history_text
    assert tool_handler.calls == []


def test_active_meeting_turn_uses_only_main_llm_meeting_tool_and_stays_silent():
    tools = [_tool("meeting_minutes"), _tool("play_music")]
    llm = CapturingFunctionCallLLM([("我已经结束会议。", None)])
    conn = _chat_conn(llm, CapturingToolHandler(tools))
    conn.meeting_minutes.start("发布评审")
    conn.meeting_minutes.prepare_turn(None, "先完成回归测试。", "张敏")

    assert conn.chat("先完成回归测试。") is True

    function_names = [
        item.get("function", {}).get("name") for item in llm.calls[0]["functions"]
    ]
    assert function_names == ["meeting_minutes"]
    assert conn.tts.queued_text() == []
    assert conn.tts.stored_text == []
    assert conn.meeting_minutes.is_active() is True
    assert [
        item["text"] for item in conn.meeting_minutes.build_transcript()
    ] == ["先完成回归测试。"]
    meeting_context = next(
        item["content"]
        for item in llm.calls[0]["dialogue"]
        if item.get("role") == "system"
        and "<meeting_runtime>" in item.get("content", "")
    )
    assert "active=true" in meeting_context
    assert "action=record" in meeting_context


def test_active_meeting_executes_main_llm_record_tool(monkeypatch):
    tool_call = SimpleNamespace(
        index=0,
        id="meeting-call-1",
        function=SimpleNamespace(
            name="meeting_minutes",
            arguments='{"action":"record"}',
        ),
    )

    class MeetingToolHandler(CapturingToolHandler):
        async def handle_llm_function_call(self, conn, tool_call_data):
            self.calls.append(tool_call_data)
            appended = conn.meeting_minutes.record_pending_turn()
            return ActionResponse(
                action=Action.RECORD,
                result=f"meeting_recorded:{appended}",
                response="[会议内容已记录，继续保持静默]",
            )

    def run_immediately(coro, _loop):
        future = concurrent.futures.Future()
        try:
            future.set_result(asyncio.run(coro))
        except Exception as error:
            future.set_exception(error)
        return future

    monkeypatch.setattr(
        "core.connection.asyncio.run_coroutine_threadsafe",
        run_immediately,
    )
    handler = MeetingToolHandler([_tool("meeting_minutes"), _tool("play_music")])
    llm = CapturingFunctionCallLLM([(None, [tool_call])])
    conn = _chat_conn(llm, handler)
    conn.meeting_minutes.start("发布评审")
    conn.meeting_minutes.prepare_turn(None, "先完成回归测试。", "张敏")

    assert conn.chat("先完成回归测试。") is True

    assert len(handler.calls) == 1
    assert handler.calls[0]["name"] == "meeting_minutes"
    assert conn.tts.queued_text() == []
    assert conn.meeting_minutes.has_pending_turn() is False
    assert [
        item["text"] for item in conn.meeting_minutes.build_transcript()
    ] == ["先完成回归测试。"]


def test_active_meeting_discards_hallucinated_direct_answer_tool():
    tool_call = SimpleNamespace(
        index=0,
        id="direct-answer-1",
        function=SimpleNamespace(
            name="direct_answer",
            arguments='{"response":"会议已经结束。"}',
        ),
    )
    handler = CapturingToolHandler([_tool("meeting_minutes")])
    llm = CapturingFunctionCallLLM([(None, [tool_call])])
    conn = _chat_conn(llm, handler)
    conn.meeting_minutes.start("发布评审")
    conn.meeting_minutes.prepare_turn(None, "继续讨论发布时间。", "张敏")

    assert conn.chat("继续讨论发布时间。") is True

    assert handler.calls == []
    assert conn.tts.queued_text() == []
    assert conn.tts.stored_text == []
    assert conn.meeting_minutes.is_active() is True
    assert [
        item["text"] for item in conn.meeting_minutes.build_transcript()
    ] == ["继续讨论发布时间。"]
