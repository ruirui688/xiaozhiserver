"""Model failures must not cancel their own spoken fallback."""
import asyncio
from concurrent.futures import Future

import pytest

from test_function_call_runtime_mode import _chat_conn, CapturingToolHandler, SentenceType
from core.handle import receiveAudioHandle as receive
from core.handle.llmErrorHandle import queue_llm_error_reply


@pytest.mark.parametrize("phase", ["request", "stream"])
def test_error_speech_survives_chat_completion_and_next_turn(monkeypatch, phase):
    class Model:
        def response(self, *args):
            if phase == "request":
                raise RuntimeError("request rejected")
            def stream():
                raise RuntimeError("stream rejected")
                yield  # generator failure occurs on iteration
            return stream()

    conn = _chat_conn(Model(), CapturingToolHandler([]))
    terminations = []

    class Executor:
        def submit(self, fn, *args):
            future = Future()
            try:
                future.set_result(fn(*args))
            except Exception as error:
                future.set_exception(error)
            return future

    async def no_intent(*args, **kwargs):
        return False

    async def begin(target, text):
        return target.reply_lifecycle.begin()

    monkeypatch.setattr(receive, "handle_user_intent", no_intent)
    monkeypatch.setattr(receive, "begin_assistant_reply", begin)
    monkeypatch.setattr(receive, "schedule_reply_termination", lambda *a, **kw: terminations.append(kw))
    conn.executor = Executor()
    conn.semantic_center = None
    conn.need_bind = False
    conn.max_output_size = 0
    conn.conversation_ready.set()
    for text in ("请回答第一个问题", "请回答第二个问题"):
        assert asyncio.run(receive.startToChat(conn, text)) is True
        snapshot = conn.reply_lifecycle.snapshot()
        assert snapshot.is_active
        assert not terminations
        items = list(conn.tts.tts_text_queue.queue)
        assert items[-1].sentence_type == SentenceType.LAST
        assert items[-1].generation == snapshot.generation
        assert conn.tts.queued_text()
        # The TTS completion, not the worker callback, owns normal termination.
        conn.reply_lifecycle.complete(snapshot.generation)


@pytest.mark.parametrize("stale", ["abort", "new_generation"])
def test_error_after_interrupt_cannot_speak_into_new_turn(stale):
    conn = _chat_conn(None, CapturingToolHandler([]))
    generation = conn.reply_lifecycle.begin()
    conn.reply_lifecycle.bind_sentence(generation, "old")
    abort_generation = conn.abort_generation
    conn.reply_lifecycle.interrupt(generation)
    if stale == "abort":
        conn.abort_generation += 1
    else:
        new_generation = conn.reply_lifecycle.begin()
        conn.reply_lifecycle.bind_sentence(new_generation, "new")
    assert queue_llm_error_reply(conn, "old", generation, abort_generation, 0) is False
    assert conn.tts.tts_text_queue.empty()
