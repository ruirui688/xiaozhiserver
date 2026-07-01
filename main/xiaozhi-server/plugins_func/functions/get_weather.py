import requests
import re
from bs4 import BeautifulSoup
from config.logger import setup_logging
from plugins_func.register import register_function, ToolType, ActionResponse, Action
from core.utils.util import get_ip_info
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__
logger = setup_logging()

GET_WEATHER_FUNCTION_DESC = {
    "type": "function",
    "function": {
        "name": "get_weather",
        "description": (
            "获取某个地点的天气，用户应提供一个位置，比如用户说杭州天气，参数为：杭州。"
            "如果用户说的是省份，默认用省会城市。如果用户说的不是省份或城市而是一个地名，默认用该地所在省份的省会城市。"
            "重要：本地未来7天天气已在上下文中提供，用户未指明其他城市且未询问实时状态/预警时不要调用此工具。"
            "用户询问天气预警、预警级别或预警状态时，query_type必须传warning；普通当前天气或天气预报传weather。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "location": {
                    "type": "string",
                    "description": "地点名，例如杭州。可选参数，如果不提供则不传",
                },
                "lang": {
                    "type": "string",
                    "description": "返回用户使用的语言code，例如zh_CN/zh_HK/en_US/ja_JP等，默认zh_CN",
                },
                "query_type": {
                    "type": "string",
                    "description": "查询类型。普通当前天气或天气预报传weather；天气预警、预警级别或预警状态传warning。",
                    "enum": ["weather", "warning"],
                },
            },
            "required": ["lang"],
        },
    },
}

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/92.0.4515.107 Safari/537.36"
    )
}

OPEN_METEO_WEATHER_CODE_MAP = {
    0: "晴",
    1: "大部晴朗",
    2: "局部多云",
    3: "阴",
    45: "有雾",
    48: "雾凇",
    51: "小毛毛雨",
    53: "毛毛雨",
    55: "较强毛毛雨",
    61: "小雨",
    63: "中雨",
    65: "大雨",
    71: "小雪",
    73: "中雪",
    75: "大雪",
    80: "小阵雨",
    81: "阵雨",
    82: "强阵雨",
    95: "雷阵雨",
    96: "雷阵雨伴有小冰雹",
    99: "雷阵雨伴有冰雹",
}

KNOWN_CITY_PREFIXES = (
    "北京",
    "上海",
    "天津",
    "重庆",
    "南京",
    "杭州",
    "广州",
    "深圳",
    "苏州",
    "成都",
    "武汉",
    "西安",
    "郑州",
    "长沙",
    "合肥",
    "宁波",
    "无锡",
    "青岛",
    "济南",
    "厦门",
    "福州",
)

# 天气代码 https://dev.qweather.com/docs/resource/icons/#weather-icons
WEATHER_CODE_MAP = {
    "100": "晴",
    "101": "多云",
    "102": "少云",
    "103": "晴间多云",
    "104": "阴",
    "150": "晴",
    "151": "多云",
    "152": "少云",
    "153": "晴间多云",
    "300": "阵雨",
    "301": "强阵雨",
    "302": "雷阵雨",
    "303": "强雷阵雨",
    "304": "雷阵雨伴有冰雹",
    "305": "小雨",
    "306": "中雨",
    "307": "大雨",
    "308": "极端降雨",
    "309": "毛毛雨/细雨",
    "310": "暴雨",
    "311": "大暴雨",
    "312": "特大暴雨",
    "313": "冻雨",
    "314": "小到中雨",
    "315": "中到大雨",
    "316": "大到暴雨",
    "317": "暴雨到大暴雨",
    "318": "大暴雨到特大暴雨",
    "350": "阵雨",
    "351": "强阵雨",
    "399": "雨",
    "400": "小雪",
    "401": "中雪",
    "402": "大雪",
    "403": "暴雪",
    "404": "雨夹雪",
    "405": "雨雪天气",
    "406": "阵雨夹雪",
    "407": "阵雪",
    "408": "小到中雪",
    "409": "中到大雪",
    "410": "大到暴雪",
    "456": "阵雨夹雪",
    "457": "阵雪",
    "499": "雪",
    "500": "薄雾",
    "501": "雾",
    "502": "霾",
    "503": "扬沙",
    "504": "浮尘",
    "507": "沙尘暴",
    "508": "强沙尘暴",
    "509": "浓雾",
    "510": "强浓雾",
    "511": "中度霾",
    "512": "重度霾",
    "513": "严重霾",
    "514": "大雾",
    "515": "特强浓雾",
    "900": "热",
    "901": "冷",
    "999": "未知",
}


