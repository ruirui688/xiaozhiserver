from typing import TYPE_CHECKING

from core.handle.sendAudioHandle import send_tts_message

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
TAG = __name__


async def handleAbortMessage(conn: "ConnectionHandler", preserve_user_audio=False):
    conn.logger.bind(tag=TAG).info("Abort message received")
    turn_metrics = getattr(conn, "turn_metrics", None)
    if turn_metrics is not None:
        turn_metrics.record_interruption(
            getattr(conn, "sentence_id", None),
            "aec_barge_in" if preserve_user_audio else "user_interrupt",
        )
    tts = getattr(conn, "tts", None)
    if tts is not None and hasattr(tts, "finalize_interrupted_report"):
        tts.finalize_interrupted_report(getattr(conn, "sentence_id", None))
    # 设置成打断状态，会自动打断llm、tts任务
    conn.close_after_chat = False
    with conn._abort_lock:
        conn.client_abort = True
    conn.abort_generation += 1
    conn.clear_queues(preserve_asr=preserve_user_audio)
    turn_context = getattr(conn, "turn_context", None)
    if turn_context is not None and not preserve_user_audio:
        turn_context.clear()
    if not preserve_user_audio:
        # 显式 abort 清理输入状态；AEC 开口打断则保留正在形成的新用户轮次。
        conn.reset_audio_states()
    # 打断客户端说话状态
    try:
        await send_tts_message(
            conn,
            "stop",
            protocol_sentence_id=getattr(conn, "sentence_id", None),
            drain_audio=False,
            reason="abort",
        )
    except Exception as e:
        conn.logger.bind(tag=TAG).error(f"发送打断消息失败: {e}")
    conn.clearSpeakStatus()
    conn.logger.bind(tag=TAG).info("Abort message received-end")
