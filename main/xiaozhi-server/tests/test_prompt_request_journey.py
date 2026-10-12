"""Real Connection.chat and OpenAI SDK requests; all upstream I/O stays offline."""

import asyncio
import concurrent.futures
import copy
import json
import time
from types import SimpleNamespace

import httpx
import openai
import pytest

from test_function_call_runtime_mode import CapturingToolHandler, _chat_conn, _tool
from core.providers.llm.openai.openai import LLMProvider
from core.providers.tts.dto.dto import SentenceType
from core.session.managed_memory_snapshot import snapshot_for
from core.session.prompt_context import FINAL_ANSWER_INSTRUCTION, PromptTurnContext
from core.utils.dialogue import Message
from plugins_func.register import Action, ActionResponse


def _systems(body):
    return "\n".join(message["content"] for message in body["messages"] if message["role"] == "system")


def _user(text, speaker_id=None):
    return json.dumps({"content": text, "speaker_id": speaker_id}, ensure_ascii=False)


def _tool_reply(name="lookup_reference"):
    return {"tool_calls": [{"index": 0, "id": "lookup-1", "type": "function",
             "function": {"name": name, "arguments": "{}"}}]}


class RetrievalHandler(CapturingToolHandler):
    def __init__(self, functions, on_execute=None):
        super().__init__(functions)
        self.on_execute = on_execute

    async def handle_llm_function_call(self, conn, tool_call_data):
        self.calls.append(tool_call_data)
        if self.on_execute is not None:
            self.on_execute(conn)
        return ActionResponse(action=Action.REQLLM, result="检索资料：这条成语可接。")


@pytest.fixture
def wire_connection(monkeypatch):
    resources = []

    def run_immediately(coro, _loop):
        future = concurrent.futures.Future()
        try:
            future.set_result(asyncio.run(coro))
        except Exception as error:
            future.set_exception(error)
        return future

    monkeypatch.setattr("core.connection.asyncio.run_coroutine_threadsafe", run_immediately)

    def create(script, handler=None):
        requests = []

        def serve(request):
            body = json.loads(request.content)
            requests.append(body)
            if len(requests) > len(script):
                raise AssertionError("Unexpected additional model request")
            reply = script[len(requests) - 1]
            delta = {"content": reply} if isinstance(reply, str) else reply
            chunk = {"id": "offline-completion", "object": "chat.completion.chunk", "created": 0,
                     "model": "offline-chat", "choices": [{"index": 0, "delta": delta,
                     "finish_reason": None}]}
            content = f"data: {json.dumps(chunk, ensure_ascii=False)}\n\ndata: [DONE]\n\n"
            return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=content)

        llm = LLMProvider({"model_name": "offline-chat", "api_key": "offline-placeholder",
                           "base_url": "https://example.invalid/v1"})
        llm.client.close()
        llm.client = openai.OpenAI(api_key="offline-placeholder", base_url="https://example.invalid/v1",
                                  max_retries=0, http_client=httpx.Client(transport=httpx.MockTransport(serve)))
        conn = _chat_conn(llm, handler or CapturingToolHandler([]))
        conn.change_system_prompt("角色：自然、简短地回答。<context>本轮上下文</context><memory></memory>")
        conn.config["meeting"] = {"enabled": False}
        resources.append((conn, llm.client))
        return conn, requests

    yield create
    for conn, client in resources:
        client.close()
        conn.executor.shutdown(wait=False)


def _enable_activity(conn):
    conn.config["plugins"] = {"idiom_chain": {
        "custom_plugin": True, "instructions": "用户选择成语接龙后，一人一句，允许先查资料。",
    }}
    conn.config["Intent"]["function_call"]["functions"] = ["idiom_chain", "lookup_reference"]


def _install_memory(conn, shared="- 公共事实", private=None):
    now = int(time.time() * 1000)
    state = snapshot_for(conn)
    assert state.install({
        "schema_version": 1, "status": "ready", "agent_id": conn.config["agent_id"],
        "device_id": conn.device_id, "revision": "a" * 64, "issued_at_ms": now,
        "expires_at_ms": now + 30_000, "shared_context": shared,
        "speaker_contexts": private or {},
    }, state.epoch)
    return state


