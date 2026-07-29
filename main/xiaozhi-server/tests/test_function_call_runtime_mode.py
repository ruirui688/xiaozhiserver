import asyncio
import concurrent.futures
import queue
import sys
import pytest
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


class RecordingLogger(FakeLogger):
    def __init__(self):
        self.records = []

    def _record(self, level, *args):
        self.records.append((level, str(args[0]) if args else ""))

    def debug(self, *args, **_kwargs):
        self._record("debug", *args)

    def info(self, *args, **_kwargs):
        self._record("info", *args)

    def warning(self, *args, **_kwargs):
        self._record("warning", *args)

    def error(self, *args, **_kwargs):
        self._record("error", *args)


fake_logger_module = ModuleType("config.logger")
fake_logger_module.setup_logging = lambda: FakeLogger()
fake_logger_module.build_module_string = lambda *_args, **_kwargs: "test"
fake_logger_module.create_connection_logger = (
    lambda *_args, **_kwargs: FakeLogger()
)
sys.modules.setdefault("config.logger", fake_logger_module)

from core.connection import ConnectionHandler
from core.handle.receiveAudioHandle import startToChat
from core.providers.intent.function_call.function_call import IntentProvider as FunctionCallIntent
from core.providers.tts.dto.dto import ContentType, SentenceType
from plugins_func.register import Action, ActionResponse


class NativeFunctionCallLLM:
    supports_native_function_call = True


class TextOnlyLLM:
    supports_native_function_call = False


class CapturingFunctionCallLLM:
    supports_native_function_call = True
    supports_tool_choice = True
    supports_parallel_tool_calls = True

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


class CapturingNoToolLLM:
    supports_native_function_call = True

    def __init__(self, responses):
        self.responses = responses
        self.calls = []

    def response(self, session_id, dialogue):
        self.calls.append({"session_id": session_id, "dialogue": dialogue})
        return iter(self.responses)

    def response_with_functions(self, *_args, **_kwargs):
        raise AssertionError("empty registry must not enable function calling")


class CapturingTTS:
    def __init__(self):
        self.tts_text_queue = queue.Queue()
        self.stored_text = []
        self.previews = []

    def store_tts_text(self, sentence_id, text):
        self.stored_text.append(text)

    def store_tts_preview(self, sentence_id, text):
        self.previews.append((sentence_id, text))

    def queued_text(self):
        items = list(self.tts_text_queue.queue)
        return [
            item.content_detail
            for item in items
            if item.content_type == ContentType.TEXT and item.content_detail
        ]


class CapturingToolHandler:
    def __init__(
        self,
        functions,
        result_action=Action.RESPONSE,
    ):
        self.functions = functions
        self.calls = []
        self.result_action = result_action

    def get_functions(self):
        return list(self.functions)

    def has_tool(self, name):
        return any(
            item.get("function", {}).get("name") == name for item in self.functions
        )

    async def handle_llm_function_call(self, conn, tool_call_data):
        self.calls.append(tool_call_data)
        return ActionResponse(action=self.result_action, response="执行完成")


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
    assert llm.calls[0]["options"] == {
        "tool_choice": "required",
        "parallel_tool_calls": False,
    }


def test_direct_answer_streams_to_tts_before_llm_generator_finishes():
    conn_ref = {}
    observed_during_stream = []
    deltas = [
        ('direct-answer-1', 'direct_answer', '{"response":"惠易'),
        ('', None, '新能源'),
        ('', None, '专注于'),
        ('', None, '自动化'),
        ('', None, '冲压和'),
        ('', None, '智能物流'),
        ('', None, '整体解决方案，'),
        ('', None, '覆盖生产线规划'),
        ('', None, '设备集成和运维'),
        ('', None, '服务保障完善'),
        ('', None, '并建设了A12焊接生产线，这是结尾。"}'),
    ]

    def responses():
        for delta_id, name, arguments in deltas:
            yield None, [
                SimpleNamespace(
                    index=0,
                    id=delta_id,
                    function=SimpleNamespace(name=name, arguments=arguments),
                )
            ]
            observed_during_stream.append(conn_ref["conn"].tts.queued_text())

    llm = CapturingFunctionCallLLM(responses())
    conn = _chat_conn(llm, CapturingToolHandler([_tool("play_music")]))
    conn_ref["conn"] = conn

    assert conn.chat("给我讲一句话") is True

    assert observed_during_stream
    assert observed_during_stream[:2] == [[], []]
    assert observed_during_stream[2] == ["惠易新"]
    assert any(items for items in observed_during_stream[:-1])
    answer = (
        "惠易新能源专注于自动化冲压和智能物流整体解决方案，"
        "覆盖生产线规划设备集成和运维服务保障完善"
        "并建设了A12焊接生产线，这是结尾。"
    )
    assert "".join(conn.tts.queued_text()) == answer
    assert conn.tts.stored_text == [answer]
    assert conn.tts.previews
    assert conn.tts.previews[0][1] == conn.tts.queued_text()[0]
    assert llm.calls[0]["options"] == {
        "tool_choice": "required",
        "parallel_tool_calls": False,
    }


