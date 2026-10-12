"""Offline range regressions using synthetic forecasts, never real services."""

import asyncio
import json
import subprocess
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest
from bs4 import BeautifulSoup

# Reuse the weather test's private-config-free logger and connection fixture.
from test_get_weather_warning import FakeConn, get_weather
from core.providers.intent.intent_llm.intent_llm import IntentProvider
from core.providers.tools.server_plugins.plugin_executor import ServerPluginExecutor
from core.utils.cache.manager import cache_manager
from plugins_func import weather_scope
from plugins_func.weather_scope import forecast_date, scoped_weather_report


TODAY = date(2026, 10, 12)


@pytest.fixture(autouse=True)
def no_network_and_isolated_cache(monkeypatch):
    def unexpected_request(*args, **kwargs):
        pytest.fail("weather regression attempted an external request")
    monkeypatch.setattr(get_weather.requests, "get", unexpected_request)
    monkeypatch.setattr(get_weather, "weather_today", lambda: TODAY)
    cache = {}
    monkeypatch.setattr(cache_manager, "get", lambda kind, key: cache.get(key))
    monkeypatch.setattr(cache_manager, "set", lambda kind, key, value: cache.__setitem__(key, value))
    return cache


@pytest.fixture
def primary(monkeypatch):
    days = [TODAY + timedelta(days=index) for index in range(7)]
    rows = "".join(
        f'<div class="city-forecast-tabs__row"><div class="date-bg">'
        f'<span class="date">{day.month:02}/{day.day:02}</span>'
        '<img class="icon" src="/icons/100.svg"></div>'
        f'<div class="tmp-cont"><span class="temp">{20 + index}°</span>'
        f'<span class="temp">{10 + index}°</span></div></div>'
        for index, day in enumerate(days)
    )
    current = (
        '<div class="c-city-weather-current">'
        '<div class="current-abstract">现在23度，有风。</div>'
        '<div class="current-basic">'
        '<div class="current-basic___item"><span>3级</span><span>西风</span></div>'
        '<div class="current-basic___item"><span>68%</span><span>相对湿度</span></div>'
        '<div class="current-basic___item"><span>24°</span><span>体感温度</span></div>'
        '</div></div>'
    )
    soup = BeautifulSoup('<h1 class="c-submenu__location">合成市</h1>' + current + rows, "html.parser")
    calls = []
    monkeypatch.setattr(get_weather, "fetch_city_info", lambda *args: {"fxLink": "synthetic-page"})
    monkeypatch.setattr(get_weather, "fetch_weather_page", lambda url: calls.append(url) or soup)
    return calls


@pytest.mark.parametrize("start,count", [(0, 1), (1, 1), (0, 7)])
def test_primary_only_reports_requested_dates(primary, start, count):
    result = get_weather.get_weather(FakeConn(), location="合成市", start_day=start, days=count)
    assert result.action == get_weather.Action.RESPONSE
    for index in range(7):
        expected = start <= index < start + count
        assert ((TODAY + timedelta(days=index)).isoformat() in result.response) is expected
    assert "如需某一天" not in result.response
    assert len(primary) == 1


@pytest.mark.parametrize("start,count", [(0, 1), (1, 1), (0, 7)])
def test_primary_keeps_current_observations_only_for_today(primary, start, count):
    report = get_weather.get_weather(
        FakeConn(), location="合成市", start_day=start, days=count,
    ).response
    for observation in ("现在23度", "西风3级", "相对湿度68%", "体感温度24°"):
        assert (observation in report) == (start == 0 and count == 1)


