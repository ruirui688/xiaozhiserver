"""Catalog overrides must not advertise capabilities absent from real plugins."""

from types import SimpleNamespace

from core.providers.tools.server_plugins.plugin_executor import ServerPluginExecutor
from plugins_func.functions import get_news_from_chinanews, web_search  # noqa: F401
from plugins_func.register import all_function_registry


def _tools(search=True, plugin_config=None, search_prompt="运营搜索说明。"):
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
            "web_search": search_prompt,
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


def test_legacy_date_prompt_cannot_replace_current_query_contract():
    legacy = "每次查询必须追加当前年月日期和北京时间。"
    description = _tools(search_prompt=legacy)["web_search"].description["function"]["description"]
    assert description.startswith("实际查询合同优先于下文目录说明")
    assert "不得自行填入年月、具体日期或时间戳" in description
    assert "保留用户明确指定的具体日期、对象、来源和事实目标" in description
    assert "不要求写进query" in description
    assert legacy in description


def test_catalog_prompt_cannot_override_internal_refinement_contract():
    legacy = "每个用户请求只允许搜索一次，任何情况下都禁止再次搜索。"
    description = _tools(search_prompt=legacy)["web_search"].description["function"]["description"]
    assert description.startswith("实际查询合同优先于下文目录说明")
    assert "同一用户请求只调用一次web_search" in description
    assert "内部最多两次真实检索" in description
    assert "调用完成后不要再发起搜索" in description
    assert "以工具返回的实际查询次数为准" in description
    assert "只有工具明确允许补查时必须改写关键词再查一次" not in description
    assert "不得声称未实际执行的查询或查询次数" in description
    assert description.index("最多两次") < description.index(legacy)


def test_default_only_feed_omits_unsupported_category_selector():
    function = _tools(plugin_config={
        "default_rss_url": "https://example.com/default.xml",
    })["get_news_from_chinanews"].description["function"]
    assert "category" not in function["parameters"]["properties"]
