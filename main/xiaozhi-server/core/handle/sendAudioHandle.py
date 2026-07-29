import json
import math
import time
import asyncio
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
from core.utils import textUtils
from core.utils.util import audio_to_data
from core.providers.tts.dto.dto import SentenceType
from core.utils.audioRateController import AudioRateController

TAG = __name__
# 音频帧时长（毫秒）
AUDIO_FRAME_DURATION = 60
# 预缓冲包数量，直接发送以减少延迟
PRE_BUFFER_COUNT = 5


async def sendAudioMessage(
    conn: "ConnectionHandler",
    sentenceType,
    audios,
    text,
    sentence_id=None,
    *,
    generation=None,
    terminal_reason=None,
):
    # 跳过旧句子残留音频
    if sentence_id is not None and sentence_id != conn.sentence_id:
        return False

    reply_lifecycle = getattr(conn, "reply_lifecycle", None)
    if generation is not None and reply_lifecycle is not None:
        snapshot = reply_lifecycle.snapshot()
        if (
            not snapshot.is_active
            or snapshot.generation != generation
            or snapshot.sentence_id not in {None, sentence_id}
        ):
            return False

    if conn.tts.tts_audio_first_sentence:
        log_text = getattr(conn.tts, "_text_for_log", lambda value, _sid: value)(
            text, sentence_id
        )
        conn.logger.bind(tag=TAG).info(f"发送第一段语音: {log_text}")
        conn.tts.tts_audio_first_sentence = False

    protocol_sentence_id = sentence_id or conn.sentence_id
    if sentenceType == SentenceType.FIRST:
        if reply_lifecycle is not None:
            if generation is None:
                active_generation = reply_lifecycle.ensure_started(
                    protocol_sentence_id
                )
            else:
                active_generation = generation
            if not reply_lifecycle.bind_sentence(
                active_generation, protocol_sentence_id
            ):
                return False
            reply_lifecycle.mark_synthesizing(active_generation)
        is_new_protocol_sentence = (
            getattr(conn, "_tts_protocol_sentence_id", None) != protocol_sentence_id
        )
        if is_new_protocol_sentence:
            # 官方在 STT 后已发送 TTS start，此时 sentence_id 尚未生成。
            # 首包到达时将已发送的 start 绑定到当前句子，避免重复发送。
            if (
                getattr(conn, "_tts_protocol_sentence_id", None) is None
                and getattr(conn, "_tts_protocol_start_sent", False)
                and not getattr(conn, "_tts_protocol_stop_sent", False)
            ):
                conn._tts_protocol_sentence_id = protocol_sentence_id
            else:
                conn._tts_protocol_sentence_id = protocol_sentence_id
                conn._tts_protocol_start_sent = False
                conn._tts_protocol_stop_sent = False
        # 直接 TTS 等未经 STT 的路径仍由首包补发 start。
        start_sent_with_first_audio = await send_tts_message(
            conn,
            "start",
            protocol_sentence_id=protocol_sentence_id,
            protocol_generation=generation,
        )
        if start_sent_with_first_audio and reply_lifecycle is None:
            conn.client_is_speaking = True
        # 这里是真实首音频边界；STT 后的 start 只表示回复可打断。
        conn._tts_started_at = time.time() * 1000
        mark_report_started = getattr(conn.tts, "mark_report_started", None)
        if callable(mark_report_started):
            mark_report_started(protocol_sentence_id)
        if is_new_protocol_sentence or start_sent_with_first_audio:
            # 同一句子的后续消息加入流控队列，其他情况立即发送
            if (
                hasattr(conn, "audio_rate_controller")
                and conn.audio_rate_controller
                and getattr(conn, "audio_flow_control", {}).get("sentence_id")
                == conn.sentence_id
            ):
                conn.audio_rate_controller.add_message(
                    lambda: send_tts_message(conn, "sentence_start", text)
                )
            else:
                # 新句子或流控器未初始化，立即发送
                await send_tts_message(conn, "sentence_start", text)

    await sendAudio(conn, audios)
    if audios:
        if reply_lifecycle is not None:
            reply_lifecycle.mark_streaming(generation)
        external_tts = getattr(conn, "external_tts_coordinator", None)
        if external_tts is not None:
            external_tts.mark_streaming(conn, protocol_sentence_id)
    # 发送句子开始消息
    if sentenceType is not SentenceType.MIDDLE:
        log_text = getattr(conn.tts, "_text_for_log", lambda value, _sid: value)(
            text, sentence_id
        )
        conn.logger.bind(tag=TAG).info(
            f"发送音频消息: {sentenceType}, {log_text}"
        )

    # 发送结束消息（如果是最后一个文本）
    # 通话需要维持speaking状态
    if not conn.calling and sentenceType == SentenceType.LAST:
        await send_tts_message(
            conn,
            "stop",
            None,
            protocol_sentence_id=protocol_sentence_id,
            protocol_generation=generation,
            reason=terminal_reason,
            allow_pending_stop=bool(terminal_reason),
        )
        external_tts = getattr(conn, "external_tts_coordinator", None)
        if external_tts is not None:
            if terminal_reason:
                external_tts.mark_failed(
                    conn, protocol_sentence_id, terminal_reason
                )
            else:
                external_tts.mark_completed(conn, protocol_sentence_id)
        if conn.close_after_chat:
            if (getattr(conn, "features", None) or {}).get(
                "persistent_websocket"
            ) is True:
                # 新固件声明可复用控制通道时，退出只结束当前音频/对话轮次。
                # WebSocket 保持连接，避免设备把服务端主动关闭误报成 TLS
                # 接收错误后立即重连。未声明能力的旧固件维持原有行为。
                conn.close_after_chat = False
                conn.client_abort = False
                conn.reset_audio_states()
                conn.logger.bind(tag=TAG).info(
                    "退出会话已完成，按客户端能力保持WebSocket连接"
                )
            else:
                await conn.close()
    return True


