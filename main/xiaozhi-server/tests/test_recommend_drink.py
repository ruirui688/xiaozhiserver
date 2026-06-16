from types import SimpleNamespace

from plugins_func.functions.recommend_drink import (
    _build_display_arguments,
    _extract_candidate_drink_names,
    _extract_drink_name,
    _load_configured_menu,
    _pick_drink,
    _pick_drink_from_candidates,
    _query_local_knowledge_menu,
    recommend_drink,
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


def test_recommend_drink_refuses_without_bar_menu():
    conn = SimpleNamespace(config={"plugins": {"recommend_drink": {}}})

    result = recommend_drink(conn, "帮我推荐一杯拍照好看的酒")

    assert result.result == "no_bar_menu"
    assert "不能乱推荐" in result.response


def test_recommend_drink_uses_configured_bar_menu_only():
    conn = SimpleNamespace(
        config={
            "plugins": {
                "recommend_drink": {
                    "drink_menu": [
                        {
                            "key": "rose_spritz",
                            "name": "玫瑰气泡",
                            "tags": ["花香", "拍照", "好看"],
                            "strength": "low",
                            "description": "本店酒单里的玫瑰香气低度气泡酒。",
                            "visual": "粉色酒体，气泡明显。",
                        },
                        {
                            "key": "whiskey_highball",
                            "name": "威士忌嗨棒",
                            "tags": ["威士忌", "清爽"],
                            "strength": "medium",
                        },
                    ]
                }
            }
        }
    )

    result = recommend_drink(conn, "我要拍照好看的", strength="low")

    assert result.result == "玫瑰气泡"
    assert "玫瑰气泡" in result.response


def test_load_configured_menu_accepts_json_string():
    drinks = _load_configured_menu(
        {"drink_menu": '[{"name":"本店酸","tags":"酸甜 威士忌","strength":"medium"}]'}
    )

    assert drinks[0]["name"] == "本店酸"
    assert "酸甜" in drinks[0]["tags"]


def test_pick_drink_from_candidates_never_uses_builtin_catalog():
    drink = _pick_drink_from_candidates(
        [{"key": "bar_only", "name": "本店限定", "strength": "low", "tags": ("清爽",)}],
        "想喝莫吉托",
        "",
        "low",
    )

    assert drink["name"] == "本店限定"


def test_extract_drink_name_ignores_retrieval_instruction_text():
    content = "用户问题：商务场合推荐一款体面但不浮夸的\n优先检索字段：适配场景"

    assert _extract_drink_name(content) == ""


def test_extract_candidate_drink_names_from_knowledge_recommendation_list():
    content = "\n".join(
        [
            "用户问题：商务场合推荐一款体面但不浮夸的",
            "适配场景=商务宴请；",
            "推荐候选方向：Macallan 12, Hennessy VSOP, Veuve Clicquot, Penfolds Bin 389",
        ]
    )

    assert _extract_candidate_drink_names(content) == [
        "Macallan 12",
        "Hennessy VSOP",
        "Veuve Clicquot",
        "Penfolds Bin 389",
    ]


def test_local_knowledge_menu_uses_default_bar_dataset(monkeypatch):
    conn = SimpleNamespace(
        config={
            "manager-api": {"url": "http://manager-api", "secret": "secret"},
            "plugins": {
                "recommend_drink": {},
                "search_from_local_knowledge": {"dataset_ids": ["bar-kb"]},
            },
        }
    )
    captured = {}

    class FakeResponse:
        encoding = "utf-8"

        def raise_for_status(self):
            return None

        def json(self):
            return {
                "code": 0,
                "data": {
                    "chunks": [
                        {
                            "content": "\n".join(
                                [
                                    "用户问题：商务场合推荐一款体面但不浮夸的",
                                    "推荐候选方向：Macallan 12, Hennessy VSOP",
                                ]
                            )
                        }
                    ]
                },
            }

    def fake_post(url, json, headers, timeout):
        captured["dataset_ids"] = json["dataset_ids"]
        return FakeResponse()

    import requests

    monkeypatch.setattr(requests, "post", fake_post)

    drinks = _query_local_knowledge_menu(conn, {}, "帮我推荐一杯酒", "", "any")

    assert captured["dataset_ids"] == ["bar-kb"]
    assert [drink["name"] for drink in drinks] == ["Macallan 12", "Hennessy VSOP"]
