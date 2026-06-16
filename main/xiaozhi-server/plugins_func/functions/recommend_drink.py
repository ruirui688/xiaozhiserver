"""酒类推荐与图片展示插件"""

import asyncio
import base64
import json
import os
import re
from pathlib import Path
from typing import TYPE_CHECKING, Any, Dict, Optional
from urllib.parse import urlsplit, urlunsplit

from config.logger import setup_logging
from core.providers.tools.device_mcp.mcp_handler import call_mcp_tool
from core.utils.util import get_vision_url
from plugins_func.register import Action, ActionResponse, ToolType, register_function

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__
logger = setup_logging()

DISPLAY_TOOL_CANDIDATES = (
    "self_screen_display_image",
    "self_screen_show_image",
    "display_image",
    "show_image",
    "render_image",
)

DRINK_CATALOG = {
    "gin_tonic": {
        "name": "金汤力",
        "category": "鸡尾酒",
        "strength": "medium",
        "tags": ("清爽", "柑橘", "微苦", "气泡", "低糖"),
        "reply": "我推荐金汤力。金酒的草本香气加上汤力水的微苦和气泡，适合想喝清爽、不太甜的人。",
        "visual": "高球杯，透明酒液，冰块很多，杯壁有冷凝水，通常会放一片青柠或柠檬，整体看起来干净清爽。",
        "prompt": "Photorealistic product shot of a gin and tonic in a tall highball glass, clear sparkling liquid, large ice cubes, lime wedge garnish, condensation on the glass, elegant bar counter, no logo, no text.",
    },
    "mojito": {
        "name": "莫吉托",
        "category": "鸡尾酒",
        "strength": "low",
        "tags": ("清爽", "薄荷", "青柠", "气泡", "微甜"),
        "reply": "我推荐莫吉托。它有薄荷、青柠和苏打水的清凉感，酒精压力不高，适合刚开始喝或想要轻松一点的客人。",
        "visual": "高杯里能看到薄荷叶、青柠角、碎冰和透明气泡，颜色偏浅绿，视觉上很有夏天感。",
        "prompt": "Photorealistic cocktail photo of a mojito in a tall glass, mint leaves, lime wedges, crushed ice, sparkling soda bubbles, pale green tones, clean bar lighting, no logo, no text.",
    },
    "old_fashioned": {
        "name": "古典鸡尾酒",
        "category": "鸡尾酒",
        "strength": "high",
        "tags": ("威士忌", "醇厚", "微甜", "苦精", "经典"),
        "reply": "我推荐古典鸡尾酒。它以威士忌为核心，带一点糖和苦精，口感醇厚，适合想喝经典、层次重一点的人。",
        "visual": "矮杯，琥珀色酒液，一大块方冰或圆冰，常见橙皮装饰，整体很沉稳。",
        "prompt": "Photorealistic product shot of an old fashioned cocktail in a rocks glass, amber whiskey color, one large clear ice cube, orange peel garnish, dark refined bar counter, no logo, no text.",
    },
    "aperol_spritz": {
        "name": "阿佩罗橙光",
        "category": "鸡尾酒",
        "strength": "low",
        "tags": ("橙味", "微苦", "气泡", "开胃", "低酒精"),
        "reply": "我推荐阿佩罗橙光。它有明亮的橙香、轻微苦味和气泡感，很适合开胃或者边聊天边喝。",
        "visual": "大酒杯或葡萄酒杯，橙红色酒液，冰块、橙片和气泡很明显，外观非常醒目。",
        "prompt": "Photorealistic cocktail photo of an Aperol spritz in a large wine glass, vivid orange-red drink, ice cubes, orange slice garnish, sparkling bubbles, bright aperitif style, no logo, no text.",
    },
    "whiskey_sour": {
        "name": "威士忌酸",
        "category": "鸡尾酒",
        "strength": "medium",
        "tags": ("酸甜", "威士忌", "顺口", "柠檬", "绵密"),
        "reply": "我推荐威士忌酸。它把威士忌、柠檬和甜感平衡在一起，入口酸甜顺滑，比纯饮威士忌更容易接受。",
        "visual": "矮杯或鸡尾酒杯，酒体偏金黄，表面可能有细腻泡沫，常用樱桃或柠檬皮点缀。",
        "prompt": "Photorealistic cocktail photo of a whiskey sour, golden drink, silky foam top, cherry and lemon peel garnish, elegant coupe or rocks glass, warm bar lighting, no logo, no text.",
    },
    "negroni": {
        "name": "尼格罗尼",
        "category": "鸡尾酒",
        "strength": "high",
        "tags": ("金酒", "苦甜", "草本", "浓郁", "经典"),
        "reply": "我推荐尼格罗尼。它是金酒、金巴利和甜味美思的苦甜组合，风味成熟，适合能接受草本苦味的人。",
        "visual": "矮杯，深红色酒液，一大块冰，橙皮或橙片装饰，颜色浓郁，很有辨识度。",
        "prompt": "Photorealistic product shot of a negroni in a rocks glass, deep ruby red cocktail, large clear ice cube, orange peel garnish, sophisticated bar counter, no logo, no text.",
    },
}