def _managed(conn):
    conn.config["agent_id"] = "agent-offline"
    conn.device_id = "device-offline"
    conn.config["selected_module"]["Memory"] = "managed"
    conn.config["Memory"] = {"managed": {"type": "managed_memory"}}
    # Renewal is exercised separately; this journey never reaches an HTTP config API.
    conn.runtime_config_reloader.schedule_snapshot_refresh = lambda: None


def test_real_chat_tool_followup_keeps_turn_memory_activity_then_isolates_next_speaker(wire_connection):
    handler = RetrievalHandler([_tool("lookup_reference")])
    conn, requests = wire_connection([_tool_reply(), "这个词可以接，我们继续。", "你好，我来回答新的问题。"], handler)
    _enable_activity(conn)
    conn.config["selected_module"]["Memory"] = "local"
    conn.config["Memory"] = {"local": {"type": "mem_local_short"}}
    reads = []

    async def query_memory(query):
        reads.append((query, conn.current_speaker_id))
        return "- 小林喜欢慢节奏游戏" if conn.current_speaker_id == "alice" else "- 小王偏好简短回复"

    conn.memory = SimpleNamespace(query_memory=query_memory)
    conn.current_speaker, conn.current_speaker_id = "小林", "alice"
    from core.game.interactions import activate_interaction
    assert activate_interaction(conn, "idiom_chain")
    first_user = _user("开始成语接龙，先查这个词能不能接。", "alice")
    assert conn.chat(first_user) is True
    assert len(requests) == 2 and len(reads) == 1 and len(handler.calls) == 1
    for request in requests:
        prompt = _systems(request)
        assert "小林喜欢慢节奏游戏" in prompt
        assert "当前说话人：小林" in prompt
        assert '<active_interaction name="idiom_chain">' in prompt
        assert "用户选择成语接龙后，一人一句" in prompt
    followup = requests[1]["messages"]
    assert [m["content"] for m in followup if m["role"] == "user"] == [first_user]
    assert followup[-2]["tool_calls"][0]["id"] == followup[-1]["tool_call_id"] == "lookup-1"
    assert followup[-1]["role"] == "tool"
    assert "direct_answer" not in [tool["function"]["name"] for tool in requests[1]["tools"]]
    assert all("<active_interaction" not in str(m.content) for m in conn.dialogue.dialogue)

    conn.config["plugins"] = {}
    conn.current_speaker, conn.current_speaker_id = "小王", "bob"
    second_user = _user("换个人了，介绍一下你自己。", "bob")
    assert conn.chat(second_user) is True
    assert len(reads) == 2 and reads[-1] == (second_user, "bob")
    prompt = _systems(requests[-1])
    assert "小王偏好简短回复" in prompt and "当前说话人：小王" in prompt
    assert "小林喜欢慢节奏游戏" not in json.dumps(requests[-1], ensure_ascii=False)
    assert "<active_interaction" not in prompt
    assert [m["content"] for m in requests[-1]["messages"] if m["role"] == "user"] == [second_user]
    assert not any(m["role"] == "tool" for m in requests[-1]["messages"])


@pytest.mark.parametrize("change", ["expire", "revoke", "replace"])
def test_managed_memory_lease_is_revalidated_on_actual_recursive_provider_request(wire_connection, change):
    def on_execute(conn):
        if change == "expire":
            snapshot_for(conn).deadline = 0
        elif change == "revoke":
            snapshot_for(conn).invalidate()
        else:
            _install_memory(conn, "- 新的有效事实")

    conn, requests = wire_connection([_tool_reply(), "我根据目前可见的信息回答。"],
                                    RetrievalHandler([_tool("lookup_reference")], on_execute))
    _managed(conn)
    _install_memory(conn, "- 先前有效事实")
    assert conn.chat("帮我查资料并结合记忆回答。") is True
    assert len(requests) == 2
    assert "先前有效事实" in _systems(requests[0])
    assert "先前有效事实" not in _systems(requests[1])
    assert ("新的有效事实" in _systems(requests[1])) is (change == "replace")


