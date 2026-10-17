"""Catalog overrides must not advertise capabilities absent from real plugins."""

from types import SimpleNamespace

from core.providers.tools.server_plugins.plugin_executor import ServerPluginExecutor
from plugins_func.functions import get_news_from_chinanews, web_search  # noqa: F401
from plugins_func.register import all_function_registry


def _tools(search=True, plugin_config=None):
    functions = ["get_news_from_chinanews"] + (["web_search"] if search else [])
    config = {
        "selected_module": {"Intent": "function_call"},
        "Intent": {"function_call": {"functions": functions}},
        "plugins": {"get_news_from_chinanews": plugin_config or {
            "society_rss_url": "https://example.com/society.xml",
            "world_rss_url": "https://example.com/world.xml",
        }},
        "tool_prompt_overrides": {
            "get_news_from_chinanews": "运营自定义：支持科技新闻和热点。",
            "web_search": "运营搜索说明。",
        },
    }
    return ServerPluginExecutor(SimpleNamespace(config=config, intent_type="function_call")).get_tools()


def test_news_catalog_override_keeps_real_categories_and_capability_boundary():
    function = _tools()["get_news_from_chinanews"].description["function"]
    assert function["parameters"]["properties"]["category"]["enum"] == ["社会", "国际"]
    assert "不能提供热点排行" in function["description"]
    assert "当前可用的web_search" in function["description"]
    assert "运营自定义" in function["description"]
    assert function["description"].startswith("实际能力约束优先")


def test_news_does_not_offer_disabled_search():
    tools = _tools(search=False)
    assert "web_search" not in tools
    description = tools["get_news_from_chinanews"].description["function"]["description"]
    assert "当前未启用网页搜索" in description
    assert "当前可用的web_search" not in description


def test_news_does_not_offer_unregistered_search(monkeypatch):
    monkeypatch.delitem(all_function_registry, "web_search")
    tools = _tools()
    assert "web_search" not in tools
    assert "当前未启用网页搜索" in tools["get_news_from_chinanews"].description["function"]["description"]


def test_news_categories_follow_each_connections_configuration():
    function = _tools(plugin_config={
        "finance_rss_url": "https://example.com/finance.xml",
        "science_rss_url": "https://example.com/science.xml",
        "default_rss_url": "https://example.com/default.xml",
    })["get_news_from_chinanews"].description["function"]
    assert function["parameters"]["properties"]["category"]["enum"] == ["财经", "science_rss_url"]
    # Registry schemas are shared; per-connection restrictions cannot leak.
    assert _tools()["get_news_from_chinanews"].description["function"]["parameters"]["properties"]["category"]["enum"] == ["社会", "国际"]


def test_search_catalog_override_keeps_time_range_guidance():
    description = _tools()["web_search"].description["function"]["description"]
    assert "time_range" in description
    assert "day/week/month/year" in description
    assert "运营搜索说明" in description


def test_default_only_feed_omits_unsupported_category_selector():
    function = _tools(plugin_config={
        "default_rss_url": "https://example.com/default.xml",
    })["get_news_from_chinanews"].description["function"]
    assert "category" not in function["parameters"]["properties"]
