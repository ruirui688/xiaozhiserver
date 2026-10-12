"""Compare fixed synthetic requests through two real Connection/OpenAI serializers.

Capture runs offline in separate processes. Opt-in measurement sends at most 18
requests through the existing official-DeepSeek bridge, without retries. Model
answers never become history. This measures requests, not whole user turns or
the extra model call needed to start/end an activity.
"""

import argparse
import ast
import asyncio
import copy
from datetime import datetime
import hashlib
import importlib
import json
from pathlib import Path
import runpy
import subprocess
import sys
import threading
from unittest.mock import patch

from evaluate_prompt_journeys import QuietLogger, TextSink, load_runtime


SERVER = Path(__file__).resolve().parents[1]
RUNTIME = SERVER.parents[1] / "runtime"
FIXED_TIME = "2026-10-10T12:00:00+08:00"
MUSIC = ["小星星：轻柔儿歌", "春日散步：纯音乐"]
POEM = ("第一段：晨光落在窗前。小鸟唱起新歌。\n"
        "第二段：微风走过花园。花瓣轻轻摇摆。\n"
        "第三段：星光照亮归途。愿你好梦常在。")
SOURCE_FILES = ["core/connection.py", "core/game/freeform.py", "core/game/interactions.py",
                "core/utils/dialogue.py", "core/session/prompt_context.py",
                "core/session/tool_examples.py", "core/providers/tools/server_plugins/plugin_executor.py",
                "agent-base-prompt.txt"]


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def write_json(path, value):
    with path.open("x", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)


def fixtures():
    profiles = runpy.run_path(str(SERVER / "core/game/prompt_profiles.py"))["default_configs"]()
    plugins = {**profiles, "play_music": {}, "poetry_reading": {
        "custom_plugin": True, "description": "诗歌朗诵：朗读预设三段诗歌。",
        "instructions": "用户要求开始时完整朗诵下面三段，不加标题或解释，也不删句。后续按用户指定段落重读。\n" + POEM,
    }}
    return {"time": FIXED_TIME, "music": MUSIC, "plugins": plugins,
            "role": "你是中文语音助手小智。回答自然、简洁。", "scenarios": [
        {"id": "ordinary_greeting", "query": "你好，请用一句话介绍你自己。", "history": []},
        {"id": "ordinary_relaxation", "query": "睡前怎样放松？简单说两点。",
         "history": [["user", "你好。"], ["assistant", "你好，我是小智，很高兴和你聊天。"]]},
        {"id": "music_catalog", "query": "你这里有哪些歌？只介绍，不要播放。", "history": []},
        {"id": "activity_explanation", "query": "成语接龙是什么？先解释一下，不要开始。", "history": []},
        {"id": "activity_declined", "query": "今天不玩成语接龙，介绍一下月亮。", "history": []},
        {"id": "idiom_continuation", "active": "idiom_chain",
         "query": "这局不要结束，请提示我怎么接你刚才的成语。",
         "history": [["user", "现在开始成语接龙，我先出一心一意。"],
                     ["assistant", "意气风发，形容精神振奋、气概豪迈。轮到你接‘发’。"]]},
        {"id": "poetry_continuation", "active": "poetry_reading", "query": "把刚才的第三段再读一遍。",
         "history": [["user", "现在开始诗歌朗诵，请完整读出预设的三段诗歌。"], ["assistant", POEM]]},
        {"id": "tools_disabled_music", "query": "请播放小星星。", "history": [],
         "followup": {"name": "play_music", "arguments": '{"song_name":"小星星"}',
                      "result": "合成工具结果：小星星播放请求已交给设备。"}},
        {"id": "tools_disabled_activity", "active": "idiom_chain",
         "query": "现在开始成语接龙，我先出一心一意。", "history": [],
         "followup": {"name": "idiom_chain", "arguments": "{}", "result": "合成工具结果：成语接龙活动已开始。"}},
    ]}


