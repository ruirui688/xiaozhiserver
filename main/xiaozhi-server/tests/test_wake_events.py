import asyncio
import json
from concurrent.futures import Future
from unittest.mock import AsyncMock, Mock

import pytest

from test_barge_in_flow import _Conn
import core.handle.receiveAudioHandle as receive
import core.handle.textHandler.listenMessageHandler as listen
from core.session import ReplyLifecycle, RuntimeReadiness
from core.session.wake_events import classify_input


@pytest.fixture
def runtime(monkeypatch):
    conn = _Conn()
    conn.client_is_speaking = False
    conn.config.update(enable_greeting=True, enable_wakeup_words_response_cache=False,
                       wakeup_words=["你好小智", "嘿你好呀", "你好小红"])
    conn.reply_lifecycle = ReplyLifecycle()
    conn.closing = conn.closed = conn.initialization_failed = False
    conn.runtime_readiness = RuntimeReadiness(conn)
    conn.conversation_ready = conn.runtime_readiness.conversation_ready
    conn.conversation_ready.set()
    conn.defer_listen_detect = conn.runtime_readiness.defer_listen_detect
    conn.executor = Mock()
    future = Future()
    future.set_result(True)
    conn.executor.submit.return_value = future
    conn.semantic_center = Mock(handle_user_text=AsyncMock(return_value=False))
    monkeypatch.setattr(receive, "handle_user_intent", AsyncMock(return_value=False))
    monkeypatch.setattr(receive, "handleAbortMessage", AsyncMock())
    monkeypatch.setattr(listen, "enqueue_asr_report", Mock())

    async def begin(_conn, text, **kwargs):
        return conn.reply_lifecycle.ensure_started()

    monkeypatch.setattr(receive, "begin_assistant_reply", AsyncMock(side_effect=begin))
    return conn


async def detect(conn, text="你好小智"):
    await listen.ListenTextMessageHandler().handle(
        conn, {"type": "listen", "state": "detect", "text": text}
    )


@pytest.mark.parametrize("asr_first", [False, True])
def test_wake_pair_one_llm_and_next_wake_works(runtime, asr_first):
    async def run():
        for _ in range(2):
            if asr_first:
                assert await receive.startToChat(runtime, "你好，小智。")
                await detect(runtime)
            else:
                await detect(runtime)
                assert not await receive.startToChat(runtime, "你好，小智。")
    asyncio.run(run())
    assert runtime.executor.submit.call_count == 2
    assert receive.begin_assistant_reply.await_count == 2
    runtime.semantic_center.handle_user_text.assert_not_called()
    receive.handle_user_intent.assert_not_called()
    receive.handleAbortMessage.assert_not_called()
    for call in runtime.executor.submit.call_args_list:
        assert call.args[1] in {"你好小智", "你好，小智。"}
        assert call.args[3] is False  # Wake response has no tool invocation.
    assert runtime.reset_audio_calls == 0


@pytest.mark.parametrize("asr_first", [False, True])
def test_initialization_delay_replays_only_one_reply(runtime, monkeypatch, asr_first):
    async def dispatch(conn, payload):
        await listen.ListenTextMessageHandler().handle(conn, json.loads(payload))
    monkeypatch.setattr("core.handle.textHandle.handleTextMessage", dispatch)

    async def run():
        runtime.conversation_ready.clear()
        await detect(runtime)
        await detect(runtime)
        assert runtime.executor.submit.call_count == 0
        runtime.conversation_ready.set()
        if asr_first:
            await receive.startToChat(runtime, "你好，小智。")
        await runtime.runtime_readiness.flush_deferred_listen_detects()
        if not asr_first:
            await receive.startToChat(runtime, "你好，小智。")
    asyncio.run(run())
    assert runtime.executor.submit.call_count == 1


@pytest.mark.parametrize("text", [None, "", " \n\t", "，。！？…——", "~!@#$%^&*()_+", '{"content":""}',
                                '{"speaker":"甲","content":" ，。 "}', '{"content":null}'])
def test_empty_input_has_no_reply_or_abort(runtime, text):
    runtime.client_is_speaking = True
    assert asyncio.run(receive.startToChat(runtime, text)) is False
    runtime.executor.submit.assert_not_called()
    receive.begin_assistant_reply.assert_not_called()
    receive.handleAbortMessage.assert_not_called()
    runtime.semantic_center.handle_user_text.assert_not_called()


