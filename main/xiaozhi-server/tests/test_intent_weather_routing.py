"""Exercise classifier context/cache and the actual intent-to-chat boundary."""
import asyncio
import json
import threading
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from core.handle import intentHandler
from core.providers.intent.intent_llm.intent_llm import IntentProvider
from core.providers.intent.intent_llm.routing import normalize_route
from core.utils.dialogue import Dialogue, Message
from plugins_func.functions.get_weather import GET_WEATHER_FUNCTION_DESC
from test_function_call_runtime_mode import FakeLogger
from test_intent_knowledge_streaming import run_knowledge_intent
from plugins_func.register import Action, ActionResponse


def classifier(respond):
    provider = IntentProvider({})
    provider.llm = SimpleNamespace(response_no_stream=respond)
    cache = {}
    provider.cache_manager = SimpleNamespace(
        get=lambda kind, key: cache.get(key),
        set=lambda kind, key, value: cache.__setitem__(key, value),
    )
    conn = SimpleNamespace(
        config={}, device_id="synthetic-intent-weather", session_id="test",
        func_handler=SimpleNamespace(get_functions=lambda: [GET_WEATHER_FUNCTION_DESC]),
        logger=FakeLogger(), intent=provider, intent_type="intent_llm",
        abort_generation=0, dialogue=SimpleNamespace(dialogue=[]),
    )
    return provider, conn


def test_weather_request_does_not_offer_time_only_shortcut():
    provider = IntentProvider({})
    prompt = provider.get_intent_system_prompt([GET_WEATHER_FUNCTION_DESC])
    assert "result_for_context" not in prompt
    assert "get_weather" in prompt
    assert '"weather", "warning"' in prompt
    assert 'required: ["lang"]' in prompt


def test_same_question_uses_current_city_and_excludes_examples_from_cache():
    prompts = []
    def respond(**kwargs):
        prompts.append(json.loads(kwargs["user_prompt"]))
        city = "杭州" if "杭州" in kwargs["user_prompt"] else "北京"
        return json.dumps({"function_call": {"name": "get_weather", "arguments": {"location": city}}})
    provider, conn = classifier(respond)
    history = [Message("system", "secret-role"), Message("user", "我在杭州。")]
    first = asyncio.run(provider.detect_intent(conn, history, "今天天气怎么样？"))
    history.extend([Message("user", "假示例", is_temporary=True), Message("assistant", "内部提醒", is_internal=True)])
    assert asyncio.run(provider.detect_intent(conn, history, "今天天气怎么样？")) == first
    second = asyncio.run(provider.detect_intent(conn, [Message("user", "我在北京。")], "今天天气怎么样？"))
    assert len(prompts) == 2
    assert json.loads(first)["function_call"]["arguments"]["location"] == "杭州"
    assert json.loads(second)["function_call"]["arguments"]["location"] == "北京"
    assert prompts[0]["history"] == [{"role": "user", "content": "我在杭州。"}]


@pytest.mark.parametrize("raw", [
    '{"function_call":{"name":"result_for_context"}}',
    '{"function_call":{"name":"unregistered"}}',
    '{"function_call":{"name":[]}}',
    '{"function_call":{"name":"get_weather","arguments":[]}}',
    '[{"function_call":{"name":"get_weather"}}]', 'null', 'not json',
])
def test_invalid_or_legacy_classifier_output_returns_main_conversation(raw):
    assert json.loads(normalize_route(raw, {"get_weather"}))["function_call"]["name"] == "continue_chat"


def test_legacy_context_result_cannot_start_time_only_reply():
    conn = SimpleNamespace(logger=FakeLogger())
    assert asyncio.run(intentHandler.process_intent_result(
        conn, '{"function_call":{"name":"result_for_context"}}', "今天天气怎么样？"
    )) is False


def test_abort_during_slow_classifier_does_not_execute_tool_or_reply():
    started, release = threading.Event(), threading.Event()
    def respond(**kwargs):
        started.set()
        assert release.wait(3)
        return '{"function_call":{"name":"get_weather","arguments":{"lang":"zh_CN"}}}'
    _, conn = classifier(respond)
    async def scenario():
        task = asyncio.create_task(intentHandler.handle_user_intent(conn, "今天天气怎么样？"))
        try:
            assert await asyncio.to_thread(started.wait, 2), "classifier blocked the event loop"
            conn.abort_generation += 1
        finally:
            release.set()
        assert await task is True
    asyncio.run(scenario())


