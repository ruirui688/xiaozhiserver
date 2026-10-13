"""Names reserved or retired across model-facing tool registration paths."""

RETIRED_TOOL_NAMES = frozenset({"manage_memory", "set_session_voice"})

DEVICE_REMINDER_TOOL_NAME = "device_reminder"
RESERVED_SERVER_PLUGIN_NAMES = frozenset({DEVICE_REMINDER_TOOL_NAME})


def is_reserved_server_plugin_name(name) -> bool:
    """Match a reserved plugin name, including nested Server-MCP aliases."""
    if not isinstance(name, str):
        return False
    normalized = name.strip()
    while normalized.startswith("mcp_"):
        normalized = normalized[4:]
    return normalized in RESERVED_SERVER_PLUGIN_NAMES
