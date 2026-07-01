"""IoT设备支持模块，提供IoT设备描述符和状态处理

IoT descriptors sent by the device are gated by server-side configuration.  Only
device types whose corresponding server capability is enabled are registered as
LLM-callable tools.  Add new entries to SERVER_GATED_IOT_DEVICES when introducing
a device type that requires explicit server-side opt-in.
"""

import asyncio
from config.logger import setup_logging
from .iot_descriptor import IotDescriptor
from typing import TYPE_CHECKING, Dict, Any

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__
logger = setup_logging()


# ---------------------------------------------------------------------------
# Server-gated IoT device allowlist
#
# Keys are matched case-insensitively against the descriptor "name" field sent
# by the device.  When a device name is listed here the corresponding
# `config_check(conn)` must return True before the descriptor is registered.
# Descriptors whose name is NOT listed here are allowed by default (backward
# compatibility for un-gated IoT device types).
# ---------------------------------------------------------------------------
def _ha_configured(conn: "ConnectionHandler") -> bool:
    """Return True if Home Assistant is configured on the server."""
    plugins = conn.config.get("plugins", {}) if hasattr(conn, "config") else {}
    return bool(plugins.get("home_assistant") or plugins.get("hass_get_state"))


SERVER_GATED_IOT_DEVICES: Dict[str, Dict[str, Any]] = {
    "hass": {
        "config_check": _ha_configured,
        "label": "Home Assistant",
    },
}


def _is_iot_device_allowed(conn: "ConnectionHandler", device_name: str) -> bool:
    """Check whether an IoT device descriptor should be registered as tools.

    Returns False only when the device name is listed in
    SERVER_GATED_IOT_DEVICES and its config_check fails.
    """
    rule = SERVER_GATED_IOT_DEVICES.get(device_name.lower())
    if rule is None:
        return True  # not gated – allow
    allowed = rule["config_check"](conn)
    if not allowed:
        logger.bind(tag=TAG).info(
            "IoT device %r (%s) ignored – server-side capability not enabled",
            device_name,
            rule["label"],
        )
    return allowed


async def handleIotDescriptors(conn: "ConnectionHandler", descriptors):
    """处理物联网描述"""
    wait_max_time = 5
    while (
        not hasattr(conn, "func_handler")
        or conn.func_handler is None
        or not conn.func_handler.finish_init
    ):
        await asyncio.sleep(1)
        wait_max_time -= 1
        if wait_max_time <= 0:
            logger.bind(tag=TAG).debug("连接对象没有func_handler")
            return

    functions_changed = False
    allowed_descriptors = []

    for descriptor in descriptors:
        # 如果descriptor没有properties和methods，则直接跳过
        if "properties" not in descriptor and "methods" not in descriptor:
            continue

        device_name = descriptor.get("name", "")
        if not _is_iot_device_allowed(conn, device_name):
            continue

        # 处理缺失properties的情况
        if "properties" not in descriptor:
            descriptor["properties"] = {}
            # 从methods中提取所有参数作为properties
            if "methods" in descriptor:
                for method_name, method_info in descriptor["methods"].items():
                    if "parameters" in method_info:
                        for param_name, param_info in method_info["parameters"].items():
                            # 将参数信息转换为属性信息
                            descriptor["properties"][param_name] = {
                                "description": param_info["description"],
                                "type": param_info["type"],
                            }

        # 创建IOT设备描述符
        iot_descriptor = IotDescriptor(
            descriptor["name"],
            descriptor["description"],
            descriptor["properties"],
            descriptor["methods"],
        )
        # 只将允许的描述符存入 conn.iot_descriptors，与注册为工具的集合保持一致
        conn.iot_descriptors[descriptor["name"]] = iot_descriptor
        iot_descriptor._gated = True  # 标记为已通过门控
        allowed_descriptors.append(descriptor)
        functions_changed = True

    # 如果注册了新函数，更新function描述列表
    if functions_changed and hasattr(conn, "func_handler"):
        # 注册IoT工具到统一工具处理器（仅限通过门控的描述符）
        await conn.func_handler.register_iot_tools(allowed_descriptors)

        conn.func_handler.current_support_functions()


async def handleIotStatus(conn: "ConnectionHandler", states):
    """处理物联网状态"""
    for state in states:
        for key, value in conn.iot_descriptors.items():
            if key == state["name"]:
                for property_item in value.properties:
                    for k, v in state["state"].items():
                        if property_item["name"] == k:
                            # 类型转换：存储的值可能为默认初始值（0/False/""），
                            # 所以不能直接比较类型，应让存储值适配收到的实际类型
                            if type(v) != type(property_item["value"]):
                                logger.bind(tag=TAG).debug(
                                    f"属性{property_item['name']}类型不匹配，转换存储值类型: "
                                    f"期望{type(v).__name__}，当前{type(property_item['value']).__name__}"
                                )
                                # 转换存储值为传入值的类型
                                try:
                                    if isinstance(v, bool):
                                        stored = str(property_item["value"]).lower() in ("true", "1", "yes")
                                    elif isinstance(v, (int, float)):
                                        stored = type(v)(property_item["value"])
                                    else:
                                        stored = v  # 用新值覆盖
                                    property_item["value"] = stored
                                except (ValueError, TypeError):
                                    property_item["value"] = v
                            else:
                                property_item["value"] = v
                            logger.bind(tag=TAG).info(
                                f"物联网状态更新: {key} , {property_item['name']} = {v}"
                            )
                            break
                break
