"""Intent tool replies stream through the main model with paired tool history."""
import asyncio
import json
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import pytest

from test_function_call_runtime_mode import (
    _chat_conn, _tool, CapturingToolHandler, SentenceType, Action, ActionResponse,
)
from core.handle import intentHandler


@pytest.mark.parametrize("tool_name", [
    "search_from_local_knowledge", "search_from_ragflow",
    "self_get_device_status", "get_weather",
])
@pytest.mark.parametrize("empty", [False, True])
def test_knowledge_intent_streams_before_answer_complete(monkeypatch, tool_name, empty):
    result = ActionResponse(
        Action.REQLLM,
        "没有相关资料" if empty else "来源：产品说明\n支持蓝牙连接。",
        None,
        next_llm_tools_enabled=False if empty else None,
        next_llm_system_prompt="没有私有资料时不要猜测。" if empty else None,
    )
    run_knowledge_intent(monkeypatch, tool_name, result)


def run_knowledge_intent(monkeypatch, tool_name, result, *, cancel=None, original_text="怎么连接？"):
    calls = []

    class StreamingLLM:
        supports_native_function_call = True

        def response(self, session_id, dialogue):
            calls.append(dialogue)
            yield "根据资料，"
            yield "这款产品支持蓝牙连接。"
            yield "连接方式很简单。"
            assert conn.tts.queued_text(), "TTS waited for the complete answer"
            if cancel == "during_stream":
                conn.client_abort = True
                conn.abort_generation += 1
            yield "最后一句。"

        def response_with_functions(self, *args, **kwargs):
            raise AssertionError("knowledge follow-up must not call tools again")

    handler = CapturingToolHandler([_tool(tool_name)])
    conn = _chat_conn(StreamingLLM(), handler)
    conn.intent_type = "intent_llm"
    conn.intent = SimpleNamespace(replyResult=lambda *a: pytest.fail("non-streaming reply used"))
    conn.sentence_id = "intent-sentence"
    futures = []

    async def execute_tool(*args):
        if cancel == "during_retrieval":
            conn.abort_generation += 1
        if cancel == "stale_generation":
            conn.reply_lifecycle.interrupt()
            generation = conn.reply_lifecycle.begin()
            conn.reply_lifecycle.bind_sentence(generation, "new-reply")
            conn.sentence_id = "new-reply"
        return result

    handler.handle_llm_function_call = execute_tool
    monkeypatch.setattr(intentHandler, "enqueue_tool_report", lambda *a, **kw: None)
    monkeypatch.setattr(intentHandler, "schedule_reply_termination", lambda *a, **kw: None)

    async def begin_reply(*args):
        return conn.reply_lifecycle.begin()

    monkeypatch.setattr(intentHandler, "send_stt_message", begin_reply)

    async def scenario():
        conn.loop = asyncio.get_running_loop()
        with ThreadPoolExecutor(max_workers=1) as executor:
            def submit(fn):
                future = executor.submit(fn)
                futures.append(future)
                return future
            conn.executor = SimpleNamespace(submit=submit)
            assert await intentHandler.process_intent_result(
                conn,
                json.dumps({"function_call": {"name": tool_name, "arguments": {}}}),
                original_text,
            )
            await asyncio.gather(*(asyncio.wrap_future(f) for f in futures))

    asyncio.run(scenario())
    messages = conn.dialogue.dialogue
    assert [m.content for m in messages if m.role == "user"] == [original_text]
    if cancel in {"during_retrieval", "stale_generation"}:
        assert not calls
        assert not conn.tts.queued_text()
        assert not [m for m in messages if m.role == "tool"]
        if cancel == "stale_generation":
            assert conn.sentence_id == "new-reply"
        return
    assert len(calls) == 1
    assert any(m["role"] == "tool" and m["content"] == result.result for m in calls[0])
    tool_message = next(m for m in calls[0] if m["role"] == "tool")
    tool_call_message = next(m for m in calls[0] if m.get("tool_calls"))
    assert tool_message["tool_call_id"] == tool_call_message["tool_calls"][0]["id"]
    assert tool_call_message["tool_calls"][0]["function"]["name"] == tool_name
    assert not any(m["role"] == "system" and m["content"] == result.result for m in calls[0])
    if result.next_llm_system_prompt:
        assert any(m["role"] == "system" and m["content"] == result.next_llm_system_prompt for m in calls[0])
        assert not any(m.content == result.next_llm_system_prompt for m in messages)
    items = list(conn.tts.tts_text_queue.queue)
    assert items[0].sentence_type == SentenceType.FIRST
    if cancel == "during_stream":
        assert "最后一句。" not in "".join(conn.tts.queued_text())
    else:
        assert items[-1].sentence_type == SentenceType.LAST
        assert len([m for m in messages if m.role == "assistant" and m.content]) == 1
        assert "最后一句。" in "".join(conn.tts.queued_text())
    return conn


@pytest.mark.parametrize("tool_name", ["search_from_local_knowledge", "self_get_device_status"])
@pytest.mark.parametrize("cancel", ["during_retrieval", "stale_generation", "during_stream"])
def test_interrupted_knowledge_reply_does_not_resume(monkeypatch, cancel, tool_name):
    run_knowledge_intent(
        monkeypatch, tool_name,
        ActionResponse(Action.REQLLM, "来源：说明书\n支持蓝牙。", None), cancel=cancel,
    )


@pytest.mark.parametrize("tool_name", ["search_from_local_knowledge", "self_get_device_status"])
@pytest.mark.parametrize("cached", [False, True])
def test_knowledge_then_two_normal_turns_keep_valid_tool_history(monkeypatch, cached, tool_name):
    from core.providers.intent.intent_llm.intent_llm import IntentProvider

    conn = run_knowledge_intent(
        monkeypatch, tool_name,
        ActionResponse(Action.REQLLM, '{"screen":{"brightness":100}}', None),
        original_text="现在屏幕亮度是多少？",
    )
    intent = IntentProvider({})
    decision = '{"function_call":{"name":"continue_chat"}}'
    intent.llm = SimpleNamespace(response_no_stream=lambda **kw: decision)
    intent.cache_manager = SimpleNamespace(
        get=lambda *args: decision if cached else None,
        set=lambda *args: None,
    )
    conn.intent = intent
    captured = []

    class StrictModel:
        def response(self, session_id, dialogue):
            pending = set()
            for message in dialogue:
                if message.get("tool_calls"):
                    assert not pending
                    pending = {call["id"] for call in message["tool_calls"]}
                elif message["role"] == "tool":
                    assert message["tool_call_id"] in pending
                    pending.remove(message["tool_call_id"])
                else:
                    assert not pending, "model would reject orphan tool calls with HTTP 400"
            assert not pending
            captured.append(dialogue)
            yield "好的，我听到了。"

    conn.llm = StrictModel()
    for question in ("都100了，你还怎么调高呢？", "再聊点别的吧"):
        conn.reply_lifecycle.complete()
        assert "continue_chat" in asyncio.run(
            intent.detect_intent(conn, conn.dialogue.dialogue, question)
        )
        generation = conn.reply_lifecycle.begin()
        assert conn.chat(question, reply_generation=generation) is True
    assert len(captured) == 2
    assert any(message["role"] == "tool" for message in captured[-1])