def test_plain_content_streams_while_real_tools_are_attached():
    conn_ref = {}
    observed_during_stream = []

    def responses():
        for content in ["工具结果已经", "整理完成，", "现在直接回答你。"]:
            yield content, None
            observed_during_stream.append(conn_ref["conn"].tts.queued_text())

    llm = CapturingFunctionCallLLM(responses())
    conn = _chat_conn(llm, CapturingToolHandler([_tool("web_search")]))
    conn_ref["conn"] = conn

    assert conn.chat("世界杯支持谁") is True

    assert observed_during_stream == [
        ["工具结果已经"],
        ["工具结果已经", "整理完成，"],
        ["工具结果已经", "整理完成，", "现在直接回答你。"],
    ]
    assert conn.tts.stored_text == ["工具结果已经整理完成，现在直接回答你。"]


def test_start_to_chat_keeps_successful_direct_answer_alive(monkeypatch):
    import core.handle.receiveAudioHandle as receive_handle

    direct_answer = SimpleNamespace(
        index=0,
        id="direct-answer-success",
        function=SimpleNamespace(
            name="direct_answer",
            arguments='{"response":"瑞瑞你好呀，小慧等你很久啦～"}',
        ),
    )
    llm = CapturingFunctionCallLLM([(None, [direct_answer])])
    conn = _chat_conn(llm, CapturingToolHandler([_tool("play_music")]))
    terminations = []

    class ImmediateExecutor:
        def submit(self, function, *args):
            future = concurrent.futures.Future()
            try:
                future.set_result(function(*args))
            except BaseException as exc:
                future.set_exception(exc)
            return future

    async def no_intent(*_args, **_kwargs):
        return False

    async def begin_reply(target, _text):
        return target.reply_lifecycle.begin()

    monkeypatch.setattr(receive_handle, "handle_user_intent", no_intent)
    monkeypatch.setattr(receive_handle, "begin_assistant_reply", begin_reply)
    monkeypatch.setattr(
        receive_handle,
        "schedule_reply_termination",
        lambda _conn, reason, **_kwargs: terminations.append(reason),
    )
    conn.executor = ImmediateExecutor()
    conn.semantic_center = None
    conn.need_bind = False
    conn.max_output_size = 0

    assert asyncio.run(startToChat(conn, "你好")) is True
    assert terminations == []
    assert "".join(conn.tts.queued_text()) == "瑞瑞你好呀，小慧等你很久啦～"
    queued = list(conn.tts.tts_text_queue.queue)
    assert [item.sentence_type for item in queued] == [
        SentenceType.FIRST,
        SentenceType.MIDDLE,
        SentenceType.MIDDLE,
        SentenceType.LAST,
    ]


def test_direct_answer_falls_back_without_single_tool_constraint():
    class UnconstrainedFunctionCallLLM(CapturingFunctionCallLLM):
        supports_tool_choice = True
        supports_parallel_tool_calls = False

    conn_ref = {}
    observed_during_stream = []
    first_delta = SimpleNamespace(
        index=0,
        id="direct-answer-1",
        function=SimpleNamespace(
            name="direct_answer",
            arguments='{"response":"兼容模式仍在生成',
        ),
    )
    second_delta = SimpleNamespace(
        index=0,
        id="",
        function=SimpleNamespace(
            name=None,
            arguments='，现在结束。"}',
        ),
    )

    def responses():
        yield None, [first_delta]
        observed_during_stream.append(conn_ref["conn"].tts.queued_text())
        yield None, [second_delta]

    llm = UnconstrainedFunctionCallLLM(responses())
    conn = _chat_conn(llm, CapturingToolHandler([_tool("play_music")]))
    conn_ref["conn"] = conn

    assert conn.chat("说句话") is True

    assert observed_during_stream == [[]]
    assert conn.tts.queued_text() == ["兼容模式仍在生成，现在结束。"]
    assert llm.calls[0]["options"] == {}


def test_direct_answer_helpers_preserve_text_and_remove_json_tail():
    arguments = '{"response":"第一行\\n第二行，对话说：\\"你好\\"。"}'

    assert ConnectionHandler._extract_direct_answer_response(arguments) == (
        '第一行\n第二行，对话说："你好"。'
    )
    assert ConnectionHandler._clean_response_garbage("回答完成。\n\"}}") == "回答完成。"


def test_empty_direct_answer_returns_none_without_publishing_last():
    direct_answer = SimpleNamespace(
        index=0,
        id="direct-answer-empty",
        function=SimpleNamespace(
            name="direct_answer",
            arguments='{"response":""}',
        ),
    )
    llm = CapturingFunctionCallLLM([(None, [direct_answer])])
    conn = _chat_conn(llm, CapturingToolHandler([_tool("play_music")]))

    assert conn.chat("不要返回有效内容") is None
    assert conn.tts.queued_text() == []
    queued = list(conn.tts.tts_text_queue.queue)
    assert all(item.sentence_type is not SentenceType.LAST for item in queued)


def test_function_call_mode_with_empty_registry_sends_no_internal_routing_tool():
    llm = CapturingNoToolLLM(["普通回复"])
    conn = _chat_conn(llm, CapturingToolHandler([]))

    assert conn.chat("你好") is True

    assert len(llm.calls) == 1
    assert conn.tts.queued_text() == ["普通回复"]


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
