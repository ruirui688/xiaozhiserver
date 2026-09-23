"""Regression for the 2026-09-22 DSML reply leak (sanitized replay)."""

import html
import json
import asyncio
from concurrent.futures import Future
from types import SimpleNamespace

import pytest

from core.providers.tools.tool_protocol_guard import TOOL_PROTOCOL_ERROR_REPLY, ToolProtocolStreamGuard
from core.providers.tts.dto.dto import SentenceType
from test_function_call_runtime_mode import (
    CapturingFunctionCallLLM,
    CapturingNoToolLLM,
    CapturingToolHandler,
    RecordingLogger,
    _chat_conn,
    _tool,
)


PROTOCOL = (
    '<｜｜DSML｜｜ calls>\n'
    '<｜｜DSML｜｜ invoke name="search_from_local_knowledge">\n'
    '<｜｜DSML｜｜ parameter name="question" string="true">'
    '园区各业态营业时间 民宿早餐8:30-9:30 酒吧下午1点开业'
    '</｜｜DSML｜｜ parameter>\n</｜｜DSML｜｜ invoke>\n</｜｜DSML｜｜ calls>'
)


@pytest.mark.parametrize("payload", [
    PROTOCOL,
    PROTOCOL.replace("｜", "|"),
    html.escape(PROTOCOL),
    html.escape(html.escape(PROTOCOL)),
    PROTOCOL.replace("<", "&#60;").replace("｜", "&#xff5c;"),
    '<｜｜DSML｜｜ invoke name="search_from_local_knowledge">私有参数',
    '<｜｜DSML｜｜ parameter name="question">私有参数',
    '<｜｜DSML｜｜ calls',  # truncated model output must not be flushed as speech
])
def test_protocol_never_escapes_at_any_chunk_boundary(payload):
    for split in range(len(payload) + 1):
        guard = ToolProtocolStreamGuard()
        output = guard.feed("我查一下。" + payload[:split])[0]
        output += guard.feed(payload[split:])[0]
        output += guard.finish()[0]
        assert guard.blocked
        assert output in ("", "我查一下。")
    guard = ToolProtocolStreamGuard()
    output = "".join(guard.feed(char)[0] for char in payload) + guard.finish()[0]
    assert guard.blocked
    assert output == ""


@pytest.mark.parametrize("text", [
    "价格 < 100 元，A & B 都可以。",
    "解释 DSML 和 tool_calls 的区别。",
    "普通 HTML：<b>你好</b> &lt;示例&gt;",
    "数学公式 x <",
])
def test_plain_text_is_preserved_with_single_character_chunks(text):
    guard = ToolProtocolStreamGuard()
    output = "".join(guard.feed(char)[0] for char in text) + guard.finish()[0]
    assert not guard.blocked
    assert output == text


@pytest.mark.parametrize("mode", ["intent_llm", "function_call", "follow_up", "direct", "direct_buffered"])
def test_connection_replay_blocks_protocol_before_tts_preview_and_history(mode):
    handler = CapturingToolHandler([_tool("search_from_local_knowledge")])
    if mode.startswith("direct"):
        arguments = json.dumps({"response": PROTOCOL}, ensure_ascii=False)
        responses = [
            (None, [SimpleNamespace(
                index=0, id="reply" if i == 0 else "",
                function=SimpleNamespace(name="direct_answer" if i == 0 else None, arguments=char),
            )])
            for i, char in enumerate(arguments)
        ]
        llm = CapturingFunctionCallLLM(responses)
    elif mode == "function_call":
        llm = CapturingFunctionCallLLM([(char, None) for char in PROTOCOL])
    else:
        llm = CapturingNoToolLLM(list(PROTOCOL))
    conn = _chat_conn(llm, handler)
    conn.logger = RecordingLogger()
    if mode == "intent_llm":
        conn.intent_type = "intent_llm"
    if mode == "direct_buffered":
        conn.config["direct_answer_streaming"] = False
    try:
        result = conn.chat(
            None if mode == "follow_up" else "去哪里？",
            depth=1 if mode == "follow_up" else 0,
            allow_tools=mode != "follow_up",
        )
        outputs = [
            "".join(conn.tts.queued_text()),
            "".join(conn.tts.stored_text),
            "".join(text for _, text in conn.tts.previews),
            "".join(message.content or "" for message in conn.dialogue.dialogue),
            "".join(text for _, text in conn.logger.records),
        ]
        assert result is True
        assert "".join(conn.tts.queued_text()) == TOOL_PROTOCOL_ERROR_REPLY
        assert "".join(conn.tts.stored_text) == TOOL_PROTOCOL_ERROR_REPLY
        assert sum(
            item.sentence_type == SentenceType.LAST
            for item in conn.tts.tts_text_queue.queue
        ) == (0 if mode == "follow_up" else 1)
        for output in outputs:
            assert "DSML" not in output
            assert "营业时间" not in output
        assert handler.calls == []
    finally:
        conn.executor.shutdown(wait=False)


def test_native_tool_call_still_executes_once(monkeypatch):
    def run_tool(coroutine, _loop):
        future = Future()
        future.set_result(asyncio.run(coroutine))
        return future

    monkeypatch.setattr(asyncio, "run_coroutine_threadsafe", run_tool)
    handler = CapturingToolHandler([_tool("search_from_local_knowledge")])
    call = SimpleNamespace(index=0, id="native-call", function=SimpleNamespace(
        name="search_from_local_knowledge", arguments='{"question":"园区营业时间"}',
    ))
    conn = _chat_conn(CapturingFunctionCallLLM([(None, [call])]), handler)
    spoken = []
    conn.tts.tts_one_sentence = lambda *_args, **kwargs: spoken.append(kwargs["content_detail"])
    try:
        assert conn.chat("园区几点开门？") is True
        assert len(handler.calls) == 1
        assert handler.calls[0]["name"] == "search_from_local_knowledge"
        assert spoken == ["执行完成"]
    finally:
        conn.executor.shutdown(wait=False)


def test_aborted_protocol_reply_does_not_emit_fallback():
    llm = CapturingNoToolLLM([])
    conn = _chat_conn(llm, CapturingToolHandler([]))
    conn.intent_type = "intent_llm"

    def interrupted_response():
        yield PROTOCOL[:30]
        conn.client_abort = True
        yield PROTOCOL[30:]

    llm.responses = interrupted_response()
    try:
        conn.chat("去哪里？")
        assert conn.tts.queued_text() == []
        assert conn.tts.stored_text == []
    finally:
        conn.executor.shutdown(wait=False)
