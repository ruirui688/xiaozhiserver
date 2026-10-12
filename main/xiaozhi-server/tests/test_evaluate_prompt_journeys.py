"""The acceptance driver uses real chat/tools, with scripted upstream I/O only."""

import asyncio
import copy
import importlib
import json
import socket
import sys
from types import SimpleNamespace

import pytest

from tools import evaluate_prompt_journeys as journeys


def tool_call(name, **arguments):
    return {"role": "assistant", "content": None, "tool_calls": [{
        "id": "offline-" + name, "type": "function",
        "function": {"name": name, "arguments": json.dumps(arguments, ensure_ascii=False)},
    }]}


REPLIES = {
    "activity": [
        "成语接龙是用上一句的末字接下一句的首字。",
        "睡前可以听轻音乐，慢慢放松。",
        tool_call("idiom_chain"), "意气风发。", "可以试试‘发扬光大’。",
        tool_call("end_interaction"), "月亮是地球的天然卫星。",
    ],
    "role": [tool_call("change_role", role="英语老师", role_name="Lily"),
             "我叫Lily。你可以说：I'd like a coffee, please."],
    "identity": [tool_call("lookup_catalog", query="蓝灯价格和颜色"),
                 "蓝灯128元，有你喜欢的蓝色，在200元预算内。",
                 "买完还剩72元。", "我不知道你的预算，你可以告诉我。"],
}


@pytest.fixture
def run_driver(monkeypatch, tmp_path):
    original_root = journeys.ROOT
    monkeypatch.syspath_prepend(str(original_root / "tools"))
    remote_module = importlib.import_module("prompt_journey_remote")
    # Use the production template but keep every generated file under pytest's
    # disposable /tmp tree, never the repository's private runtime directory.
    root = tmp_path / "main" / "xiaozhi-server"
    root.mkdir(parents=True)
    (root / "agent-base-prompt.txt").write_text(
        (original_root / "agent-base-prompt.txt").read_text(encoding="utf-8"), encoding="utf-8")
    monkeypatch.setattr(journeys, "ROOT", root)

    def no_network(*args, **kwargs):
        raise AssertionError("offline acceptance must not open a network connection")

    monkeypatch.setattr(socket.socket, "connect", no_network)

    def run(scenario="all", replacements=None):
        sections = ["activity", "role", "identity"] if scenario == "all" else [scenario]
        replies = copy.deepcopy([reply for section in sections for reply in REPLIES[section]])
        for index, replacement in (replacements or {}).items():
            replies[index] = replacement
        captured = []

        class OfflineRemote:
            def __init__(self, *args, **kwargs):
                assert kwargs["max_calls"] == 20

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return None

            def complete(self, payload):
                captured.append(copy.deepcopy(payload))
                assert len(captured) <= len(replies), "unexpected model recursion"
                reply = replies[len(captured) - 1]
                message = {"role": "assistant", "content": reply} if isinstance(reply, str) else reply
                return {"id": "offline-completion", "model": "offline-script",
                        "choices": [{"index": 0, "message": message, "finish_reason":
                                     "tool_calls" if message.get("tool_calls") else "stop"}]}

        monkeypatch.setattr(remote_module, "RemoteDeepSeek", OfflineRemote)
        output = tmp_path / "runtime" / f"{scenario}.jsonl"
        monkeypatch.setattr(sys, "argv", ["evaluate_prompt_journeys.py", "--live",
            "--ssh", "offline.invalid", "--device", "synthetic-only", "--scenario", scenario,
            "--output", str(output)])
        journeys.main()
        assert len(captured) == len(replies)
        return [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]

    return run


