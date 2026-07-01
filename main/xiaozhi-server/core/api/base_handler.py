from aiohttp import web
from config.logger import setup_logging


class BaseHandler:
    def __init__(self, config: dict):
        self.config = config
        self.logger = setup_logging()

    def _add_cors_headers(self, response, request=None):
        """添加CORS头信息，避免通配符与凭据同时生效的安全风险"""
        # 仅当 Origin 明确匹配已知来源时才设置 Allow-Credentials
        # 否则使用通配符 * 且不发送凭据
        if request is not None:
            origin = request.headers.get("Origin", "")
            allowed_origins = self.config.get("server", {}).get(
                "cors_allowed_origins",
                ["http://localhost:8002", "http://127.0.0.1:8002"],
            )
            if origin in allowed_origins:
                response.headers["Access-Control-Allow-Origin"] = origin
                response.headers["Access-Control-Allow-Credentials"] = "true"
            else:
                response.headers["Access-Control-Allow-Origin"] = "*"
        else:
            response.headers["Access-Control-Allow-Origin"] = "*"
        response.headers["Access-Control-Allow-Headers"] = (
            "client-id, content-type, device-id, authorization"
        )

    async def handle_options(self, request):
        """处理OPTIONS请求，添加CORS头信息"""
        response = web.Response(body=b"", content_type="text/plain")
        self._add_cors_headers(response, request)
        # 添加允许的方法
        response.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
        return response