def test_depth_limit_is_request_system_control_never_fake_user_or_memory_evidence(wire_connection):
    conn, requests = wire_connection(["现有资料不足，我只能说明已知部分。"],
                                    RetrievalHandler([_tool("lookup_reference")]))
    evidence = []
    conn.dialogue.memory_sink = evidence.append
    user = "查不到更多也没关系，告诉我已有的信息。"
    conn.dialogue.put(Message("user", user))
    turn = PromptTurnContext.capture(conn, user)
    assert conn.chat(None, depth=5, prompt_turn_context=turn) is True
    assert "tools" not in requests[0]
    assert FINAL_ANSWER_INSTRUCTION in _systems(requests[0])
    assert [m["content"] for m in requests[0]["messages"] if m["role"] == "user"] == [user]
    assert all(FINAL_ANSWER_INSTRUCTION not in str(m.content) for m in conn.dialogue.dialogue)
    assert [m.content for m in evidence if m.role == "user"] == [user]


@pytest.mark.parametrize("depth,allow_tools,expected", [
    (0, True, {"fewshot_da_001", "fewshot_exit_001"}),
    (1, True, {"fewshot_exit_001"}),
    (1, False, set()), (5, True, set()),
])
def test_actual_requests_only_include_examples_for_available_tools(wire_connection, depth, allow_tools, expected):
    conn, requests = wire_connection(["已回答。"], CapturingToolHandler([_tool("handle_exit_intent")]))
    conn._inject_tool_call_fewshot()
    if depth:
        conn.dialogue.put(Message("user", "请根据已有资料回答。"))
    conn.chat("请根据已有资料回答。" if depth == 0 else None, depth=depth, allow_tools=allow_tools)
    calls = {call["id"] for message in requests[0]["messages"] for call in message.get("tool_calls", [])}
    assert calls == expected
    if not expected:
        assert [m["content"] for m in requests[0]["messages"] if m["role"] == "user"] == ["请根据已有资料回答。"]
    # Filtering is request-local: a later normal turn can still use its examples.
    assert sum(message.is_temporary for message in conn.dialogue.dialogue) == 7


@pytest.mark.parametrize("change", ["speaker", "abort", "config", "closed"])
def test_stale_turn_is_rejected_before_provider_io(wire_connection, change):
    conn, requests = wire_connection([])
    conn.current_speaker, conn.current_speaker_id = "小林", "alice"
    conn.dialogue.put(Message("user", _user("继续刚才的话题", "alice")))
    turn = PromptTurnContext.capture(conn, "继续刚才的话题")
    if change == "speaker":
        conn.current_speaker, conn.current_speaker_id = "小王", "bob"
    elif change == "abort":
        conn.abort_generation += 1
    elif change == "config":
        conn.config = copy.deepcopy(conn.config)
    else:
        conn.closed = True
    assert conn.chat(None, depth=1, prompt_turn_context=turn) is None
    assert requests == []


@pytest.mark.parametrize("stage", ["memory_capture", "message_assembly"])
def test_turn_invalidated_during_assembly_never_reaches_provider(wire_connection, monkeypatch, stage):
    conn, requests = wire_connection([])
    if stage == "memory_capture":
        def memory_query(_query):
            conn.abort_generation += 1
            return "不得发出的过期记忆"
        monkeypatch.setattr(conn, "_query_memory_context", memory_query)
    else:
        original = conn.dialogue.get_llm_dialogue_with_memory

        def assembly(*args, **kwargs):
            messages = original(*args, **kwargs)
            conn.current_speaker_id = "new-person"
            return messages
        monkeypatch.setattr(conn.dialogue, "get_llm_dialogue_with_memory", assembly)
    assert conn.chat("继续解释刚才的话题。") is None
    assert requests == []


