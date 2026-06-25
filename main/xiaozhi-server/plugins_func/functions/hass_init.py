from config.logger import setup_logging
from core.utils.util import check_model_key
from core.providers.tools.home_assistant_config import (
    build_home_assistant_devices_prompt,
    get_home_assistant_config,
    is_home_assistant_runtime_enabled,
)

TAG = __name__
logger = setup_logging()


def append_devices_to_prompt(conn):
    if conn.intent_type == "function_call":
        prompt = build_home_assistant_devices_prompt(conn)
        if prompt:
            conn.prompt += prompt
            # 更新提示词
            conn.dialogue.update_system_message(conn.prompt)


def initialize_hass_handler(conn):
    ha_config = {}
    if not conn.load_function_plugin:
        return ha_config

    if not is_home_assistant_runtime_enabled(conn):
        return ha_config

    # 统一获取配置
    plugin_config = get_home_assistant_config(conn)
    if not plugin_config:
        return ha_config
    ha_config["base_url"] = plugin_config.get("base_url")
    ha_config["api_key"] = plugin_config.get("api_key")

    # 统一检查API密钥
    model_key_msg = check_model_key("home_assistant", ha_config.get("api_key"))
    if model_key_msg:
        logger.bind(tag=TAG).error(model_key_msg)

    return ha_config
