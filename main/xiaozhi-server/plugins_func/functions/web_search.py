import requests
import time
from config.logger import setup_logging
from core.utils.current_time import get_current_datetime
from plugins_func.register import (
    register_function,
    ToolType,
    ActionResponse,
    Action,
)
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__
logger = setup_logging()

_DEFAULT_DESCRIPTION = (
    "联网搜索工具。当用户明确要求联网查询、搜索最新信息、实时事实、新闻事件详情或当前数据时使用此工具。"
    "不要用于查询本地知识库、播放音乐或控制设备。"
    "用户使用今天、今晚、明天、昨天等相对时间时，必须按系统提示词中的当前北京时间理解。"
    "生成查询词时必须同时保留用户原话里的相对时间短语、绝对日期、对象和问题类型；"
    "如果用户问已经发生的结果、结论、状态、数据或最新进展，查询词也要体现用户要查的事实目标，"
    "避免改写成泛泛的资料或百科查询。"
)

WEB_SEARCH_FUNCTION_DESC = {
    "type": "function",
    "function": {
        "name": "web_search",
        "description": _DEFAULT_DESCRIPTION,
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": (
                        "搜索关键词或完整问题。必须结合用户当前原话和已明确上下文生成，"
                        "例如用户追问“联网搜搜呢”时，要把上一轮关心的对象一并写入查询词。"
                        "必须保留用户原话中的相对时间短语、目标对象和问题类型，例如今天早上、今晚、昨天、结果、状态、数据、进展、新闻详情等。"
                        "涉及相对时间时，查询词要同时包含原始相对时间短语和根据当前北京时间换算出的绝对日期。"
                        "用户问结果、结论、状态、数据或最新进展时，查询词要体现用户要查的事实目标，不要改写成宽泛的“最新资料”。"
                        "不要脱离用户原意自行补充未确认的对象、地点或日期。"
                    ),
                }
            },
            "required": ["query"],
        },
    },
}


def _get_beijing_time_context() -> str:
    return get_current_datetime()


def _bool_config(value, default=False) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return default
    if isinstance(value, str):
        return value.strip().lower() in ("1", "true", "yes", "on")
    return bool(value)


def _int_config(value, default: int, minimum: int = 0) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return max(parsed, minimum)


def _build_search_query(query: str, current_time_context: str, include_time_context: bool = True) -> str:
    query = query.strip()
    if not include_time_context or not current_time_context:
        return query
    if current_time_context in query:
        return query
    return f"{query} 当前北京时间 {current_time_context}"


def _truncate_text(value: str, max_chars: int) -> str:
    if not value or max_chars <= 0:
        return ""
    if len(value) <= max_chars:
        return value
    return value[:max_chars].rstrip() + "..."


def _string_value(value) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, (list, tuple)):
        return " ".join(_string_value(item) for item in value if item is not None).strip()
    if isinstance(value, dict):
        return " ".join(_string_value(item) for item in value.values()).strip()
    return str(value).strip()


def _first_text(item: dict, keys) -> str:
    for key in keys:
        value = _string_value(item.get(key))
        if value:
            return value
    return ""