@pytest.mark.parametrize("allow_tools", [True, False])
def test_actual_tools_control_music_and_meeting_instructions(wire_connection, monkeypatch, allow_tools):
    monkeypatch.setattr("plugins_func.functions.play_music.list_music_prompt_entries",
                        lambda _conn, limit: ["离线曲目：晚风"])
    conn, requests = wire_connection(["我可以介绍当前资料。"],
                                    CapturingToolHandler([_tool("play_music"), _tool("meeting_minutes")]))
    conn.config["meeting"] = {"enabled": True}
    assert conn.chat("介绍一下曲库和会议功能。", allow_tools=allow_tools) is True
    body = requests[0]
    prompt = _systems(body)
    assert "离线曲目：晚风" in prompt
    if allow_tools:
        assert {t["function"]["name"] for t in body["tools"]} == {"play_music", "meeting_minutes", "direct_answer"}
        assert "仅当用户明确要求播放时才调用 play_music" in prompt
        assert "main_llm_tool_available=true" in prompt
    else:
        assert "tools" not in body
        assert "本次请求不允许播放操作" in prompt
        assert "main_llm_tool_available=false" in prompt
        assert "本轮没有可执行的 meeting_minutes 工具" in prompt
        assert "才调用 meeting_minutes(action=start)" not in prompt


def test_active_meeting_limits_schema_and_excludes_activity_prompt(wire_connection, monkeypatch):
    monkeypatch.setattr("plugins_func.functions.play_music.list_music_prompt_entries",
                        lambda _conn, limit: ["离线曲目"])

    class MeetingHandler(CapturingToolHandler):
        async def handle_llm_function_call(self, conn, data):
            self.calls.append(data)
            conn.meeting_minutes.record_pending_turn()
            return ActionResponse(action=Action.RECORD, result="recorded", response="[已记录]")

    handler = MeetingHandler([_tool("meeting_minutes"), _tool("play_music")])
    conn, requests = wire_connection([_tool_reply("meeting_minutes")], handler)
    _enable_activity(conn)
    conn.config["meeting"] = {"enabled": True}
    conn.meeting_minutes.start("产品会议")
    conn.meeting_minutes.prepare_turn(None, "先完成测试。", "小林")
    assert conn.chat("先完成测试。") is True
    assert [t["function"]["name"] for t in requests[0]["tools"]] == ["meeting_minutes"]
    prompt = _systems(requests[0])
    assert "<activity_capabilities>" not in prompt
    assert "本次请求不允许播放操作" in prompt
    assert "当前会议已经真实激活" in prompt
    assert conn.tts.queued_text() == []


def test_intent_tool_entry_keeps_activity_and_original_user_for_memory(wire_connection):
    conn, requests = wire_connection(["查到了，我们接着玩。"])
    _enable_activity(conn)
    conn.intent_type = "intent_llm"
    from core.game.interactions import activate_interaction
    assert activate_interaction(conn, "idiom_chain")
    conn.config["selected_module"]["Memory"] = "local"
    conn.config["Memory"] = {"local": {"type": "mem_local_short"}}
    queries = []

    async def query_memory(query):
        queries.append(query)
        return "- 用户喜欢成语小游戏"

    conn.memory = SimpleNamespace(query_memory=query_memory)
    original_user = "成语接龙继续，先查一下这个词。"
    conn.dialogue.put(Message("user", original_user))
    assert conn.chat(None, allow_tools=False, tool_result="词典里的解释", tool_call={
        "id": "intent-tool", "type": "function",
        "function": {"name": "lookup_reference", "arguments": "{}"},
    }) is True
    assert queries == [original_user]
    prompt = _systems(requests[0])
    assert '<active_interaction name="idiom_chain">' in prompt and "用户喜欢成语小游戏" in prompt
    assert "词典里的解释" not in prompt
    assert "tools" not in requests[0]
    assert [m["content"] for m in requests[0]["messages"] if m["role"] == "user"] == [original_user]
    assert requests[0]["messages"][-1] == {"role": "tool", "tool_call_id": "intent-tool", "content": "词典里的解释"}


