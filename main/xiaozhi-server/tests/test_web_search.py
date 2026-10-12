import sys
from pathlib import Path
from types import ModuleType


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

fake_logger_module = ModuleType("config.logger")


class FakeLogger:
    def bind(self, **kwargs):
        return self

    def debug(self, *args, **kwargs):
        return None

    def info(self, *args, **kwargs):
        return None

    def warning(self, *args, **kwargs):
        return None

    def error(self, *args, **kwargs):
        return None


fake_logger_module.setup_logging = lambda: FakeLogger()
fake_logger_module.build_module_string = lambda *args, **kwargs: ""
fake_logger_module.create_connection_logger = lambda *args, **kwargs: FakeLogger()
sys.modules.setdefault("config.logger", fake_logger_module)

from plugins_func.functions import web_search


def test_metaso_search_keeps_snippet_and_answer_constraints(monkeypatch):
    captured = {}
    monkeypatch.setattr(
        web_search,
        "_get_beijing_time_context",
        lambda: "2026-06-29 15:00 北京时间",
    )

    class FakeResponse:
        status_code = 200

        def raise_for_status(self):
            return None

        def json(self):
            return {
                "webpages": [
                    {
                        "title": "加拿大1比0战胜南非，晋级2026美加墨世界杯十六强",
                        "link": "https://www.news.cn/sports/example.html",
                        "snippet": "6月28日，南非队球员在比赛后离场。",
                        "content": "6月30日（周二）\n01:00C1VSF2第74场\n04:30E1VSA3/B3/C3/D3/F3第75场",
                        "date": "2026年06月28日",
                    }
                ]
            }

    def fake_post(url, json, headers, timeout):
        captured["url"] = url
        captured["payload"] = json
        captured["headers"] = headers
        captured["timeout"] = timeout
        return FakeResponse()

    monkeypatch.setattr(web_search.requests, "post", fake_post)

    result = web_search._search_metaso("mk-test", "南非加拿大比赛结果", 5)

    assert captured["url"] == "https://metaso.cn/api/v1/search"
    assert captured["payload"]["q"] == "南非加拿大比赛结果 当前北京时间 2026-06-29 15:00 北京时间"
    assert captured["payload"]["size"] == 5
    assert captured["payload"]["includeRawContent"] is True
    assert "实际搜索查询：南非加拿大比赛结果 当前北京时间 2026-06-29 15:00 北京时间" in result
    assert "当前参考时间：2026-06-29 15:00 北京时间" in result
    assert "只能依据下面列出的搜索结果回答" in result
    assert "判断结果是否直接回答用户原始问题" in result
    assert "不要使用旧知识、猜测或再次搜索" in result
    assert "证据不足或相互冲突的内容明确标为无法确认" in result
    assert "只回答用户询问的范围" in result
    assert "不能从搜索结果直接定位的内容，说明未查到明确依据" in result
    assert "不能把不确定的日期当成确定事实" in result
    assert "加拿大1比0战胜南非" in result
    assert "摘要：6月28日，南非队球员在比赛后离场。" in result
    assert "正文：6月30日（周二）" in result
    assert "04:30E1VSA3/B3/C3/D3/F3第75场" in result
    assert "链接：https://www.news.cn/sports/example.html" in result
    assert "<untrusted_search_results>" in result
    assert "</untrusted_search_results>" in result


def test_metaso_empty_result_preserves_beijing_context_and_stops(monkeypatch):
    captured = {}

    class FakeResponse:
        status_code = 200

        def raise_for_status(self):
            return None

        def json(self):
            return {"webpages": []}

    def fake_post(url, json, headers, timeout):
        captured["payload"] = json
        return FakeResponse()

    monkeypatch.setattr(web_search.requests, "post", fake_post)

    result = web_search._search_metaso(
        "mk-test",
        "用户提交的完整查询",
        5,
        current_time_context="2026-08-06 09:30 北京时间",
    )

    assert captured["payload"]["q"].endswith(
        "当前北京时间 2026-08-06 09:30 北京时间"
    )
    assert "结果状态：本次查询未返回网页" in result
    assert "模型提交查询：用户提交的完整查询" in result
    assert "当前参考时间：2026-08-06 09:30 北京时间" in result
    assert "空结果不等于目标事实不存在" in result
    assert "这是当前请求唯一一次联网搜索" in result
    assert "不得使用模型旧知识、常识推断、预测" in result


def test_tavily_empty_result_preserves_beijing_context_and_stops(monkeypatch):
    captured = {}

    class FakeResponse:
        status_code = 200

        def raise_for_status(self):
            return None

        def json(self):
            return {"results": []}

    def fake_post(url, json, headers, timeout):
        captured["payload"] = json
        return FakeResponse()

    monkeypatch.setattr(web_search.requests, "post", fake_post)

    result = web_search._search_tavily(
        "tvly-test",
        "用户提交的完整查询",
        5,
        current_time_context="2026-08-06 09:30 北京时间",
    )

    assert captured["payload"]["query"].endswith(
        "当前北京时间 2026-08-06 09:30 北京时间"
    )
    assert "结果状态：本次查询未返回网页" in result
    assert "模型提交查询：用户提交的完整查询" in result
    assert "搜索源：Tavily" in result
    assert "当前参考时间：2026-08-06 09:30 北京时间" in result
    assert "这是当前请求唯一一次联网搜索" in result


