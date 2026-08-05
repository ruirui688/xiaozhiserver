"""Authenticated runtime tests for user-configurable server plugins."""

import asyncio
import hmac
import json

from aiohttp import web

from core.api.base_handler import BaseHandler
from core.benchmark.security import sanitize_benchmark_error


class PluginTestHandler(BaseHandler):
    SUPPORTED_PROVIDERS = {"get_weather", "web_search"}

    def _json_response(self, body, status=200, request=None):
        response = web.Response(
            text=json.dumps(body, ensure_ascii=False),
            content_type="application/json",
            status=status,
        )
        self._add_cors_headers(response, request)
        return response

    def _is_authorized(self, request):
        auth_header = request.headers.get("Authorization", "")
        if not auth_header.startswith("Bearer "):
            return False
        token = auth_header[7:]
        server_secret = self.config.get("server", {}).get("auth_key", "")
        manager_secret = self.config.get("manager-api", {}).get("secret", "")
        return bool(token) and any(
            secret and hmac.compare_digest(token, str(secret))
            for secret in (server_secret, manager_secret)
        )

    async def handle_test(self, request):
        if not self._is_authorized(request):
            return self._json_response(
                {"success": False, "message": "未授权"}, 401, request
            )
        try:
            body = await request.json()
        except Exception:
            return self._json_response(
                {"success": False, "message": "请求体不是有效JSON"}, 400, request
            )

        provider_code = str(body.get("providerCode") or "").strip()
        plugin_config = body.get("config")
        if provider_code not in self.SUPPORTED_PROVIDERS:
            return self._json_response(
                {"success": False, "message": "该插件暂不支持运行时测试"},
                400,
                request,
            )
        if not isinstance(plugin_config, dict) or len(plugin_config) > 20:
            return self._json_response(
                {"success": False, "message": "插件配置无效"}, 400, request
            )

        try:
            message = await asyncio.wait_for(
                asyncio.to_thread(
                    self._run_test, provider_code, dict(plugin_config)
                ),
                timeout=25,
            )
            self.logger.bind(tag=__name__).info(
                "插件运行时测试通过: providerCode={}", provider_code
            )
            return self._json_response(
                {
                    "success": True,
                    "message": message,
                    "data": {"providerCode": provider_code},
                },
                request=request,
            )
        except asyncio.TimeoutError:
            message = "插件运行时测试超时，请检查第三方服务网络后重试"
        except Exception as error:
            message = sanitize_benchmark_error(error)
        self.logger.bind(tag=__name__).warning(
            "插件运行时测试失败: providerCode={}", provider_code
        )
        return self._json_response(
            {"success": False, "message": message}, request=request
        )

    @classmethod
    def _run_test(cls, provider_code, plugin_config):
        if provider_code == "get_weather":
            return cls._test_weather(plugin_config)
        if provider_code == "web_search":
            return cls._test_web_search(plugin_config)
        raise ValueError("该插件暂不支持运行时测试")

    @staticmethod
    def _test_weather(plugin_config):
        from plugins_func.functions.get_weather import (
            _validate_weather_api_host,
            fetch_city_info,
        )

        api_host = str(plugin_config.get("api_host") or "").strip()
        api_key = str(plugin_config.get("api_key") or "").strip()
        location = str(plugin_config.get("default_location") or "").strip()
        if not api_host or not api_key or not location:
            raise ValueError("天气配置不完整，请填写 API Host、API Key 和默认城市")
        if not _validate_weather_api_host(api_host):
            raise ValueError("天气 API Host 不在允许范围内")
        city = fetch_city_info(location, api_key, api_host)
        if not city:
            raise ValueError(
                "天气 API 未返回城市信息，请检查 API Host、API Key 和默认城市"
            )
        city_name = str(city.get("name") or location).strip()
        return f"天气接口连接成功，已识别默认城市：{city_name}"

    @staticmethod
    def _test_web_search(plugin_config):
        from plugins_func.functions.web_search import _search_metaso, _search_tavily

        provider = str(plugin_config.get("provider") or "").strip().lower()
        api_key = str(plugin_config.get("api_key") or "").strip()
        if not api_key:
            raise ValueError("联网搜索配置不完整，请填写 API Key")
        if provider == "metaso":
            result = _search_metaso(
                api_key,
                "小智 ESP32 Server",
                1,
                include_raw_content=False,
                include_time_context=False,
                content_max_chars=500,
            )
            label = "秘塔搜索"
        elif provider == "tavily":
            result = _search_tavily(
                api_key,
                "小智 ESP32 Server",
                1,
                include_raw_content=False,
                include_time_context=False,
                content_max_chars=500,
            )
            label = "Tavily"
        else:
            raise ValueError("联网搜索源仅支持秘塔搜索或 Tavily")
        if not isinstance(result, str) or not result.strip():
            raise ValueError("联网搜索接口没有返回有效响应")
        return f"{label}连接成功，已完成一次真实搜索请求"

    async def handle_options(self, request):
        response = web.Response(status=204)
        self._add_cors_headers(response, request)
        return response
