import time
import uuid
import asyncio
from typing import Dict, Any, TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

from core.utils.dialogue import Message
from core.providers.asr.dto.dto import InterfaceType
from core.handle.receiveAudioHandle import startToChat
from core.handle.wakeupHandle import arm_wakeup_vad_ignore
from core.handle.reportHandle import enqueue_asr_report
from core.handle.sendAudioHandle import send_stt_message, send_tts_message
from core.handle.textMessageHandler import TextMessageHandler
from core.handle.textMessageType import TextMessageType
from core.utils.util import remove_punctuation_and_length
from core.providers.tts.dto.dto import ContentType, TTSMessageDTO, SentenceType


TAG = __name__

class ListenTextMessageHandler(TextMessageHandler):
    """Listen消息处理器"""

    @property
    def message_type(self) -> TextMessageType:
        return TextMessageType.LISTEN

    async def handle(self, conn: "ConnectionHandler", msg_json: Dict[str, Any]) -> None:
        if "mode" in msg_json:
            conn.client_listen_mode = msg_json["mode"]
            conn.logger.bind(tag=TAG).debug(
                f"客户端拾音模式：{conn.client_listen_mode}"
            )
        if msg_json.get("state") == "start":
            reloader = getattr(conn, "runtime_config_reloader", None)
            if reloader is not None:
                try:
                    await reloader.refresh_if_due(reason="listen_start")
                except Exception as error:
                    conn.logger.bind(tag=TAG).warning(
                        "本轮开始前配置热更新失败，继续使用旧配置: errorType={}",
                        type(error).__name__,
                    )
        conversation_router = getattr(conn, "conversation_router", None)
        if conversation_router is not None and conversation_router.is_realtime:
            conversation_ready = getattr(conn, "conversation_ready", None)
            if conversation_ready is not None and not conversation_ready.is_set():
                if msg_json.get("state") == "detect":
                    conn.defer_listen_detect(
                        msg_json,
                        ttl_seconds=getattr(
                            conn, "_deferred_listen_detect_ttl_seconds", 10.0
                        ),
                    )
                return
            if msg_json.get("state") == "detect" and "text" in msg_json:
                await conversation_router.handle_text(msg_json["text"])
            else:
                await conversation_router.handle_listen(msg_json)
            return
        if msg_json["state"] == "start":
            # 设备从播放模式切回录音模式,清除所有音频状态和缓冲区
            conn.reset_audio_states()
            turn_context = getattr(conn, "turn_context", None)
            if turn_context is None:
                return
            update = turn_context.replace(msg_json.get("context"))
            if update.accepted:
                conn.logger.bind(tag=TAG).info(
                    "已接收本轮语音背景: "
                    f"type={update.context_type}, size_bytes={update.size_bytes}"
                )
            elif "context" in msg_json and update.reason not in {"", "empty"}:
                conn.logger.bind(tag=TAG).warning(
                    "忽略无效本轮语音背景: "
                    f"type={update.context_type}, size_bytes={update.size_bytes}, "
                    f"reason={update.reason}"
                )
        elif msg_json["state"] == "stop":
            conn.client_voice_stop = True
            if conn.asr is None:
                conn.logger.bind(tag=TAG).warning("ASR未配置，忽略本次语音识别")
                turn_context = getattr(conn, "turn_context", None)
                if turn_context is not None:
                    turn_context.clear()
                conn.reset_audio_states()
                return
            if conn.asr.interface_type == InterfaceType.STREAM:
                # 流式模式下，发送结束请求
                asyncio.create_task(conn.asr._send_stop_request())
            else:
                # 非流式模式：直接触发ASR识别
                if len(conn.asr_audio) > 0:
                    asr_audio_task = conn.asr_audio.copy()
                    conn.reset_audio_states(preserve_voiceprint=True)

                    if len(asr_audio_task) > 0:
                        await conn.asr.handle_voice_stop(conn, asr_audio_task)
                else:
                    turn_context = getattr(conn, "turn_context", None)
                    if turn_context is not None:
                        turn_context.clear()
        elif msg_json["state"] == "detect":
            conversation_ready = getattr(conn, "conversation_ready", None)
            if conversation_ready is not None and not conversation_ready.is_set():
                if getattr(conn, "initialization_failed", False):
                    clear_deferred = getattr(
                        conn, "_clear_deferred_listen_detects", None
                    )
                    if callable(clear_deferred):
                        clear_deferred("initialization_failed")
                    conn.logger.bind(tag=TAG).warning(
                        "初始化失败，拒绝listen.detect: "
                        f"hasInitializationError={bool(getattr(conn, 'initialization_error', ''))}"
                    )
                    return
                conn.defer_listen_detect(
                    msg_json,
                    ttl_seconds=getattr(
                        conn, "_deferred_listen_detect_ttl_seconds", 10.0
                    ),
                )
                return
            speech_runtime = getattr(conn, "speech_runtime", None)
            speech_route = getattr(speech_runtime, "route", None)
            uses_device_asr = bool(
                speech_route is not None and speech_route.uses_device_asr
            )
            device_asr_message = msg_json.get("source") == "device_asr"
            input_turn_id = None
            if uses_device_asr or device_asr_message:
                decision = speech_runtime.validate_device_asr(msg_json)
                await speech_runtime.send_device_asr_ack(decision)
                if not decision.accepted:
                    conn.logger.bind(tag=TAG).warning(
                        "拒绝设备ASR最终文本: turnId={}, reason={}, duplicate={}",
                        decision.turn_id,
                        decision.reason,
                        decision.duplicate,
                    )
                    return
                msg_json = dict(msg_json)
                msg_json["text"] = decision.text
                input_turn_id = decision.turn_id
                conn.logger.bind(tag=TAG).info(
                    "接收设备ASR最终文本: turnId={}, textLength={}",
                    input_turn_id,
                    len(decision.text),
                )
            conn.client_have_voice = False
            turn_context = getattr(conn, "turn_context", None)
            input_turn_context = None
            if turn_context is not None:
                if input_turn_id is not None:
                    input_turn_context = turn_context.take()
                else:
                    turn_context.clear()
            conn.reset_audio_states()
            if "text" in msg_json:
                conn.last_activity_time = time.time() * 1000
                original_text = msg_json["text"]  # 保留原始文本

                # 先做文本预处理（后续防抖和唤醒词检测都需要）
                filtered_len, filtered_text = remove_punctuation_and_length(
                    original_text
                )

                # 特殊指令仍走原有专用入口；普通文本交给 startToChat 处理。

                # 检查是否是设备呼叫指令 [device_call]
                if original_text.startswith("[device_call]"):
                    # 提取 tag 后的文本
                    call_text = original_text[len("[device_call]"):].strip()
                    if input_turn_id is not None:
                        conn.logger.bind(tag=TAG).info(
                            "收到设备ASR呼叫指令: turnId={}, textLength={}",
                            input_turn_id,
                            len(call_text),
                        )
                    else:
                        conn.logger.bind(tag=TAG).info(
                            f"收到设备呼叫指令: {call_text}"
                        )

                    # 准备开始新会话
                    conn.sentence_id = uuid.uuid4().hex

                    await send_stt_message(conn, call_text)
                    conn.tts.store_tts_text(conn.sentence_id, call_text)
                    conn.tts.tts_text_queue.put(TTSMessageDTO(sentence_id=conn.sentence_id, sentence_type=SentenceType.FIRST, content_type=ContentType.ACTION))
                    conn.tts.tts_one_sentence(conn, ContentType.TEXT, content_detail=call_text)
                    conn.tts.tts_text_queue.put(TTSMessageDTO(sentence_id=conn.sentence_id, sentence_type=SentenceType.LAST, content_type=ContentType.ACTION))

                    # 添加到对话历史，让模型理解上下文
                    conn.dialogue.put(Message(role="assistant", content=call_text))
                    return

                # 识别是否是唤醒词
                is_wakeup_words = filtered_text in (
                    conn.config.get("wakeup_words") or []
                )
                # 是否开启唤醒词回复
                enable_greeting = conn.config.get("enable_greeting", False)

                if is_wakeup_words and not enable_greeting:
                    # 如果是唤醒词，且关闭了唤醒词回复，就不用回答
                    arm_wakeup_vad_ignore(conn)
                    enqueue_asr_report(conn, original_text, [])
                    await send_stt_message(conn, original_text)
                    await send_tts_message(conn, "stop", None)
                    if getattr(conn, "reply_lifecycle", None) is None:
                        conn.client_is_speaking = False
                elif is_wakeup_words:
                    arm_wakeup_vad_ignore(conn)
                    # 聊天记录保存设备真实检测到的唤醒词，不用内部问候语替代证据。
                    # 先上报用户侧，确保同步播放的缓存回复之后仍按 user -> assistant 排序。
                    enqueue_asr_report(conn, original_text, [])
                    if input_turn_id is None:
                        await startToChat(conn, "嘿，你好呀")
                    else:
                        await startToChat(
                            conn,
                            "嘿，你好呀",
                            turn_context=input_turn_context,
                            turn_id=input_turn_id,
                        )
                else:
                    arm_wakeup_vad_ignore(conn)
                    # 否则需要LLM对文字内容进行答复
                    if input_turn_id is None:
                        accepted = await startToChat(conn, original_text)
                    else:
                        accepted = await startToChat(
                            conn,
                            original_text,
                            turn_context=input_turn_context,
                            turn_id=input_turn_id,
                        )
                    if accepted:
                        # 上报纯文字数据（复用ASR上报功能，但不提供音频数据）
                        enqueue_asr_report(conn, original_text, [])
