import asyncio
import json
import threading
from types import SimpleNamespace

from core.handle.reportHandle import _logical_event_time, _memory_event_content
from core.session.turn_context import (
    MAX_TURN_CONTEXT_BYTES,
    TurnContextBuffer,
    build_contextual_user_content,
    normalize_turn_context,
    redact_dialogue_contexts_for_log,
)
from core.utils.dialogue import Dialogue, Message


class _Logger:
    def __init__(self):
        self.records = []

    def bind(self, **_kwargs):
        return self

    def info(self, message, *_args):
        self.records.append(("info", str(message)))

    def warning(self, message, *_args):
        self.records.append(("warning", str(message)))

    def error(self, message, *_args):
        self.records.append(("error", str(message)))

    def debug(self, message, *_args):
        self.records.append(("debug", str(message)))


def test_turn_context_accepts_supported_json_values_and_rejects_oversize():
    for value in ("商品详情页", {"productId": "123"}, ["a", {"b": 1}]):
        normalized, update = normalize_turn_context(value)
        assert normalized == value
        assert update.accepted is True
        assert update.size_bytes > 0

    normalized, update = normalize_turn_context(True)
    assert normalized is None
    assert update.reason == "unsupported_type"

    normalized, update = normalize_turn_context("x" * MAX_TURN_CONTEXT_BYTES)
    assert normalized is None
    assert update.reason == "too_large"


def test_turn_context_buffer_is_one_shot_and_new_start_clears_old_value():
    buffer = TurnContextBuffer()
    assert buffer.replace({"page": "detail"}).accepted is True
    assert buffer.take() == {"page": "detail"}
    assert buffer.take() is None

    buffer.replace("old")
    update = buffer.replace(None)
    assert update.reason == "empty"
    assert buffer.take() is None


def test_contextual_user_content_preserves_voiceprint_payload():
    source = json.dumps(
        {
            "speaker": "用户A",
            "speaker_id": "voice-1",
            "content": "帮我介绍一下",
        },
        ensure_ascii=False,
    )
    payload = json.loads(
        build_contextual_user_content(source, {"productId": "123"})
    )
    assert payload == {
        "speaker": "用户A",
        "speaker_id": "voice-1",
        "content": "帮我介绍一下",
        "context": {"productId": "123"},
    }


def test_dialogue_exposes_context_to_llm_but_keeps_raw_content_for_memory():
    dialogue = Dialogue()
    message = Message(
        role="user",
        content="帮我介绍一下",
        turn_context={"productId": "123"},
    )
    dialogue.put(message)

    llm_message = dialogue.get_llm_dialogue()[-1]
    assert json.loads(llm_message["content"])["context"] == {"productId": "123"}
    assert message.content == "帮我介绍一下"
    assert message.turn_context == {"productId": "123"}

    redacted = redact_dialogue_contexts_for_log([llm_message])
    assert "productId" not in redacted[0]["content"]
    assert redacted[0]["content"].startswith("<redacted:")
    assert redacted[0]["content"].endswith(" chars>")


def test_dialogue_log_redaction_hides_system_prompt_and_tool_arguments():
    messages = [
        {"role": "system", "content": "INTERNAL_MEETING_PROMPT"},
        {
            "role": "assistant",
            "tool_calls": [
                {
                    "id": "call-1",
                    "type": "function",
                    "function": {
                        "name": "private_tool",
                        "arguments": '{"secret":"customer-data"}',
                    },
                }
            ],
        },
        {"role": "tool", "content": "PRIVATE_MEMORY_RESULT"},
    ]

    redacted = redact_dialogue_contexts_for_log(messages)
    serialized = json.dumps(redacted, ensure_ascii=False)

    assert "INTERNAL_MEETING_PROMPT" not in serialized
    assert "customer-data" not in serialized
    assert "PRIVATE_MEMORY_RESULT" not in serialized
    assert redacted[1]["tool_calls"][0]["function"] == {
        "name": "private_tool",
        "arguments": "<redacted>",
    }


def test_chat_report_payload_keeps_context_but_memory_event_removes_it():
    report_content = build_contextual_user_content(
        json.dumps(
            {
                "speaker": "用户A",
                "speaker_id": "voice-1",
                "content": "帮我介绍一下",
            },
            ensure_ascii=False,
        ),
        {"productId": "123"},
    )
    assert json.loads(report_content)["context"] == {"productId": "123"}

    memory_text, memory_json, speaker_id = _memory_event_content(1, report_content)
    assert memory_text == "帮我介绍一下"
    assert speaker_id == "voice-1"
    assert "context" not in memory_json
    assert memory_json["speaker"] == "用户A"


