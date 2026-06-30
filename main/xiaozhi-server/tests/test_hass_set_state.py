import json
import sys
from pathlib import Path
from types import SimpleNamespace


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from plugins_func.functions import hass_set_state as hass_module
from plugins_func.register import Action


class Response:
    status_code = 200


def _conn():
    return SimpleNamespace(
        load_function_plugin=True,
        config={
            "selected_module": {"Intent": "function_call"},
            "Intent": {
                "function_call": {
                    "type": "function_call",
                    "functions": ["hass_set_state"],
                }
            },
            "plugins": {
                "home_assistant": {
                    "devices": ["音箱,播放器,media_player.speaker"],
                    "base_url": "http://homeassistant.local:8123",
                    "api_key": "ha-token",
                }
            },
        },
    )


def test_hass_volume_set_returns_structured_result_for_llm(monkeypatch):
    posted = {}

    def fake_post(url, headers=None, json=None, timeout=None):
        posted.update({"url": url, "headers": headers, "json": json, "timeout": timeout})
        return Response()

    monkeypatch.setattr(hass_module.requests, "post", fake_post)

    result = hass_module.hass_set_state(
        _conn(),
        entity_id="media_player.speaker",
        state={"type": "volume_set", "input": 60},
    )

    assert result.action == Action.REQLLM
    payload = json.loads(result.result)
    assert payload["ok"] is True
    assert payload["transport"] == "home_assistant"
    assert payload["entity_id"] == "media_player.speaker"
    assert payload["domain"] == "media_player"
    assert payload["service"] == "volume_set"
    assert payload["request"] == {
        "entity_id": "media_player.speaker",
        "volume_level": 0.6,
    }
    assert payload["state_verified"] is False
    assert posted["url"].endswith("/api/services/media_player/volume_set")
    assert posted["json"] == {"entity_id": "media_player.speaker", "volume_level": 0.6}


def test_hass_set_state_http_error_returns_error(monkeypatch):
    class ErrorResponse:
        status_code = 500

    monkeypatch.setattr(hass_module.requests, "post", lambda *args, **kwargs: ErrorResponse())

    result = hass_module.hass_set_state(
        _conn(),
        entity_id="media_player.speaker",
        state={"type": "volume_set", "input": 60},
    )

    assert result.action == Action.REQLLM
    payload = json.loads(result.result)
    assert payload["ok"] is False
    assert payload["http_status"] == 500
    assert payload["state_verified"] is False
