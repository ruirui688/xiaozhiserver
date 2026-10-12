"""Exercise acknowledged policy updates and tool output on both realtime paths."""

import asyncio
import json
import threading
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from test_function_call_runtime_mode import FakeLogger, _tool
from core.providers.realtime.qwen_omni_realtime import QwenOmniRealtimeSession, QwenOmniRealtimeTransport
from core.session.realtime_tools import RealtimeToolPolicy
from plugins_func.register import Action, ActionResponse
from test_doubao_duplex import make_session


def result():
    return ActionResponse(action=Action.REQLLM, result="合成外部数据",
                          next_llm_system_prompt="仅依据已取得的证据回答，不得重试搜索。",
                          next_llm_tools_enabled=False)


def test_duplex_updates_trusted_rules_before_tool_output_and_rejects_followup_calls():
    async def run():
        session, transport, conn = make_session()
        conn.func_handler.has_tool.side_effect = lambda name: name == "lookup"
        conn.func_handler.get_functions.return_value = [_tool("lookup")]
        conn.func_handler.handle_llm_function_call = AsyncMock(return_value=result())
        order = []
        transport.configure.side_effect = lambda *args: order.append("configure")
        transport.send.side_effect = lambda *args: order.append("output")
        await session._execute_tools([{"call_id": "one", "name": "lookup"}], 0)
        assert order == ["configure", "output"]
        prompt, tools = transport.configure.call_args.args
        assert "不得重试搜索" in prompt and tools == []
        assert "不得重试搜索" not in json.dumps(transport.send.call_args.args[0], ensure_ascii=False)
        await session._execute_tools([{"call_id": "two", "name": "lookup"}], 0)
        assert conn.func_handler.handle_llm_function_call.await_count == 1
        session._restore_tool_policy()
        await session._policy_restore_task
        prompt, tools = transport.configure.call_args.args
        assert "不得重试搜索" not in prompt and tools == [_tool("lookup")]
        await session.close()
    asyncio.run(run())


def test_qwen_reader_can_ack_policy_update_before_continuation():
    class Socket:
        def __init__(self):
            self.queue, self.sent = asyncio.Queue(), []
            self.continued = asyncio.Event()

        def __aiter__(self):
            return self

        async def __anext__(self):
            return json.dumps(await self.queue.get())

        async def send(self, raw):
            value = json.loads(raw)
            self.sent.append(value)
            if value["type"] == "session.update":
                await self.queue.put({"type": "session.updated"})
            if value["type"] == "response.create":
                self.continued.set()

        async def close(self):
            pass

    async def run():
        handler = SimpleNamespace(get_functions=lambda: [_tool("lookup")],
                                  has_tool=lambda name: name == "lookup",
                                  handle_llm_function_call=AsyncMock(return_value=result()))
        conn = SimpleNamespace(config={}, prompt="角色", logger=FakeLogger(), func_handler=handler,
                               _abort_lock=threading.Lock(), client_abort=False,
                               audio_input_ready=asyncio.Event(), conversation_ready=asyncio.Event())
        socket = Socket()
        transport = QwenOmniRealtimeTransport({})
        transport.websocket = socket
        session = QwenOmniRealtimeSession(conn, {}, transport=transport)
        transport.event_handler = session.handle_event
        transport.reader_task = asyncio.create_task(transport._reader())
        session.function_calls = {"one": {"name": "lookup", "arguments": "{}"}}
        await socket.queue.put({"type": "response.done"})
        await asyncio.wait_for(socket.continued.wait(), 2)
        assert [item["type"] for item in socket.sent] == [
            "session.update", "conversation.item.create", "response.create"]
        assert socket.sent[0]["session"]["tools"] == []
        assert "不得重试搜索" in socket.sent[0]["session"]["instructions"]
        assert "不得重试搜索" not in socket.sent[1]["item"]["output"]
        session.function_calls = {"two": {"name": "lookup", "arguments": "{}"}}
        await session._execute_function_calls()
        assert handler.handle_llm_function_call.await_count == 1
        session._reset_tool_policy()
        await session._session_maintenance_task
        assert socket.sent[-1]["session"]["tools"] == [_tool("lookup")]
        assert "不得重试搜索" not in socket.sent[-1]["session"]["instructions"]
        await session.close()
    asyncio.run(run())


def test_realtime_continuation_limit_is_enforced_by_executor_policy():
    conn = SimpleNamespace(func_handler=SimpleNamespace(has_tool=lambda name: True, get_functions=lambda: [_tool("lookup")]))
    policy = RealtimeToolPolicy(conn)
    for _ in range(5):
        policy.accept([(ActionResponse(action=Action.REQLLM, result="资料"), {})])
    assert policy.tools() == [] and not policy.permits("lookup")
    policy.reset()
    assert policy.permits("lookup")


