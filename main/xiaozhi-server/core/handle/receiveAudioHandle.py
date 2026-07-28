import asyncio
import json
import time
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
from core.utils.util import audio_to_data
from core.utils.correct_words import apply_correct_words
from core.handle.abortHandle import handleAbortMessage
from core.handle.intentHandler import handle_user_intent
from core.handle.reportHandle import (
    mark_asr_report_text_override,
    mark_skip_memory_event,
)
from core.semantic.normalizer import TextNormalizer
from core.utils.output_counter import check_device_output_limit
from core.handle.sendAudioHandle import (
    begin_assistant_reply,
    send_stt_display,
    send_stt_message,
    schedule_reply_termination,
    terminate_pending_reply,
    SentenceType,
)
from core.handle.wakeupHandle import should_ignore_wakeup_audio

TAG = __name__
NORMALIZER = TextNormalizer()


async def handleAudioMessage(conn: "ConnectionHandler", audio):
    if conn.vad is None or conn.asr is None:
        conn.logger.bind(tag=TAG).warning("VAD或ASR未配置，忽略音频消息")
        return
    # 无AEC设备可通过wakeup_vad_ignore_ms配置短暂回声保护；默认0不丢首包。
    # 必须在VAD前判断，避免唤醒回复音频污染VAD内部状态。
    if should_ignore_wakeup_audio(conn):
        return
    # 当前片段是否有人说话
    vad_started_at = time.monotonic()
    have_voice = conn.vad.is_vad(conn, audio)
    turn_metrics = getattr(conn, "turn_metrics", None)
    if turn_metrics is not None:
        try:
            turn_metrics.record_vad_frame(
                have_voice,
                int((time.monotonic() - vad_started_at) * 1000),
                len(audio) if audio else 0,
            )
        except Exception as e:
            logger = conn.logger.bind(tag=TAG)
            if hasattr(logger, "debug"):
                logger.debug(f"记录VAD指标失败: {e}")
    update_voice_activity(conn, have_voice)
    # 与官方一致：支持 AEC 的设备只要确认用户已经开口，就立即停止当前播报。
    # 这里不等待句尾、完整 ASR 或 LLM；当前用户音频继续进入 ASR。
    if (
        getattr(conn, "client_aec", False)
        and have_voice
        and conn.client_is_speaking
        and conn.client_listen_mode != "manual"
    ):
        await handleAbortMessage(conn, preserve_user_audio=True)
    # 接收音频
    await conn.asr.receive_audio(conn, audio, have_voice)


