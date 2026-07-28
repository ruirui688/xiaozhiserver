from types import SimpleNamespace

from core.providers.tools.device_iot.iot_handler import _is_iot_device_allowed


def _conn(config):
    return SimpleNamespace(config=config)


def test_unknown_iot_device_is_denied_by_default():
    assert _is_iot_device_allowed(_conn({}), "light") is False
    assert _is_iot_device_allowed(_conn({}), "") is False


def test_unknown_iot_device_requires_exact_server_allowlist_entry():
    conn = _conn({"device_iot_allowlist": ["Light", "speaker"]})

    assert _is_iot_device_allowed(conn, "light") is True
    assert _is_iot_device_allowed(conn, "SPEAKER") is True
    assert _is_iot_device_allowed(conn, "screen") is False


def test_home_assistant_iot_gate_requires_real_runtime_configuration():
    disabled = _conn(
        {
            "plugins": {
                "home_assistant": {
                    "base_url": "https://ha.example",
                    "api_key": "secret",
                }
            }
        }
    )
    assert _is_iot_device_allowed(disabled, "hass") is False

    enabled = _conn(
        {
            "selected_module": {"Intent": "Intent_function_call"},
            "Intent": {
                "Intent_function_call": {"functions": ["hass_get_state"]}
            },
            "plugins": {
                "home_assistant": {
                    "base_url": "https://ha.example",
                    "api_key": "secret",
                }
            },
        }
    )
    assert _is_iot_device_allowed(enabled, "hass") is True
