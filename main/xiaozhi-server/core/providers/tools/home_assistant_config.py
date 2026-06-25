"""Home Assistant tool configuration gates."""

from typing import Any

from core.utils.util import is_placeholder_value


HOME_ASSISTANT_TOOLS = {
    "hass_get_state",
    "hass_set_state",
    "hass_play_music",
}


def get_enabled_intent_functions(conn) -> set[str]:
    config = getattr(conn, "config", {}) or {}
    selected_intent = config.get("selected_module", {}).get("Intent")
    intent_config = config.get("Intent", {})
    functions = []
    if selected_intent and selected_intent in intent_config:
        functions = intent_config[selected_intent].get("functions", [])
    if isinstance(functions, str):
        functions = [item.strip() for item in functions.split(";") if item.strip()]
    try:
        return set(functions)
    except TypeError:
        return set()


def get_home_assistant_config(conn) -> dict[str, Any]:
    plugins = (getattr(conn, "config", {}) or {}).get("plugins", {})
    if not isinstance(plugins, dict):
        return {}
    for key in ("home_assistant", "hass_get_state", "hass_set_state", "hass_play_music"):
        value = plugins.get(key)
        if isinstance(value, dict):
            return value
    return {}


def has_home_assistant_tools_enabled(conn) -> bool:
    return bool(get_enabled_intent_functions(conn) & HOME_ASSISTANT_TOOLS)


def is_home_assistant_runtime_enabled(conn) -> bool:
    if not has_home_assistant_tools_enabled(conn):
        return False
    config = get_home_assistant_config(conn)
    if not config:
        return False
    base_url = config.get("base_url")
    api_key = config.get("api_key")
    return not is_placeholder_value(base_url) and not is_placeholder_value(api_key)


def get_home_assistant_devices(conn) -> list[str]:
    if not is_home_assistant_runtime_enabled(conn):
        return []
    devices = get_home_assistant_config(conn).get("devices", [])
    if isinstance(devices, str):
        return [line.strip() for line in devices.splitlines() if line.strip()]
    if isinstance(devices, (list, tuple)):
        return [str(device).strip() for device in devices if str(device).strip()]
    return []


def build_home_assistant_devices_prompt(conn) -> str:
    devices = get_home_assistant_devices(conn)
    if not devices:
        return ""
    header = "\n下面是我家智能设备列表（位置，设备名，entity_id），可以通过homeassistant控制\n"
    return header + "\n".join(devices) + "\n"