def _build_search_context(
    query: str,
    provider_label: str,
    items,
    answer: str = "",
    current_time_context: str = "",
    search_query: str = "",
    content_max_chars: int = 3000,
) -> str:
    current_time_context = current_time_context or _get_beijing_time_context()
    lines = [
        "【联网搜索结果】",
        f"用户原始查询：{query}",
        f"实际搜索查询：{search_query or query}",
        f"当前参考时间：{current_time_context}",
        f"搜索源：{provider_label}",
        "回答要求：",
        "1. 只能依据下面列出的搜索结果回答，不要使用旧知识或猜测。",
        "2. 先判断每条结果是否真正回答了用户原始查询，而不是只看是否包含少量关键词。",
        "3. 优先采用日期最新且与问题直接相关的结果。",
        "4. 如果结果大多跑偏，或只给出预告/背景而用户问的是已发生事实，不要立刻说没查到；"
        "应保留用户原始时间范围和目标，重新调用 web_search 生成更精确的查询。",
        "5. 只有在已经用更精确查询复核后仍没有明确依据时，才说明没有查到明确结果。",
        "6. 只陈述搜索结果明确支持的事实；对不明确、冲突或只出现预告/背景的信息，要标为未确认。",
        "7. 回答范围必须贴合用户问法：用户只问一个结果、状态或数据时，只给这个结果、状态或数据；不要主动展开背景、人物、过程、后续安排、分类标签、预测或评价。",
        "8. 涉及时间、数字、结论时，要保留关键依据，不要改写成相反结论。",
        "9. 搜索结果里的日期可能是发布时间，也可能是事件时间；不能把不确定的日期当成确定事实。",
        "10. 最终回复必须是适合语音播报的纯文本，不要使用Markdown加粗、列表符号或分段标题。",
    ]
    if answer:
        lines.append(f"搜索源摘要：{answer}")
    lines.append("搜索结果明细：")

    for i, item in enumerate(items, 1):
        title = _first_text(item, ("title", "name")) or "无标题"
        link = _first_text(item, ("link", "url", "href"))
        date = _first_text(item, ("date", "published_date", "publishedAt", "time"))
        snippet = _first_text(item, ("summary", "snippet", "content", "description"))
        content = _first_text(item, ("content", "raw_content", "rawContent"))
        content = _truncate_text(content, content_max_chars)

        lines.append(f"{i}. 标题：{title}")
        if date:
            lines.append(f"   日期：{date}")
        if link:
            lines.append(f"   链接：{link}")
        if snippet:
            lines.append(f"   摘要：{snippet}")
        if content and content != snippet:
            lines.append(f"   正文：{content}")

    lines.extend(
        [
            "最终回答前自检：",
            "回复是否只覆盖了用户问到的字段？是否删除了未请求的背景、人物、过程、后续安排、分类标签、预测、评价和Markdown？",
            "不能从搜索结果直接定位的内容，说明未查到明确依据，不要补。",
        ]
    )

    return "\n".join(lines)


def _post_metaso(api_key: str, query: str, max_results: int, include_raw_content: bool):
    url = "https://metaso.cn/api/v1/search"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    payload = {
        "q": query,
        "size": max_results,
        "stream": False,
        "scope": "webpage",
        "includeSummary": True,
        "includeRawContent": include_raw_content,
        "conciseSnippet": False,
    }
    logger.bind(tag=TAG).debug(f"秘塔搜索请求 | URL: {url} | payload: {payload}")
    start_time = time.time()
    response = requests.post(url, json=payload, headers=headers, timeout=15)
    response.raise_for_status()
    elapsed = time.time() - start_time
    logger.bind(tag=TAG).info(
        f"秘塔搜索响应 | status={response.status_code} | elapsed={elapsed:.3f}s | query={query}"
    )
    return response.json()


def _search_metaso(
    api_key: str,
    query: str,
    max_results: int,
    current_time_context: str = "",
    include_raw_content: bool = True,
    content_max_chars: int = 3000,
    include_time_context: bool = True,
) -> str:
    """调用秘塔搜索API"""
    current_time_context = current_time_context or _get_beijing_time_context()
    search_query = _build_search_query(
        query,
        current_time_context,
        include_time_context=include_time_context,
    )
    data = _post_metaso(api_key, search_query, max_results, include_raw_content)

    webpages = data.get("webpages", [])
    if not webpages:
        return "未找到相关搜索结果。"

    answer = _first_text(data, ("answer", "summary"))
    return _build_search_context(
        query,
        "秘塔搜索",
        webpages,
        answer=answer,
        current_time_context=current_time_context,
        search_query=search_query,
        content_max_chars=content_max_chars,
    )


