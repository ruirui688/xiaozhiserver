import hmac

from aiohttp import web

from core.benchmark.security import sanitize_benchmark_error


class BenchmarkHandler:
    def __init__(self, config):
        self.config = config

    async def handle_health(self, request):
        return web.json_response({
            "ok": True,
            "service": "benchmark",
            "endpoint": "/benchmark/models",
            "health": "/benchmark/health",
        })

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

    @staticmethod
    def _safe_error_message(error):
        return sanitize_benchmark_error(error)

    async def handle_models(self, request):
        if not self._is_authorized(request):
            return web.json_response({"error": "未授权"}, status=401)
        try:
            from core.benchmark.runner import run_benchmark

            payload = await request.json()
            result = await run_benchmark(payload, self.config)
            return web.json_response(result)
        except Exception as exc:
            return web.json_response(
                {"error": self._safe_error_message(exc)}, status=500
            )

    async def handle_options(self, request):
        return web.Response(headers={
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers": "Content-Type, Authorization",
        })
