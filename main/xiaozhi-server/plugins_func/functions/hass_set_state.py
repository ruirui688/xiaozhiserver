from plugins_func.register import register_function, ToolType, ActionResponse, Action
from plugins_func.functions.hass_init import initialize_hass_handler
from config.logger import setup_logging
import asyncio
import json
import requests
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__
logger = setup_logging()

hass_set_state_function_desc = {
    "type": "function",
    "function": {
        "name": "hass_set_state",
        "description": "设置homeassistant里设备的状态,包括开、关,调整灯光亮度、颜色、色温,调整播放器的音量,设备的暂停、继续、静音操作",
        "parameters": {
            "type": "object",
            "properties": {
                "state": {
                    "type": "object",
                    "properties": {
                        "type": {
                            "type": "string",
                            "description": "需要操作的动作,打开设备:turn_on,关闭设备:turn_off,增加亮度:brightness_up,降低亮度:brightness_down,设置亮度:brightness_value,增加音量:volume_up,降低音量:volume_down,设置音量:volume_set,设置色温:set_kelvin,设置颜色:set_color,设备暂停:pause,设备继续:continue,静音/取消静音:volume_mute",
                        },
                        "input": {
                            "type": "integer",
                            "description": "只有在设置音量,设置亮度时候才需要,有效值为1-100,对应音量和亮度的1%-100%",
                        },
                        "is_muted": {
                            "type": "string",
                            "description": "只有在设置静音操作时才需要,设置静音的时候该值为true,取消静音时该值为false",
                        },
                        "rgb_color": {
                            "type": "array",
                            "items": {"type": "integer"},
                            "description": "只有在设置颜色时需要,这里填目标颜色的rgb值",
                        },
                    },
                    "required": ["type"],
                },
                "entity_id": {
                    "type": "string",
                    "description": "需要操作的设备id,homeassistant里的entity_id",
                },
            },
            "required": ["state", "entity_id"],
        },
    },
}


@register_function("hass_set_state", hass_set_state_function_desc, ToolType.SYSTEM_CTL)
def hass_set_state(conn: "ConnectionHandler", entity_id="", state=None):
    if state is None:
        state = {}
    try:
        ha_response = handle_hass_set_state(conn, entity_id, state)
        return ActionResponse(
            Action.REQLLM,
            json.dumps(ha_response, ensure_ascii=False),
            None,
        )
    except asyncio.TimeoutError:
        logger.bind(tag=TAG).error("设置Home Assistant状态超时")
        return ActionResponse(
            Action.REQLLM,
            json.dumps(
                {"ok": False, "transport": "home_assistant", "error": "timeout"},
                ensure_ascii=False,
            ),
            None,
        )
    except Exception as e:
        logger.bind(tag=TAG).error(f"执行Home Assistant操作失败: {e}")
        return ActionResponse(
            Action.REQLLM,
            json.dumps(
                {"ok": False, "transport": "home_assistant", "error": str(e)},
                ensure_ascii=False,
            ),
            None,
        )


def handle_hass_set_state(conn: "ConnectionHandler", entity_id, state):
    ha_config = initialize_hass_handler(conn)
    api_key = ha_config.get("api_key")
    base_url = ha_config.get("base_url")
    """
    state = { "type":"brightness_up","input":"80","is_muted":"true"}
    """
    domains = entity_id.split(".")
    if len(domains) > 1:
        domain = domains[0]
    else:
        return {
            "ok": False,
            "transport": "home_assistant",
            "entity_id": entity_id,
            "error": "invalid_entity_id",
        }
    action = ""
    arg = ""
    value = ""
    if state["type"] == "turn_on":
        if domain == "cover":
            action = "open_cover"
        elif domain == "vacuum":
            action = "start"
        else:
            action = "turn_on"
    elif state["type"] == "turn_off":
        if domain == "cover":
            action = "close_cover"
        elif domain == "vacuum":
            action = "stop"
        else:
            action = "turn_off"
    elif state["type"] == "brightness_up":
        action = "turn_on"
        arg = "brightness_step_pct"
        value = 10
    elif state["type"] == "brightness_down":
        action = "turn_on"
        arg = "brightness_step_pct"
        value = -10
    elif state["type"] == "brightness_value":
        action = "turn_on"
        arg = "brightness_pct"
        input_val = int(state["input"])
        value = input_val
    elif state["type"] == "set_color":
        action = "turn_on"
        arg = "rgb_color"
        value = state["rgb_color"]
    elif state["type"] == "set_kelvin":
        action = "turn_on"
        arg = "kelvin"
        value = state["input"]
    elif state["type"] == "volume_up":
        action = state["type"]
    elif state["type"] == "volume_down":
        action = state["type"]
    elif state["type"] == "volume_set":
        action = state["type"]
        arg = "volume_level"
        input_val = int(state["input"])
        value = input_val
        if input_val >= 1:
            value = input_val / 100
    elif state["type"] == "volume_mute":
        action = state["type"]
        arg = "is_volume_muted"
        value = state["is_muted"]
    elif state["type"] == "pause":
        action = state["type"]
        if domain == "media_player":
            action = "media_pause"
        if domain == "cover":
            action = "stop_cover"
        if domain == "vacuum":
            action = "pause"
    elif state["type"] == "continue":
        if domain == "media_player":
            action = "media_play"
        if domain == "vacuum":
            action = "start"
    else:
        return {
            "ok": False,
            "transport": "home_assistant",
            "entity_id": entity_id,
            "domain": domain,
            "state": state,
            "error": "unsupported_state_type",
        }

    if arg == "":
        data = {
            "entity_id": entity_id,
        }
    else:
        data = {"entity_id": entity_id, arg: value}
    url = f"{base_url}/api/services/{domain}/{action}"
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    response = requests.post(url, headers=headers, json=data, timeout=5)  # 设置5秒超时
    logger.bind(tag=TAG).info(
        f"Home Assistant service call: domain={domain}, action={action}, return_code={response.status_code}"
    )
    return {
        "ok": response.status_code == 200,
        "transport": "home_assistant",
        "entity_id": entity_id,
        "domain": domain,
        "service": action,
        "request": data,
        "http_status": response.status_code,
        "state_verified": False,
        "state": state,
    }
