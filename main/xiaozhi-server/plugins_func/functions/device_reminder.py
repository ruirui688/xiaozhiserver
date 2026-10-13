"""Persistent, device-scoped reminder actions mediated by manager-api."""

from __future__ import annotations

import json
import math
import re
import time
from datetime import datetime, timedelta, timezone as dt_timezone
from pathlib import Path
from typing import TYPE_CHECKING, Optional
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from config.manage_api_client import execute_reminder_action
from core.utils.prompt_data import wrap_untrusted_content
from plugins_func.register import Action, ActionResponse, ToolType, register_function

if TYPE_CHECKING:
    from core.connection import ConnectionHandler


DEVICE_REMINDER_TOOL_DESCRIPTION = {
    "type": "function",
    "function": {
        "name": "device_reminder",
        "description": (
            "管理当前设备的一次性语音提醒。用户明确要求设置、查看或取消提醒时调用。"
            "创建前必须根据系统提供的当前时间把相对时间换算成未来的Unix秒时间戳；"
            "时间有歧义时先向用户确认，不得猜测。提醒只属于当前设备。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["create", "list", "cancel"],
                    "description": "创建、查询或取消一次性提醒。",
                },
                "title": {
                    "type": "string",
                    "description": "create时必填，用户要被提醒的简短事项。",
                },
                "remind_timestamp": {
                    "type": "integer",
                    "description": (
                        "create时必填，已消除歧义的未来Unix时间戳（秒）；"
                        "例如用户说相对时间时，先依据系统当前时间换算。"
                    ),
                },
                "timezone": {
                    "type": "string",
                    "description": "IANA时区，默认Asia/Shanghai。",
                },
                "reminder_id": {
                    "type": "string",
                    "description": "cancel时必填，来自list结果的提醒ID。",
                },
            },
            "required": ["action"],
        },
    },
}


@register_function(
    "device_reminder",
    DEVICE_REMINDER_TOOL_DESCRIPTION,
    ToolType.SYSTEM_CTL,
)
async def device_reminder(
    conn: "ConnectionHandler",
    action: str,
    title: Optional[str] = None,
    remind_timestamp: Optional[int] = None,
    timezone: Optional[str] = None,
    reminder_id: Optional[str] = None,
    request_id: Optional[str] = None,
):
    normalized_action = str(action or "").strip().lower()
    if normalized_action not in {"create", "list", "cancel"}:
        return _response("不支持这个日程操作。")

    identity_error = _identity_error(conn, normalized_action)
    if identity_error:
        return _response(identity_error)

    agent_id = str(conn.config.get("agent_id") or "").strip()
    device_id = str(getattr(conn, "device_id", "") or "").strip()
    normalized_request_id = _normalize_request_id(request_id)
    if normalized_request_id is None:
        return _response("提醒请求标识无效，请重试。")

    payload = {
        "deviceId": device_id,
        "requestId": normalized_request_id,
    }
    display_time = None
    normalized_title = None

    if normalized_action == "create":
        normalized_title = _normalize_title(title)
        if normalized_title is None:
            return _response("请告诉我要提醒你的具体事项。")
        timestamp = _normalize_timestamp(remind_timestamp)
        if timestamp is None:
            return _response("提醒时间无效，请说一个明确的未来时间。")
        now = time.time()
        if timestamp < now + 5:
            return _response("提醒时间必须至少晚于现在五秒，请重新确认时间。")
        if timestamp > now + 365 * 24 * 60 * 60:
            return _response("目前只能设置一年以内的一次性提醒。")
        timezone_name, zone = _normalize_timezone(timezone)
        if zone is None:
            return _response("提醒时区无效，请使用明确的IANA时区。")
        remind_at = (
            datetime.fromtimestamp(timestamp, dt_timezone.utc)
            .isoformat()
            .replace("+00:00", "Z")
        )
        display_time = datetime.fromtimestamp(timestamp, zone)
        payload.update(
            {
                "title": normalized_title,
                "remindAt": remind_at,
                "timezone": timezone_name,
            }
        )
    elif normalized_action == "cancel":
        normalized_reminder_id = _normalize_identifier(reminder_id, 64)
        if normalized_reminder_id is None:
            return _response("请先查询日程，并提供要取消的提醒ID。")
        payload["reminderId"] = normalized_reminder_id

    try:
        result = await execute_reminder_action(
            agent_id,
            normalized_action,
            **payload,
        )
    except Exception as error:
        conn.logger.bind(tag=__name__).warning(
            "设备提醒工具执行失败: action={}, errorType={}",
            normalized_action,
            type(error).__name__,
        )
        if normalized_action == "create":
            return _response(
                "日程创建结果未确认，请先查询当前日程，避免重复创建。"
            )
        return _response("日程服务暂时不可用，请稍后再试。")

    if normalized_action == "create":
        if not _created(result):
            return _response("提醒未能创建，请稍后再试。")
        confirmed_title = _normalize_title(result.get("title")) or normalized_title
        confirmed_timezone, confirmed_zone = _result_timezone(
            result.get("timezone"), timezone_name, zone
        )
        confirmed_time = _result_display_time(
            result.get("remindAt"), display_time, confirmed_zone
        )
        return _response(
            "好的，已设置{}（时区：{}）的提醒：{}。".format(
                confirmed_time, confirmed_timezone, confirmed_title
            )
        )
    if normalized_action == "cancel":
        if not _cancelled(result):
            return _response("没有取消该提醒，请先查询仍待执行的日程。")
        return _response("这个提醒已取消。")

    reminders = result.get("reminders") if isinstance(result, dict) else None
    if not isinstance(reminders, list):
        return _response("日程查询失败，请稍后再试。")
    if not reminders:
        return _response("当前设备没有待执行的提醒。")
    safe_reminders = [_reminder_view(item) for item in reminders[:20]]
    safe_reminders = [item for item in safe_reminders if item is not None]
    if not safe_reminders:
        return _response("当前设备没有可展示的待执行提醒。")
    reminder_data = json.dumps(
        {"reminders": safe_reminders}, ensure_ascii=False, separators=(",", ":")
    )
    return ActionResponse(
        action=Action.REQLLM,
        result=wrap_untrusted_content(
            reminder_data,
            tag="untrusted_device_reminders",
        ),
        next_llm_tools_enabled=False,
    )


