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

from plugins_func.functions import get_weather


class FakeConn:
    client_ip = None
    config = {
        "plugins": {
            "get_weather": {
                "api_key": "test-key",
                "api_host": "test.qweather.example",
                "default_location": "广州",
            }
        }
    }


def test_get_weather_schema_exposes_warning_query_type():
    properties = get_weather.GET_WEATHER_FUNCTION_DESC["function"]["parameters"][
        "properties"
    ]

    assert properties["query_type"]["enum"] == ["weather", "warning"]
    assert "天气预警、预警级别或预警状态传warning" in properties["query_type"]["description"]
    assert "query_type必须传warning" in get_weather.GET_WEATHER_FUNCTION_DESC["function"]["description"]


def test_build_weather_warning_report_is_concise():
    report = get_weather.build_weather_warning_report(
        "广州",
        [
            {
                "title": "广州市高温黄色预警",
                "typeName": "高温",
                "severity": "黄色",
                "status": "生效",
            }
        ],
    )

    assert report == "广州当前有天气预警：广州市高温黄色预警，状态生效。"


def test_get_weather_warning_branch_uses_warning_api(monkeypatch):
    captured = {}

    def fake_city_info(location, api_key, api_host):
        captured["city_lookup"] = (location, api_key, api_host)
        return {"id": "101280101", "name": "广州"}

    def fake_warning(location_id, api_key, api_host):
        captured["warning_lookup"] = (location_id, api_key, api_host)
        return []

    monkeypatch.setattr(get_weather, "fetch_city_info", fake_city_info)
    monkeypatch.setattr(get_weather, "fetch_weather_warning", fake_warning)

    result = get_weather.get_weather(
        FakeConn(),
        location="广州",
        lang="zh_CN",
        query_type="warning",
    )

    assert result.action == get_weather.Action.RESPONSE
    assert result.response == "广州当前未查到生效天气预警。"
    assert captured["city_lookup"] == ("广州", "test-key", "test.qweather.example")
    assert captured["warning_lookup"] == ("101280101", "test-key", "test.qweather.example")