class AckSocket:
    def __init__(self, auto_ack=True):
        self.queue = asyncio.Queue()
        self.sent = []
        self.changed = asyncio.Event()
        self.auto_ack = auto_ack

    def __aiter__(self):
        return self

    async def __anext__(self):
        value = await self.queue.get()
        if value is None:
            raise StopAsyncIteration
        return json.dumps(value)

    async def send(self, raw):
        value = json.loads(raw)
        self.sent.append(value)
        self.changed.set()
        kind = value["type"]
        if kind == "session.close" or (self.auto_ack and kind == "session.update"):
            await self.queue.put({"type": "session.closed" if kind == "session.close" else "session.updated"})

    async def wait_sent(self, kind, count=1):
        async def wait():
            while len([event for event in self.sent if event["type"] == kind]) < count:
                self.changed.clear()
                await self.changed.wait()
        await asyncio.wait_for(wait(), 2)

    async def close(self):
        await self.queue.put(None)


def wire_session(provider, auto_ack=True):
    _, _, conn = make_session()
    conn.func_handler.has_tool.side_effect = lambda name: name == "lookup"
    conn.func_handler.get_functions.return_value = [_tool("lookup")]
    conn.func_handler.handle_llm_function_call = AsyncMock(return_value=result())
    socket = AckSocket(auto_ack)
    if provider == "qwen":
        transport = QwenOmniRealtimeTransport({})
        session = QwenOmniRealtimeSession(conn, {}, transport=transport)
        transport.event_handler = session.handle_event
    else:
        from core.providers.realtime.doubao_duplex import DoubaoDuplexSession
        from core.providers.realtime.doubao_duplex_transport import DoubaoDuplexTransport
        transport = DoubaoDuplexTransport({})
        session = DoubaoDuplexSession(conn, {}, transport=transport)
        transport.handler = session.handle_event
        transport.started = True
        transport.session_id = "offline-session"
    transport.websocket = socket
    transport.reader_task = asyncio.create_task(transport._reader())
    return session, socket, conn


def test_qwen_manual_turn_updates_rules_and_restores_next_voice_turn():
    async def run():
        session, socket, conn = wire_session("qwen")
        session._instruction_turn_active = True
        session._response_active = True
        await session._configure_instruction_turn("查询本周的事实")
        session.function_calls = {"one": {"name": "lookup", "arguments": "{}"}}
        await socket.queue.put({"type": "response.done"})
        await socket.wait_sent("response.create")
        updated = [event["session"] for event in socket.sent if event["type"] == "session.update"][-1]
        assert "查询本周的事实" in updated["instructions"]
        assert "不得重试搜索" in updated["instructions"]
        assert updated["tools"] == []
        assert updated["turn_detection"] is None
        await session._finish_response()
        await session._restore_normal_session()
        restored = socket.sent[-1]["session"]
        assert "查询本周的事实" not in restored["instructions"]
        assert "不得重试搜索" not in restored["instructions"]
        assert restored["tools"] == [_tool("lookup")]
        assert session._tool_policy.permits("lookup")
        await session.close()
    asyncio.run(run())


@pytest.mark.parametrize("provider", ["qwen", "duplex"])
def test_first_tool_disables_later_calls_in_the_same_batch(provider):
    async def run():
        session, socket, conn = wire_session(provider)
        items = [{"call_id": key, "name": "lookup", "arguments": "{}"} for key in ("one", "two")]
        if provider == "qwen":
            session.function_calls = {item["call_id"]: item for item in items}
            await session._execute_function_calls()
        else:
            await session._execute_tools(items, 0)
        assert conn.func_handler.handle_llm_function_call.await_count == 1
        assert session._tool_policy.rounds == 1
        assert "工具未启用" in json.dumps(socket.sent[-1], ensure_ascii=False)
        await session.close()
    asyncio.run(run())


def test_duplex_concurrent_batches_observe_first_batch_policy():
    async def run():
        session, socket, conn = wire_session("duplex")
        entered, release = asyncio.Event(), asyncio.Event()
        async def tool(*args):
            entered.set()
            await release.wait()
            return result()
        conn.func_handler.handle_llm_function_call.side_effect = tool
        session._response_active = True
        for key in ("one", "two"):
            await session.handle_event({"type": "response.function_call_arguments.done",
                                        "items": [{"call_id": key, "name": "lookup"}]})
        await asyncio.wait_for(entered.wait(), 2)
        release.set()
        await asyncio.wait_for(asyncio.gather(*session._tool_tasks), 2)
        assert conn.func_handler.handle_llm_function_call.await_count == 1
        assert len([event for event in socket.sent if event["type"] == "conversation.item.create"]) == 2
        await session.close()
    asyncio.run(run())