def test_metaso_search_can_disable_raw_content_and_time_context(monkeypatch):
    captured = {}

    class FakeResponse:
        status_code = 200

        def raise_for_status(self):
            return None

        def json(self):
            return {"webpages": [{"title": "测试", "snippet": "摘要"}]}

    def fake_post(url, json, headers, timeout):
        captured["payload"] = json
        return FakeResponse()

    monkeypatch.setattr(web_search.requests, "post", fake_post)

    result = web_search._search_metaso(
        "mk-test",
        "今天有什么比赛",
        3,
        current_time_context="2026-06-29 15:00 北京时间",
        include_raw_content=False,
        include_time_context=False,
    )

    assert captured["payload"]["q"] == "今天有什么比赛"
    assert captured["payload"]["includeRawContent"] is False
    assert "实际搜索查询：今天有什么比赛" in result


def test_beijing_time_context_uses_shared_current_time(monkeypatch):
    monkeypatch.setattr(
        web_search,
        "get_current_datetime",
        lambda: "2026-06-29 15:45 北京时间",
    )

    assert web_search._get_beijing_time_context() == "2026-06-29 15:45 北京时间"


def test_web_search_description_preserves_user_temporal_intent():
    description = web_search.WEB_SEARCH_FUNCTION_DESC["function"]["description"]
    query_description = web_search.WEB_SEARCH_FUNCTION_DESC["function"]["parameters"][
        "properties"
    ]["query"]["description"]

    assert "同时保留用户的相对时间表达" in description
    assert "绝对日期" in description
    assert "事实目标和来源要求" in description
    assert "同一用户请求只有一次联网搜索机会" in description
    assert "必须保留用户原话中的相对时间短语" in query_description
    assert "查询词要同时包含原始相对时间短语和根据当前北京时间换算出的绝对日期" in query_description
    assert "查询词要体现用户要查的事实目标" in query_description
    assert "来源要求" in query_description


def test_base_prompt_does_not_claim_optional_tools_are_always_available():
    from core.utils.prompt_manager import PromptManager

    manager = PromptManager({}, logger=FakeLogger())
    manager.base_prompt_template = (SERVER_ROOT / "agent-base-prompt.txt").read_text(encoding="utf-8")
    enabled = manager.build_enhanced_prompt("测试助手", "offline-tool-gate",
                                           available_tools=[web_search.WEB_SEARCH_FUNCTION_DESC],
                                           emoji_enabled=False)
    disabled = manager.build_enhanced_prompt("测试助手", "offline-tool-gate",
                                            available_tools=[], emoji_enabled=False)
    assert "原生toolsschema是能力的唯一依据" in "".join(enabled.split())
    assert "只调用已注册工具" in enabled
    assert "<available_tools>" in enabled and "web_search" in enabled
    assert "<available_tools>" not in disabled
    assert "<tool_and_knowledge>" not in disabled
    for name in ("play_music", "get_weather", "handle_exit_intent", "has_exit_tool"):
        assert name not in enabled and name not in disabled
    assert "web_search" not in disabled


def test_web_search_ignores_lang_argument_without_losing_query():
    class FakeConn:
        config = {
            "plugins": {
                "web_search": {
                    "provider": "metaso",
                    "max_results": 5,
                    "api_key": "",
                }
            }
        }

    result = web_search.web_search(
        FakeConn(),
        query="今天晚上有什么球赛",
        lang="zh_CN",
    )

    assert result.action == web_search.Action.REQLLM
    assert "未配置API Key" in result.result
    assert result.next_llm_tools_enabled is False
    assert "不要通过改写查询重复调用搜索" in result.next_llm_system_prompt


def test_web_search_success_disables_follow_up_tools(monkeypatch):
    class FakeConn:
        config = {
            "plugins": {
                "web_search": {
                    "provider": "metaso",
                    "max_results": 5,
                    "api_key": "mk-test",
                }
            }
        }

    monkeypatch.setattr(
        web_search,
        "_search_metaso",
        lambda *args, **kwargs: "【联网搜索结果】\n搜索结果明细：\n1. 标题：证据",
    )

    result = web_search.web_search(FakeConn(), query="需要联网验证的问题")

    assert result.action == web_search.Action.REQLLM
    assert result.next_llm_tools_enabled is False
    assert "唯一一次 web_search 已经完成" in result.next_llm_system_prompt
    assert "不得再次调用联网搜索" in result.next_llm_system_prompt


def test_web_search_transport_error_stops_query_rewrites(monkeypatch):
    class FakeConn:
        config = {
            "plugins": {
                "web_search": {
                    "provider": "metaso",
                    "max_results": 5,
                    "api_key": "mk-test",
                }
            }
        }

    def fail_search(*args, **kwargs):
        raise web_search.requests.exceptions.Timeout("timeout")

    monkeypatch.setattr(web_search, "_search_metaso", fail_search)

    result = web_search.web_search(FakeConn(), query="需要联网验证的问题")

    assert result.action == web_search.Action.REQLLM
    assert result.next_llm_tools_enabled is False
    assert "联网搜索请求超时" in result.result
    assert "不要通过改写查询重复调用搜索" in result.next_llm_system_prompt