def test_weather_result_reaches_main_model_as_paired_tool_data(monkeypatch):
    run_knowledge_intent(monkeypatch, "get_weather", ActionResponse(
        Action.REQLLM, "合成天气：杭州今天小雨，18至23摄氏度。", None,
    ), original_text="今天天气怎么样？")


@pytest.mark.parametrize("change", [
    "config", "handler", "intent", "dialogue", "session", "agent", "config_agent",
    "speaker", "closing", "closed", "superseded", "reply", "abort",
])
def test_slow_classifier_cannot_cross_runtime_or_identity_boundaries(monkeypatch, change):
    started, release = threading.Event(), threading.Event()
    def respond(**kwargs):
        started.set()
        assert release.wait(3)
        return '{"function_call":{"name":"get_weather","arguments":{"lang":"zh_CN"}}}'
    provider, conn = classifier(respond)
    conn.current_speaker = "同名用户"
    conn.current_speaker_id = "person-a"
    conn.reply_lifecycle = SimpleNamespace(generation=1)
    provider.cache_manager.set = Mock()
    process = AsyncMock()
    monkeypatch.setattr(intentHandler, "process_intent_result", process)

    async def run():
        task = asyncio.create_task(intentHandler.handle_user_intent(conn, "今天天气怎么样？"))
        try:
            assert await asyncio.to_thread(started.wait, 2)
            if change == "config":
                conn.config = {}
            elif change == "handler":
                conn.func_handler = SimpleNamespace(get_functions=lambda: [GET_WEATHER_FUNCTION_DESC])
            elif change == "intent":
                conn.intent = object()
            elif change == "dialogue":
                conn.dialogue = Dialogue()
            elif change == "session":
                conn.session_id = "new-session"
            elif change == "agent":
                conn.agent_id = "new-agent"
            elif change == "config_agent":
                conn.config["agent_id"] = "new-agent"
            elif change == "speaker":
                conn.current_speaker_id = "person-b"
            elif change in {"closing", "closed"}:
                setattr(conn, change, True)
            elif change == "superseded":
                conn._intent_request_marker = object()
            elif change == "reply":
                conn.reply_lifecycle.generation += 1
            else:
                conn.abort_generation += 1
        finally:
            release.set()
        assert await task is True
        process.assert_not_awaited()
        provider.cache_manager.set.assert_not_called()
    asyncio.run(run())


def test_cancelled_classifier_does_not_cache_or_process_late_thread_result(monkeypatch):
    started, release = threading.Event(), threading.Event()
    def respond(**kwargs):
        started.set()
        assert release.wait(3)
        return '{"function_call":{"name":"get_weather"}}'
    provider, conn = classifier(respond)
    provider.cache_manager.set = Mock()
    process = AsyncMock()
    monkeypatch.setattr(intentHandler, "process_intent_result", process)
    async def run():
        task = asyncio.create_task(intentHandler.handle_user_intent(conn, "杭州天气"))
        try:
            assert await asyncio.to_thread(started.wait, 2)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        finally:
            release.set()
        process.assert_not_awaited()
        provider.cache_manager.set.assert_not_called()
    asyncio.run(run())


@pytest.mark.parametrize("previous,current,visible", [
    ("person-a", "person-a", True), ("person-a", "person-b", False),
    ("person-a", None, False), (None, "person-a", False), (None, None, True),
])
def test_classifier_history_is_scoped_to_stable_speaker_before_new_user_write(previous, current, visible):
    captured = []
    def respond(**kwargs):
        captured.append(json.loads(kwargs["user_prompt"]))
        return '{"function_call":{"name":"continue_chat"}}'
    provider, conn = classifier(respond)
    conn.dialogue = Dialogue()
    conn.dialogue.put(Message("user", json.dumps({
        "speaker": "同名用户", "speaker_id": previous, "content": "我在杭州。",
    }, ensure_ascii=False)))
    conn.dialogue.put(Message("assistant", "杭州是你刚才给出的地点。"))
    conn.dialogue.put(Message("tool", "上一人的私有查询结果"))
    history_before = list(conn.dialogue.dialogue)
    conn.current_speaker = "同名用户"
    conn.current_speaker_id = current
    asyncio.run(provider.detect_intent(conn, conn.dialogue.dialogue, "今天呢？"))
    assert bool(captured[0]["history"]) is visible
    if not visible:
        assert "杭州" not in json.dumps(captured[0], ensure_ascii=False)
        assert "私有查询" not in json.dumps(captured[0], ensure_ascii=False)
    assert conn.dialogue.dialogue == history_before


