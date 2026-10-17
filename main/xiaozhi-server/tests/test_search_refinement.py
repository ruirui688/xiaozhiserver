from types import SimpleNamespace
from plugins_func import search_refinement


def test_rewrite_is_isolated_short_and_inherits_metering_scope(monkeypatch):
    options, prompts, scopes = [], [], []
    class Client:
        def with_options(self, **kwargs):
            options.append(kwargs)
            return Client()
    class Provider:
        client = Client()
        stream_total_timeout_seconds = 30
        def response_no_stream(self, system, user, **kwargs):
            from core.metering.context import current_scope
            scopes.append(current_scope.get())
            prompts.append((system, user, kwargs))
            assert self is not original
            return '{"query":"替代关键词"}'
    original = Provider()
    monkeypatch.setattr(search_refinement, "snapshot", lambda conn: "test-scope")
    conn = SimpleNamespace(llm=original, dialogue="不得读取的聊天")
    assert search_refinement.refine_query(conn, "原始查询", "week", 10) == "替代关键词"
    assert options == [{"timeout": 6.0, "max_retries": 0}]
    assert scopes == ["test-scope"]
    assert prompts[0][2]["max_tokens"] == 128
    assert "不得读取的聊天" not in str(prompts)
    assert original.stream_total_timeout_seconds == 30


def test_unsupported_or_malformed_rewrite_is_skipped():
    assert search_refinement.refine_query(SimpleNamespace(), "query", "week", 5) is None
    assert search_refinement.refine_query(SimpleNamespace(), "query", "week", 0) is None
    class Client:
        def with_options(self, **kwargs):
            return self
    provider = SimpleNamespace(client=Client(), stream_total_timeout_seconds=30,
        response_no_stream=lambda *a, **k: "not JSON")
    assert search_refinement.refine_query(SimpleNamespace(llm=provider), "query", "week", 5) is None