def test_interrupted_tool_history_is_repaired_before_new_user_in_actual_wire_request(wire_connection):
    conn, requests = wire_connection(["好，我们换个话题。"])
    conn.dialogue.put(Message("user", "帮我查一下"))
    conn.dialogue.put(Message("assistant", tool_calls=[{
        "id": "interrupted", "type": "function",
        "function": {"name": "lookup_reference", "arguments": "{}"},
    }]))
    assert conn.chat("不查了，我们换个话题。") is True
    messages = requests[0]["messages"]
    call_index = next(i for i, message in enumerate(messages) if message.get("tool_calls"))
    assert messages[call_index + 1]["role"] == "tool"
    assert messages[call_index + 1]["tool_call_id"] == "interrupted"
    assert '"status": "interrupted"' in messages[call_index + 1]["content"]
    assert messages[call_index + 2] == {"role": "user", "content": "不查了，我们换个话题。"}
    assert not any(m.role == "tool" for m in conn.dialogue.snapshot_for_memory())


def test_over_budget_current_user_queues_spoken_fallback_without_provider_io_or_evidence_rewrite(wire_connection):
    conn, requests = wire_connection([])
    conn.config["prompt_budget"] = {"max_input_chars": 1024}
    evidence = []
    conn.dialogue.memory_sink = evidence.append
    conn.dialogue.put(Message("user", "前面的问题需要保留。"))
    conn.dialogue.put(Message("assistant", "好的。"))
    previous = [(m, copy.deepcopy(vars(m))) for m in evidence]
    query = "完整用户原文：" + "这是需要分次发送的长问题。" * 150
    generation = conn.reply_lifecycle.begin()

    assert conn.chat(query, reply_generation=generation) is True

    assert requests == []
    assert conn.tts.queued_text() == ["这次内容较长，请缩短问题或分几次发送。"]
    snapshot = conn.reply_lifecycle.snapshot()
    assert snapshot.is_active and snapshot.generation == generation
    queued = list(conn.tts.tts_text_queue.queue)
    assert [item.sentence_type for item in queued] == [
        SentenceType.FIRST, SentenceType.MIDDLE, SentenceType.LAST,
    ]
    assert all(item.generation == generation for item in queued)
    assert all(item.sentence_id == snapshot.sentence_id for item in queued)
    assert evidence[:2] == [m for m, _ in previous]
    assert all(vars(m) == original for m, original in previous)
    assert [(m.role, m.content, m.memory_content) for m in evidence[2:]] == [
        ("user", query, query),
    ]
    assert [m for m in conn.dialogue.snapshot_for_memory() if m.role != "system"] == evidence


def test_request_budget_removes_old_tool_exchange_only_from_actual_wire_payload(wire_connection):
    conn, requests = wire_connection(["新问题已收到。"])
    conn.config["prompt_budget"] = {"max_input_chars": 1024}
    conn.dialogue.set_max_history_turns(0)
    evidence = []
    conn.dialogue.memory_sink = evidence.append
    conn.dialogue.put(Message("user", "旧问题：" + "需要保留的历史原文。" * 200))
    conn.dialogue.put(Message("assistant", tool_calls=[{
        "id": "old-lookup", "type": "function",
        "function": {"name": "lookup_reference", "arguments": "{}"},
    }]))
    conn.dialogue.put(Message("tool", "旧轮查询结果。", tool_call_id="old-lookup"))
    conn.dialogue.put(Message("assistant", "之前的问题已回答。"))
    previous = [(m, copy.deepcopy(vars(m))) for m in evidence]
    query = "这是当前的新问题。"
    generation = conn.reply_lifecycle.begin()

    assert conn.chat(query, reply_generation=generation) is True

    assert len(requests) == 1
    body = requests[0]
    assert [m for m in body["messages"] if m["role"] != "system"] == [
        {"role": "user", "content": query},
    ]
    assert "角色：自然、简短地回答。" in _systems(body)
    assert "old-lookup" not in json.dumps(body, ensure_ascii=False)
    assert len(json.dumps({"messages": body["messages"], "tools": body.get("tools", [])},
                          ensure_ascii=False, separators=(",", ":"))) <= 1024
    assert evidence[:4] == [m for m, _ in previous]
    assert all(vars(m) == original for m, original in previous)
    assert [(m.role, m.content, m.memory_content) for m in evidence[4:]] == [
        ("user", query, query), ("assistant", "新问题已收到。", "新问题已收到。"),
    ]
    assert [m for m in conn.dialogue.snapshot_for_memory() if m.role != "system"] == evidence
