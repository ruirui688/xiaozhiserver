import copy
import json
from concurrent.futures import ThreadPoolExecutor

from core.observability.prompt_inventory import summarize_prompt, safe_prompt_summary
from core.observability.prompt_inventory import aggregate_prompt_summaries
from core.utils.turn_metrics import TurnMetrics


def test_categories_are_disjoint_and_no_content_leaks():
    secret = "SECRET_DO_NOT_LOG"
    messages = [
        {"role": "system", "content": 'base<identity>role</identity><context>time<memory>' + secret + '</memory><speakers_info>name</speakers_info></context>'},
        {"role": "user", "content": "old"},
        {"role": "assistant", "content": "reply"},
        {"role": "tool", "content": secret},
        {"role": "system", "content": '<freeform_game name="x">rules</freeform_game>'},
        {"role": "user", "content": 'new "question"\n'},
    ]
    tools = [{"type": "function", "function": {"name": secret}}]
    original = copy.deepcopy((messages, tools))
    result = summarize_prompt(messages, tools)
    assert sum(v["chars"] for v in result["categories"].values()) == result["total_chars"]
    assert result["total_chars"] == sum(len(json.dumps(x, ensure_ascii=False, separators=(",", ":"))) for x in (messages, tools))
    assert result["categories"]["role_identity"]["chars"] == len('<identity>role</identity>')
    assert result["categories"]["memory"]["chars"] == len('<memory>' + secret + '</memory>')
    assert result["categories"]["conversation_history"]["chars"] == 8
    assert result["category_tokens"] is None
    assert secret not in json.dumps(result)
    assert (messages, tools) == original


def test_multimodal_and_tool_calls_are_not_text_tokens():
    messages = [{"role": "assistant", "content": None, "tool_calls": [{"id": "private-id", "arguments": "secret"}]},
                {"role": "user", "content": [{"type": "image_url", "image_url": {"url": "data:image/png;base64,private"}}]}]
    result = summarize_prompt(messages)
    assert result["categories"]["structured_content"]["chars"] > 0
    assert result["categories"]["message_json_structure"]["chars"] > 0
    assert "private" not in json.dumps(result)
    assert sum(v["chars"] for v in result["categories"].values()) == result["total_chars"]


def test_user_markup_is_not_misattributed_to_system():
    r = summarize_prompt([{"role": "user", "content": "<memory>not memory</memory>"}])
    assert r["categories"]["memory"]["chars"] == 0
    assert r["categories"]["last_user_message"]["chars"] == len("<memory>not memory</memory>")


def test_diagnostic_failure_is_fail_open():
    assert safe_prompt_summary([object()])["status"] == "unavailable"


def test_concurrent_connections_and_provider_usage_preserve_breakdown():
    def record(size):
        m = TurnMetrics()
        tid = m.ensure_text_turn()
        m.record_llm_started(True, 0, dialogue=[{"role": "user", "content": "字" * size}], functions=[], turn_id=tid)
        m.record_llm_complete("答", {"prompt_tokens": 99, "completion_tokens": 1, "total_tokens": 100}, False, 0, depth=0, turn_id=tid)
        step = m.build_assistant_metrics("答", turn_id=tid)["trace"]["steps"][0]
        assert step["tokens"]["source"] == "provider_usage"
        assert step["tokens"]["prompt_tokens"] == 99
        return step["input_breakdown"]["categories"]["last_user_message"]["chars"]
    with ThreadPoolExecutor(max_workers=4) as pool:
        assert list(pool.map(record, range(1, 9))) == list(range(1, 9))


def test_repeated_calls_are_kept_separate_and_disabled_tools_excluded():
    m = TurnMetrics()
    tid = m.ensure_text_turn()
    for depth in (0, 1):
        m.record_llm_started(False, depth, dialogue=[{"role": "user", "content": "hello"}], functions=[{"name": "not_sent"}], turn_id=tid)
    steps = m.build_assistant_metrics("", turn_id=tid)["trace"]["steps"]
    assert [s["call_index"] for s in steps] == [1, 2]
    assert all(s["input_breakdown"]["tool_count"] == 0 for s in steps)


def test_weighted_average_excludes_missing_samples():
    rows = [summarize_prompt([{"role": "user", "content": "a" * n}]) for n in (10, 100)]
    result = aggregate_prompt_summaries(rows + [{"status": "unavailable"}])
    assert result["sample_count"] == 2
    assert result["categories"]["last_user_message"]["mean_chars"] == 55
    assert result["total_chars"] == sum(r["total_chars"] for r in rows)
    assert result["categories"]["last_user_message"]["weighted_percent"] == round(110 * 100 / result["total_chars"], 2)
    assert aggregate_prompt_summaries([])["mean_total_chars"] is None