def fetch_city_info(location, api_key, api_host):
    url = f"https://{api_host}/geo/v2/city/lookup?key={api_key}&location={location}&lang=zh"
    response = requests.get(url, headers=HEADERS, timeout=10).json()
    if response.get("error") is not None:
        logger.bind(tag=TAG).error(
            f"获取天气失败，原因：{response.get('error', {}).get('detail')}"
        )
        return None
    return response.get("location", [])[0] if response.get("location") else None


def fetch_weather_warning(location_id, api_key, api_host):
    url = f"https://{api_host}/v7/warning/now"
    response = requests.get(
        url,
        params={"key": api_key, "location": location_id, "lang": "zh"},
        headers=HEADERS,
        timeout=10,
    )
    data = response.json()
    if data.get("error") is not None:
        logger.bind(tag=TAG).error(
            f"获取天气预警失败，原因：{data.get('error', {}).get('detail')}"
        )
        return None
    if data.get("code") and str(data.get("code")) != "200":
        logger.bind(tag=TAG).error(f"获取天气预警失败，code={data.get('code')}")
        return None
    return data.get("warning", [])


def build_weather_warning_report(city_name, warnings):
    if warnings is None:
        return f"暂时没查到{city_name}的天气预警。"
    if not warnings:
        return f"{city_name}当前未查到生效天气预警。"

    parts = []
    for item in warnings[:3]:
        title = (item.get("title") or "").strip()
        type_name = (item.get("typeName") or "").strip()
        severity = (item.get("severity") or item.get("severityColor") or "").strip()
        status = (item.get("status") or "").strip()
        summary = title or "".join(part for part in [type_name, severity, "预警"] if part)
        if status and status not in summary:
            summary = f"{summary}，状态{status}"
        parts.append(summary)

    if not parts:
        return f"{city_name}当前未查到生效天气预警。"
    return f"{city_name}当前有天气预警：" + "；".join(parts) + "。"


def fetch_weather_page(url):
    response = requests.get(url, headers=HEADERS, timeout=10)
    return BeautifulSoup(response.text, "html.parser") if response.ok else None


def parse_weather_info(soup):
    city_name = soup.select_one("h1.c-submenu__location").get_text(strip=True)

    current_abstract = soup.select_one(".c-city-weather-current .current-abstract")
    current_abstract = (
        current_abstract.get_text(strip=True) if current_abstract else "未知"
    )

    current_basic = {}
    for item in soup.select(
        ".c-city-weather-current .current-basic .current-basic___item"
    ):
        parts = item.get_text(strip=True, separator=" ").split(" ")
        if len(parts) == 2:
            key, value = parts[1], parts[0]
            current_basic[key] = value

    temps_list = []
    for row in soup.select(".city-forecast-tabs__row")[:7]:  # 取前7天的数据
        date = row.select_one(".date-bg .date").get_text(strip=True)
        weather_code = (
            row.select_one(".date-bg .icon")["src"].split("/")[-1].split(".")[0]
        )
        weather = WEATHER_CODE_MAP.get(weather_code, "未知")
        temps = [span.get_text(strip=True) for span in row.select(".tmp-cont .temp")]
        high_temp, low_temp = (temps[0], temps[-1]) if len(temps) >= 2 else (None, None)
        temps_list.append((date, weather, high_temp, low_temp))

    return city_name, current_abstract, current_basic, temps_list