def _identity_error(conn, action: str) -> str | None:
    router = getattr(conn, "conversation_router", None)
    if router is not None and getattr(router, "is_realtime", False):
        if action == "create":
            return "当前Realtime会话暂不支持设备日程提醒，提醒未创建。"
        return "当前Realtime会话暂不支持设备日程管理。"
    config = getattr(conn, "config", {}) or {}
    plugins = config.get("plugins", {}) or {}
    if not getattr(conn, "read_config_from_api", False):
        return "当前设备没有接入可持久化的日程服务。"
    if not isinstance(plugins, dict) or "device_reminder" not in plugins:
        return "当前智能体没有开启设备日程功能。"
    if not config.get("agent_id") or not getattr(conn, "device_id", None):
        return "当前设备身份不完整，暂时不能管理日程。"
    return None


def _normalize_title(value) -> str | None:
    if not isinstance(value, str):
        return None
    title = re.sub(r"[\x00-\x1f\x7f]", " ", value)
    title = re.sub(r"\s+", " ", title).strip()
    return title if 1 <= len(title) <= 200 else None


def _normalize_timestamp(value) -> int | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        numeric = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(numeric) or not numeric.is_integer():
        return None
    return int(numeric)


def _normalize_timezone(value):
    if value is None or (isinstance(value, str) and not value.strip()):
        name = "Asia/Shanghai"
    elif isinstance(value, str):
        name = value.strip()
    else:
        return "", None
    if not name or len(name) > 64 or re.search(r"[\x00-\x1f\x7f]", name):
        return name, None
    try:
        return name, ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError):
        zoneinfo_root = Path("/usr/share/zoneinfo").resolve()
        try:
            zoneinfo_path = (zoneinfo_root / name).resolve()
            zoneinfo_path.relative_to(zoneinfo_root)
            if zoneinfo_path.is_file():
                with zoneinfo_path.open("rb") as zone_file:
                    return name, ZoneInfo.from_file(zone_file, key=name)
        except (OSError, ValueError):
            pass
        # Some relocated Conda environments retain a stale compiled TZPATH.
        # Asia/Shanghai has had a fixed UTC+08:00 offset since 1991, which is
        # sufficient for the supported one-year reminder horizon.
        if name == "Asia/Shanghai":
            return name, dt_timezone(timedelta(hours=8), name)
        return name, None


def _normalize_request_id(value) -> str | None:
    return _normalize_identifier(value, 128)


def _normalize_identifier(value, max_length: int) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    if (
        not normalized
        or len(normalized) > max_length
        or re.search(r"[\x00-\x1f\x7f]", normalized)
    ):
        return None
    return normalized


def _created(result) -> bool:
    if not isinstance(result, dict) or not result.get("reminderId"):
        return False
    return str(result.get("status") or "").strip().upper() == "PENDING"


def _cancelled(result) -> bool:
    if not isinstance(result, dict) or not result.get("reminderId"):
        return False
    return str(result.get("status") or "").strip().upper() == "CANCELLED"


def _reminder_view(value):
    if not isinstance(value, dict):
        return None
    reminder_id = _normalize_identifier(value.get("reminderId"), 64)
    title = _normalize_title(value.get("title"))
    remind_at = _normalize_identifier(value.get("remindAt"), 64)
    timezone_name = _normalize_identifier(value.get("timezone"), 64)
    if not reminder_id or not title or not remind_at:
        return None
    return {
        "reminderId": reminder_id,
        "title": title,
        "remindAt": remind_at,
        "timezone": timezone_name or "Asia/Shanghai",
        "status": "PENDING",
    }


def _format_display_time(value: datetime) -> str:
    return value.strftime("%Y年%m月%d日%H点%M分")


def _result_timezone(value, fallback_name, fallback_zone):
    if isinstance(value, str) and value.strip():
        result_name, result_zone = _normalize_timezone(value)
        if result_zone is not None:
            return result_name, result_zone
    return fallback_name, fallback_zone


def _result_display_time(value, fallback: datetime, zone) -> str:
    text = str(value or "").strip()
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if parsed.tzinfo is not None:
            return _format_display_time(parsed.astimezone(zone))
    except ValueError:
        pass
    return _format_display_time(fallback)


def _response(text: str) -> ActionResponse:
    return ActionResponse(
        action=Action.RESPONSE,
        response=text,
    )
