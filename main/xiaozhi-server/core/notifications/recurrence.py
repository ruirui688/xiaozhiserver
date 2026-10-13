"""Voice-side validation of the manager-api reminder recurrence contract.

Only the control plane computes occurrence dates. This module validates and
describes a bounded rule; it never starts a timer or expands recurring events.
"""

from datetime import datetime


FREQUENCIES = ("ONCE", "DAILY", "WEEKLY", "MONTHLY", "YEARLY")
RECURRENCE_SCHEMA = {
    "type": "object",
    "description": (
        "可选重复规则，缺省为一次性。仅用户明确要求重复时填写。"
        "工作日用WEEKLY及weekdays=[1,2,3,4,5]，不等同节假日调休。"
        "仅公历规则，不支持农历或生理周期预测。"
    ),
    "properties": {
        "frequency": {"type": "string", "enum": list(FREQUENCIES)},
        "interval": {
            "type": "integer", "minimum": 1, "maximum": 365,
            "description": "重复间隔，默认1。例如DAILY+2为每两天。",
        },
        "weekdays": {
            "type": "array", "items": {"type": "integer", "minimum": 1, "maximum": 7},
            "minItems": 1, "maxItems": 7, "uniqueItems": True,
            "description": "仅WEEKLY可用，周一为1、周日为7；省略则沿用首次提醒的星期。",
        },
        "until": {
            "type": "string",
            "description": "可选重复截止时间，带时区偏移的ISO8601；不得早于首次提醒。",
        },
    },
    "required": ["frequency"],
    "additionalProperties": False,
}


def normalize_recurrence(value, *, first_timestamp=None):
    """Return a validated rule or raise ValueError; None means legacy ONCE."""
    if value is None:
        return None
    if not isinstance(value, dict) or set(value) - {"frequency", "interval", "weekdays", "until"}:
        raise ValueError("invalid recurrence fields")
    # Jackson DTOs return optional properties as null. Treat those exactly as
    # omitted, matching Java's defaults, without mutating the supplied object.
    value = {key: item for key, item in value.items() if key == "frequency" or item is not None}
    frequency = value.get("frequency")
    if frequency not in FREQUENCIES:
        raise ValueError("invalid recurrence frequency")
    interval = value.get("interval", 1)
    if isinstance(interval, bool) or not isinstance(interval, int) or not 1 <= interval <= 365:
        raise ValueError("invalid recurrence interval")
    normalized = {"frequency": frequency, "interval": interval}
    if frequency == "ONCE" and (interval != 1 or "until" in value or "weekdays" in value):
        raise ValueError("ONCE does not accept repeat options")
    if "weekdays" in value:
        weekdays = value["weekdays"]
        if (
            frequency != "WEEKLY"
            or not isinstance(weekdays, list)
            or not 1 <= len(weekdays) <= 7
            or any(isinstance(day, bool) or not isinstance(day, int) or not 1 <= day <= 7 for day in weekdays)
            or len(set(weekdays)) != len(weekdays)
        ):
            raise ValueError("invalid recurrence weekdays")
        normalized["weekdays"] = sorted(weekdays)
    if "until" in value:
        until = value["until"]
        if not isinstance(until, str) or not 1 <= len(until) <= 64:
            raise ValueError("invalid recurrence until")
        parsed = datetime.fromisoformat(until.replace("Z", "+00:00"))
        if parsed.tzinfo is None or (first_timestamp is not None and parsed.timestamp() < first_timestamp):
            raise ValueError("invalid recurrence until")
        normalized["until"] = parsed.isoformat()
    return normalized


def describe_recurrence(rule):
    if not rule or rule["frequency"] == "ONCE":
        return ""
    unit = {"DAILY": "天", "WEEKLY": "周", "MONTHLY": "个月", "YEARLY": "年"}[rule["frequency"]]
    description = f"，每{rule['interval']}{unit}重复"
    weekdays = rule.get("weekdays")
    if weekdays:
        names = "一二三四五六日"
        description += "，星期" + "、".join(names[day - 1] for day in weekdays)
    if rule.get("until"):
        description += f"，截止{rule['until']}"
    return description