async def _wait_for_audio_completion(conn: "ConnectionHandler"):
    """
    等待音频队列清空并等待预缓冲包播放完成

    Args:
        conn: 连接对象
    """
    if hasattr(conn, "audio_rate_controller") and conn.audio_rate_controller:
        rate_controller = conn.audio_rate_controller
        conn.logger.bind(tag=TAG).debug(
            f"等待音频发送完成，队列中还有 {len(rate_controller.queue)} 个包"
        )
        if rate_controller.last_send_error is not None:
            error = rate_controller.last_send_error
            rate_controller.reset()
            raise RuntimeError(
                "音频发送失败，已清空发送队列: "
                f"{type(error).__name__}"
            ) from error
        timeout_seconds = _audio_completion_timeout(conn, rate_controller)
        try:
            await asyncio.wait_for(
                rate_controller.queue_empty_event.wait(),
                timeout=timeout_seconds,
            )
        except asyncio.TimeoutError as exc:
            rate_controller.reset()
            raise TimeoutError(
                "等待音频发送完成超时"
                f"（{timeout_seconds:.1f}s），已清空发送队列"
            ) from exc

        if rate_controller.last_send_error is not None:
            error = rate_controller.last_send_error
            rate_controller.reset()
            raise RuntimeError(
                "音频发送失败，已清空发送队列: "
                f"{type(error).__name__}"
            ) from error

        # 等待预缓冲包播放完成
        # 前N个包直接发送，增加2个网络抖动包，需要额外等待它们在客户端播放完成
        frame_duration_ms = rate_controller.frame_duration
        pre_buffer_playback_time = (PRE_BUFFER_COUNT + 2) * frame_duration_ms / 1000.0
        await asyncio.sleep(pre_buffer_playback_time)

        conn.logger.bind(tag=TAG).debug("音频发送完成")


def _positive_timeout(value, field_name):
    try:
        timeout = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{field_name} 必须是大于 0 的数字") from exc
    if not math.isfinite(timeout) or timeout <= 0:
        raise ValueError(f"{field_name} 必须是大于 0 的数字")
    return timeout


def _audio_send_timeout(conn: "ConnectionHandler"):
    tts = getattr(conn, "tts", None)
    configured_timeout = getattr(tts, "audio_send_timeout_seconds", None)
    if configured_timeout is None:
        configured_timeout = conn.config.get("tts_audio_send_timeout_seconds", 5)
    return _positive_timeout(
        configured_timeout,
        "tts_audio_send_timeout_seconds",
    )


