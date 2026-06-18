from aiohttp import web


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

    async def handle_models(self, request):
        try:
            from core.benchmark.runner import run_benchmark

            payload = await request.json()
            result = await run_benchmark(payload)
            return web.json_response(result)
        except Exception as exc:
            return web.json_response({"error": str(exc)}, status=500)

    async def handle_options(self, request):
        return web.Response(headers={
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers": "Content-Type, Authorization",
        })
