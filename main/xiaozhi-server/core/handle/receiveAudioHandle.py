import json
import time
import uuid
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
from core.utils.util import audio_to_data
from core.utils.correct_words import apply_correct_words
from core.handle.abortHandle import handleAbortMessage
from core.handle.intentHandler import handle_user_intent
from core.semantic.interruption import SemanticInterruptionClassifier
from core.semantic.router import SemanticRouter
from core.semantic.normalizer import TextNormalizer
from core.utils.output_counter import check_device_output_limit
from core.handle.sendAudioHandle import send_stt_message, SentenceType
from core.handle.wakeupHandle import should_ignore_wakeup_audio
from core.meeting import (
    MeetingControlClassifier,
    extract_meeting_topic,
    get_meeting_entry_prompt,
    is_meeting_mode_enabled,
    is_meeting_start_command,
)
from core.providers.tts.dto.dto import ContentType, TTSMessageDTO

TAG = __name__


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
    # 接收音频
    await conn.asr.receive_audio(conn, audio, have_voice)


async def startToChat(conn: "ConnectionHandler", text):
    """Handle recognized user text.

    Returns True when the text should be treated as an accepted user turn.
    Returns False when overlap speech is classified as continue/echo and should
    not be persisted as a standalone chat-history turn.
    """
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

                introduced_speakers = getattr(conn, "introduced_speakers", None)
                if introduced_speakers is None:
                    introduced_speakers = set()
                    conn.introduced_speakers = introduced_speakers
                speaker_identity = speaker_id or speaker_name

                # 已识别身份只在首次作为JSON交给LLM；后续使用纯文本，避免反复点名。
                if (
                    speaker_name
                    and speaker_name != "未知说话人"
                    and speaker_identity in introduced_speakers
                ):
                    actual_text = actual_content
                else:
                    actual_text = text
                    if speaker_name and speaker_name != "未知说话人":
                        introduced_speakers.add(speaker_identity)
                if not speaker_name:
                    actual_text = actual_content
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

    # 保存说话人信息到连接对象
    if speaker_name:
        conn.current_speaker = speaker_name
        conn.current_speaker_id = speaker_id
    else:
        conn.current_speaker = None
        conn.current_speaker_id = None

    meeting = getattr(conn, "meeting_minutes", None)
    if meeting is not None:
        if is_meeting_start_command(
            interruption_text
        ) and is_meeting_mode_enabled(conn):
            meeting.start(extract_meeting_topic(interruption_text))
            conn.logger.bind(tag=TAG).info("已进入会议模式，后续语音将静默记录")
            _enqueue_tts_text(conn, get_meeting_entry_prompt(conn))
            return True

        if meeting.is_active():
            control = await MeetingControlClassifier().classify(
                conn,
                interruption_text,
                meeting,
            )
            conn.logger.bind(tag=TAG).info(
                "会议控制语义裁决: "
                f"decision={control.decision}, "
                f"confidence={control.confidence:.2f}, "
                f"model={control.model_source}, "
                f"reason={control.reason}"
            )
            if control.should_end:
                _finish_meeting(conn, meeting)
                return True
            _append_meeting_utterances(
                conn,
                meeting,
                parsed_payload,
                interruption_text,
                speaker_name,
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

    # manual 模式下不打断正在播放的内容；auto 模式交给当前 LLM 做语义裁决。
    semantic_checked_after_interrupt = False

    if conn.client_is_speaking and conn.client_listen_mode != "manual":
        context = None
        if getattr(conn, "semantic_center", None):
            context = conn.semantic_center.state_manager.get_context(conn)
        if context is None:
            context = type(
                "RobotContext",
                (),
                {"active_game_tool": None},
            )()
        # Fast-path: check against deterministic stop words before firing LLM
        normalizer = TextNormalizer()
        route_key = normalizer.route_key(interruption_text)
        if route_key in SemanticRouter.STOP_ALL:
            # Deterministic stop — skip LLM call, abort immediately
            await handleAbortMessage(conn)
            handled_by_semantic = False
            if getattr(conn, "semantic_center", None):
                handled_by_semantic = await conn.semantic_center.handle_user_text(conn, interruption_text)
            if not handled_by_semantic and getattr(conn, "semantic_center", None):
                with conn._abort_lock:
                    conn.client_abort = False
                conn.executor.submit(conn.chat, interruption_text)
            return True
        classifier = SemanticInterruptionClassifier()
        classification = await classifier.classify(
            conn, interruption_text, context
        )
        conn.logger.bind(tag=TAG).info(
            "语义打断分类: "
            f"category={classification.category}, "
            f"reason={classification.reason}"
        )
        if not classification.should_abort:
            conn.logger.bind(tag=TAG).info(
                "语义打断裁决继续播报: "
                f"category={classification.category}, "
                f"reason={classification.reason}, "
                f"text={interruption_text[:50]}"
            )
            return False
        conn.logger.bind(tag=TAG).info(
            "语义打断裁决进入新轮: "
            f"category={classification.category}, "
            f"reason={classification.reason}, "
            f"text={interruption_text[:50]}"
        )
        await handleAbortMessage(conn)
        if classification.category == "interrupt":
            if getattr(conn, "semantic_center", None):
                semantic_checked_after_interrupt = True
                handled_by_semantic = await conn.semantic_center.handle_user_text(conn, actual_text)
                if handled_by_semantic:
                    return True
                conn.logger.bind(tag=TAG).info(
                    "语义打断interrupt未命中控制指令，按新用户轮次处理: "
                    f"text={interruption_text[:50]}"
                )
            else:
                return True

    # 语义控制中枢优先处理高置信度控制类指令，未处理再进入旧流程
    if getattr(conn, "semantic_center", None) and not semantic_checked_after_interrupt:
        semantic_handled = await conn.semantic_center.handle_user_text(conn, actual_text)
        if semantic_handled:
            return True

    # 首先进行意图分析，使用实际文本内容
    intent_handled = await handle_user_intent(conn, actual_text)

    if intent_handled:
        # 如果意图已被处理，不再进行聊天
        return True

    # 意图未被处理，继续常规聊天流程，使用实际文本内容
    await send_stt_message(conn, actual_text)
    if conn.llm is None:
        conn.logger.bind(tag=TAG).warning("LLM未配置，无法生成回复")
        return True

    # 准备开始新会话
    with conn._abort_lock:
        conn.client_abort = False

    future = conn.executor.submit(conn.chat, actual_text)
    future.add_done_callback(lambda f: conn.logger.bind(tag=__name__).error(f"chat() failed: {f.exception()}") if f.exception() else None)
    return True


def _finish_meeting(conn: "ConnectionHandler", meeting) -> None:
    meeting.end()
    conn.logger.bind(tag=TAG).info("会议模式已结束，开始生成会议纪要")
    summary_prompt = meeting.build_summary_prompt()
    transcript = meeting.build_transcript()
    future = conn.executor.submit(
        _generate_meeting_minutes,
        conn,
        summary_prompt,
        transcript,
    )
    future.add_done_callback(
        lambda f: conn.logger.bind(tag=TAG).error(
            f"会议纪要生成失败: {f.exception()}"
        )
        if f.exception()
        else None
    )


def _append_meeting_utterances(
    conn: "ConnectionHandler",
    meeting,
    payload,
    fallback_text,
    fallback_speaker,
) -> None:
    if isinstance(payload, dict) and isinstance(payload.get("utterances"), list):
        appended = 0
        utterances = payload["utterances"]
        for item in payload["utterances"]:
            if not isinstance(item, dict):
                continue
            text = str(item.get("text") or "").strip()
            if not text:
                continue
            meeting.append_utterance(
                text,
                speaker_label=_meeting_speaker_label(item),
                resolved_speaker=item.get("resolved_speaker")
                or (fallback_speaker if len(utterances) == 1 else None),
                timestamp=_meeting_timestamp(item),
                duration_ms=_meeting_duration_ms(item),
            )
            appended += 1
        if appended:
            conn.logger.bind(tag=TAG).debug(f"会议模式记录分段数量: {appended}")
            return

    text = str(fallback_text or "").strip()
    if not text:
        return
    meeting.append_utterance(
        text,
        speaker_label=None,
        resolved_speaker=fallback_speaker,
    )
    conn.logger.bind(tag=TAG).debug("会议模式记录普通语音分段")


def _meeting_speaker_label(item) -> str:
    for key in (
        "provider_speaker",
        "speaker_label",
        "speakerLabel",
        "speaker",
        "speaker_id",
        "speakerId",
        "role",
    ):
        value = item.get(key)
        if value is not None:
            return str(value)
    return ""


def _meeting_timestamp(item):
    for key in ("start_time", "startTime", "start_ms", "startMs", "begin_time", "beginTime"):
        value = item.get(key)
        if value is not None:
            return value
    return None


def _meeting_duration_ms(item):
    start = _coerce_float(
        _first_present(item, "start_time", "startTime", "start_ms", "startMs", "start")
    )
    end = _coerce_float(
        _first_present(item, "end_time", "endTime", "end_ms", "endMs", "end")
    )
    if start is None or end is None or end < start:
        return None
    return end - start


def _first_present(payload, *keys):
    for key in keys:
        value = payload.get(key)
        if value is not None:
            return value
    return None


def _coerce_float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _generate_meeting_minutes(
    conn: "ConnectionHandler",
    prompt: str,
    transcript,
) -> None:
    if not transcript:
        _enqueue_tts_text(conn, "本次会议没有记录到有效发言，无法生成会议纪要。")
        return
    if conn.llm is None:
        _enqueue_tts_text(conn, "会议已结束，但当前没有配置大模型，无法生成会议纪要。")
        return

    system_prompt = (
        "你是专业的会议纪要助手。只基于用户提供的逐句记录整理会议纪要，"
        "不得编造没有出现的信息。"
    )
    try:
        summary = conn.llm.response_no_stream(system_prompt, prompt)
    except Exception as error:
        conn.logger.bind(tag=TAG).error(
            "会议纪要LLM生成失败: errorType={}".format(type(error).__name__)
        )
        _enqueue_tts_text(conn, "会议纪要生成失败，请稍后重试。")
        return

    summary = str(summary or "").strip()
    if not summary:
        summary = "会议已结束，但大模型没有返回有效会议纪要。"
    _enqueue_tts_text(conn, summary)


def _enqueue_tts_text(conn: "ConnectionHandler", text: str) -> None:
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
