"""Model failures must not cancel their own spoken fallback."""
import asyncio
from concurrent.futures import Future

import pytest

from test_function_call_runtime_mode import _chat_conn, CapturingToolHandler, SentenceType
from core.handle import receiveAudioHandle as receive
from core.handle.llmErrorHandle import admission_notice, queue_llm_error_reply
from core.providers.tts.dto.dto import ContentType


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


def test_managed_service_failure_uses_local_audio_without_paid_tts():
    conn = _chat_conn(None, CapturingToolHandler([]))
    conn.llm = type("ManagedModel", (), {"_platform_managed": True})()
    generation = conn.reply_lifecycle.begin()
    conn.reply_lifecycle.bind_sentence(generation, "current")
    assert queue_llm_error_reply(conn, "current", generation, conn.abort_generation, 0)
    messages = list(conn.tts.tts_text_queue.queue)
    assert not any(message.content_type == ContentType.TEXT for message in messages)
    assert any(message.content_type == ContentType.FILE and message.content_file == "config/assets/tts_notify.mp3" for message in messages)


@pytest.mark.parametrize("code,expected", [
    ("ALLOWANCE_EXHAUSTED", "服务额度已用完，请在智控台提交额度申请。"),
    ("WORKSPACE_ALLOWANCE_LIMIT", "本工作空间已达使用上限，请联系负责人。"),
    ("TEAM_ALLOWANCE_LIMIT", "本工作空间已达使用上限，请联系负责人。"),
    ("PAYER_FROZEN", "账号服务已暂停，请在智控台查看服务状态。"),
    ("PENDING_CREDENTIAL", "模型服务待开通，请在智控台查看服务状态。"),
])
def test_managed_refusal_speaks_the_real_reason(code, expected):
    from core.metering.admission import PlatformAdmissionError

    conn = _chat_conn(None, CapturingToolHandler([]))
    conn.llm = type("ManagedModel", (), {"_platform_managed": True})()
    generation = conn.reply_lifecycle.begin()
    conn.reply_lifecycle.bind_sentence(generation, "current")
    assert queue_llm_error_reply(conn, "current", generation, conn.abort_generation, 0, error=PlatformAdmissionError(code))
    spoken = [message.content_detail for message in conn.tts.tts_text_queue.queue if message.content_detail]
    assert spoken == [expected]


def test_unknown_admission_code_keeps_the_generic_notice():
    from core.handle.llmErrorHandle import MANAGED_NOTICE
    from core.metering.admission import PlatformAdmissionError

    assert admission_notice(PlatformAdmissionError("SOMETHING_NEW")) == MANAGED_NOTICE
    # A non-platform failure keeps the generic managed notice instead of the error text.
    assert admission_notice(RuntimeError("boom")) is None


@pytest.mark.parametrize("message,expected", [
    ("ALLOWANCE_EXHAUSTED", "ALLOWANCE_EXHAUSTED"),
    ("WORKSPACE_ALLOWANCE_LIMIT: workspace limit", "WORKSPACE_ALLOWANCE_LIMIT"),
    ("TEAM_ALLOWANCE_LIMIT: workspace limit", "TEAM_ALLOWANCE_LIMIT"),
    ("PENDING_CREDENTIAL", "PENDING_CREDENTIAL"),
    ("内部错误", "PLATFORM_CALL_DENIED"),
])
def test_denial_code_is_extracted_without_trusting_arbitrary_text(message, expected):
    from core.metering.admission import _denial_code

    assert _denial_code({"code": 1, "msg": message}) == expected
    assert _denial_code("not-a-dict") == "PLATFORM_SERVICE_UNAVAILABLE"