def test_memory_event_uses_turn_time_and_message_sequence_for_visual_order():
    assert _logical_event_time(
        999999,
        {"started_at_epoch_ms": 1000, "message_sequence": 3},
    ) == 1003
    assert _logical_event_time(999999, None) == 999999


def test_listen_start_only_stores_context_without_starting_chat(monkeypatch):
    import config.logger as logger_config
    from loguru import logger as test_logger

    monkeypatch.setattr(logger_config, "setup_logging", lambda: test_logger)
    from core.handle.textHandler.listenMessageHandler import ListenTextMessageHandler

    conn = SimpleNamespace(
        turn_context=TurnContextBuffer(),
        logger=_Logger(),
        reset_audio_states=lambda: None,
        client_listen_mode="auto",
    )
    asyncio.run(
        ListenTextMessageHandler().handle(
            conn,
            {
                "type": "listen",
                "state": "start",
                "context": {"page": "detail"},
            },
        )
    )
    assert conn.turn_context.take() == {"page": "detail"}
    assert any("size_bytes=" in message for _level, message in conn.logger.records)


def test_text_message_log_redacts_context_content(monkeypatch):
    import config.logger as logger_config
    from loguru import logger as test_logger

    monkeypatch.setattr(logger_config, "setup_logging", lambda: test_logger)
    from core.handle.textMessageProcessor import TextMessageProcessor

    logger = _Logger()

    class _Handler:
        async def handle(self, _conn, _payload):
            return None

    registry = SimpleNamespace(get_handler=lambda _message_type: _Handler())
    conn = SimpleNamespace(logger=logger)
    secret_context = "不要出现在日志里的背景"
    message = json.dumps(
        {
            "type": "listen",
            "state": "start",
            "context": secret_context,
        },
        ensure_ascii=False,
    )
    asyncio.run(TextMessageProcessor(registry).process_message(conn, message))

    rendered_logs = "\n".join(record for _level, record in logger.records)
    assert secret_context not in rendered_logs
    assert "<redacted:string>" in rendered_logs


def test_start_to_chat_keeps_context_out_of_intent_and_passes_it_to_main_llm(
    monkeypatch,
):
    import config.logger as logger_config
    from loguru import logger as test_logger

    monkeypatch.setattr(logger_config, "setup_logging", lambda: test_logger)
    import core.handle.receiveAudioHandle as receive_handle

    captured = {}

    async def fake_intent(_conn, text, turn_context=None):
        captured["intent_text"] = text
        captured["intent_context"] = turn_context
        return False

    async def fake_send_stt(_conn, text):
        captured["stt"] = text

    class _Future:
        def add_done_callback(self, _callback):
            return None

    class _Executor:
        def submit(self, func, *args):
            captured["chat_func"] = func
            captured["chat_args"] = args
            return _Future()

    class _SemanticCenter:
        async def handle_user_text(self, _conn, text):
            captured["semantic_text"] = text
            return False

    conn = SimpleNamespace(
        voiceprint_enrollment=None,
        current_speaker=None,
        current_speaker_id=None,
        meeting_minutes=None,
        need_bind=False,
        max_output_size=0,
        client_is_speaking=False,
        client_listen_mode="auto",
        semantic_center=_SemanticCenter(),
        llm=object(),
        client_abort=False,
        _abort_lock=threading.Lock(),
        executor=_Executor(),
        chat=lambda *_args: None,
        logger=_Logger(),
        config={"correct_words": None},
        session_id="session-1",
        device_id="device-1",
    )
    monkeypatch.setattr(receive_handle, "handle_user_intent", fake_intent)
    monkeypatch.setattr(receive_handle, "send_stt_message", fake_send_stt)

    context = {"productId": "123"}
    accepted = asyncio.run(
        receive_handle.startToChat(
            conn,
            "帮我介绍一下",
            turn_context=context,
        )
    )

    assert accepted is True
    assert captured["semantic_text"] == "帮我介绍一下"
    assert captured["intent_text"] == "帮我介绍一下"
    assert captured["intent_context"] == context
    assert captured["stt"] == "帮我介绍一下"
    assert captured["chat_args"] == ("帮我介绍一下", 0, True, context)
