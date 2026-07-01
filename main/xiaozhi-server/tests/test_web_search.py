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
    assert "先判断每条结果是否真正回答了用户原始查询" in result
    assert "重新调用 web_search 生成更精确的查询" in result
    assert "只有搜索结果明确给出比分、胜负、晋级、完成等信息，才能当作已确认结果" in result
    assert "不要擅自补充搜索结果没有明确写出的赛事阶段、轮次、状态、胜者或比分" in result
    assert "用户没有询问阶段或轮次时，不要主动概括为小组赛、淘汰赛、决赛等阶段" in result
    assert "使用“比赛”这类中性表述" in result
    assert "不能把不确定的日期当成确定事实" in result
    assert "加拿大1比0战胜南非" in result
    assert "摘要：6月28日，南非队球员在比赛后离场。" in result
    assert "正文：6月30日（周二）" in result
    assert "04:30E1VSA3/B3/C3/D3/F3第75场" in result
    assert "链接：https://www.news.cn/sports/example.html" in result


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

    assert "保留用户原话里的相对时间短语" in description
    assert "绝对日期" in description
    assert "避免改写成泛泛的赛程" in description
    assert "必须保留用户原话中的相对时间短语" in query_description
    assert "查询词要同时包含原始相对时间短语和根据当前北京时间换算出的绝对日期" in query_description
    assert "查询词要体现已发生/已结束" in query_description


def test_base_prompt_routes_latest_queries_to_web_search():
    prompt = (SERVER_ROOT / "agent-base-prompt.txt").read_text(encoding="utf-8")

    assert "联网搜索、查最新、实时资料、今天/昨天/刚结束的结果/比分/赛果→web_search" in prompt
    assert "禁止在调用工具前用静态知识否定用户的问题" in prompt


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