def _audio_completion_timeout(conn: "ConnectionHandler", rate_controller=None):
    """Return a duration-aware queue drain budget without a playback cap."""
    completion_grace = _positive_timeout(
        conn.config.get("tts_audio_completion_grace_seconds", 5),
        "tts_audio_completion_grace_seconds",
    )
    audio_send_timeout = _audio_send_timeout(conn)
    queued_playback_seconds = 0.0
    if rate_controller is not None:
        queued_playback_seconds = (
            len(rate_controller.queue) * rate_controller.frame_duration / 1000.0
        )
    # The queue excludes a packet that is already being sent, so reserve one
    # per-packet send timeout in addition to the remaining playback duration.
    return queued_playback_seconds + audio_send_timeout + completion_grace


async def _send_to_mqtt_gateway(
    conn: "ConnectionHandler", opus_packet, timestamp, sequence
):
    """
    发送带16字节头部的opus数据包给mqtt_gateway
    Args:
        conn: 连接对象
        opus_packet: opus数据包
        timestamp: 时间戳
        sequence: 序列号
    """
    # 为opus数据包添加16字节头部
    header = bytearray(16)
    header[0] = 1  # type
    header[2:4] = len(opus_packet).to_bytes(2, "big")  # payload length
    header[4:8] = sequence.to_bytes(4, "big")  # sequence
    header[8:12] = timestamp.to_bytes(4, "big")  # 时间戳
    header[12:16] = len(opus_packet).to_bytes(4, "big")  # opus长度

    # 发送包含头部的完整数据包
    complete_packet = bytes(header) + opus_packet
    await conn.websocket.send(complete_packet)


async def sendAudio(
    conn: "ConnectionHandler", audios, frame_duration=AUDIO_FRAME_DURATION
):
    """
    发送音频包，使用 AudioRateController 进行精确的流量控制

    Args:
        conn: 连接对象
        audios: 单个opus包(bytes) 或 opus包列表
        frame_duration: 帧时长（毫秒），默认使用全局常量AUDIO_FRAME_DURATION
    """
    if audios is None or len(audios) == 0:
        return

    send_delay = conn.config.get("tts_audio_send_delay", -1) / 1000.0
    is_single_packet = isinstance(audios, bytes)

    # 初始化或获取 RateController
    rate_controller, flow_control = _get_or_create_rate_controller(
        conn, frame_duration, is_single_packet
    )

    # 统一转换为列表处理
    audio_list = [audios] if is_single_packet else audios

    # 发送音频包
    await _send_audio_with_rate_control(
        conn, audio_list, rate_controller, flow_control, send_delay
    )


def _get_or_create_rate_controller(
    conn: "ConnectionHandler", frame_duration, is_single_packet
):
    """
    获取或创建 RateController 和 flow_control

    Args:
        conn: 连接对象
        frame_duration: 帧时长
        is_single_packet: 是否单包模式（True: TTS流式单包, False: 批量包）

    Returns:
        (rate_controller, flow_control)
    """
    # 检查是否需要重置控制器
    need_reset = False

    if not hasattr(conn, "audio_rate_controller"):
        # 控制器不存在，需要创建
        need_reset = True
    else:
        rate_controller = conn.audio_rate_controller

        # 后台发送任务已停止, 则需要重置
        if (
            not rate_controller.pending_send_task
            or rate_controller.pending_send_task.done()
        ):
            need_reset = True
        # 当sentence_id 变化，需要重置
        elif (
            getattr(conn, "audio_flow_control", {}).get("sentence_id")
            != conn.sentence_id
        ):
            need_reset = True

    if need_reset:
        # 创建或获取 rate_controller
        if not hasattr(conn, "audio_rate_controller"):
            conn.audio_rate_controller = AudioRateController(frame_duration)
        else:
            conn.audio_rate_controller.reset()

        # 初始化 flow_control
        conn.audio_flow_control = {
            "packet_count": 0,
            "sequence": 0,
            "sentence_id": conn.sentence_id,
        }

        # 启动后台发送循环
        _start_background_sender(
            conn, conn.audio_rate_controller, conn.audio_flow_control
        )

    return conn.audio_rate_controller, conn.audio_flow_control


