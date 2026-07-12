from pathlib import Path

import yaml

from config.config_loader import get_config_from_api_async


def test_default_config_enables_websocket_authentication():
    config_path = Path(__file__).resolve().parents[1] / "config.yaml"

    with config_path.open("r", encoding="utf-8") as config_file:
        config = yaml.safe_load(config_file)

    assert config["server"]["auth"]["enabled"] is True


def test_manager_api_config_defaults_authentication_to_enabled(monkeypatch):
    async def fake_get_server_config():
        return {"server": {}}

    monkeypatch.setattr(
        "config.config_loader.get_server_config",
        fake_get_server_config,
    )
    monkeypatch.setattr("config.config_loader.init_service", lambda _config: None)

    config = {
        "server": {
            "ip": "127.0.0.1",
            "port": 8000,
            "http_port": 8003,
            "auth_key": "test-auth-key",
        },
        "manager-api": {"url": "http://manager-api", "secret": "test-secret"},
    }

    import asyncio

    result = asyncio.run(get_config_from_api_async(config))

    assert result["server"]["auth"]["enabled"] is True


def test_manager_api_config_preserves_explicit_authentication_disable(monkeypatch):
    async def fake_get_server_config():
        return {"server": {"auth": {"enabled": False}}}

    monkeypatch.setattr(
        "config.config_loader.get_server_config",
        fake_get_server_config,
    )
    monkeypatch.setattr("config.config_loader.init_service", lambda _config: None)

    config = {
        "server": {
            "ip": "127.0.0.1",
            "port": 8000,
            "http_port": 8003,
            "auth_key": "test-auth-key",
        },
        "manager-api": {"url": "http://manager-api", "secret": "test-secret"},
    }

    import asyncio

    result = asyncio.run(get_config_from_api_async(config))

    assert result["server"]["auth"]["enabled"] is False
