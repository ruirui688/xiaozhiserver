import json
from datetime import datetime
from typing import Any, Dict, Iterable

from config.manage_api_client import get_reception_context, report_reception_event
from core.reception_interrupt import lock_reception_reply

MAX_PEOPLE = 8
MAX_EVENT_ID = 128


async def handle_reception_event(conn, message: Dict[str, Any]) -> None:
    if not (getattr(conn, "features", None) or {}).get("reception_v1"):
        await _send_error(conn, message, "feature_not_enabled")
        return
    if message.get("event") != "group_identified" or message.get("version") != 1:
        await _send_error(conn, message, "unsupported_event")
        return
    event_id = str(message.get("eventId") or "").strip()
    group_id = str(message.get("groupSessionId") or "").strip()
    people = message.get("recognized")
    if not (1 <= len(event_id) <= MAX_EVENT_ID) or not (1 <= len(group_id) <= 128):
        await _send_error(conn, message, "invalid_request")
        return
    if not isinstance(people, list) or not 1 <= len(people) <= MAX_PEOPLE:
        await _send_error(conn, message, "invalid_people")
        return
    person_ids = _person_ids(people)
    if not person_ids:
        await _send_error(conn, message, "invalid_people")
        return
    selected_ids = person_ids if len(person_ids) <= 3 else person_ids[:1]
    try:
        response = await get_reception_context(
            {
                "macAddress": str(getattr(conn, "device_id", "") or ""),
                "personIds": selected_ids,
            }
        ) or {}
        action = build_reception_action(message, response, selected_ids)
        lock_reception_reply(conn)
        await conn.websocket.send(json.dumps(action, ensure_ascii=False))
    except Exception as error:
        logger = getattr(conn, "logger", None)
        if logger is not None:
            logger.bind(tag=__name__).warning(
                "接待上下文查询失败: eventId={}, errorType={}".format(
                    event_id, type(error).__name__
                )
            )
        await _send_error(conn, message, "server_unavailable")
        return
    try:
        await report_reception_event(
            {
                "macAddress": str(getattr(conn, "device_id", "") or ""),
                "eventId": event_id,
                "groupSessionId": group_id,
                "sceneEpoch": int(message.get("sceneEpoch") or 0),
                "selectedPersonIds": selected_ids,
                "presentCount": int(message.get("presentCount") or len(person_ids)),
                "capturedAt": message.get("capturedAt"),
            }
        )
    except Exception as error:
        logger = getattr(conn, "logger", None)
        if logger is not None:
            logger.bind(tag=__name__).warning(
                "接待审计写入失败但已发送动作: eventId={}, errorType={}".format(
                    event_id, type(error).__name__
                )
            )


def build_reception_action(
    message: Dict[str, Any], response: Dict[str, Any], selected_ids: list[str]
) -> Dict[str, Any]:
    contexts = response.get("people") if isinstance(response.get("people"), list) else []
    by_id = {str(item.get("personId") or ""): item for item in contexts if isinstance(item, dict)}
    selected = [by_id[person_id] for person_id in selected_ids if person_id in by_id]
    names = [str(item.get("personName") or "").strip() for item in selected]
    names = [name for name in names if name]
    appointment = any(bool(item.get("appointment")) for item in selected)
    returning = any(str(item.get("customerStage") or "NEW") != "NEW" for item in selected)
    if names:
        welcome = "、".join(names) + ("，欢迎回来。" if returning else "，欢迎光临。")
    else:
        welcome = "欢迎光临。"
    company_intro = str(response.get("companyIntroText") or "").strip()[:60]
    products = _bounded_json_list(response.get("productsJson"), 20)
    faq = _bounded_json_list(response.get("faqJson"), 100)
    seats = _bounded_json_list(response.get("seatsJson"), 100)
    stage_show = _json_object(response.get("stageShowJson"))
    order_qr_content = str(response.get("orderQrContent") or "").strip()
    personal_topic = _personal_topic(selected)
    appointment_text = "已经为您确认了今天的预约。" if appointment else ""
    spoken_text = "".join(
        part for part in (welcome, personal_topic, appointment_text, company_intro) if part
    )[:1000]
    return {
        "type": "reception",
        "event": "action",
        "version": 1,
        "eventId": message.get("eventId"),
        "groupSessionId": message.get("groupSessionId"),
        "capturedAt": message.get("capturedAt"),
        "selectedPersonIds": selected_ids,
        "welcomeText": welcome,
        "personalTopicText": personal_topic,
        "spokenText": spoken_text,
        "companyIntroText": company_intro,
        "companyIntroRequired": bool(company_intro),
        "appointment": appointment,
        "orderQrAvailable": bool(order_qr_content),
        "orderQrContent": order_qr_content,
        "conversationContext": {
            "formatVersion": "1.0",
            "people": selected,
            "presentCount": int(message.get("presentCount") or len(selected_ids)),
            "companyName": response.get("companyName"),
            "companyIntroText": company_intro,
            "products": products,
            "faq": faq,
            "seats": seats,
            "stageShow": stage_show,
            "serviceContactText": response.get("serviceContactText"),
            "orderQrAvailable": bool(order_qr_content),
            "instruction": (
                "先自然说欢迎语；存在 companyIntroText 时原样介绍且不超过十秒。"
                "只能使用 people 中经过授权的客户事实；没有的事实不得编造。"
                "餐品推荐只能从 products 中选择；用户不满意时提供二次推荐、服务员协助或扫码点餐。"
            ),
        },
        "generatedAt": datetime.now().astimezone().isoformat(timespec="milliseconds"),
    }


def _bounded_json_list(value: Any, limit: int) -> list[Dict[str, Any]]:
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except json.JSONDecodeError:
            return []
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)][:limit]


def _personal_topic(people: list[Dict[str, Any]]) -> str:
    for person in people:
        if str(person.get("customerStage") or "NEW") not in {"LONG_TERM", "VIP"}:
            continue
        hobbies = _bounded_json_list_value(person.get("hobbiesJson"), 3)
        if hobbies:
            return "还记得您喜欢{}，今天也可以继续聊聊。".format(hobbies[0])
    return ""


def _bounded_json_list_value(value: Any, limit: int) -> list[str]:
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except json.JSONDecodeError:
            return []
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()][:limit]


def _json_object(value: Any) -> Dict[str, Any]:
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except json.JSONDecodeError:
            return {}
    return dict(value) if isinstance(value, dict) else {}


def _person_ids(people: Iterable[Any]) -> list[str]:
    result = []
    for item in people:
        if not isinstance(item, dict):
            return []
        person_id = str(item.get("personId") or "").strip()
        if not _valid_identifier(person_id):
            return []
        if person_id not in result:
            result.append(person_id)
    return result


def _valid_identifier(value: str) -> bool:
    return 1 <= len(value) <= 64 and all(char.isalnum() or char in "_-" for char in value)


async def _send_error(conn, message: Dict[str, Any], error_code: str) -> None:
    await conn.websocket.send(
        json.dumps(
            {
                "type": "reception",
                "event": "error",
                "version": 1,
                "eventId": message.get("eventId"),
                "errorCode": error_code,
            },
            ensure_ascii=False,
        )
    )