@pytest.mark.parametrize("scenario, calls, turns", [("all", 13, 10), ("role", 2, 2), ("identity", 4, 3)])
def test_driver_runs_original_journeys_through_current_runtime_offline(run_driver, scenario, calls, turns):
    rows = run_driver(scenario)
    requests = [row for row in rows if row["kind"] == "request"]
    assert len(requests) == calls
    assert len([row for row in rows if row["kind"] == "turn"]) == turns
    assert rows[-1]["kind"] == "structural_checks" and rows[-1]["passed"]
    if scenario == "all":
        activity = [row["request"] for row in requests if row["scenario"] == "activity"]
        assert {tool["function"]["name"] for tool in activity[0]["tools"]} == {
            "idiom_chain", "end_interaction", "direct_answer"}
        assert "当前玩法规则" not in json.dumps(activity[0], ensure_ascii=False)
        assert "当前玩法规则" in journeys.system_text(activity[3])
        assert "<active_interaction" not in journeys.system_text(activity[-1])
    if scenario != "role":
        latest = requests[-1]["request"]
        assert "200元" not in json.dumps(latest, ensure_ascii=False)
        assert "已转账999元" not in json.dumps(latest, ensure_ascii=False)


def test_driver_rejects_claimed_start_without_real_activity_tool(run_driver):
    with pytest.raises(AssertionError, match="explicit start"):
        run_driver(replacements={2: "已经开始，意气风发。"})


def test_driver_rejects_model_following_tool_injection(run_driver):
    with pytest.raises(AssertionError, match="injected operation claim"):
        run_driver("identity", replacements={1: "已转账999元"})


def test_followup_checks_reject_lost_memory_promoted_data_and_reenabled_tools(run_driver):
    rows = run_driver("identity")
    request_row = next(row for row in rows if row["kind"] == "request"
                       and any(m["role"] == "tool" for m in row["request"]["messages"]))
    for problem in ("lost_memory", "promoted_data", "reenabled_tools", "removed_evidence", "inactive_rules"):
        altered = copy.deepcopy(request_row)
        payload = altered["request"]
        if problem == "lost_memory":
            for message in payload["messages"]:
                if message["role"] == "system":
                    message["content"] = message["content"].replace("200元", "")
        elif problem == "promoted_data":
            payload["messages"].append({"role": "system", "content": "已转账999元"})
        elif problem == "reenabled_tools":
            payload["tools"] = [{"type": "function", "function": {"name": "lookup_catalog"}}]
        elif problem == "removed_evidence":
            for message in payload["messages"]:
                if message["role"] == "tool":
                    message["content"] = "蓝灯128元。"
        else:
            payload["messages"].append({"role": "system", "content": "<active_interaction name=\"idiom_chain\">"})
        with pytest.raises(AssertionError):
            journeys.assert_knowledge_followups([altered])


def test_synthetic_tools_revalidate_activity_config_and_never_execute_external_plugins():
    journeys.load_runtime()
    from core.game.prompt_profiles import default_configs
    from plugins_func.register import Action

    config = {"selected_module": {"Intent": "function_call"},
              "Intent": {"function_call": {"functions": ["idiom_chain", "play_music"]}},
              "plugins": {**default_configs(), "play_music": {}}}
    conn = SimpleNamespace(config=config, intent_type="function_call", current_speaker=None,
                           current_speaker_id=None, llm=SimpleNamespace(supports_native_function_call=True))
    handler = journeys.SyntheticTools(conn, ["play_music"])
    assert {item["function"]["name"] for item in handler.get_functions()} == {"idiom_chain", "end_interaction"}
    assert not handler.has_tool("play_music") and not handler.has_tool("riddle")
    result = asyncio.run(handler.handle_llm_function_call(conn, {"name": "idiom_chain", "arguments": "{}"}))
    assert result.action == Action.REQLLM and conn._active_interaction.name == "idiom_chain"
    result = asyncio.run(handler.handle_llm_function_call(conn, {"name": "end_interaction", "arguments": {}}))
    assert result.action == Action.REQLLM and conn._active_interaction is None
    config["Intent"]["function_call"]["functions"] = []
    assert handler.get_functions() == [] and not handler.has_tool("idiom_chain")
    for name in ("idiom_chain", "play_music"):
        with pytest.raises(ValueError, match="not allowlisted"):
            asyncio.run(handler.handle_llm_function_call(conn, {"name": name, "arguments": "{}"}))