def _start_background_sender(conn: "ConnectionHandler", rate_controller, flow_control):
    """
    启动后台发送循环任务

    Args:
        conn: 连接对象
        rate_controller: 速率控制器
        flow_control: 流控状态
    """

    async def send_callback(packet):
        # 检查是否应该中止
        if conn.client_abort:
            raise asyncio.CancelledError("客户端已中止")

        conn.last_activity_time = time.time() * 1000
        timeout_seconds = _audio_send_timeout(conn)
        try:
            await asyncio.wait_for(
                _do_send_audio(conn, packet, flow_control),
                timeout=timeout_seconds,
            )
        except asyncio.TimeoutError as exc:
            raise TimeoutError(
                f"发送单个音频包超时（{timeout_seconds:.1f}s）"
            ) from exc

    # 使用 start_sending 启动后台循环
    rate_controller.start_sending(send_callback)


async def _send_audio_with_rate_control(
    conn: "ConnectionHandler", audio_list, rate_controller, flow_control, send_delay
):
    """
    使用 rate_controller 发送音频包

    Args:
        conn: 连接对象
        audio_list: 音频包列表
        rate_controller: 速率控制器
        flow_control: 流控状态
        send_delay: 固定延迟（秒），-1表示使用动态流控
    """
    for packet in audio_list:
        if conn.client_abort:
            return

        conn.last_activity_time = time.time() * 1000

        # 预缓冲：前N个包直接发送
        if flow_control["packet_count"] < PRE_BUFFER_COUNT:
            await _do_send_audio(conn, packet, flow_control)
        elif send_delay > 0:
            # 固定延迟模式
            await asyncio.sleep(send_delay)
            await _do_send_audio(conn, packet, flow_control)
        else:
            # 动态流控模式：仅添加到队列，由后台循环负责发送
            rate_controller.add_audio(packet)


async def _do_send_audio(conn: "ConnectionHandler", opus_packet, flow_control):
    """
    执行实际的音频发送
    """
    packet_index = flow_control.get("packet_count", 0)
    sequence = flow_control.get("sequence", 0)

    if conn.conn_from_mqtt_gateway:
        # 计算时间戳（基于播放位置）
        start_time = time.time()
        timestamp = int(start_time * 1000) % (2**32)
        await _send_to_mqtt_gateway(conn, opus_packet, timestamp, sequence)
    else:
        # 直接发送opus数据包
        await conn.websocket.send(opus_packet)

    # 更新流控状态
    flow_control["packet_count"] = packet_index + 1
    flow_control["sequence"] = sequence + 1


