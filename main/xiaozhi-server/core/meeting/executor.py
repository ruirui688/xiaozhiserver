"""Execute authoritative meeting state changes selected by the main LLM."""

import asyncio
import uuid

from config.manage_api_client import execute_feishu_action
from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO


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
        "你是专业的会议纪要助手。只基于用户提供的完整逐句记录整理会议纪要，"
        "不得编造没有出现的信息。"
    )
    try:
        summary = conn.llm.response_no_stream(system_prompt, prompt)
    except Exception as error:
        conn.logger.bind(tag=__name__).error(
            "会议纪要LLM生成失败: errorType={}".format(type(error).__name__)
        )
        enqueue_tts_text(conn, "会议纪要生成失败，请稍后重试。")
        return

    summary = str(summary or "").strip()
    if not summary:
        summary = "会议已结束，但大模型没有返回有效会议纪要。"
    enqueue_tts_text(conn, summary)
    enqueue_feishu_report(conn, summary, topic)


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
    if not text or conn.tts is None:
        return
    with conn._abort_lock:
        conn.client_abort = False
    sentence_id = str(uuid.uuid4().hex)
    conn.sentence_id = sentence_id
    conn.tts.store_tts_text(sentence_id, text)
    conn.tts.tts_text_queue.put(
        TTSMessageDTO(
            sentence_id=sentence_id,
            sentence_type=SentenceType.FIRST,
            content_type=ContentType.ACTION,
        )
    )
    conn.tts.tts_one_sentence(
        conn,
        ContentType.TEXT,
        content_detail=text,
        sentence_id=sentence_id,
    )
    conn.tts.tts_text_queue.put(
        TTSMessageDTO(
            sentence_id=sentence_id,
            sentence_type=SentenceType.LAST,
            content_type=ContentType.ACTION,
        )
    )