def normalize_location_candidates(location):
    normalized = re.sub(r"\s+", "", location or "")
    normalized = re.sub(r"(天气|温度|气温)$", "", normalized)
    candidates = []

    def add(value):
        value = (value or "").strip()
        if value and value not in candidates:
            candidates.append(value)

    add(normalized)
    add(re.sub(r"(市|区|县)$", "", normalized))

    city_match = re.search(r"(.+?市)", normalized)
    if city_match:
        city = city_match.group(1)
        add(city)
        add(city[:-1] if city.endswith("市") else city)

    for city in KNOWN_CITY_PREFIXES:
        if normalized.startswith(city) and normalized != city:
            add(city)

    return candidates


def fetch_open_meteo_geo(location):
    for candidate in normalize_location_candidates(location):
        response = requests.get(
            "https://geocoding-api.open-meteo.com/v1/search",
            params={
                "name": candidate,
                "count": 5,
                "language": "zh",
                "format": "json",
            },
            timeout=10,
        )
        if not response.ok:
            continue
        results = response.json().get("results") or []
        if results:
            china_results = [item for item in results if item.get("country_code") == "CN"]
            return (china_results or results)[0], candidate
    return None, ""


def build_open_meteo_weather_report(location):
    geo, matched_query = fetch_open_meteo_geo(location)
    if not geo:
        return ""

    response = requests.get(
        "https://api.open-meteo.com/v1/forecast",
        params={
            "latitude": geo["latitude"],
            "longitude": geo["longitude"],
            "current": [
                "temperature_2m",
                "apparent_temperature",
                "weather_code",
                "relative_humidity_2m",
            ],
            "daily": ["weather_code", "temperature_2m_max", "temperature_2m_min"],
            "timezone": "Asia/Shanghai",
            "forecast_days": 3,
        },
        timeout=10,
    )
    if not response.ok:
        return ""

    data = response.json()
    current = data.get("current", {})
    daily = data.get("daily", {})
    city_name = geo.get("name") or matched_query or location
    admin1 = geo.get("admin1")
    if admin1 and admin1 not in city_name:
        city_name = f"{admin1}{city_name}"

    weather = OPEN_METEO_WEATHER_CODE_MAP.get(current.get("weather_code"), "未知")
    temp = current.get("temperature_2m")
    apparent = current.get("apparent_temperature")
    humidity = current.get("relative_humidity_2m")

    report = f"{city_name}现在{weather}"
    if temp is not None:
        report += f"，气温{temp}度"
    if apparent is not None:
        report += f"，体感{apparent}度"
    if humidity is not None:
        report += f"，湿度{humidity}%"

    times = daily.get("time") or []
    highs = daily.get("temperature_2m_max") or []
    lows = daily.get("temperature_2m_min") or []
    codes = daily.get("weather_code") or []
    if times and highs and lows:
        forecast_parts = []
        for index, day in enumerate(times[:3]):
            day_weather = OPEN_METEO_WEATHER_CODE_MAP.get(
                codes[index] if index < len(codes) else None, "未知"
            )
            forecast_parts.append(
                f"{day}{day_weather}，{lows[index]}到{highs[index]}度"
            )
        report += "。未来三天：" + "；".join(forecast_parts) + "。"
    else:
        report += "。"

    if matched_query and matched_query != location:
        report += f"（按{matched_query}查询）"
    return report