@pytest.mark.parametrize("text", ["你好小智，打开摄像头", '{"speaker":"甲","content":"你好小智，打开摄像头"}'])
def test_immediate_real_instruction_survives_and_can_interrupt(runtime, text):
    async def run():
        await detect(runtime)
        runtime.client_is_speaking = True
        assert await receive.startToChat(runtime, text)
    asyncio.run(run())
    assert runtime.executor.submit.call_count == 2
    actual = runtime.executor.submit.call_args.args[1]
    assert "打开摄像头" in actual
    assert "你好小智" not in actual
    receive.handleAbortMessage.assert_awaited_once()


@pytest.mark.parametrize("text", ["你好，小智。", "小智", '{"speaker":"甲","content":"你好，小智。"}', "你好小红"])
def test_standalone_asr_wake_preserves_evidence(runtime, text):
    assert asyncio.run(receive.startToChat(runtime, text))
    assert runtime.executor.submit.call_args.args[1] == text


def test_greeting_disabled_is_silent_in_both_orders(runtime):
    runtime.config["enable_greeting"] = False
    runtime.config["enable_wakeup_words_response_cache"] = True
    runtime.client_is_speaking = True
    async def run():
        await detect(runtime)
        assert not await receive.startToChat(runtime, "你好小智")
        assert not await receive.startToChat(runtime, "你好小智")
        await detect(runtime)
    asyncio.run(run())
    runtime.executor.submit.assert_not_called()
    receive.begin_assistant_reply.assert_not_called()
    receive.handleAbortMessage.assert_not_called()
    listen.enqueue_asr_report.assert_not_called()


def test_cache_disabled_never_reads_audio(runtime, monkeypatch):
    audio = AsyncMock(side_effect=AssertionError("must generate with LLM"))
    monkeypatch.setattr("core.handle.helloHandle.audio_to_data", audio)
    asyncio.run(detect(runtime))
    audio.assert_not_called()
    runtime.executor.submit.assert_called_once()


def test_pair_expiry_and_session_boundary(runtime, monkeypatch):
    now = [100.0]
    monkeypatch.setattr("core.session.wake_events.time.monotonic", lambda: now[0])
    async def run():
        await detect(runtime)
        now[0] += 11
        assert await receive.startToChat(runtime, "你好小智")
        runtime.session_id = "new-session"
        await detect(runtime)
    asyncio.run(run())
    assert runtime.executor.submit.call_count == 3


def test_ordinary_greeting_is_not_wake_and_continuous_chat_works(runtime):
    assert classify_input(runtime.config, "你好") == "text"
    async def run():
        for text in ["你好", "今天天气怎么样", "接着说"]:
            assert await receive.startToChat(runtime, text)
    asyncio.run(run())
    assert runtime.executor.submit.call_count == 3
    assert receive.handle_user_intent.await_count == 3


def test_concurrent_pair_claims_before_first_await(runtime, monkeypatch):
    entered = asyncio.Event()
    release = asyncio.Event()

    async def attention(*args, **kwargs):
        entered.set()
        await release.wait()
        return True

    monkeypatch.setattr(receive, "evaluate_interaction_attention", attention)

    async def run():
        first = asyncio.create_task(detect(runtime))
        await entered.wait()
        assert not await receive.startToChat(runtime, "你好，小智。")
        release.set()
        await first

    asyncio.run(run())
    runtime.executor.submit.assert_called_once()


def test_same_asr_turn_retry_is_filtered_but_next_turn_works(runtime):
    async def run():
        assert await receive.startToChat(runtime, "你好小智", turn_id="one")
        assert not await receive.startToChat(runtime, "你好小智", turn_id="one")
        assert await receive.startToChat(runtime, "你好小智", turn_id="two")
    asyncio.run(run())
    assert runtime.executor.submit.call_count == 2


def test_empty_detect_does_not_reset_audio_or_report(runtime):
    asyncio.run(detect(runtime, '{"content":" ，。 "}'))
    assert runtime.reset_audio_calls == 0
    runtime.executor.submit.assert_not_called()
    listen.enqueue_asr_report.assert_not_called()


def test_duplicate_wake_during_playback_does_not_abort(runtime):
    async def run():
        await detect(runtime)
        runtime.client_is_speaking = True
        assert not await receive.startToChat(runtime, "你好，小智。")
    asyncio.run(run())
    receive.handleAbortMessage.assert_not_called()
    assert runtime.reply_lifecycle.generation == 1