recommend_drink_function_desc = {
    "type": "function",
    "function": {
        "name": "recommend_drink",
        "description": (
            "酒吧机器人推荐酒类时调用。用户询问喝什么、想要某种口味、场景、酒精强度、"
            "外观或鸡尾酒推荐时，使用本工具给出一款具体推荐，并尽量向设备端发送酒水外观图片。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "preference": {
                    "type": "string",
                    "description": "用户描述的口味偏好、基酒、风味或当前需求，例如清爽、酸甜、威士忌、不要太烈。",
                },
                "scene": {
                    "type": "string",
                    "description": "饮用场景，例如开胃、聊天、女士、第一次喝、夜店、餐后。没有就留空。",
                },
                "strength": {
                    "type": "string",
                    "description": "用户期望酒精强度：low、medium、high、any。没有明确说明时填any。",
                },
            },
            "required": ["preference"],
        },
    },
}


def _normalize_text(value: Any) -> str:
    return str(value or "").strip().lower()


def _config_bool(value: Any, default: bool = True) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"true", "1", "yes", "on"}:
            return True
        if normalized in {"false", "0", "no", "off"}:
            return False
    return default


def _config_int(value: Any, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _config_float(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _get_plugin_config(conn: "ConnectionHandler") -> Dict[str, Any]:
    config = conn.config.get("plugins", {}).get("recommend_drink", {})
    return config if isinstance(config, dict) else {}


def _pick_drink(preference: str, scene: str, strength: str) -> Dict[str, Any]:
    query = f"{preference} {scene} {strength}".lower()
    requested_strength = strength if strength in {"low", "medium", "high"} else ""
    best_key = "gin_tonic"
    best_score = -1

    for key, drink in DRINK_CATALOG.items():
        score = 0
        if requested_strength and drink["strength"] == requested_strength:
            score += 3
        for tag in drink["tags"]:
            if tag.lower() in query:
                score += 2
        if drink["name"].lower() in query or key.replace("_", " ") in query:
            score += 4
        if "不太甜" in query or "低糖" in query:
            score += 2 if "低糖" in drink["tags"] or "微苦" in drink["tags"] else -1
        if "第一次" in query or "入门" in query or "女生" in query:
            score += 2 if drink["strength"] == "low" else 0
        if "威士忌" in query:
            score += 3 if "威士忌" in drink["tags"] else 0
        if "苦" in query:
            score += 2 if "微苦" in drink["tags"] or "苦甜" in drink["tags"] else 0

        if score > best_score:
            best_key = key
            best_score = score

    selected = dict(DRINK_CATALOG[best_key])
    selected["key"] = best_key
    return selected


def _build_image_url_from_filename(config: Dict[str, Any], filename: str) -> str:
    vision_url = get_vision_url(config)
    parsed = urlsplit(vision_url)
    marker = "/mcp/vision/explain"
    path = parsed.path or ""
    prefix = path.split(marker)[0] if marker in path else ""
    image_path = f"{prefix}/mcp/vision/images/{filename}"
    return urlunsplit((parsed.scheme, parsed.netloc, image_path, "", ""))


def _safe_filename(value: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9_-]+", "_", value).strip("_")
    return safe or "drink"


def _get_configured_image_url(
    plugin_config: Dict[str, Any], drink: Dict[str, Any]
) -> Optional[str]:
    image_urls = plugin_config.get("image_urls")
    if isinstance(image_urls, str):
        try:
            image_urls = json.loads(image_urls)
        except json.JSONDecodeError:
            image_urls = {}
    if isinstance(image_urls, dict):
        value = image_urls.get(drink["key"]) or image_urls.get(drink["name"])
        if isinstance(value, str) and value.strip():
            return value.strip()

    default_url = plugin_config.get("default_image_url")
    if isinstance(default_url, str) and default_url.strip():
        return default_url.strip()
    return None


def _generate_openai_image_if_configured(
    conn: "ConnectionHandler", plugin_config: Dict[str, Any], drink: Dict[str, Any]
) -> Optional[str]:
    provider = _normalize_text(plugin_config.get("image_provider"))
    if provider != "openai":
        return None

    api_key = plugin_config.get("openai_api_key") or os.environ.get("OPENAI_API_KEY")
    if not api_key or "你的" in str(api_key):
        return None

    cache_dir = Path(plugin_config.get("image_cache_dir") or "./data/vision")
    cache_dir.mkdir(parents=True, exist_ok=True)
    filename = f"recommend_drink_{_safe_filename(drink['key'])}.png"
    image_path = cache_dir / filename
    if image_path.is_file():
        return _build_image_url_from_filename(conn.config, filename)

    try:
        from openai import OpenAI

        client = OpenAI(
            api_key=api_key,
            base_url=plugin_config.get("openai_base_url") or None,
            timeout=_config_float(plugin_config.get("image_timeout"), 45),
        )
        result = client.images.generate(
            model=plugin_config.get("image_model", "gpt-image-1"),
            prompt=drink["prompt"],
            size=plugin_config.get("image_size", "1024x1024"),
        )
        image_data = result.data[0]
        image_b64 = getattr(image_data, "b64_json", None)
        image_url = getattr(image_data, "url", None)
        if image_url:
            return image_url
        if not image_b64:
            return None
        image_path.write_bytes(base64.b64decode(image_b64))
        return _build_image_url_from_filename(conn.config, filename)
    except Exception as e:
        logger.bind(tag=TAG).warning(f"酒水图片生成失败: {e}")
        return None


def _resolve_image_url(
    conn: "ConnectionHandler", plugin_config: Dict[str, Any], drink: Dict[str, Any]
) -> Optional[str]:
    configured_url = _get_configured_image_url(plugin_config, drink)
    if configured_url:
        return configured_url
    return _generate_openai_image_if_configured(conn, plugin_config, drink)


def _find_display_tool(
    conn: "ConnectionHandler", plugin_config: Dict[str, Any]
) -> Optional[str]:
    if not hasattr(conn, "mcp_client") or not conn.mcp_client:
        return None
    configured = plugin_config.get("display_tool_name")
    candidates = [configured] if configured else []
    candidates.extend(DISPLAY_TOOL_CANDIDATES)
    for candidate in candidates:
        if candidate and conn.mcp_client.has_tool(candidate):
            return candidate
    return None


def _build_display_arguments(
    conn: "ConnectionHandler",
    tool_name: str,
    drink: Dict[str, Any],
    image_url: str,
) -> Dict[str, Any]:
    default_args = {
        "image_url": image_url,
        "title": drink["name"],
        "description": drink["visual"],
        "alt": f"{drink['name']}外观图",
        "image_prompt": drink["prompt"],
    }
    tool_data = getattr(conn.mcp_client, "tools", {}).get(tool_name, {})
    schema = tool_data.get("inputSchema") if isinstance(tool_data, dict) else {}
    properties = schema.get("properties", {}) if isinstance(schema, dict) else {}
    required = schema.get("required", []) if isinstance(schema, dict) else []
    if not isinstance(properties, dict) or not properties:
        return default_args

    args: Dict[str, Any] = {}
    for name in properties.keys():
        lower_name = name.lower()
        if lower_name in {"image_url", "imageurl", "url", "src", "image"}:
            args[name] = image_url
        elif lower_name in {"title", "name"}:
            args[name] = drink["name"]
        elif lower_name in {"description", "caption", "alt", "text"}:
            args[name] = drink["visual"]
        elif lower_name in {"prompt", "image_prompt", "imageprompt"}:
            args[name] = drink["prompt"]

    for name in required:
        if name not in args:
            args[name] = default_args.get(name, "")
    return args or default_args


async def _send_drink_image_to_device(
    conn: "ConnectionHandler", plugin_config: Dict[str, Any], drink: Dict[str, Any]
) -> None:
    if not _config_bool(plugin_config.get("enable_image_display"), True):
        return
    if not hasattr(conn, "mcp_client") or not conn.mcp_client:
        return
    if not await conn.mcp_client.is_ready():
        return

    tool_name = _find_display_tool(conn, plugin_config)
    if not tool_name:
        return

    image_url = await asyncio.to_thread(_resolve_image_url, conn, plugin_config, drink)
    if not image_url:
        return

    arguments = _build_display_arguments(conn, tool_name, drink, image_url)
    try:
        await call_mcp_tool(
            conn,
            conn.mcp_client,
            tool_name,
            json.dumps(arguments, ensure_ascii=False),
            timeout=_config_int(plugin_config.get("display_timeout"), 8),
        )
    except Exception as e:
        logger.bind(tag=TAG).warning(f"发送酒水图片到设备失败: {e}")


@register_function("recommend_drink", recommend_drink_function_desc, ToolType.SYSTEM_CTL)
def recommend_drink(
    conn: "ConnectionHandler",
    preference: str = "",
    scene: str = "",
    strength: str = "any",
):
    drink = _pick_drink(preference, scene, strength)
    plugin_config = _get_plugin_config(conn)

    if getattr(conn, "loop", None) and conn.loop.is_running():
        conn.loop.create_task(_send_drink_image_to_device(conn, plugin_config, drink))

    response = f"{drink['reply']} 外观上，{drink['visual']}"
    return ActionResponse(
        action=Action.RESPONSE, response=response, result=drink["name"]
    )
