"""Execute authoritative meeting state changes selected by the main LLM."""

import asyncio
import json
import re

from config.manage_api_client import execute_feishu_action
from core.spoken_notifications import enqueue_spoken_notification
from core.utils.util import extract_json_from_string


def finish_meeting(conn) -> None:
    meeting = getattr(conn, "meeting_minutes", None)
    if meeting is None or not meeting.is_active():
        return

    meeting.discard_pending_turn()
    meeting.end()
    conn.logger.bind(tag=__name__).info("会议模式已结束，开始生成完整会议纪要")
    summary_prompt = meeting.build_summary_prompt()
    transcript = meeting.build_transcript()
    topic = meeting.get_status().get("topic", "")
    future = conn.executor.submit(
        generate_meeting_minutes,
        conn,
        summary_prompt,
        transcript,
        topic,
    )
    future.add_done_callback(
        lambda item: conn.logger.bind(tag=__name__).error(
            f"会议纪要生成失败: {item.exception()}"
        )
        if item.exception()
        else None
    )


def generate_meeting_minutes(conn, prompt: str, transcript, topic: str = "") -> None:
    if not transcript:
        enqueue_tts_text(conn, "本次会议没有记录到有效发言，无法生成会议纪要。")
        return
    if conn.llm is None:
        enqueue_tts_text(conn, "会议已结束，但当前没有配置大模型，无法生成会议纪要。")
        return

    system_prompt = (
        "你是一位克制、清晰的会议整理助手。忠实保留原意，优先提炼真正有用的信息，"
        "不要把模糊表达包装成正式成果，也不要为了凑模板制造结论或待办。"
    )
    try:
        summary = conn.llm.response_no_stream(system_prompt, prompt)
    except Exception as error:
        conn.logger.bind(tag=__name__).error(
            "会议纪要LLM生成失败: errorType={}".format(type(error).__name__)
        )
        enqueue_tts_text(conn, "会议纪要生成失败，请稍后重试。")
        return

    spoken_summary, written_minutes = parse_meeting_summary(summary)
    enqueue_tts_text(conn, spoken_summary)
    enqueue_feishu_report(conn, written_minutes, topic)


def parse_meeting_summary(raw_summary) -> tuple[str, str]:
    raw_text = str(raw_summary or "").strip()
    payload = None
    json_text = extract_json_from_string(raw_text)
    if json_text:
        try:
            parsed = json.loads(json_text)
            if isinstance(parsed, dict):
                payload = parsed
        except (TypeError, ValueError, json.JSONDecodeError):
            payload = None

    if payload is not None:
        spoken = str(payload.get("spoken_summary") or "").strip()
        written = str(payload.get("written_minutes") or "").strip()
    else:
        spoken = ""
        written = raw_text

    if not written:
        written = "会议已结束，但没有生成有效的书面纪要。"
    if not spoken:
        spoken = _plain_spoken_summary(written)
    if not spoken:
        spoken = "会议已结束，但没有生成有效的口头总结。"
    return spoken, written


def _plain_spoken_summary(text: str) -> str:
    lines = []
    for raw_line in str(text or "").splitlines():
        line = re.sub(r"^[#>*\s\-\d.、]+", "", raw_line).strip()
        line = line.replace("**", "").replace("__", "")
        if not line or line.endswith("："):
            continue
        lines.append(line)
    return "。".join(lines[:4])[:500].strip("。 ")


def enqueue_feishu_report(conn, summary: str, topic: str = "") -> None:
    if not getattr(conn, "read_config_from_api", False):
        return
    agent_id = (getattr(conn, "config", None) or {}).get("agent_id")
    loop = getattr(conn, "loop", None)
    if not agent_id or loop is None or loop.is_closed():
        return
    coroutine = execute_feishu_action(
        agent_id,
        "publish_meeting_report",
        title=f"会议纪要：{topic}" if topic else "会议纪要",
        content=summary,
        macAddress=getattr(conn, "device_id", None),
    )
    try:
        future = asyncio.run_coroutine_threadsafe(coroutine, loop)
    except Exception as error:
        coroutine.close()
        conn.logger.bind(tag=__name__).warning(
            "提交飞书会议纪要失败: errorType={}".format(type(error).__name__)
        )
        return

    def log_result(item):
        try:
            item.result()
            conn.logger.bind(tag=__name__).info("飞书会议纪要投递完成")
        except Exception as error:
            conn.logger.bind(tag=__name__).warning(
                "飞书会议纪要投递失败: errorType={}".format(
                    type(error).__name__
                )
            )

    future.add_done_callback(log_result)


def enqueue_tts_text(conn, text: str) -> None:
    enqueue_spoken_notification(conn, text, require_idle=False)