def capture(args):
    source = args.runtime_root.resolve()
    fixture = json.loads(args.fixture.read_text())
    sys.path.insert(0, str(source))
    Connection, Provider = load_runtime()
    import httpx
    import openai
    from core.providers.tools.unified_tool_handler import UnifiedToolHandler
    from core.session.prompt_context import PromptTurnContext
    from core.utils.dialogue import Message

    for name in ("handle_exit_intent", "get_time", "session_controls", "play_music"):
        importlib.import_module("plugins_func.functions." + name)
    music = importlib.import_module("plugins_func.functions.play_music")
    clock = importlib.import_module("core.utils.current_time")

    class FrozenDateTime(datetime):
        @classmethod
        def now(cls, tz=None):
            fixed = datetime.fromisoformat(fixture["time"])
            return fixed.astimezone(tz) if tz else fixed.replace(tzinfo=None)

    loop = asyncio.new_event_loop()
    thread = threading.Thread(target=loop.run_forever, daemon=True)
    thread.start()
    rows, connections, pending = [], [], []
    provider = None
    try:
        def transport(request):
            pending.append(json.loads(request.content))
            chunk = {"id": "fixed-comparison", "object": "chat.completion.chunk", "created": 0,
                     "model": "synthetic-capture", "choices": [{"index": 0,
                     "delta": {"role": "assistant", "content": "固定合成回答。"}, "finish_reason": "stop"}]}
            return httpx.Response(200, headers={"content-type": "text/event-stream"},
                                  text="data: " + json.dumps(chunk, ensure_ascii=False) + "\n\ndata: [DONE]\n\n")

        provider = Provider({"model_name": "synthetic-capture", "api_key": "offline-placeholder",
                             "base_url": "https://diagnostic.invalid/v1", "max_tokens": 650, "temperature": 0.3})
        provider.client.close()
        provider.client = openai.OpenAI(api_key="offline-placeholder", base_url="https://diagnostic.invalid/v1",
                max_retries=0, http_client=httpx.Client(transport=httpx.MockTransport(transport)))
        with patch.object(clock, "datetime", FrozenDateTime), patch.object(
                music, "list_music_prompt_entries", return_value=fixture["music"]):
            for scenario in fixture["scenarios"]:
                plugins = copy.deepcopy(fixture["plugins"])
                config = {"prompt": fixture["role"], "prompt_template": str(source / "agent-base-prompt.txt"),
                          "selected_module": {"Intent": "function_call", "LLM": "diagnostic"},
                          "Intent": {"function_call": {"type": "function_call", "functions": list(plugins)}},
                          "meeting": {"enabled": False}, "plugins": plugins}
                conn = Connection(config, None, None, provider, None, None)
                connections.append(conn)
                conn.device_id, conn.loop, conn.tts = "synthetic-fixed-comparison", loop, TextSink()
                conn.features = {"emoji": False}
                conn.intent_type = conn.configured_intent_type = "function_call"
                conn.func_handler = UnifiedToolHandler(conn)
                # Both versions receive a normally initialized system and examples.
                conn._init_prompt_enhancement(refresh_context=False)
                conn._inject_tool_call_fewshot()
                assert conn.prompt and fixture["role"] in conn.prompt
                examples = sum(m.is_temporary for m in conn.dialogue.snapshot_for_memory())
                for role, content in scenario["history"]:
                    conn.dialogue.put(Message(role=role, content=content))
                active = scenario.get("active")
                if active and args.version == "after":
                    from core.game.interactions import activate_interaction
                    assert activate_interaction(conn, active), "synthetic activity was not authorized"
                elif active:
                    conn.game_session_manager.build_freeform_game_prompt(
                        scenario["history"][0][1] if scenario["history"] else scenario["query"],
                        conn._get_enabled_plugin_configs())

                pending.clear()
                if "followup" in scenario:
                    tool = scenario["followup"]
                    conn.dialogue.put(Message(role="user", content=scenario["query"]))
                    conn.dialogue.put(Message(role="assistant", tool_calls=[{
                        "id": "fixed_tool_result", "type": "function", "function": {
                            "name": tool["name"], "arguments": tool["arguments"]}}]))
                    conn.dialogue.put(Message(role="tool", content=tool["result"], tool_call_id="fixed_tool_result"))
                    activity = (conn.game_session_manager.build_freeform_game_prompt(
                        scenario["query"], conn._get_enabled_plugin_configs()) if args.version == "before" else None)
                    turn = PromptTurnContext.capture(conn, scenario["query"], activity)
                    conn.sentence_id = "fixed-comparison-turn"
                    conn.chat(None, depth=1, allow_tools=False, prompt_turn_context=turn,
                              trusted_follow_up_system_prompt="仅根据已取得的工具结果回答，不再调用工具。")
                else:
                    conn.chat(scenario["query"])
                assert len(pending) == 1, "each fixture must produce exactly one serialized request"
                payload = pending[0]
                system = "\n".join(m.get("content", "") for m in payload["messages"] if m["role"] == "system")
                assert fixture["role"] in system and "2026-10-10 12:00" in system
                assert not any(name in json.dumps(payload, ensure_ascii=False)
                               for name in ("data/.config.yaml", "Authorization", "Bearer "))
                rows.append({"id": scenario["id"], "fixture_sha256": digest(scenario),
                             "initialized_example_messages": examples,
                             "request_sha256": digest(payload), "request": payload,
                             "message_count": len(payload["messages"]),
                             "tool_names": [item["function"]["name"] for item in payload.get("tools", [])]})
        write_json(args.capture_output, {"version": args.version, "fixture_sha256": digest(fixture),
            "source_hashes": {name: hashlib.sha256((source / name).read_bytes()).hexdigest()
                              for name in SOURCE_FILES if (source / name).is_file()}, "requests": rows})
    finally:
        if provider is not None:
            provider.client.close()
        for conn in connections:
            conn.executor.shutdown(wait=True)
        loop.call_soon_threadsafe(loop.stop)
        thread.join(3)
        loop.close()