def _search_tavily(
    api_key: str,
    query: str,
    max_results: int,
    current_time_context: str = "",
    include_raw_content: bool = True,
    content_max_chars: int = 3000,
    include_time_context: bool = True,
) -> str:
    """调用Tavily搜索API"""
    current_time_context = current_time_context or _get_beijing_time_context()
    url = "https://api.tavily.com/search"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    search_query = _build_search_query(
        query,
        current_time_context,
        include_time_context=include_time_context,
    )
    payload = {
        "query": search_query,
        "max_results": max_results,
        "search_depth": "advanced",
        "include_answer": "advanced",
        "include_raw_content": include_raw_content,
    }
    logger.bind(tag=TAG).debug(f"Tavily搜索请求 | URL: {url} | payload: {payload}")
    response = requests.post(url, json=payload, headers=headers, timeout=15)
    response.raise_for_status()
    data = response.json()
    logger.bind(tag=TAG).debug(f"Tavily搜索响应 | status: {response.status_code} | data: {data}")

    results = data.get("results", [])
    if not results:
        return "未找到相关搜索结果。"

    answer = _first_text(data, ("answer",))
    return _build_search_context(
        query,
        "Tavily",
        results,
        answer=answer,
        current_time_context=current_time_context,
        search_query=search_query,
        content_max_chars=content_max_chars,
    )


@register_function("web_search", WEB_SEARCH_FUNCTION_DESC, ToolType.SYSTEM_CTL)
def web_search(conn: "ConnectionHandler", query: str = None, lang: str = None, **extra_args):
    if lang or extra_args:
        logger.bind(tag=TAG).debug(
            f"web_search 忽略非核心参数 | lang={lang} | extra_args={extra_args}"
        )
    logger.bind(tag=TAG).info(f"web_search 被调用 | query={query}")
    if not query:
        return ActionResponse(Action.REQLLM, "请提供搜索关键词。", None)

    web_search_config = conn.config.get("plugins", {}).get("web_search", {})
    provider = web_search_config.get("provider", "").lower()
    max_results = _int_config(web_search_config.get("max_results"), 3, minimum=1)
    include_raw_content = _bool_config(
        web_search_config.get("include_raw_content"),
        default=True,
    )
    include_time_context = _bool_config(
        web_search_config.get("include_time_context"),
        default=True,
    )
    content_max_chars = _int_config(
        web_search_config.get("content_max_chars"),
        3000,
        minimum=500,
    )
    logger.bind(tag=TAG).info(f"web_search 配置 | provider={provider} | max_results={max_results} | config_keys={list(web_search_config.keys())}")

    api_key = web_search_config.get("api_key", "")
    if not api_key:
        return ActionResponse(
            Action.REQLLM,
            "联网搜索功能未配置API Key，请在配置文件中填写。",
            None,
        )

    current_time_context = _get_beijing_time_context()

    if provider == "metaso":
        search_fn = lambda: _search_metaso(
            api_key,
            query,
            max_results,
            current_time_context=current_time_context,
            include_raw_content=include_raw_content,
            include_time_context=include_time_context,
            content_max_chars=content_max_chars,
        )
    elif provider == "tavily":
        search_fn = lambda: _search_tavily(
            api_key,
            query,
            max_results,
            current_time_context=current_time_context,
            include_raw_content=include_raw_content,
            include_time_context=include_time_context,
            content_max_chars=content_max_chars,
        )
    else:
        return ActionResponse(
            Action.REQLLM,
            f"联网搜索功能未配置或配置的搜索源无效（当前：{provider}），请检查配置。",
            None,
        )

    start_time = time.time()
    try:
        result_text = search_fn()
        elapsed = time.time() - start_time
        logger.bind(tag=TAG).info(f"联网搜索完成 | provider={provider} | elapsed={elapsed:.3f}s")
        logger.bind(tag=TAG).info(f"搜索结果组装完成 | chars={len(result_text)}")
        logger.bind(tag=TAG).debug(f"搜索结果预览:\n{_truncate_text(result_text, 2000)}")
    except requests.exceptions.Timeout:
        logger.bind(tag=TAG).error("联网搜索请求超时")
        result_text = "联网搜索请求超时，请稍后重试。"
    except requests.exceptions.RequestException as e:
        logger.bind(tag=TAG).error(f"联网搜索请求失败: {e}")
        result_text = "联网搜索请求失败，请稍后重试。"
    except Exception as e:
        logger.bind(tag=TAG).error(f"联网搜索异常: {e}")
        result_text = "联网搜索出现异常，请稍后重试。"

    return ActionResponse(Action.REQLLM, result_text, None)