async def startToChat(
    conn: "ConnectionHandler", text, turn_context=None, turn_id=None
):
    """Handle recognized user text.

    Returns True when the text should be treated as an accepted user turn.
    Returns False only when another session mode consumes the text.
    """
    reply_generation = None

    def submit_chat(chat_text):
        if reply_generation is not None:
            return conn.executor.submit(
                conn.chat,
                chat_text,
                0,
                True,
                turn_context,
                turn_id,
                reply_generation,
            )
        if turn_id is not None:
            return conn.executor.submit(
                conn.chat, chat_text, 0, True, turn_context, turn_id
            )
        if turn_context is not None:
            return conn.executor.submit(
                conn.chat, chat_text, 0, True, turn_context
            )
        return conn.executor.submit(conn.chat, chat_text)

    def schedule_termination(reason):
        schedule_reply_termination(
            conn, reason, generation=reply_generation
        )

    def on_chat_done(future):
        try:
            error = future.exception()
        except Exception as callback_error:
            schedule_termination(
                "llm_task_cancelled"
                if "cancel" in type(callback_error).__name__.lower()
                else "llm_task_failed"
            )
            return
        if error is not None:
            conn.logger.bind(tag=TAG).error(
                f"chat() failed: errorType={type(error).__name__}"
            )
            schedule_termination("llm_task_failed")
            return
        try:
            result = future.result()
        except Exception:
            schedule_termination("llm_task_failed")
            return
        if result is not True:
            schedule_termination("llm_no_spoken_result")

    enrollment = getattr(conn, "voiceprint_enrollment", None)
    if enrollment is not None and enrollment.status.active:
        conn.logger.bind(tag=TAG).info("声纹采集模式忽略普通对话文本，跳过语义/LLM")
        return False

    # 检查输入是否是JSON格式（包含说话人信息）
    speaker_name = None
    speaker_id = None
    actual_text = text
    interruption_text = text
    parsed_payload = None

    try:
        # 尝试解析JSON格式的输入
        if text.strip().startswith("{") and text.strip().endswith("}"):
            data = json.loads(text)
            if isinstance(data, dict) and "content" in data:
                parsed_payload = data
                speaker_name = data.get("speaker")
                speaker_id = data.get("speaker_id") or None
                actual_content = data.get("content") or ""
                interruption_text = actual_content or text
                if speaker_name:
                    conn.logger.bind(tag=TAG).info(f"解析到说话人信息: {speaker_name}")

                # 声纹身份必须和当前用户消息绑定并在每轮传给LLM；避免重复称呼
                # 应由提示词约束，不能通过丢弃本轮身份事实实现。
                actual_text = text if speaker_name else actual_content
    except (json.JSONDecodeError, KeyError):
        # 如果解析失败，继续使用原始文本
        pass

    corrected_text = apply_correct_words(conn, actual_text)
    if corrected_text != actual_text:
        actual_text = corrected_text
        try:
            if actual_text.strip().startswith("{") and actual_text.strip().endswith("}"):
                data = json.loads(actual_text)
                if "speaker" in data and "content" in data:
                    speaker_name = data["speaker"]
                    speaker_id = data.get("speaker_id") or speaker_id
                    interruption_text = data.get("content") or interruption_text
        except (json.JSONDecodeError, KeyError):
            pass
    elif actual_text != text:
        interruption_text = actual_text

    runtime_text = NORMALIZER.normalize_runtime_text(actual_text)
    if runtime_text != actual_text:
        mark_asr_report_text_override(conn, text, runtime_text)
        actual_text = runtime_text
        try:
            if actual_text.strip().startswith("{") and actual_text.strip().endswith("}"):
                data = json.loads(actual_text)
                if isinstance(data, dict) and "content" in data:
                    parsed_payload = data
                    speaker_name = data.get("speaker") or speaker_name
                    speaker_id = data.get("speaker_id") or speaker_id
                    interruption_text = data.get("content") or interruption_text
            else:
                interruption_text = actual_text
        except (json.JSONDecodeError, KeyError):
            interruption_text = actual_text
    else:
        normalized_display = NORMALIZER.normalize(conn, actual_text).display_text
        interruption_text = normalized_display or interruption_text

    # 保存说话人信息到连接对象
    if speaker_name:
        conn.current_speaker = speaker_name
        conn.current_speaker_id = speaker_id
    else:
        conn.current_speaker = None
        conn.current_speaker_id = None

    meeting = getattr(conn, "meeting_minutes", None)
    if meeting is not None and meeting.is_active():
        async with conn._meeting_turn_lock:
            mark_skip_memory_event(conn, 1, actual_text, "meeting_active")
            if getattr(conn, "reply_lifecycle", None) is not None:
                await send_stt_display(conn, actual_text)
            else:
                # Compatibility for lightweight integrations without lifecycle.
                await send_stt_message(conn, actual_text)
            meeting.prepare_turn(parsed_payload, interruption_text, speaker_name)
            logger = getattr(conn, "logger", None)
            if logger is not None:
                logger.bind(tag=TAG).info(
                    "会议已激活，当前ASR提交主LLM决定record或end"
                )
            if conn.llm is None:
                appended = meeting.record_pending_turn()
                conn.logger.bind(tag=TAG).warning(
                    "会议已激活但LLM未配置，安全回退继续记录: "
                    f"utteranceCount={appended}"
                )
                return True
            with conn._abort_lock:
                conn.client_abort = False
            try:
                future = submit_chat(actual_text)
                await asyncio.wrap_future(future)
            except Exception as error:
                appended = meeting.record_pending_turn()
                conn.logger.bind(tag=TAG).error(
                    "会议主LLM轮次执行失败，安全回退继续记录: "
                    f"errorType={type(error).__name__}, utteranceCount={appended}"
                )
        return True

    if conn.need_bind:
        await check_bind_device(conn)
        return True

    # 如果当日的输出字数大于限定的字数
    if conn.max_output_size > 0:
        if check_device_output_limit(
            conn.headers.get("device-id"), conn.max_output_size
        ):
            await max_out_size(conn)
            return True

    # 与官方一致：非 AEC 路径取得完整 ASR 后，只要仍在播报就直接停止。
    # manual 模式由设备显式控制录音边界，不自动打断。
    if conn.client_is_speaking and conn.client_listen_mode != "manual":
        await handleAbortMessage(conn)

    # 语义控制中枢优先处理高置信度控制类指令，未处理再进入旧流程
    if getattr(conn, "semantic_center", None):
        if turn_id is None:
            semantic_handled = await conn.semantic_center.handle_user_text(
                conn, actual_text
            )
        else:
            semantic_handled = await conn.semantic_center.handle_user_text(
                conn, actual_text, turn_id=turn_id
            )
        if semantic_handled:
            return True

    # 首先进行意图分析，使用实际文本内容
    if turn_id is None:
        if turn_context is None:
            intent_handled = await handle_user_intent(conn, actual_text)
        else:
            intent_handled = await handle_user_intent(
                conn,
                actual_text,
                turn_context=turn_context,
            )
    else:
        intent_handled = await handle_user_intent(
            conn,
            actual_text,
            turn_context=turn_context,
            turn_id=turn_id,
        )

    if intent_handled:
        # 如果意图已被处理，不再进行聊天
        return True

    # 意图未被处理，先校验能力，再创建可追踪的回复 generation。
    if conn.llm is None:
        conn.logger.bind(tag=TAG).warning("LLM未配置，拒绝启动回复轮次")
        return True

    if getattr(conn, "reply_lifecycle", None) is not None:
        reply_generation = await begin_assistant_reply(conn, actual_text)
    else:
        reply_generation = await send_stt_message(conn, actual_text)
    if getattr(conn, "reply_lifecycle", None) is not None and reply_generation is None:
        return True

    # 准备开始新会话
    with conn._abort_lock:
        conn.client_abort = False

    try:
        future = submit_chat(actual_text)
    except Exception as error:
        conn.logger.bind(tag=TAG).error(
            f"提交LLM任务失败: errorType={type(error).__name__}"
        )
        await terminate_pending_reply(
            conn, "llm_submit_failed", generation=reply_generation
        )
        return True
    future.add_done_callback(on_chat_done)
    return True

