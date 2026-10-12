"""Opt-in synthetic plugin conversations through the real local runtime.

No device, private conversation or external tool is used. Cloud credentials
stay in the deployed container. --runtime-root permits an isolated before/after
source snapshot; results are written only to a new repository runtime file.
"""

import argparse
import asyncio
import copy
import importlib
import json
from pathlib import Path
import sys
import threading
import time
from unittest.mock import patch

from evaluate_prompt_journeys import QuietLogger, TextSink, load_runtime
from prompt_journey_remote import RemoteDeepSeek


SERVER = Path(__file__).resolve().parents[1]
JOURNEYS = {
    "ordinary": ["你好，请用一句话介绍你自己。", "睡前怎样放松？简单说两点。", "你这里有哪些歌？只介绍，不要播放。"],
    "activity": ["成语接龙是什么？先解释一下，不要开始。", "今天不玩成语接龙，介绍一下月亮。",
                 "现在开始成语接龙，我先出一心一意。", "这局不要结束，请提示我怎么接你刚才的成语。",
                 "不玩了，换个话题，用一句话介绍太阳。"],
    "recitation": ["现在开始诗歌朗诵，请完整读出预设的三段诗歌。", "把刚才的第三段再读一遍。"],
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="authorize up to 20 model calls; no retries")
    parser.add_argument("--ssh", default="ubuntu@yh.wejoy.link")
    parser.add_argument("--device", required=True)
    parser.add_argument("--runtime-root", type=Path, default=SERVER)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expect-upgraded", action="store_true")
    args = parser.parse_args()
    if not args.live:
        parser.error("--live is required")
    destination = args.output.resolve()
    if not destination.is_relative_to(SERVER.parents[1] / "runtime"):
        parser.error("output must be inside repository runtime/")
    sys.path.insert(0, str(args.runtime_root.resolve()))
    Connection, Provider = load_runtime()
    import httpx
    import openai
    from core.game.prompt_profiles import default_configs
    from core.providers.tools.unified_tool_handler import UnifiedToolHandler
    from plugins_func.register import Action, ActionResponse

    for module in ("handle_exit_intent", "get_time", "session_controls", "play_music"):
        importlib.import_module("plugins_func.functions." + module)
    music = importlib.import_module("plugins_func.functions.play_music")
    loop = asyncio.new_event_loop()
    worker = threading.Thread(target=loop.run_forever, daemon=True)
    worker.start()
    connections, rows = [], []
    state = {"scenario": "", "turn": 0}
    provider = None
    try:
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("x", encoding="utf-8") as output, RemoteDeepSeek(
                args.ssh, device=args.device, max_calls=20, live=True) as remote, patch.object(
                    music, "list_music_prompt_entries", return_value=["小星星：轻柔儿歌", "春日散步：纯音乐"]):
            def emit(row):
                rows.append(row)
                output.write(json.dumps(row, ensure_ascii=False) + "\n")
                output.flush()

            def transport(request):
                body = json.loads(request.content)
                started = time.monotonic()
                result = remote.complete(body)
                choice = result["choices"][0]
                emit({**state, "kind": "request", "request": body, "response": choice,
                      "model": result["model"], "usage": result.get("usage", {}),
                      "elapsed_ms": round((time.monotonic() - started) * 1000)})
                delta = copy.deepcopy(choice["message"])
                for index, tool in enumerate(delta.get("tool_calls") or []):
                    tool["index"] = index
                chunk = {"id": result.get("id", "plugin-audit"), "object": "chat.completion.chunk",
                         "created": int(time.time()), "model": result["model"],
                         "choices": [{"index": 0, "delta": delta, "finish_reason": choice.get("finish_reason")}]}
                usage = {**chunk, "choices": [], "usage": result.get("usage", {})}
                stream = "".join("data: " + json.dumps(value, ensure_ascii=False) + "\n\n" for value in (chunk, usage))
                return httpx.Response(200, headers={"content-type": "text/event-stream"}, text=stream + "data: [DONE]\n\n")

            provider = Provider({"model_name": "cloud-deepseek", "api_key": "offline-placeholder",
                                 "base_url": "https://diagnostic.invalid/v1", "max_tokens": 650,
                                 "temperature": 0.3, "stream_total_timeout_seconds": 90})
            provider.client.close()
            provider.client = openai.OpenAI(api_key="offline-placeholder", base_url="https://diagnostic.invalid/v1",
                                            max_retries=0, http_client=httpx.Client(transport=httpx.MockTransport(transport)))

            class SafeTools(UnifiedToolHandler):
                async def handle_llm_function_call(self, conn, call):
                    name = call["name"]
                    if name not in {*default_configs(), "poetry_reading", "end_interaction"}:
                        emit({**state, "kind": "unexpected_tool", "name": name})
                        return ActionResponse(action=Action.REQLLM, result="合成验收不执行外部操作。", next_llm_tools_enabled=False)
                    emit({**state, "kind": "tool", "name": name})
                    return await super().handle_llm_function_call(conn, call)

            for scenario, utterances in JOURNEYS.items():
                plugins = {**default_configs(), "play_music": {}, "poetry_reading": {
                    "custom_plugin": True, "description": "诗歌朗诵：朗读预设三段诗歌。",
                    "instructions": "用户要求开始时完整朗诵下面三段，不加标题或解释，也不删句。后续按用户指定段落重读。\n第一段：晨光落在窗前。小鸟唱起新歌。\n第二段：微风走过花园。花瓣轻轻摇摆。\n第三段：星光照亮归途。愿你好梦常在。",
                }}
                config = {"prompt": "你是中文语音助手小智。回答自然、简洁。",
                          "prompt_template": str(args.runtime_root / "agent-base-prompt.txt"),
                          "selected_module": {"Intent": "function_call", "LLM": "diagnostic"},
                          "Intent": {"function_call": {"type": "function_call", "functions": list(plugins)}},
                          "meeting": {"enabled": False}, "plugins": plugins}
                conn = Connection(config, None, None, provider, None, None)
                connections.append(conn)
                conn.device_id, conn.loop, conn.tts = "synthetic-plugin-audit", loop, TextSink()
                conn.features = {"emoji": False}
                conn.intent_type = conn.configured_intent_type = "function_call"
                conn.func_handler = SafeTools(conn)
                conn._inject_tool_call_fewshot()
                for index, text in enumerate(utterances, 1):
                    state.update(scenario=scenario, turn=index)
                    start = len(rows)
                    conn.chat(text)
                    requests = [row for row in rows[start:] if row["kind"] == "request"]
                    if not requests:
                        raise RuntimeError("conversation did not reach model")
                    answer = next((m.content for m in reversed(conn.dialogue.snapshot_for_memory())
                                   if m.role == "assistant" and m.content and not m.is_temporary), "")
                    emit({**state, "kind": "turn", "user": text, "assistant": answer,
                          "calls": len(requests), "prompt_tokens": sum(row["usage"]["prompt_tokens"] for row in requests)})
                    print(json.dumps(rows[-1], ensure_ascii=False), flush=True)
                    if args.expect_upgraded:
                        first = requests[0]["request"]
                        system = "\n".join(m.get("content", "") for m in first["messages"] if m["role"] == "system")
                        assert "activity_capabilities" not in system
                        assert "春日散步" not in json.dumps(first.get("tools"), ensure_ascii=False)
                        if scenario == "ordinary" or (scenario == "activity" and index <= 2):
                            assert "<active_interaction" not in system
                            assert not [row for row in rows[start:] if row["kind"] == "tool"]
                        if scenario == "activity" and index == 3:
                            assert getattr(conn, "_active_interaction", None).name == "idiom_chain"
                        if scenario == "activity" and index == 4:
                            assert len(requests) == 1 and '<active_interaction name="idiom_chain">' in system
                        if scenario == "activity" and index == 5:
                            assert getattr(conn, "_active_interaction", None) is None
                        if scenario == "recitation" and index == 1:
                            assert "星光照亮归途" in answer and "晨光落在窗前" in answer
            requests = [row for row in rows if row["kind"] == "request"]
            turns = [row for row in rows if row["kind"] == "turn"]
            total = sum(row["usage"]["prompt_tokens"] for row in requests)
            emit({"kind": "summary", "requests": len(requests), "user_turns": len(turns),
                  "prompt_tokens": total, "mean_per_request": total / len(requests),
                  "mean_per_user_turn": total / len(turns), "assertions": "passed" if args.expect_upgraded else "baseline"})
            print(json.dumps(rows[-1], ensure_ascii=False), flush=True)
    finally:
        if provider is not None:
            provider.client.close()
        for conn in connections:
            conn.executor.shutdown(wait=True)
        loop.call_soon_threadsafe(loop.stop)
        worker.join(3)
        loop.close()


if __name__ == "__main__":
    main()