def test_weather_today_without_iana_timezone_data():
    # Use an isolated interpreter so a populated ZoneInfo cache or installed
    # tzdata package cannot hide the deployment regression.
    script = """
import sys
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError, reset_tzpath

sys.path.insert(0, sys.argv[1])
sys.modules['tzdata'] = None
reset_tzpath(())
ZoneInfo.clear_cache()
try:
    ZoneInfo('Asia/Shanghai')
except ZoneInfoNotFoundError:
    pass
else:
    raise AssertionError('Timezone data was not isolated')

from plugins_func.weather_scope import weather_today
before = datetime.now(timezone(timedelta(hours=8))).date()
actual = weather_today()
after = datetime.now(timezone(timedelta(hours=8))).date()
assert actual in {before, after}
"""
    subprocess.run(
        [sys.executable, "-I", "-B", "-c", script, str(Path(__file__).resolve().parents[1])],
        check=True, capture_output=True, text=True, timeout=10,
    )


@pytest.mark.parametrize("instant,expected", [
    (datetime(2026, 12, 31, 15, 59, 59, tzinfo=timezone.utc), date(2026, 12, 31)),
    (datetime(2026, 12, 31, 16, 0, 0, tzinfo=timezone.utc), date(2027, 1, 1)),
])
def test_weather_today_rolls_over_at_beijing_midnight(monkeypatch, instant, expected):
    class FrozenDatetime(datetime):
        @classmethod
        def now(cls, tz=None):
            return instant.astimezone(tz) if tz is not None else instant.replace(tzinfo=None)

    monkeypatch.setattr(weather_scope, "datetime", FrozenDatetime)
    assert weather_scope.weather_today() == expected


def test_default_is_today_and_scope_cache_does_not_reuse_full_report(primary, no_network_and_isolated_cache):
    cache = no_network_and_isolated_cache
    cache["full_weather_合成市_zh_CN"] = "旧缓存包含后面七天"
    cache[f"scoped_weather_v1_{TODAY}_合成市_zh_CN_0_1"] = "旧缓存缺少实况参数"
    today = get_weather.get_weather(FakeConn(), location="合成市").response
    tomorrow = get_weather.get_weather(FakeConn(), location="合成市", start_day=1).response
    week = get_weather.get_weather(FakeConn(), location="合成市", days=7).response
    assert len({today, tomorrow, week}) == 3
    assert "旧缓存" not in today
    assert "相对湿度68%" in today
    assert get_weather.get_weather(FakeConn(), location="合成市").response == today
    assert len(primary) == 3


def test_cache_changes_at_beijing_midnight(primary, monkeypatch):
    get_weather.get_weather(FakeConn(), location="合成市", start_day=1)
    monkeypatch.setattr(get_weather, "weather_today", lambda: TODAY + timedelta(days=1))
    report = get_weather.get_weather(FakeConn(), location="合成市").response
    assert len(primary) == 2
    assert "今天（2026-10-13）" in report and "明天" not in report


@pytest.mark.parametrize("start,count", [(0, 1), (1, 1), (0, 7)])
def test_fallback_can_return_seven_days_but_never_leaks_unrequested_dates(monkeypatch, start, count):
    monkeypatch.setattr(get_weather, "fetch_city_info", lambda *args: None)
    monkeypatch.setattr(get_weather, "fetch_open_meteo_geo",
                        lambda location: ({"latitude": 0, "longitude": 0, "name": "合成市"}, location))
    requests = []
    def forecast(url, params, timeout):
        requests.append(params)
        return SimpleNamespace(ok=True, json=lambda: {
            "current": {"weather_code": 0, "temperature_2m": 99},
            "daily": {
                "time": [(TODAY + timedelta(days=i)).isoformat() for i in range(7)],
                "weather_code": [0] * 7,
                "temperature_2m_max": list(range(20, 27)),
                "temperature_2m_min": list(range(10, 17)),
            },
        })
    monkeypatch.setattr(get_weather.requests, "get", forecast)
    result = get_weather.get_weather(FakeConn(), location="合成市", start_day=start, days=count)
    assert requests[0]["forecast_days"] == start + count
    for index in range(7):
        assert ((TODAY + timedelta(days=index)).isoformat() in result.response) == (start <= index < start + count)
    assert ("99度" in result.response) == (start == 0 and count == 1)


