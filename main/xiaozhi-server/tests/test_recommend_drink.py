from types import SimpleNamespace

from plugins_func.functions.recommend_drink import (
    _build_display_arguments,
    _pick_drink,
)


def test_pick_drink_prefers_low_strength_refreshing_entry():
    drink = _pick_drink("想要清爽一点，不要太烈，带薄荷", "第一次喝", "low")

    assert drink["key"] == "mojito"
    assert drink["strength"] == "low"


def test_pick_drink_prefers_whiskey_for_whiskey_request():
    drink = _pick_drink("想喝威士忌，酸甜一点", "聊天", "medium")

    assert drink["key"] == "whiskey_sour"


def test_build_display_arguments_maps_common_mcp_schema_fields():
    drink = {
        "name": "金汤力",
        "visual": "高球杯，透明酒液，青柠装饰。",
        "prompt": "gin tonic prompt",
    }
    conn = SimpleNamespace(
        mcp_client=SimpleNamespace(
            tools={
                "self_screen_display_image": {
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "url": {"type": "string"},
                            "caption": {"type": "string"},
                            "prompt": {"type": "string"},
                        },
                        "required": ["url"],
                    }
                }
            }
        )
    )

    args = _build_display_arguments(
        conn,
        "self_screen_display_image",
        drink,
        "http://example.local/gin-tonic.png",
    )

    assert args["url"] == "http://example.local/gin-tonic.png"
    assert args["caption"] == "高球杯，透明酒液，青柠装饰。"
    assert args["prompt"] == "gin tonic prompt"
