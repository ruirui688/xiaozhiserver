import sys
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings

settings.config_file_valid = True

from core.connection import _format_headers_for_log, _sanitize_headers_for_log


def test_sanitize_headers_for_log_redacts_credentials_by_header_name():
    headers = {
        "Authorization": "Bearer production-token",
        "X-Api-Key": "api-key-value",
        "X_AUTH_TOKEN": "auth-token-value",
        "Access-Token": "access-token-value",
        "X-Client-Secret": "client-secret-value",
        "Cookie": "session=private",
        "Set-Cookie": "session=private; HttpOnly",
        "Device-Id": "3c:dc:75:fe:26:c0",
        "User-Agent": "xiaozhi-device",
    }

    sanitized = _sanitize_headers_for_log(headers)

    assert sanitized == {
        "Authorization": "***",
        "X-Api-Key": "***",
        "X_AUTH_TOKEN": "***",
        "Access-Token": "***",
        "X-Client-Secret": "***",
        "Cookie": "***",
        "Set-Cookie": "***",
        "Device-Id": "3c:dc:75:fe:26:c0",
        "User-Agent": "xiaozhi-device",
    }
    assert headers["Authorization"] == "Bearer production-token"


def test_format_headers_for_log_never_includes_sensitive_values():
    formatted = _format_headers_for_log(
        {
            "authorization": "Bearer leaked-value",
            "x-api-key": "leaked-api-key",
            "device-id": "device-1",
        }
    )

    assert "leaked-value" not in formatted
    assert "leaked-api-key" not in formatted
    assert "device-1" in formatted