async def send_tts_message(
    conn: "ConnectionHandler",
    state,
    text=None,
    *,
    protocol_sentence_id=None,
    protocol_generation=None,
    drain_audio=True,
    reason=None,
    allow_pending_stop=False,
):
    """发送 TTS 状态消息"""
    reply_lifecycle = getattr(conn, "reply_lifecycle", None)
    reply_generation = None
    if text is None and state == "sentence_start":
        return False
    if state == "start":
        if protocol_generation is not None and reply_lifecycle is not None:
            snapshot = reply_lifecycle.snapshot()
            if (
                not snapshot.is_active
                or snapshot.generation != protocol_generation
                or snapshot.sentence_id not in {
                    None,
                    protocol_sentence_id,
                }
            ):
                return False
        current_protocol_sentence_id = getattr(
            conn, "_tts_protocol_sentence_id", None
        )
        start_sent = getattr(conn, "_tts_protocol_start_sent", False)
        stop_sent = getattr(conn, "_tts_protocol_stop_sent", False)
        same_pending_or_sentence = (
            current_protocol_sentence_id == protocol_sentence_id
            or current_protocol_sentence_id is None
            or protocol_sentence_id is None
        )
        if start_sent and not stop_sent and same_pending_or_sentence:
            return False
        if reply_lifecycle is not None:
            reply_generation = protocol_generation
            if reply_generation is None:
                reply_generation = reply_lifecycle.ensure_started(
                    protocol_sentence_id
                )
            if protocol_sentence_id:
                if not reply_lifecycle.bind_sentence(
                    reply_generation, protocol_sentence_id
                ):
                    return False
        conn._tts_protocol_sentence_id = protocol_sentence_id
        conn._tts_protocol_start_sent = True
        conn._tts_protocol_stop_sent = False
    elif state == "stop":
        resolved_sentence_id = protocol_sentence_id or getattr(
            conn, "sentence_id", None
        )
        current_protocol_sentence_id = getattr(
            conn, "_tts_protocol_sentence_id", None
        )
        # STT start uses a pending (None) sentence id until the next FIRST binds it.
        # A delayed provider LAST must not stop that new reply. Explicit abort or
        # failure convergence is allowed to close the pending start itself.
        if (
            protocol_sentence_id is not None
            and current_protocol_sentence_id is None
            and getattr(conn, "_tts_protocol_start_sent", False)
            and not getattr(conn, "_tts_protocol_stop_sent", False)
            and not allow_pending_stop
        ):
            return False
        if (
            getattr(conn, "_tts_protocol_stop_sent", False)
            and (
                current_protocol_sentence_id == resolved_sentence_id
                or current_protocol_sentence_id is None
            )
        ):
            return False
        if reply_lifecycle is not None:
            snapshot = reply_lifecycle.snapshot()
            if protocol_generation is not None and (
                not snapshot.is_active
                or snapshot.generation != protocol_generation
                or snapshot.sentence_id not in {None, resolved_sentence_id}
            ):
                return False
            if snapshot.is_active and snapshot.sentence_id in {None, resolved_sentence_id}:
                reply_generation = snapshot.generation
                reply_lifecycle.mark_stopping(reply_generation)
        conn._tts_protocol_sentence_id = resolved_sentence_id
        conn._tts_protocol_stop_sent = True
    message = {"type": "tts", "state": state, "session_id": conn.session_id}
    if text is not None:
        message["text"] = textUtils.check_emoji(text)
    if reason:
        message["reason"] = reason

    # TTS播放结束
    if state == "stop":
        # 保存当前的 sentence_id，用于后续判断是否是当前轮次
        current_sentence_id = conn.sentence_id
        # 播放提示音
        tts_notify = conn.config.get("enable_stop_tts_notify", False)
        if tts_notify:
            stop_tts_notify_voice = conn.config.get(
                "stop_tts_notify_voice", "config/assets/tts_notify.mp3"
            )
            audios = await audio_to_data(stop_tts_notify_voice, is_opus=True)
            await sendAudio(conn, audios)
        # 等待所有音频包发送完成。即使发送失败或超时，也要继续
        # 收敛服务端 speaking 状态并尽力通知设备 stop。
        completion_error = None
        if drain_audio:
            try:
                await _wait_for_audio_completion(conn)
            except (TimeoutError, RuntimeError) as exc:
                completion_error = exc

        # 检查是否是当前轮次
        if current_sentence_id != conn.sentence_id:
            return

        # 停止音频发送循环（仅在流控器已初始化时调用）
        if hasattr(conn, "audio_rate_controller") and conn.audio_rate_controller:
            conn.audio_rate_controller.stop_sending()
    # 发送消息到客户端，并让协议终态与同一 generation 一起收敛。
    try:
        await conn.websocket.send(json.dumps(message))
    except Exception:
        if reply_lifecycle is not None and reply_generation is not None:
            reply_lifecycle.fail(reply_generation, "tts_protocol_send_failed")
        raise
    if state == "stop" and reply_lifecycle is not None and reply_generation is not None:
        if reason == "abort":
            reply_lifecycle.interrupt(reply_generation, reason)
        elif reason:
            reply_lifecycle.fail(reply_generation, reason)
        else:
            reply_lifecycle.complete(reply_generation, "tts_stop")
    elif state == "stop" and reply_lifecycle is None:
        conn.clearSpeakStatus()
    if state == "stop" and completion_error is not None:
        raise completion_error
    return True


async def send_stt_display(conn: "ConnectionHandler", text):
    """Send only the user transcript display; never starts a reply."""
    display_text = text
    try:
        if text.strip().startswith("{") and text.strip().endswith("}"):
            parsed_data = json.loads(text)
            if isinstance(parsed_data, dict) and "content" in parsed_data:
                display_text = parsed_data["content"]
                if "speaker" in parsed_data:
                    conn.current_speaker = parsed_data["speaker"]
    except (json.JSONDecodeError, TypeError):
        display_text = text
    stt_text = textUtils.get_string_no_punctuation_or_emoji(display_text)
    await conn.websocket.send(
        json.dumps({"type": "stt", "text": stt_text, "session_id": conn.session_id})
    )