@pytest.mark.parametrize("start,count", [(-1, 1), (7, 1), (1, 7), (0, 0), (0, 8), (True, 1), (0, "7"), (None, 1)])
def test_invalid_range_does_not_fetch(primary, start, count):
    result = get_weather.get_weather(FakeConn(), location="合成市", start_day=start, days=count)
    assert "请指定" in result.response
    assert not primary


def test_missing_tomorrow_never_substitutes_today():
    report = scoped_weather_report("合成市", [("今天", "小雨", 20, 10)], TODAY, 1, 1, current="暴雨")
    assert "2026-10-13：暂未取得预报" in report
    assert "小雨" not in report and "暴雨" not in report


def test_missing_days_are_disclosed_and_forecasts_sorted_by_date():
    report = scoped_weather_report("合成市", [("10/14", "小雨", 20, 10), ("10/12", "晴", 21, 11)], TODAY, 0, 7)
    assert "2026-10-13：暂未取得预报" in report
    assert "2026-10-18：暂未取得预报" in report
    assert report.index("2026-10-12：晴") < report.index("2026-10-14：小雨")


@pytest.mark.parametrize("label,expected", [("01/01", date(2027, 1, 1)), ("12月31日", date(2026, 12, 31)),
                                            ("明天", date(2027, 1, 1)), ("未知日期", None)])
def test_source_date_parsing_across_year_boundary(label, expected):
    assert forecast_date(label, date(2026, 12, 31)) == expected


def test_backend_description_override_keeps_date_parameter_in_intent_prompt():
    conn = SimpleNamespace(config={
        "selected_module": {"Intent": "Intent_intent_llm"},
        "Intent": {"Intent_intent_llm": {"functions": ["get_weather"]}},
        "plugins": {"get_weather": {}},
        "tool_prompt_overrides": {"get_weather": "查询天气（目录覆盖）"},
    }, intent_type="intent_llm")
    tools = ServerPluginExecutor(conn).get_tools()
    schema = tools["get_weather"].description
    assert schema["function"]["description"] == "查询天气（目录覆盖）"
    prompt = IntentProvider({}).get_intent_system_prompt([schema])
    assert "北京时间起始偏移：今天0、明天1、后天2；默认0" in prompt
    assert "连续天数默认1" in prompt
    assert "未来一周含今天=7" in prompt
    assert "起始偏移+天数≤7" in prompt
    assert "result_for_context" not in prompt


@pytest.mark.parametrize("start,count", [(0, 1), (1, 1), (0, 7)])
def test_intent_classifier_parameters_reach_real_weather_plugin(primary, start, count):
    from test_intent_weather_routing import classifier
    args = {"location": "合成市", "lang": "zh_CN", "start_day": start, "days": count}
    provider, conn = classifier(lambda **kw: json.dumps({"function_call": {"name": "get_weather", "arguments": args}}))
    conn.config = {
        **FakeConn.config,
        "selected_module": {"Intent": "Intent_intent_llm"},
        "Intent": {"Intent_intent_llm": {"functions": ["get_weather"]}},
    }
    conn.client_ip = None
    routed = json.loads(asyncio.run(provider.detect_intent(conn, [], "合成天气查询")))
    result = asyncio.run(ServerPluginExecutor(conn).execute(conn, "get_weather", routed["function_call"]["arguments"]))
    assert result.action == get_weather.Action.RESPONSE
    assert conn.intent_type == "intent_llm"
    for index in range(7):
        assert ((TODAY + timedelta(days=index)).isoformat() in result.response) == (start <= index < start + count)


def test_old_absolute_date_is_not_reinterpreted_as_current_year():
    report = scoped_weather_report("合成市", [("2025-10-12 周日", "过期天气", 20, 10)], TODAY, 0, 1)
    assert "暂未取得预报" in report and "过期天气" not in report
