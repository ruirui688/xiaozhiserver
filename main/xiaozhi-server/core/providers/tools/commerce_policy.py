"""Reserve commerce device operations for the future order-authorized dispatcher.

Discovery is allowed for diagnostics; generic model/HTTP calls are not. A tool
description, MCP annotation, or model-supplied ``paid`` flag is not authorization.
"""

import re


COMMERCE_TOOL_PREFIX = "self_commerce_"
COMMERCE_CALL_DENIED = "售货设备工具需要订单服务授权，当前通用工具入口不允许执行"


def is_commerce_device_tool(name: str) -> bool:
    """Match raw device names, sanitized names, and server-MCP aliases."""
    if not isinstance(name, str):
        return False
    normalized = re.sub(r"[^a-zA-Z0-9_\-一-鿿]", "_", name)
    while normalized.startswith("mcp_"):
        normalized = normalized[4:]
    return normalized.startswith(COMMERCE_TOOL_PREFIX)


def require_generic_tool_access(name: str) -> None:
    if is_commerce_device_tool(name):
        raise PermissionError(COMMERCE_CALL_DENIED)
