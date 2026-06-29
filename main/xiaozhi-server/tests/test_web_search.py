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
    assert captured["payload"]["q"] == "南非加拿大比赛结果"
    assert captured["payload"]["size"] == 5
    assert "当前参考时间：2026-06-29 15:00 北京时间" in result
    assert "只能依据下面列出的搜索结果回答" in result
    assert "不能把不确定的日期当成确定事实" in result
    assert "加拿大1比0战胜南非" in result
    assert "摘要：6月28日，南非队球员在比赛后离场。" in result
    assert "链接：https://www.news.cn/sports/example.html" in result


def test_beijing_time_context_uses_shared_current_time(monkeypatch):
    monkeypatch.setattr(
        web_search,
        "get_current_datetime",
        lambda: "2026-06-29 15:45 北京时间",
    )

    assert web_search._get_beijing_time_context() == "2026-06-29 15:45 北京时间"