@register_function("get_weather", GET_WEATHER_FUNCTION_DESC, ToolType.SYSTEM_CTL)
def get_weather(
    conn: "ConnectionHandler",
    location: str = None,
    lang: str = "zh_CN",
    query_type: str = "weather",
):
    from core.utils.cache.manager import cache_manager, CacheType

    weather_config = conn.config.get("plugins", {}).get("get_weather", {})
    api_host = weather_config.get("api_host", "")
    api_key = weather_config.get("api_key", "")
    default_location = weather_config.get("default_location", "广州")
    client_ip = conn.client_ip

    # 优先使用用户提供的location参数
    if not location:
        # 通过客户端IP解析城市
        if client_ip:
            # 先从缓存获取IP对应的城市信息
            cached_ip_info = cache_manager.get(CacheType.IP_INFO, client_ip)
            if cached_ip_info:
                location = cached_ip_info.get("city")
            else:
                # 缓存未命中，调用API获取
                ip_info = get_ip_info(client_ip, logger)
                if ip_info:
                    cache_manager.set(CacheType.IP_INFO, client_ip, ip_info)
                    location = ip_info.get("city")

            if not location:
                location = default_location
        else:
            # 若无IP，使用默认位置
            location = default_location

    if query_type == "warning":
        if not api_key or not api_host:
            return ActionResponse(
                Action.RESPONSE,
                response=f"天气预警查询未配置API Key或API Host，暂时无法查询{location}预警。",
            )
        try:
            city_info = fetch_city_info(location, api_key, api_host)
            if not city_info:
                return ActionResponse(
                    Action.RESPONSE,
                    response=f"暂时没查到{location}的天气预警。",
                )
            warnings = fetch_weather_warning(city_info["id"], api_key, api_host)
            city_name = city_info.get("name") or location
            return ActionResponse(
                Action.RESPONSE,
                response=build_weather_warning_report(city_name, warnings),
            )
        except Exception as e:
            logger.bind(tag=TAG).error(f"天气预警查询异常: {e}")
            return ActionResponse(
                Action.RESPONSE,
                response=f"暂时没查到{location}的天气预警。",
            )

    # 尝试从缓存获取完整天气报告
    weather_cache_key = f"full_weather_{location}_{lang}"
    cached_weather_report = cache_manager.get(CacheType.WEATHER, weather_cache_key)
    if cached_weather_report:
        return ActionResponse(Action.RESPONSE, response=cached_weather_report)

    # 缓存未命中，获取实时天气数据
    city_info = None
    if api_key and api_host:
        try:
            city_info = fetch_city_info(location, api_key, api_host)
        except Exception as e:
            logger.bind(tag=TAG).error(f"和风天气接口异常，改用兜底天气源: {e}")
    if not city_info:
        fallback_report = build_open_meteo_weather_report(location)
        if fallback_report:
            cache_manager.set(CacheType.WEATHER, weather_cache_key, fallback_report)
            return ActionResponse(Action.RESPONSE, response=fallback_report)
        return ActionResponse(
            Action.RESPONSE, response=f"暂时没查到{location}的天气。"
        )
    soup = fetch_weather_page(city_info["fxLink"])
    if not soup:
        fallback_report = build_open_meteo_weather_report(location)
        if fallback_report:
            cache_manager.set(CacheType.WEATHER, weather_cache_key, fallback_report)
            return ActionResponse(Action.RESPONSE, response=fallback_report)
        return ActionResponse(Action.RESPONSE, response="天气请求失败，请稍后再试。")
    city_name, current_abstract, current_basic, temps_list = parse_weather_info(soup)

    weather_report = f"您查询的位置是：{city_name}\n\n当前天气: {current_abstract}\n"

    # 添加有效的当前天气参数
    if current_basic:
        weather_report += "详细参数：\n"
        for key, value in current_basic.items():
            if value != "0":  # 过滤无效值
                weather_report += f"  · {key}: {value}\n"

    # 添加7天预报
    weather_report += "\n未来7天预报：\n"
    for date, weather, high, low in temps_list:
        weather_report += f"{date}: {weather}，气温 {low}~{high}\n"

    # 提示语
    weather_report += "\n（如需某一天的具体天气，请告诉我日期）"

    # 缓存完整的天气报告
    cache_manager.set(CacheType.WEATHER, weather_cache_key, weather_report)

    return ActionResponse(Action.RESPONSE, response=weather_report)
