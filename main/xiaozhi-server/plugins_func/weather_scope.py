"""Date selection and concise speech for weather reports (no model or I/O)."""

import re
from datetime import date, datetime, timedelta, timezone


BEIJING_TZ = timezone(timedelta(hours=8), name="Asia/Shanghai")


def weather_today():
    # Beijing dates need no IANA database (not present in every Conda runtime).
    return datetime.now(BEIJING_TZ).date()


def valid_scope(start_day, days):
    return (
        type(start_day) is int and type(days) is int
        and 0 <= start_day <= 6 and 1 <= days <= 7
        and start_day + days <= 7
    )


def forecast_date(label, today):
    """Read source dates, never assume the first returned row means today."""
    text = str(label).strip()
    absolute = re.search(r"(?<!\d)\d{4}-\d{2}-\d{2}(?!\d)", text)
    if absolute:
        try:
            return date.fromisoformat(absolute.group())
        except ValueError:
            return None
    # Match common QWeather page labels, including month/year boundaries.
    match = re.search(r"(?<!\d)(\d{1,2})[月/.-](\d{1,2})(?:日)?(?!\d)", text)
    for offset in range(7):
        candidate = today + timedelta(days=offset)
        if match and (candidate.month, candidate.day) == tuple(map(int, match.groups())):
            return candidate
    if match:
        return None
    if text in {"今天", "今日"}:
        return today
    if text == "明天":
        return today + timedelta(days=1)
    if text == "后天":
        return today + timedelta(days=2)
    return None


def scoped_weather_report(city, rows, today, start_day, days, current=""):
    """Rows are (source date, condition, high, low); omit all unrequested dates."""
    first = today + timedelta(days=start_day)
    wanted = [first + timedelta(days=index) for index in range(days)]
    by_date = {}
    for label, condition, high, low in rows:
        day = forecast_date(label, today)
        if day in wanted:
            by_date[day] = (condition, high, low)
    if days == 1:
        relative = {0: "今天", 1: "明天", 2: "后天"}.get(start_day, "")
        title = f"{city}{relative}（{first.isoformat()}）天气"
    else:
        title = f"{city}{first.isoformat()}至{wanted[-1].isoformat()}的{days}天天气"
    parts = []
    for day in wanted:
        if day not in by_date:
            parts.append(f"{day.isoformat()}：暂未取得预报")
            continue
        condition, high, low = by_date[day]
        line = f"{day.isoformat()}：{condition or '天气状况暂缺'}"
        if low is not None and high is not None:
            def degrees(value):
                return str(value).replace("℃", "").replace("°", "").strip()
            line += f"，气温{degrees(low)}到{degrees(high)}度"
        parts.append(line)
    if start_day == 0 and days == 1 and current and current != "未知":
        parts.append(f"当前天气：{current}")
    return title + "：" + "；".join(parts) + "。"