@pytest.mark.parametrize("provider", ["qwen", "duplex"])
def test_cancelled_configure_cannot_lend_its_ack_to_the_next_update(provider):
    async def run():
        session, socket, conn = wire_session(provider, auto_ack=False)
        first = asyncio.create_task(session.refresh_tools())
        await socket.wait_sent("session.update")
        first.cancel()
        await asyncio.gather(first, return_exceptions=True)
        if provider == "qwen":
            session._cancel_generation += 1
        else:
            session._generation += 1
        session._tool_policy.accept([(result(), {})])
        second = asyncio.create_task(session.refresh_tools())
        await asyncio.sleep(0)
        assert len(socket.sent) == 1
        await socket.queue.put({"type": "session.updated"})
        await socket.wait_sent("session.update", 2)
        assert not second.done()
        assert socket.sent[-1]["session"]["tools"] == []
        await socket.queue.put({"type": "session.updated"})
        await asyncio.wait_for(second, 2)
        await session.close()
    asyncio.run(run())


@pytest.mark.parametrize("provider", ["qwen", "duplex"])
def test_explicit_abort_cancels_tool_and_discards_obsolete_policy(provider):
    async def run():
        session, socket, conn = wire_session(provider)
        entered = asyncio.Event()
        async def tool(*args):
            entered.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                # Even a provider that returns while cancelling must not mutate
                # the newly opened turn's trusted instructions.
                return result()
        conn.func_handler.handle_llm_function_call.side_effect = tool
        session._response_active = True
        if provider == "qwen":
            session.function_calls = {"old": {"name": "lookup", "arguments": "{}"}}
            await socket.queue.put({"type": "response.done"})
        else:
            await session.handle_event({"type": "response.function_call_arguments.done",
                                        "items": [{"call_id": "old", "name": "lookup"}]})
        await asyncio.wait_for(entered.wait(), 2)
        await asyncio.wait_for(session.handle_abort(), 2)
        assert session._tool_policy.rounds == 0
        assert session._tool_policy.permits("lookup")
        assert not any(event["type"] == "conversation.item.create" for event in socket.sent)
        conn.func_handler.handle_llm_function_call.side_effect = None
        session._response_active = True
        if provider == "qwen":
            assert session._tool_task.done()
            session.function_calls = {"new": {"name": "lookup", "arguments": "{}"}}
            await socket.queue.put({"type": "response.done"})
        else:
            await session.handle_event({"type": "response.function_call_arguments.done",
                                        "items": [{"call_id": "new", "name": "lookup"}]})
        await socket.wait_sent("conversation.item.create")
        assert conn.func_handler.handle_llm_function_call.await_count == 2
        assert session._tool_policy.rounds == 1
        await session.close()
    asyncio.run(run())


@pytest.mark.parametrize("provider", ["qwen", "duplex"])
def test_close_releases_unacknowledged_update_without_leaking_tasks(provider):
    async def run():
        session, socket, conn = wire_session(provider, auto_ack=False)
        update = asyncio.create_task(session.refresh_tools())
        await socket.wait_sent("session.update")
        await asyncio.wait_for(session.close(), 2)
        await asyncio.gather(update, return_exceptions=True)
        assert update.cancelled()
        assert not session._prompt_update_tasks
        assert session.transport.reader_task.done()
    asyncio.run(run())


@pytest.mark.parametrize("provider", ["qwen", "duplex"])
def test_next_input_commit_waits_for_policy_restoration_ack(provider):
    async def run():
        session, socket, conn = wire_session(provider, auto_ack=False)
        session._tool_policy.accept([(result(), {})])
        if provider == "qwen":
            session.config["turn_detection_type"] = "manual"
            session._reset_tool_policy()
        else:
            session.transport.muted = False
            session._restore_tool_policy()
        await socket.wait_sent("session.update")
        commit = asyncio.create_task(session.handle_listen({"state": "stop"}))
        await asyncio.sleep(0)
        assert not commit.done()
        assert not any(event["type"] == "input_audio_buffer.commit" for event in socket.sent)
        await socket.queue.put({"type": "session.updated"})
        await asyncio.wait_for(commit, 2)
        assert any(event["type"] == "input_audio_buffer.commit" for event in socket.sent)
        await session.close()
    asyncio.run(run())
