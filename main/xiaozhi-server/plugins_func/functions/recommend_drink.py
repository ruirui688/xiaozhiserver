"""酒类推荐与图片展示插件"""

import asyncio
import base64
import json
import os
import re
from pathlib import Path
from typing import TYPE_CHECKING, Any, Dict, List, Optional
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

recommend_drink_function_desc = {
    "type": "function",
    "function": {
        "name": "recommend_drink",
        "description": (
            "酒吧机器人推荐酒类时调用。只能基于本店已配置酒单或本地知识库酒单推荐，"
            "禁止编造酒名、酒款、价格或外观。查不到本店酒单时必须明确告知无法确认。"
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


def _normalize_list(value: Any) -> List[Any]:
    if isinstance(value, list):
        return value
    if isinstance(value, str) and value.strip():
        try:
            parsed = json.loads(value)
            return parsed if isinstance(parsed, list) else []
        except json.JSONDecodeError:
            return []
    return []


def _normalize_drink_item(item: Any) -> Optional[Dict[str, Any]]:
    if isinstance(item, str):
        name = item.strip()
        if not name:
            return None
        return {
            "key": _safe_filename(name),
            "name": name,
            "category": "",
            "strength": "",
            "tags": (),
            "reply": f"我推荐本店有的{name}。",
            "visual": "",
            "prompt": "",
            "source": "configured_menu",
        }

    if not isinstance(item, dict):
        return None

    name = str(item.get("name") or item.get("title") or "").strip()
    if not name:
        return None

    tags = item.get("tags") or item.get("flavors") or item.get("keywords") or []
    if isinstance(tags, str):
        tags = [part.strip() for part in re.split(r"[,，;；、\s]+", tags) if part.strip()]
    if not isinstance(tags, list):
        tags = []

    visual = str(item.get("visual") or item.get("appearance") or item.get("description") or "").strip()
    reply = str(item.get("reply") or item.get("recommendation") or "").strip()
    if not reply:
        reason = str(item.get("reason") or item.get("description") or "").strip()
        reply = f"我推荐本店有的{name}。" + (f"{reason}" if reason else "")

    return {
        "key": str(item.get("key") or _safe_filename(name)),
        "name": name,
        "category": str(item.get("category") or "").strip(),
        "strength": str(item.get("strength") or "").strip().lower(),
        "tags": tuple(str(tag).strip() for tag in tags if str(tag).strip()),
        "reply": reply,
        "visual": visual,
        "prompt": str(item.get("prompt") or "").strip(),
        "image_url": str(item.get("image_url") or item.get("imageUrl") or "").strip(),
        "source": "configured_menu",
    }


def _load_configured_menu(plugin_config: Dict[str, Any]) -> List[Dict[str, Any]]:
    menu = plugin_config.get("drink_menu") or plugin_config.get("menu") or []
    drinks = []
    for item in _normalize_list(menu):
        drink = _normalize_drink_item(item)
        if drink:
            drinks.append(drink)
    return drinks


def _query_local_knowledge_menu(
    conn: "ConnectionHandler", plugin_config: Dict[str, Any], preference: str, scene: str, strength: str
) -> List[Dict[str, Any]]:
    dataset_ids = plugin_config.get("dataset_ids")
    if not dataset_ids:
        dataset_ids = conn.config.get("plugins", {}).get("search_from_local_knowledge", {}).get("dataset_ids", [])
    if isinstance(dataset_ids, str):
        dataset_ids = _normalize_list(dataset_ids)
    if not dataset_ids:
        return []

    api_config = conn.config.get("manager-api", {})
    api_url = api_config.get("url")
    api_secret = api_config.get("secret")
    if not api_url or not api_secret:
        return []

    question_parts = ["请只从本店酒单中查找可推荐的酒款"]
    if preference:
        question_parts.append(f"口味偏好：{preference}")
    if scene:
        question_parts.append(f"场景：{scene}")
    if strength and strength != "any":
        question_parts.append(f"酒精强度：{strength}")
    question = "；".join(question_parts)

    try:
        import requests

        response = requests.post(
            f"{api_url}/config/knowledge/retrieval",
            json={"question": question, "dataset_ids": dataset_ids},
            headers={"Authorization": f"Bearer {api_secret}", "Content-Type": "application/json"},
            timeout=_config_int(plugin_config.get("knowledge_timeout"), 8),
        )
        response.encoding = "utf-8"
        response.raise_for_status()
        result = response.json()
    except Exception as e:
        logger.bind(tag=TAG).warning(f"查询本店酒单知识库失败: {e}")
        return []

    if result.get("code") != 0:
        return []

    chunks = result.get("data", {}).get("chunks", [])
    drinks = []
    for index, chunk in enumerate(chunks[:5]):
        content = str(chunk.get("content") or "").strip()
        if not content:
            continue
        names = _extract_candidate_drink_names(content)
        if not names:
            logger.bind(tag=TAG).warning(f"忽略未包含明确酒名的酒单知识库片段: index={index}")
            continue
        for name in names:
            drinks.append(
                {
                    "key": _safe_filename(name),
                    "name": name,
                    "category": "",
                    "strength": _extract_strength(content),
                    "tags": tuple(_extract_terms(content)),
                    "reply": _build_reply_from_knowledge(name, content),
                    "visual": _extract_visual(content),
                    "prompt": "",
                    "source": "local_knowledge",
                    "content": content,
                }
            )
    return drinks


def _extract_candidate_drink_names(content: str) -> List[str]:
    explicit_name = _extract_drink_name(content)
    if explicit_name:
        return [explicit_name]

    lines = [line.strip() for line in content.splitlines() if line.strip()]
    candidate_texts = []
    consume_next_line = False
    label_pattern = re.compile(r"(?:推荐候选方向|候选酒款|推荐候选|可推荐酒款|推荐酒款|候选)[:：]?\s*(.*)")
    for line in lines:
        if consume_next_line:
            candidate_texts.append(line)
            consume_next_line = False
            continue
        match = label_pattern.search(line)
        if not match:
            continue
        text = match.group(1).strip()
        if text:
            candidate_texts.append(text)
        else:
            consume_next_line = True

    names: List[str] = []
    seen = set()
    for text in candidate_texts:
        for name in _split_candidate_names(text):
            if name and name not in seen:
                seen.add(name)
                names.append(name)
    return names


def _split_candidate_names(text: str) -> List[str]:
    normalized = re.sub(r"[。.!?？].*$", "", text.strip())
    parts = re.split(r"[,，、;/；|]+", normalized)
    names = []
    blocked_markers = ("用户问题", "检索字段", "适配场景", "价格", "定位", "字段", "方向")
    for part in parts:
        name = part.strip(" \t\r\n'\"“”《》()（）[]【】")
        name = re.sub(r"^(?:推荐|酒款|酒名|名称|品名)[:：]\s*", "", name).strip()
        if not name or any(marker in name for marker in blocked_markers):
            continue
        if "=" in name or "：" in name or ":" in name:
            continue
        if 2 <= len(name) <= 60:
            names.append(name)
    return names


def _extract_drink_name(content: str) -> str:
    for pattern in (
        r"(?:酒名|名称|品名|酒款|推荐酒名|推荐酒款)[:：]\s*([^\n，,。；;]{2,30})",
        r"《([^》]{2,30})》",
        r"“([^”]{2,30})”",
    ):
        match = re.search(pattern, content)
        if match:
            return match.group(1).strip()
    return ""


def _extract_terms(content: str) -> List[str]:
    candidates = []
    for term in (
        "清爽", "酸甜", "微甜", "甜", "不甜", "果香", "花香", "威士忌", "金酒", "伏特加",
        "低度", "高度", "拍照", "好看", "颜值", "商务", "宴请", "体面", "不浮夸", "高端", "中高端",
    ):
        if term in content:
            candidates.append(term)
    return candidates


def _extract_strength(content: str) -> str:
    if "低度" in content or "低酒精" in content:
        return "low"
    if "高度" in content or "烈" in content or "高酒精" in content:
        return "high"
    return ""


def _extract_visual(content: str) -> str:
    match = re.search(r"(?:外观|颜值|颜色|拍照|杯型)[:：]?\s*([^\n]{6,120})", content)
    if match:
        return match.group(1).strip()
    return ""


def _build_reply_from_knowledge(name: str, content: str) -> str:
    reason_parts = []
    for term in _extract_terms(content):
        if term not in {"低度", "高度"}:
            reason_parts.append(term)
    if reason_parts:
        reason = "、".join(reason_parts[:4])
        return f"我推荐本店有的{name}，它更适合{reason}的需求。"
    return f"我推荐本店有的{name}。"


def _resolve_drink_candidates(
    conn: "ConnectionHandler", plugin_config: Dict[str, Any], preference: str, scene: str, strength: str
) -> List[Dict[str, Any]]:
    configured_menu = _load_configured_menu(plugin_config)
    if configured_menu:
        return configured_menu
    return _query_local_knowledge_menu(conn, plugin_config, preference, scene, strength)


def _pick_drink_from_candidates(candidates: List[Dict[str, Any]], preference: str, scene: str, strength: str) -> Dict[str, Any]:
    query = f"{preference} {scene} {strength}".lower()
    requested_strength = strength if strength in {"low", "medium", "high"} else ""
    best_drink = candidates[0]
    best_score = -1

    for drink in candidates:
        score = 0
        if requested_strength and drink["strength"] == requested_strength:
            score += 3
        for tag in drink["tags"]:
            if tag.lower() in query:
                score += 2
        if drink["name"].lower() in query or drink["key"].replace("_", " ").lower() in query:
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
            best_drink = drink
            best_score = score

    return dict(best_drink)


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
    drink_image_url = drink.get("image_url")
    if isinstance(drink_image_url, str) and drink_image_url.strip():
        return drink_image_url.strip()

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
            prompt=drink.get("prompt") or _build_image_prompt(drink),
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
        "description": drink.get("visual") or drink.get("reply") or drink["name"],
        "alt": f"{drink['name']}外观图",
        "image_prompt": drink.get("prompt") or _build_image_prompt(drink),
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
            args[name] = drink.get("visual") or drink.get("reply") or drink["name"]
        elif lower_name in {"prompt", "image_prompt", "imageprompt"}:
            args[name] = drink.get("prompt") or _build_image_prompt(drink)

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
    plugin_config = _get_plugin_config(conn)
    candidates = _resolve_drink_candidates(conn, plugin_config, preference, scene, strength)
    if not candidates:
        return ActionResponse(
            action=Action.RESPONSE,
            response="我现在查不到本店酒单，不能乱推荐。请先把酒单接入知识库，或在酒水推荐插件里配置 drink_menu。",
            result="no_bar_menu",
        )

    drink = _pick_drink_from_candidates(candidates, preference, scene, strength)

    if getattr(conn, "loop", None) and conn.loop.is_running():
        conn.loop.create_task(_send_drink_image_to_device(conn, plugin_config, drink))

    response = drink.get("reply") or f"我推荐本店有的{drink['name']}。"
    visual = drink.get("visual")
    if visual and visual not in response:
        response = f"{response} 外观上，{visual}"
    return ActionResponse(
        action=Action.RESPONSE, response=response, result=drink["name"]
    )


def _build_image_prompt(drink: Dict[str, Any]) -> str:
    visual = drink.get("visual") or drink.get("reply") or drink.get("name", "cocktail")
    return (
        "Photorealistic bar menu product shot, no logo, no text. "
        f"Drink name: {drink.get('name', '')}. Appearance: {visual}"
    )


def _pick_drink(preference: str, scene: str, strength: str) -> Dict[str, Any]:
    """Compatibility helper for older tests; runtime uses configured bar menu only."""
    demo_menu = [
        {
            "key": "gin_tonic",
            "name": "金汤力",
            "strength": "medium",
            "tags": ("清爽", "柑橘", "微苦", "气泡", "低糖"),
            "reply": "我推荐本店有的金汤力。",
            "visual": "高球杯，透明酒液，青柠装饰。",
        },
        {
            "key": "mojito",
            "name": "莫吉托",
            "strength": "low",
            "tags": ("清爽", "薄荷", "青柠", "气泡", "微甜"),
            "reply": "我推荐本店有的莫吉托。",
            "visual": "高杯里能看到薄荷叶、青柠角和碎冰。",
        },
        {
            "key": "whiskey_sour",
            "name": "威士忌酸",
            "strength": "medium",
            "tags": ("酸甜", "威士忌", "顺口", "柠檬"),
            "reply": "我推荐本店有的威士忌酸。",
            "visual": "酒体偏金黄，表面可能有细腻泡沫。",
        },
    ]
    return _pick_drink_from_candidates(demo_menu, preference, scene, strength)
