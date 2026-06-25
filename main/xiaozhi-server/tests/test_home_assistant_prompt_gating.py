from types import SimpleNamespace

from core.providers.tools.home_assistant_config import (
    build_home_assistant_devices_prompt,
    is_home_assistant_runtime_enabled,
)
from core.providers.tools.tool_protocol_guard import looks_like_unhandled_tool_protocol


def _conn(functions=None, ha_config=None):
    return SimpleNamespace(
        config={
            "selected_module": {"Intent": "function_call"},
            "Intent": {
                "function_call": {
                    "type": "function_call",
                    "functions": functions or [],
                }
            },
            "plugins": {
                "home_assistant": ha_config
                or {
                    "devices": ["客厅,灯,light.living_room"],
                    "base_url": "http://homeassistant.local:8123",
                    "api_key": "ha-token",
                }
            },
        }
    )


def test_home_assistant_config_without_enabled_tools_does_not_inject_prompt():
    conn = _conn(functions=["get_weather"])

    assert not is_home_assistant_runtime_enabled(conn)
    assert build_home_assistant_devices_prompt(conn) == ""


def test_home_assistant_placeholder_config_is_not_runtime_enabled():
    conn = _conn(
        functions=["hass_set_state"],
        ha_config={
            "devices": ["客厅,灯,light.living_room"],
            "base_url": "http://homeassistant.local:8123",
            "api_key": "你的home assistant api访问令牌",
        },
    )

    assert not is_home_assistant_runtime_enabled(conn)
    assert build_home_assistant_devices_prompt(conn) == ""


def test_home_assistant_prompt_injects_only_when_tool_and_config_are_valid():
    conn = _conn(functions=["hass_set_state"])

    prompt = build_home_assistant_devices_prompt(conn)

    assert is_home_assistant_runtime_enabled(conn)
    assert "可以通过homeassistant控制" in prompt
    assert "light.living_room" in prompt


def test_dsml_tool_protocol_is_detected_as_unhandled_model_protocol():
    text = '< | | DSML | |tool_calls>< | | DSML | | invoke name="set_device_volume">'

    assert looks_like_unhandled_tool_protocol(text)
