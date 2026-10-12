"""Opt-in real DeepSeek intent/chat journey with a synthetic weather boundary.

Uses the local provider, classifier, intent handler and Connection.chat. Only
weather retrieval is simulated; no production device, user or weather API runs.
Credentials remain in the deployed container. No retries or deployment writes.
"""
import argparse
import asyncio
import copy
import importlib
import json
from pathlib import Path
from unittest.mock import patch

from evaluate_prompt_journeys import TextSink, load_runtime
from prompt_journey_remote import RemoteDeepSeek

SERVER = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--device", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.live:
        parser.error("--live is required; up to 16 model requests, no retries")
    destination = args.output.resolve()
    if not destination.is_relative_to(SERVER.parents[1] / "runtime"):
        parser.error("output must be inside repository runtime/")
    Connection, Provider = load_runtime()
    import httpx
    import openai
    from core.handle import intentHandler
    from core.providers.intent.intent_llm.intent_llm import IntentProvider
    from core.providers.tools.unified_tool_handler import UnifiedToolHandler
    from plugins_func.register import Action, ActionResponse
    for name in ("get_weather", "get_time", "session_controls", "handle_exit_intent"):
        importlib.import_module("plugins_func.functions." + name)

    rows, connections, state = [], [], {}
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("x", encoding="utf-8") as output, RemoteDeepSeek(
            device=args.device, max_calls=16, live=True) as remote:
        def emit(row):
            rows.append(row)
            output.write(json.dumps(row, ensure_ascii=False) + "\n")
            output.flush()

        def transport(request):
            body = json.loads(request.content)
            result = remote.complete(body)
            choice = result["choices"][0]
            stage = "intent" if body["messages"][0].get("content", "").startswith("你只负责选择工具") else "reply"
            emit({**state, "kind": "request", "stage": stage,
                  "request": body, "response": choice, "model": result["model"], "usage": result["usage"]})
            if not body.get("stream"):
                return httpx.Response(200, json=result)
            delta = copy.deepcopy(choice["message"])
            chunk = {"id": "weather-audit", "object": "chat.completion.chunk", "created": 0,
                     "model": result["model"], "choices": [{"index": 0, "delta": delta,
                     "finish_reason": choice.get("finish_reason")} ]}
            return httpx.Response(200, headers={"content-type": "text/event-stream"},
                text="data: " + json.dumps(chunk, ensure_ascii=False) + "\n\ndata: [DONE]\n\n")

        provider = Provider({"model_name": "cloud-deepseek", "api_key": "offline-placeholder",
                             "base_url": "https://diagnostic.invalid/v1", "max_tokens": 650,
                             "temperature": 0.3, "stream_total_timeout_seconds": 90})
        provider.client.close()
        provider.client = openai.OpenAI(api_key="offline-placeholder", base_url="https://diagnostic.invalid/v1",
            max_retries=0, http_client=httpx.Client(transport=httpx.MockTransport(transport)))

        class WeatherBoundary(UnifiedToolHandler):
            async def handle_llm_function_call(self, conn, call):
                if call["name"] != "get_weather" or not self.has_tool("get_weather"):
                    raise AssertionError("unexpected or disabled business tool")
                arguments = json.loads(call["arguments"])
                emit({**state, "kind": "tool", "name": call["name"], "arguments": arguments})
                city = arguments.get("location") or "杭州"
                warning = arguments.get("query_type") == "warning"
                result = (f"合成验收数据：{city}当前发布暴雨黄色预警。" if warning else
                          f"合成验收数据：{city}今天小雨，18至23摄氏度；明天尚未查询。")
                return ActionResponse(Action.REQLLM, result, None, next_llm_tools_enabled=False)

        async def scenario():
            for enabled, turns in [(True, [
                ("今天天气怎么样？", "get_weather", None),
                ("明天杭州会下雨吗？", "get_weather", "杭州"),
                ("请查北京今天天气。", "get_weather", "北京"),
                ("那里的天气预警呢？", "get_weather", "北京"),
                ("现在几点？", "continue_chat", None),
                ("天气这个词是什么意思？", "continue_chat", None),
            ]), (False, [("今天天气怎么样？", "continue_chat", None)])]:
                config = {"prompt": "你是中文语音助手小智。回答自然简洁；没有天气查询结果时如实说明缺少实时数据。",
                          "prompt_template": str(SERVER / "agent-base-prompt.txt"),
                          "selected_module": {"Intent": "intent_llm", "LLM": "diagnostic"},
                          "Intent": {"intent_llm": {"type": "intent_llm", "functions": ["get_weather"] if enabled else []}},
                          "plugins": {"get_weather": {}} if enabled else {}, "meeting": {"enabled": False}}
                intent = IntentProvider({})
                intent.llm = provider
                conn = Connection(config, None, None, provider, None, intent)
                connections.append(conn)
                conn.device_id, conn.loop, conn.tts = "synthetic-weather-audit", asyncio.get_running_loop(), TextSink()
                conn.features = {"emoji": False}
                conn.intent_type = conn.configured_intent_type = "intent_llm"
                conn.func_handler = WeatherBoundary(conn)
                conn._init_prompt_enhancement(refresh_context=False)
                futures, submit = [], conn.executor.submit
                def tracked_submit(fn):
                    future = submit(fn)
                    futures.append(future)
                    return future
                conn.executor.submit = tracked_submit
                async def begin_reply(*_args):
                    return conn.reply_lifecycle.begin()
                with patch.object(intentHandler, "send_stt_message", begin_reply), patch.object(
                        intentHandler, "enqueue_tool_report", lambda *a, **k: None):
                    for number, (utterance, expected, city) in enumerate(turns, 1):
                        state.update(weather_enabled=enabled, turn=number, user=utterance)
                        start = len(rows)
                        handled = await intentHandler.handle_user_intent(conn, utterance)
                        if handled:
                            await asyncio.gather(*(asyncio.wrap_future(f) for f in futures))
                            futures.clear()
                        else:
                            assert await asyncio.to_thread(conn.chat, utterance)
                        current = rows[start:]
                        intent_row = next(row for row in current if row.get("stage") == "intent")
                        from core.providers.intent.intent_llm.routing import normalize_route
                        route = json.loads(normalize_route(intent_row["response"]["message"]["content"],
                            {t["function"]["name"] for t in conn.func_handler.get_functions()}))["function_call"]
                        assert route["name"] == expected, (utterance, route)
                        calls = [r for r in current if r["kind"] == "tool"]
                        assert len(calls) == (1 if expected == "get_weather" else 0)
                        if city:
                            assert city in calls[0]["arguments"].get("location", "")
                        replies = [r for r in current if r.get("stage") == "reply"]
                        assert len(replies) == 1 and not replies[0]["request"].get("tools")
                        assert "中文语音助手小智" in json.dumps(replies[0]["request"], ensure_ascii=False)
                        if calls:
                            assert any(m["role"] == "tool" and "合成验收数据" in m.get("content", "")
                                       for m in replies[0]["request"]["messages"])
                        answer = replies[0]["response"]["message"]["content"]
                        emit({**state, "kind": "turn", "route": route, "assistant": answer, "assertions": "passed"})
                        print(json.dumps(rows[-1], ensure_ascii=False), flush=True)
                        conn.reply_lifecycle.complete()
        try:
            asyncio.run(scenario())
        finally:
            provider.client.close()
            for conn in connections:
                conn.executor.shutdown(wait=True)
        emit({"kind": "summary", "turns": sum(r["kind"] == "turn" for r in rows),
              "requests": remote.calls_used, "assertions": "passed", "weather_api": "synthetic"})
        print(json.dumps(rows[-1], ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