def test_classifier_cache_separates_same_name_speakers():
    model = Mock(return_value='{"function_call":{"name":"continue_chat"}}')
    provider, conn = classifier(model)
    conn.current_speaker = "同名用户"
    for speaker_id in ("person-a", "person-b", "person-b"):
        conn.current_speaker_id = speaker_id
        asyncio.run(provider.detect_intent(conn, [], "今天呢？"))
    assert model.call_count == 2


def test_full_intent_tool_reply_preserves_original_speaker_envelope(monkeypatch):
    from test_function_call_runtime_mode import _chat_conn, CapturingNoToolLLM, CapturingToolHandler
    classified, submitted, displayed = [], [], []
    def respond(**kwargs):
        classified.append(json.loads(kwargs["user_prompt"]))
        return '{"function_call":{"name":"get_weather","arguments":{"lang":"zh_CN"}}}'
    provider, _ = classifier(respond)
    handler = CapturingToolHandler([GET_WEATHER_FUNCTION_DESC])
    handler.handle_llm_function_call = AsyncMock(return_value=ActionResponse(
        Action.REQLLM, "合成天气：杭州小雨", None,
    ))
    llm = CapturingNoToolLLM(["杭州今天有小雨。"])
    conn = _chat_conn(llm, handler)
    conn.intent, conn.intent_type = provider, "intent_llm"
    original = json.dumps({"speaker": "甲", "speaker_id": "person-a", "content": "今天天气怎么样？"}, ensure_ascii=False)
    previous = json.dumps({"speaker": "甲", "speaker_id": "person-a", "content": "我在杭州。"}, ensure_ascii=False)
    conn.dialogue.put(Message("user", previous))
    executor = conn.executor
    def submit(call):
        future = executor.submit(call)
        submitted.append(future)
        return future
    conn.executor = SimpleNamespace(submit=submit)
    async def begin_reply(_conn, text):
        displayed.append(text)
        return conn.reply_lifecycle.begin()
    monkeypatch.setattr(intentHandler, "send_stt_message", begin_reply)
    monkeypatch.setattr(intentHandler, "enqueue_tool_report", lambda *a, **kw: None)
    monkeypatch.setattr(intentHandler, "schedule_reply_termination", lambda *a, **kw: None)
    async def run():
        conn.loop = asyncio.get_running_loop()
        assert await intentHandler.handle_user_intent(conn, original)
        await asyncio.gather(*(asyncio.wrap_future(future) for future in submitted))
    try:
        asyncio.run(run())
        assert classified[0]["current_user"] == "今天天气怎么样？"
        assert len(classified[0]["history"]) == 1
        assert displayed == [original]
        assert [m.memory_content for m in conn.dialogue.dialogue if m.role == "user"] == [previous, original]
        assert conn.dialogue._history_speaker_id == "person-a"
        assert len(llm.calls) == 1
        assert any(message["role"] == "tool" for message in llm.calls[0]["dialogue"])
    finally:
        executor.shutdown(wait=True)


def test_legacy_game_receives_clean_parameter_and_preserves_speaker_envelope(monkeypatch):
    _, conn = classifier(lambda **kw: pytest.fail("legacy game must not call classifier"))
    conn.func_handler.has_tool = lambda name: name == "legacy_game"
    conn.game_session_manager = SimpleNamespace(
        get_active_tool=lambda: None, get_requested_game_tool=lambda text: "legacy_game",
    )
    process = AsyncMock(return_value=True)
    monkeypatch.setattr(intentHandler, "process_intent_result", process)
    original = json.dumps({"content": "开始游戏", "speaker": "甲", "speaker_id": "person-a"}, ensure_ascii=False)
    assert asyncio.run(intentHandler.handle_user_intent(conn, original))
    args = process.call_args.args
    assert args[2] == original
    assert json.loads(args[1])["function_call"]["arguments"] == {"user_message": "开始游戏"}
