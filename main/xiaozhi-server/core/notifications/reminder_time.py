"""Structured reminder times, not natural-language parsing or a scheduler.

The bounded connection journal retains original create payload times for retries.
It has no timers/threads, never evicts an anchor, and dies with the connection.
Runtime request IDs include the session ID: reconnects are new requests, not
durable replay. Only manager-api owns persistent reminders and deduplication.
"""

import hashlib
import json
import math
import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone as dt_timezone
from pathlib import Path
from threading import Lock
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from core.notifications.recurrence import normalize_recurrence


YEAR_SECONDS = 365 * 24 * 60 * 60
MAX_CREATE_ANCHORS = 256
_JOURNAL_LOCK = Lock()
_UNITS = {"seconds": 1, "minutes": 60, "hours": 3600, "days": 86400}
_ISO_DATETIME = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}"
    r"(?::[0-9]{2})?(?:Z|[+-][0-9]{2}:[0-9]{2})?"
)
DELAY_SCHEMA = {
    "type": "object",
    "description": '相对时长由服务器计时，例如两分钟后用{"amount":2,"unit":"minutes"}；days为24小时。与remind_at二选一。',
    "properties": {
        "amount": {"type": "integer", "minimum": 1, "maximum": YEAR_SECONDS},
        "unit": {"type": "string", "enum": list(_UNITS)},
    },
    "required": ["amount", "unit"],
    "additionalProperties": False,
}


class ReminderTimeError(ValueError):
    """A fixed, user-safe validation message (never input or credentials)."""


@dataclass(frozen=True)
class CreationTime:
    timestamp: float
    timezone_name: str
    zone: object
    recurrence: dict | None


def normalize_timezone(value):
    if value is None or (isinstance(value, str) and not value.strip()):
        name = "Asia/Shanghai"
    elif isinstance(value, str):
        name = value.strip()
    else:
        return "", None
    if len(name) > 64 or re.search(r"[\x00-\x1f\x7f]", name):
        return name, None
    try:
        return name, ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError):
        # Relocated Conda environments may retain a stale compiled TZPATH.
        root = Path("/usr/share/zoneinfo").resolve()
        try:
            path = (root / name).resolve()
            path.relative_to(root)
            if path.is_file():
                with path.open("rb") as source:
                    return name, ZoneInfo.from_file(source, key=name)
        except (OSError, ValueError):
            pass
        if name == "Asia/Shanghai":
            return name, dt_timezone(timedelta(hours=8), name)
        return name, None


def _legacy_timestamp(value):
    if isinstance(value, bool) or value is None:
        raise ReminderTimeError("提醒时间无效，请说一个明确的未来时间。")
    try:
        numeric = float(value)
        if math.isfinite(numeric) and numeric.is_integer():
            return int(numeric)
    except (TypeError, ValueError, OverflowError):
        pass
    raise ReminderTimeError("提醒时间无效，请说一个明确的未来时间。")


def _delay_seconds(value):
    if not isinstance(value, dict) or set(value) != {"amount", "unit"}:
        raise ReminderTimeError("相对提醒时间需要明确的整数数量和时间单位。")
    amount, unit = value["amount"], value["unit"]
    if (type(amount) is not int or not 1 <= amount <= YEAR_SECONDS
            or not isinstance(unit, str) or unit not in _UNITS):
        raise ReminderTimeError("相对提醒时间需要明确的整数数量和时间单位。")
    return amount * _UNITS[unit]


