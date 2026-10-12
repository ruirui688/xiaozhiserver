"""Weather date contracts survive the same bounded catalog used by live requests."""

import copy
from types import SimpleNamespace

import pytest

# Keep tool registration independent of private files, credentials and services.
from test_get_weather_warning import get_weather
from core.providers.intent.intent_llm.intent_llm import IntentProvider
from core.providers.realtime.base import realtime_tools
from core.providers.tools.base import ToolDefinition, ToolType
from core.providers.tools.server_plugins.plugin_executor import ServerPluginExecutor
from core.providers.tools.unified_tool_manager import ToolManager
from core.utils.prompt_budget import (
    TOOL_DESCRIPTIONS_AGGREGATE_MAX_CHARS,
    TOOL_DESCRIPTION_TOTAL_MAX_CHARS,
    compact_tool_schemas,
)


def _descriptive_chars(value):
    if isinstance(value, dict):
        return sum(len(item) if key == "description" and isinstance(item, str)
                   else _descriptive_chars(item) for key, item in value.items())
    if isinstance(value, list):
        return sum(_descriptive_chars(item) for item in value)
    return 0


def _without_descriptions(value):
    if isinstance(value, dict):
        return {key: _without_descriptions(item) for key, item in value.items()
                if key != "description" or not isinstance(item, str)}
    if isinstance(value, list):
        return [_without_descriptions(item) for item in value]
    return value


def _tool_manager(intent_type, tool_count, enabled=True):
    conn = SimpleNamespace(config={
        "selected_module": {"Intent": intent_type},
        "Intent": {intent_type: {"functions": ["get_weather"] if enabled else []}},
        "plugins": {"get_weather": {}},
        "tool_prompt_overrides": {"get_weather": "查询实时天气，尊重用户指定的地点和日期。" * 100},
    }, intent_type=intent_type)
    manager = ToolManager(conn)
    manager.register_executor(ToolType.SERVER_PLUGIN, ServerPluginExecutor(conn))
    base_count = len(manager.get_all_tools())
    assert base_count <= tool_count
    additional = {}
    for index in range(tool_count - base_count):
        name = f"device_status_{index}"
        additional[name] = ToolDefinition(name=name, tool_type=ToolType.DEVICE_MCP,
            description={"type": "function", "function": {
                "name": name, "description": "读取设备状态。" * 200,
                "parameters": {"type": "object", "properties": {
                    "scope": {"type": "string", "enum": ["current"],
                              "description": "仅当前设备。"},
                }, "required": ["scope"]},
            }})
    manager.register_executor(ToolType.DEVICE_MCP, SimpleNamespace(get_tools=lambda: additional))
    return manager


@pytest.mark.parametrize("tool_count", [15, 20, 30])
@pytest.mark.parametrize("intent_type", ["intent_llm", "function_call"])
def test_weather_date_meaning_survives_unified_catalog_budget_and_long_override(tool_count, intent_type):
    original = copy.deepcopy(get_weather.GET_WEATHER_FUNCTION_DESC)
    manager = _tool_manager(intent_type, tool_count)
    schemas = manager.get_function_descriptions()
    assert len(schemas) == tool_count
    weather = next(tool for tool in schemas if tool["function"]["name"] == "get_weather")
    props = weather["function"]["parameters"]["properties"]
    assert weather["function"]["description"].startswith("查询实时天气")
    for name in ("start_day", "days"):
        # Essential prose stays complete, not just keys/types that Intent cannot interpret.
        assert props[name]["description"] == original["function"]["parameters"]["properties"][name]["description"]
    prompt = IntentProvider({}).get_intent_system_prompt(schemas)
    assert "北京时间起始偏移：今天0、明天1、后天2；默认0。" in prompt
    assert "连续天数默认1；未来一周含今天=7；起始偏移+天数≤7。" in prompt
    assert _without_descriptions(weather) == _without_descriptions(original)
    assert _descriptive_chars(schemas) <= TOOL_DESCRIPTIONS_AGGREGATE_MAX_CHARS
    assert all(_descriptive_chars(tool) <= TOOL_DESCRIPTION_TOTAL_MAX_CHARS for tool in schemas)
    assert get_weather.GET_WEATHER_FUNCTION_DESC == original
    assert compact_tool_schemas(schemas) == schemas
    # Native realtime serialization shares this catalog and must preserve JSON constraints.
    native = next(tool for tool in realtime_tools(schemas) if tool["function"]["name"] == "get_weather")
    assert native == weather
    assert native["function"]["parameters"]["required"] == ["lang"]
    assert props["start_day"]["minimum"] == 0 and props["start_day"]["maximum"] == 6
    assert props["days"]["minimum"] == 1 and props["days"]["maximum"] == 7


def test_description_override_cannot_enable_an_unselected_weather_tool():
    schemas = _tool_manager("intent_llm", 30, enabled=False).get_function_descriptions()
    assert len(schemas) == 30
    assert all(tool["function"]["name"] != "get_weather" for tool in schemas)
    assert "北京时间起始偏移" not in IntentProvider({}).get_intent_system_prompt(schemas)
