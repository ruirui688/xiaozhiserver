import asyncio
import json

from core.api.benchmark_handler import BenchmarkHandler


class Request:
    def __init__(self, token=None, payload=None):
        self.headers = {}
        if token is not None:
            self.headers["Authorization"] = f"Bearer {token}"
        self.payload = payload or {"models": []}

    async def json(self):
        return self.payload


def test_benchmark_models_requires_shared_bearer_secret():
    handler = BenchmarkHandler(
        {"server": {"auth_key": "server-key"}, "manager-api": {"secret": "manager-key"}}
    )
    response = asyncio.run(handler.handle_models(Request()))
    assert response.status == 401
    assert json.loads(response.text) == {"error": "未授权"}


def test_benchmark_models_accepts_manager_secret():
    handler = BenchmarkHandler(
        {"server": {"auth_key": "server-key"}, "manager-api": {"secret": "manager-key"}}
    )
    response = asyncio.run(handler.handle_models(Request("manager-key")))
    assert response.status == 200
    assert json.loads(response.text)["results"] == []


def test_benchmark_error_message_redacts_credentials():
    safe = BenchmarkHandler._safe_error_message(
        RuntimeError("api_key='sk-secret' Authorization: Bearer bearer-secret")
    )
    assert "sk-secret" not in safe
    assert "bearer-secret" not in safe
