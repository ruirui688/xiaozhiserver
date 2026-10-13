"""Exercise local-knowledge tool formatting, mocking only the HTTP boundary."""
from types import SimpleNamespace
from config import settings
settings.config_file_valid = True

from core.utils.knowledge_context import BUDGET_EMPTY_NOTICE, OMITTED_NOTICE, select_knowledge_context
from core.utils.prompt_budget import KNOWLEDGE_CONTEXT_MAX_CHARS, compact_tool_history
from plugins_func.functions import search_from_local_knowledge as plugin
from plugins_func.register import Action


def call_tool(monkeypatch, chunks, question="可乐多少钱？"):
    response = SimpleNamespace(encoding="utf-8", raise_for_status=lambda: None,
        json=lambda: {"code": 0, "data": {"chunks": chunks}})
    monkeypatch.setattr(plugin.requests, "post", lambda *args, **kwargs: response)
    conn = SimpleNamespace(config={
        "plugins": {"search_from_local_knowledge": {"dataset_ids": ["dataset"]}},
        "manager-api": {"url": "http://manager-api", "secret": "test-secret"}})
    return plugin.search_from_local_knowledge(conn, question)


def test_backend_sixth_result_is_not_silently_dropped(monkeypatch):
    chunks = [{"content": f"商品{i}售价20元。"} for i in range(5)]
    chunks.append({"content": "可乐15元/听，仅限330ml罐装，不提供杯装。", "document_name": "饮品表"})
    result = call_tool(monkeypatch, chunks)
    assert result.action == Action.REQLLM
    assert "可乐15元/听，仅限330ml罐装，不提供杯装。" in result.result
    messages = compact_tool_history([{"role": "tool", "content": result.result}])
    assert messages[0]["content"] == result.result


def test_long_entry_is_skipped_whole_and_later_complete_evidence_survives(monkeypatch):
    large = "套餐说明" * 1500 + "不适用于周末。"
    evidence = "可乐15元/听，仅限330ml罐装，不提供杯装。"
    result = call_tool(monkeypatch, [{"content": large}, {"content": evidence}])
    assert evidence in result.result
    assert "套餐说明" not in result.result
    assert OMITTED_NOTICE in result.result
    assert len(result.result) <= KNOWLEDGE_CONTEXT_MAX_CHARS


def test_budget_exhaustion_is_distinct_from_no_match(monkeypatch):
    result = call_tool(monkeypatch, [{"content": "知识正文" * 2000}])
    assert result.action == Action.REQLLM
    assert result.result == BUDGET_EMPTY_NOTICE
    assert result.result != plugin.LOCAL_KNOWLEDGE_EMPTY_RESULT
    assert result.next_llm_tools_enabled is False


def test_no_partial_price_or_condition_at_budget_boundary():
    fact = "套餐价格98元；仅限周一至周五，周末不可用。"
    selection = select_knowledge_context("资料", ["甲" * 3900, fact * 10, "可乐15元/听。"])
    assert fact not in selection.text
    assert "可乐15元/听。" in selection.text
    assert selection.selected_count == 2
    assert selection.omitted_count == 1
    assert len(selection.text) <= KNOWLEDGE_CONTEXT_MAX_CHARS


def test_duplicate_records_do_not_consume_context_slots():
    selection = select_knowledge_context("资料", ["可乐15元。", " 可乐15元。 ", "果汁25元。"])
    assert selection.selected_count == 2
    assert selection.omitted_count == 0
    assert selection.text.count("可乐15元。") == 1


def test_oversized_query_cannot_consume_the_fact_budget(monkeypatch):
    result = call_tool(monkeypatch, [{"content": "可乐15元/听。"}], "可乐" * 5000)
    assert "可乐15元/听。" in result.result
    assert len(result.result) <= KNOWLEDGE_CONTEXT_MAX_CHARS


def test_request_failure_does_not_echo_private_exception(monkeypatch, capsys):
    def fail(*args, **kwargs):
        raise plugin.requests.exceptions.ConnectionError("Authorization: Bearer private-test-token")
    monkeypatch.setattr(plugin.requests, "post", fail)
    conn = SimpleNamespace(config={
        "plugins": {"search_from_local_knowledge": {"dataset_ids": ["dataset"]}},
        "manager-api": {"url": "http://manager-api", "secret": "test-secret"}})
    result = plugin.search_from_local_knowledge(conn, "可乐")
    assert "private-test-token" not in str(result.response)
    output = capsys.readouterr()
    assert "private-test-token" not in output.out + output.err