def _absolute_timestamp(value, zone, *, explicit_zone):
    if not isinstance(value, str) or len(value) > 32 or not _ISO_DATETIME.fullmatch(value):
        raise ReminderTimeError("请提供完整日期和时间，并明确时区或UTC偏移。")
    try:
        if value[-6:-5] in {"+", "-"} and (
            int(value[-5:-3]) > 23 or int(value[-2:]) > 59
        ):
            raise ValueError("invalid UTC offset")
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if value.endswith("-00:00"):
            raise ValueError("unknown RFC3339 offset")
        if parsed.tzinfo is not None:
            if explicit_zone and parsed.utcoffset() != parsed.astimezone(zone).utcoffset():
                raise ReminderTimeError("提醒时间的UTC偏移与所选时区不一致，请确认。")
            return parsed.timestamp()
        if not explicit_zone:
            raise ReminderTimeError("没有UTC偏移的日期时间必须明确提供IANA时区。")
        # Both folds must round-trip. Spring gaps have no valid instant;
        # autumn overlaps have two and require an explicit numeric offset.
        candidates = set()
        for fold in (0, 1):
            aware = parsed.replace(tzinfo=zone, fold=fold)
            utc = aware.astimezone(dt_timezone.utc)
            if utc.astimezone(zone).replace(tzinfo=None) == parsed:
                candidates.add(utc.timestamp())
        if len(candidates) != 1:
            raise ReminderTimeError("该本地时间因夏令时不存在或有歧义，请确认带UTC偏移的时间。")
        return candidates.pop()
    except ReminderTimeError:
        raise
    except (ValueError, OverflowError, OSError):
        raise ReminderTimeError("提醒日期时间无效，请重新确认。") from None


def resolve_creation_time(conn, *, request_id, title, now, timezone=None,
                          recurrence=None, remind_timestamp=None, delay=None,
                          remind_at=None):
    """Validate once; same ID/input always replays the original absolute time.

    Journal operations happen before network awaits under a short lock. An
    existing request bypasses the Python creation window so Java can read its
    durable dedup record; Java still applies its window before any new insert.
    Invalid input consumes no slot. Capacity rejects new requests, never retries.
    """
    if sum(value is not None for value in (delay, remind_at, remind_timestamp)) != 1:
        raise ReminderTimeError("创建提醒时，相对时长、日期时间和旧时间戳必须且只能提供一种。")
    timezone_name, zone = normalize_timezone(timezone)
    if zone is None:
        raise ReminderTimeError("提醒时区无效，请使用明确的IANA时区。")
    if delay is not None:
        kind, value = "delay", _delay_seconds(delay)
    elif remind_at is not None:
        kind, value = "absolute", _absolute_timestamp(
            remind_at, zone, explicit_zone=isinstance(timezone, str) and bool(timezone.strip())
        )
    else:
        kind, value = "legacy", _legacy_timestamp(remind_timestamp)
    try:
        rule = normalize_recurrence(recurrence)
    except (ValueError, TypeError, OverflowError):
        raise ReminderTimeError("重复规则无效，请确认重复频率、星期和截止时间。") from None
    fingerprint = hashlib.sha256(json.dumps(
        [conn.config.get("agent_id"), conn.device_id, title, timezone_name, kind, value, rule],
        sort_keys=True, separators=(",", ":"), ensure_ascii=False,
    ).encode("utf-8")).digest()
    with _JOURNAL_LOCK:
        journal = getattr(conn, "_reminder_create_anchors", None)
        if journal is None:
            journal = {}
            conn._reminder_create_anchors = journal
        existing = journal.get(request_id)
        if existing is not None:
            if existing[0] != fingerprint:
                raise ReminderTimeError("同一提醒请求的内容不能变更，请先查询日程核对。")
            return CreationTime(existing[1], timezone_name, zone, rule)
        if len(journal) >= MAX_CREATE_ANCHORS:
            raise ReminderTimeError("本次连接的提醒请求已达上限，请先查询日程，重新连接后再创建。")
        timestamp = now + value if kind == "delay" else value
        if timestamp < now + 5:
            raise ReminderTimeError("提醒时间必须至少晚于现在五秒，请重新确认时间。")
        if timestamp > now + YEAR_SECONDS:
            raise ReminderTimeError("首次提醒时间需要设置在一年以内。")
        try:
            normalize_recurrence(rule, first_timestamp=timestamp)
        except (ValueError, TypeError, OverflowError):
            raise ReminderTimeError("重复规则无效，请确认重复频率、星期和截止时间。") from None
        journal[request_id] = (fingerprint, timestamp)
        return CreationTime(timestamp, timezone_name, zone, rule)
