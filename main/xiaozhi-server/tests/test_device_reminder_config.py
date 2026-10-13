import asyncio
from unittest.mock import AsyncMock

from config.config_loader import get_config_from_api_async


def test_managed_mode_preserves_local_process_notification_limits(monkeypatch):
    monkeypatch.setattr("config.config_loader.init_service", lambda _: None)
    monkeypatch.setattr("config.config_loader.get_server_config", AsyncMock(return_value={
        "server_notification_limits": {"burst": 100},
    }))
    local = {"manager-api": {}, "server_notification_limits": {"burst": 3}}

    result = asyncio.run(get_config_from_api_async(local))

    assert result["server_notification_limits"] == {"burst": 3}
    result["server_notification_limits"]["burst"] = 4
    assert local["server_notification_limits"]["burst"] == 3


def test_managed_mode_without_local_limits_uses_safe_defaults(monkeypatch):
    monkeypatch.setattr("config.config_loader.init_service", lambda _: None)
    monkeypatch.setattr("config.config_loader.get_server_config", AsyncMock(return_value={
        "server_notification_limits": {"burst": 100},
    }))

    result = asyncio.run(get_config_from_api_async({"manager-api": {}}))

    assert result["server_notification_limits"] == {}