async def begin_assistant_reply(
    conn: "ConnectionHandler",
    text,
    *,
    require_conversation=True,
    kind="assistant",
):
    """Validate capabilities, display STT, and create one tracked reply."""
    end_prompt_str = conn.config.get("end_prompt", {}).get("prompt")
    if end_prompt_str and end_prompt_str == text:
        await send_tts_message(conn, "start")
        lifecycle = getattr(conn, "reply_lifecycle", None)
        return lifecycle.generation if lifecycle is not None else None

    if getattr(conn, "tts", None) is None:
        conn.logger.bind(tag=TAG).warning("TTS未配置，拒绝启动回复轮次")
        return None
    if require_conversation:
        conversation_ready = getattr(conn, "conversation_ready", None)
        if conversation_ready is not None and not conversation_ready.is_set():
            conn.logger.bind(tag=TAG).warning("对话能力尚未就绪，拒绝启动回复轮次")
            return None
        if getattr(conn, "llm", None) is None:
            conn.logger.bind(tag=TAG).warning("LLM未配置，拒绝启动回复轮次")
            return None
    else:
        tts_ready = getattr(conn, "tts_ready", None)
        if tts_ready is not None and not tts_ready.is_set():
            conn.logger.bind(tag=TAG).warning("TTS能力尚未就绪，拒绝启动回复轮次")
            return None

    lifecycle = getattr(conn, "reply_lifecycle", None)
    generation = None
    try:
        if lifecycle is not None:
            generation = lifecycle.begin(kind=kind)
        await send_stt_display(conn, text)
        await send_tts_message(conn, "start", protocol_sentence_id=None)
        if lifecycle is None:
            conn.client_is_speaking = True
        return generation
    except Exception:
        if lifecycle is not None and generation is not None:
            lifecycle.fail(generation, "reply_start_failed")
        raise


async def send_stt_message(conn: "ConnectionHandler", text):
    """Compatibility wrapper for paths that immediately produce TTS."""
    if getattr(conn, "reply_lifecycle", None) is None:
        await send_stt_display(conn, text)
        await send_tts_message(conn, "start", protocol_sentence_id=None)
        conn.client_is_speaking = True
        return None
    return await begin_assistant_reply(
        conn, text, require_conversation=False, kind="direct_tts"
    )


async def terminate_pending_reply(
    conn: "ConnectionHandler", reason, *, generation=None
):
    """Converge one active generation after an async failure or silent branch."""
    lifecycle = getattr(conn, "reply_lifecycle", None)
    if lifecycle is not None:
        snapshot = lifecycle.snapshot()
        target_generation = generation or snapshot.generation
        if not snapshot.is_active or snapshot.generation != target_generation:
            return False
        sentence_id = snapshot.sentence_id or getattr(conn, "sentence_id", None)
    else:
        if not getattr(conn, "client_is_speaking", False):
            return False
        target_generation = None
        sentence_id = getattr(conn, "sentence_id", None)

    try:
        await send_tts_message(
            conn,
            "stop",
            protocol_sentence_id=sentence_id,
            drain_audio=False,
            reason=reason,
            allow_pending_stop=True,
        )
    finally:
        if lifecycle is None or lifecycle.is_current(target_generation):
            conn.clear_queues()
            if lifecycle is not None:
                lifecycle.fail(target_generation, reason)
            else:
                conn.clearSpeakStatus()
    return True


def schedule_reply_termination(conn, reason, *, generation=None):
    """Schedule generation-scoped convergence from loop or worker threads."""
    loop = getattr(conn, "loop", None)
    if loop is None or loop.is_closed():
        return None
    coroutine = terminate_pending_reply(
        conn, reason, generation=generation
    )
    try:
        running_loop = asyncio.get_running_loop()
    except RuntimeError:
        running_loop = None
    try:
        if running_loop is loop:
            future = loop.create_task(coroutine)
        else:
            future = asyncio.run_coroutine_threadsafe(coroutine, loop)
    except Exception:
        coroutine.close()
        return None

    def consume_result(item):
        try:
            item.result()
        except (asyncio.CancelledError, Exception):
            return

    future.add_done_callback(consume_result)
    return future


async def send_display_message(conn: "ConnectionHandler", text):
    """发送纯显示消息"""
    message = {
        "type": "stt",
        "text": text,
        "session_id": conn.session_id
    }
    await conn.websocket.send(json.dumps(message))
