import asyncio
import json

import pytest

from core.api.plugin_test_handler import PluginTestHandler
from plugins_func.functions import get_weather, web_search


class Request:
    def __init__(self, token=None, payload=None):
        self.headers = {}
        if token is not None:
            self.headers["Authorization"] = f"Bearer {token}"
        self.payload = payload or {}

    async def json(self):
        return self.payload


def handler():
    return PluginTestHandler(
        {"server": {"auth_key": "server-key"}, "manager-api": {"secret": "manager-key"}}
    )


def test_plugin_runtime_test_requires_shared_bearer_secret():
    response = asyncio.run(handler().handle_test(Request(payload={"providerCode": "get_weather", "config": {}})))

    assert response.status == 401
    assert json.loads(response.text) == {"success": False, "message": "未授权"}


def test_plugin_runtime_test_returns_success_without_echoing_credentials(monkeypatch):
    current = handler()
    monkeypatch.setattr(
        current,
        "_run_test",
        lambda provider_code, config: "天气接口连接成功",
    )

    response = asyncio.run(current.handle_test(Request(
        "manager-key",
        {"providerCode": "get_weather", "config": {"api_key": "secret-value"}},
    )))
    body = json.loads(response.text)

    assert response.status == 200
    assert body["success"] is True
    assert body["message"] == "天气接口连接成功"
    assert "secret-value" not in response.text


def test_plugin_runtime_test_redacts_failure_credentials(monkeypatch):
    current = handler()

    def fail(provider_code, config):
        raise RuntimeError("api_key='secret-value' Authorization: Bearer bearer-secret")

    monkeypatch.setattr(current, "_run_test", fail)
    response = asyncio.run(current.handle_test(Request(
        "server-key",
        {"providerCode": "web_search", "config": {"api_key": "secret-value"}},
    )))

    assert response.status == 200
    assert json.loads(response.text)["success"] is False
    assert "secret-value" not in response.text
    assert "bearer-secret" not in response.text


def test_weather_runtime_test_rejects_invalid_credentials_without_fallback(monkeypatch):
    monkeypatch.setattr(get_weather, "fetch_city_info", lambda *args: None)

    with pytest.raises(ValueError, match="API 未返回城市信息"):
        PluginTestHandler._test_weather({
            "api_host": "tenant.qweatherapi.com",
            "api_key": "invalid-key",
            "default_location": "广州",
        })


def test_search_runtime_test_executes_selected_provider(monkeypatch):
    captured = {}

    def fake_search(api_key, query, max_results, **options):
        captured.update(
            api_key=api_key,
            query=query,
            max_results=max_results,
            options=options,
        )
        return "有效搜索结果"

    monkeypatch.setattr(web_search, "_search_tavily", fake_search)

    message = PluginTestHandler._test_web_search({
        "provider": "tavily",
        "api_key": "test-key",
    })

    assert message == "Tavily连接成功，已完成一次真实搜索请求"
    assert captured["api_key"] == "test-key"
    assert captured["max_results"] == 1
    assert captured["options"]["include_raw_content"] is False
