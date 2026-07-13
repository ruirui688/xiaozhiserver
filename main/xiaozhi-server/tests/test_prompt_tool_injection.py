from core.utils.prompt_manager import PromptManager


def _tool(name):
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": f"{name} description",
            "parameters": {"type": "object", "properties": {}},
        },
    }


def test_available_tools_prompt_uses_only_names_from_real_schema():
    prompt = PromptManager.build_available_tools_prompt(
        [_tool("get_weather"), _tool("handle_exit_intent")]
    )

    assert "get_weather" in prompt
    assert "handle_exit_intent" in prompt
    assert "web_search" not in prompt
    assert "play_music" not in prompt


def test_available_tools_prompt_explicitly_handles_empty_schema():
    assert (
        PromptManager.build_available_tools_prompt([])
        == "当前会话未注册任何可调用工具。"
    )


def test_replace_available_tools_prompt_preserves_other_dynamic_content():
    original = (
        "before\n<available_tools>\n当前会话未注册任何可调用工具。\n"
        "</available_tools>\nHome Assistant devices: light.living_room"
    )

    updated = PromptManager.replace_available_tools_prompt(
        original, [_tool("hass_set_state")]
    )

    assert "hass_set_state" in updated
    assert "web_search" not in updated
    assert "Home Assistant devices: light.living_room" in updated


def test_tool_name_extraction_ignores_invalid_schema_entries():
    tools = [_tool("web_search"), {}, {"function": None}, {"function": {"name": " "}}]

    assert PromptManager.get_available_tool_names(tools) == ["web_search"]
