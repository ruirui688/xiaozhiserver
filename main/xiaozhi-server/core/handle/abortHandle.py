import json
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
TAG = __name__


async def handleAbortMessage(conn: "ConnectionHandler"):
    conn.logger.bind(tag=TAG).info("Abort message received")
    # 设置成打断状态，会自动打断llm、tts任务
    conn.close_after_chat = False
    with conn._abort_lock:
        conn.client_abort = True
    conn.abort_generation += 1
    conn.clear_queues()
    # 清理 VAD/ASR 音频状态，防止打断后残留的 client_voice_stop
    # 导致下一帧音频立即触发新的 ASR 识别
    conn.reset_audio_states()
    # 排空 ASR 音频队列，丢弃打断前积累的旧音频包
    import queue
    while True:
        try:
            conn.asr_audio_queue.get_nowait()
        except queue.Empty:
            break
    # 打断客户端说话状态
    try:
        await conn.websocket.send(
            json.dumps(
                {
                    "type": "tts",
                    "state": "stop",
                    "reason": "abort",
                    "session_id": conn.session_id,
                }
            )
        )
    except Exception as e:
        conn.logger.bind(tag=TAG).error(f"发送打断消息失败: {e}")
    conn.clearSpeakStatus()
    conn.logger.bind(tag=TAG).info("Abort message received-end")