def reference_device(path):
    # Read only the authorized diagnostic's literal identifier, never execute it.
    values = [keyword.value.value for node in ast.walk(ast.parse(path.read_text()))
              if isinstance(node, ast.Call) for keyword in node.keywords
              if keyword.arg == "device" and isinstance(keyword.value, ast.Constant)
              and isinstance(keyword.value.value, str)]
    if len(set(values)) != 1:
        raise ValueError("reference must contain one unambiguous literal device identifier")
    return values[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before-root", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--ssh", default="ubuntu@yh.wejoy.link")
    parser.add_argument("--device")
    parser.add_argument("--device-reference", type=Path)
    parser.add_argument("--capture-only", action="store_true")
    parser.add_argument("--measure-existing", action="store_true")
    parser.add_argument("--capture", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--runtime-root", type=Path, help=argparse.SUPPRESS)
    parser.add_argument("--fixture", type=Path, help=argparse.SUPPRESS)
    parser.add_argument("--version", choices=("before", "after"), help=argparse.SUPPRESS)
    parser.add_argument("--capture-output", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.capture:
        capture(args)
        return
    if args.output is None or args.before_root is None:
        parser.error("--output and --before-root are required")
    output = args.output.resolve()
    if not output.is_relative_to(RUNTIME) or not output.name.startswith("plugin-") or "-comparison-" not in output.name:
        parser.error("output must be runtime/plugin-*-comparison-* with a new filename")
    output.parent.mkdir(parents=True, exist_ok=True)
    stem = output.with_suffix("")
    fixture_path = Path(str(stem) + "-fixture.json")
    captures = {}
    if not args.measure_existing:
        write_json(fixture_path, fixtures())
    for version, source in (("before", args.before_root), ("after", SERVER)):
        destination = Path(str(stem) + "-" + version + ".json")
        if not args.measure_existing:
            process = subprocess.run([sys.executable, "-s", "-B", str(Path(__file__).resolve()), "--capture",
                "--runtime-root", str(source.resolve()), "--fixture", str(fixture_path),
                "--version", version, "--capture-output", str(destination)],
                cwd=source.resolve(), text=True, capture_output=True, timeout=120)
            if process.returncode:
                raise RuntimeError("offline capture failed for " + version + ": " + process.stderr[-2400:])
        captures[version] = json.loads(destination.read_text())
    assert captures["before"]["fixture_sha256"] == captures["after"]["fixture_sha256"]
    before, after = captures["before"]["requests"], captures["after"]["requests"]
    assert len(before) == len(after) == 9
    for old, new in zip(before, after):
        assert old["id"] == new["id"] and old["fixture_sha256"] == new["fixture_sha256"]
        assert old["initialized_example_messages"] == new["initialized_example_messages"] > 0
    if args.capture_only:
        print(json.dumps({"status": "captured", "pairs": 9,
            "scenarios": [{"id": old["id"], "before_tools": len(old["tool_names"]),
                           "after_tools": len(new["tool_names"]), "initialized_examples": old["initialized_example_messages"]}
                          for old, new in zip(before, after)]}, ensure_ascii=False))
        return
    if not args.live or not (bool(args.device) ^ bool(args.device_reference)):
        parser.error("measurement needs --live and exactly one of --device/--device-reference")
    from prompt_journey_remote import RemoteDeepSeek
    device = args.device or reference_device(args.device_reference)
    pairs, failure = [], None
    with Path(str(stem) + "-usage.jsonl").open("x", encoding="utf-8") as journal, RemoteDeepSeek(
            args.ssh, device=device, max_calls=18, live=True) as remote:
        try:
            for old, new in zip(before, after):
                pair = {"id": old["id"]}
                for version, row in (("before", old), ("after", new)):
                    result = remote.complete(row["request"])
                    usage = result.get("usage", {})
                    if not isinstance(usage.get("prompt_tokens"), int):
                        raise ValueError("provider did not return integer usage.prompt_tokens")
                    record = {"scenario": row["id"], "version": version, "model": result["model"],
                              "request_sha256": row["request_sha256"], "usage": usage}
                    journal.write(json.dumps(record, ensure_ascii=False) + "\n")
                    journal.flush()
                    pair[version] = {"model": result["model"], "prompt_tokens": usage["prompt_tokens"]}
                if pair["before"]["model"] != pair["after"]["model"]:
                    raise ValueError("model changed across a comparison pair")
                pair["saved_tokens"] = pair["before"]["prompt_tokens"] - pair["after"]["prompt_tokens"]
                pairs.append(pair)
                print(json.dumps(pair, ensure_ascii=False), flush=True)
        except Exception as error:
            failure = {"type": type(error).__name__, "message": str(error)}
        calls = remote.calls_used
    totals = {version: sum(row[version]["prompt_tokens"] for row in pairs) for version in ("before", "after")}
    report = {"status": "complete" if failure is None else "incomplete", "failure": failure,
        "scope": "9 fixed requests, identical synthetic inputs/time/history; includes full messages and tools; not end-to-end user-turn cost",
        "activation": "after uses real activate_interaction as fixture precondition; startup/exit extra requests are excluded",
        "history": "all assistant/tool history is fixed; captured and cloud responses are never fed into later fixtures",
        "initialized_system_and_examples": True, "source_versions": {
            version: value["source_hashes"] for version, value in captures.items()},
        "fixture_sha256": captures["before"]["fixture_sha256"], "calls_attempted": calls,
        "paired_requests": len(pairs), "pairs": pairs, "input_tokens": totals,
        "mean_input_tokens": {version: total / len(pairs) if pairs else None for version, total in totals.items()},
        "saved_tokens": totals["before"] - totals["after"],
        "mean_saved_tokens": (totals["before"] - totals["after"]) / len(pairs) if pairs else None,
        "saved_percent": 100 * (totals["before"] - totals["after"]) / totals["before"] if totals["before"] else None}
    write_json(output, report)
    print(json.dumps({key: report[key] for key in ("status", "calls_attempted", "paired_requests", "input_tokens",
        "mean_input_tokens", "saved_tokens", "mean_saved_tokens", "saved_percent", "failure")}, ensure_ascii=False), flush=True)
    if failure:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