def update_voice_activity(conn: "ConnectionHandler", have_voice):
    if have_voice:
        conn.last_activity_time = time.time() * 1000


async def max_out_size(conn: "ConnectionHandler"):
    # 播放超出最大输出字数的提示
    with conn._abort_lock:
        conn.client_abort = False
    text = "不好意思，我现在有点事情要忙，明天这个时候我们再聊，约好了哦！明天不见不散，拜拜！"
    await send_stt_message(conn, text)
    file_path = "config/assets/max_output_size.wav"
    opus_packets = await audio_to_data(file_path)
    conn.tts.tts_audio_queue.put((SentenceType.LAST, opus_packets, text))
    conn.close_after_chat = True


async def check_bind_device(conn: "ConnectionHandler"):
    if conn.bind_code:
        # 确保bind_code是6位数字
        if len(conn.bind_code) != 6:
            conn.logger.bind(tag=TAG).error(f"无效的绑定码格式: {conn.bind_code}")
            text = "绑定码格式错误，请检查配置。"
            await send_stt_message(conn, text)
            return

        text = f"请登录控制面板，输入{conn.bind_code}，绑定设备。"
        await send_stt_message(conn, text)

        # 播放提示音
        music_path = "config/assets/bind_code.wav"
        opus_packets = await audio_to_data(music_path)
        conn.tts.tts_audio_queue.put((SentenceType.FIRST, opus_packets, text))

        # 逐个播放数字
        for i in range(6):  # 确保只播放6位数字
            try:
                digit = conn.bind_code[i]
                num_path = f"config/assets/bind_code/{digit}.wav"
                num_packets = await audio_to_data(num_path)
                conn.tts.tts_audio_queue.put((SentenceType.MIDDLE, num_packets, None))
            except Exception as e:
                conn.logger.bind(tag=TAG).error(f"播放数字音频失败: {e}")
                continue
        conn.tts.tts_audio_queue.put((SentenceType.LAST, [], None))
    else:
        # 播放未绑定提示
        with conn._abort_lock:
            conn.client_abort = False
        text = f"没有找到该设备的版本信息，请正确配置 OTA地址，然后重新编译固件。"
        await send_stt_message(conn, text)
        music_path = "config/assets/bind_not_found.wav"
        opus_packets = await audio_to_data(music_path)
        conn.tts.tts_audio_queue.put((SentenceType.LAST, opus_packets, text))
