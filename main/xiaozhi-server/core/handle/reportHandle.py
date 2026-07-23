"""
TTS上报功能由 ReportThreadManager 管理。

上报功能包括：
1. 每个连接对象通过 ReportThreadManager 管理自己的上报队列和处理线程
2. 上报线程的生命周期与连接对象绑定
3. 使用 enqueue_tts_report / enqueue_tool_report 函数进行上报

具体实现请参考 core/handle/reportThreadManager.py。
"""

import time
import json
import opuslib_next
import threading
from typing import TYPE_CHECKING
from core.game import GameSessionManager

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

from config.manage_api_client import report as manage_report
from config.manage_api_client import report_memory_event
from config.manage_api_client import build_memory_event_id

TAG = __name__


async def report(conn: "ConnectionHandler", type, text, opus_data, report_time, metrics=None):
    """执行聊天记录上报操作

    Args:
        conn: 连接对象
        type: 上报类型，1为用户，2为智能体，3为工具调用
        text: 合成文本
        opus_data: opus音频数据
        report_time: 上报时间
    """
    audio_data = None
    try:
        if opus_data:
            audio_data = opus_to_wav(conn, opus_data)
        await manage_report(
            mac_address=conn.device_id,
            session_id=conn.session_id,
            chat_type=type,
            content=text,
            audio=audio_data,
            report_time=report_time,
            metrics=metrics,
        )
    except Exception as e:
        conn.logger.bind(tag=TAG).error(f"聊天记录上报失败: {e}")

    try:
        if _consume_skip_memory_event(conn, type, text):
            conn.logger.bind(tag=TAG).debug("长期记忆事件已按运行时标记跳过")
            return
        event_type = "tool" if type == 3 else "chat"
        agent_id = conn.config.get("agent_id") if getattr(conn, "config", None) else None
        memory_text, memory_json, speaker_id = _memory_event_content(type, text)
        logical_event_time = _logical_event_time(report_time, metrics)
        await report_memory_event(
            mac_address=conn.device_id,
            session_id=conn.session_id,
            chat_type=type,
            content=memory_text,
            content_json=memory_json,
            speaker_id=speaker_id,
            event_type=event_type,
            report_time=logical_event_time,
            agent_id=agent_id,
            event_id=build_memory_event_id(
                conn.device_id,
                conn.session_id,
                type,
                event_type,
                text,
                logical_event_time,
            ),
        )
    except Exception as e:
        conn.logger.bind(tag=TAG).error(f"长期记忆事件上报失败: {e}")


def _logical_event_time(report_time, metrics):
    if not isinstance(metrics, dict):
        return report_time
    try:
        started_at = int(metrics.get("started_at_epoch_ms"))
        sequence = max(0, int(metrics.get("message_sequence") or 0))
    except (TypeError, ValueError):
        return report_time
    if started_at <= 0:
        return report_time
    return started_at + sequence


def _memory_event_content(chat_type, text):
    """从声纹增强消息中拆出可维护文本和稳定说话人ID。"""
    if chat_type != 1 or not isinstance(text, str):
        return text, None, None
    try:
        payload = json.loads(text)
    except (TypeError, json.JSONDecodeError):
        return text, None, None
    if not isinstance(payload, dict) or "content" not in payload:
        return text, None, None
    content = str(payload.get("content") or "").strip()
    speaker_id = str(payload.get("speaker_id") or "").strip() or None
    memory_payload = dict(payload)
    memory_payload.pop("context", None)
    return content, memory_payload, speaker_id


def mark_asr_report_text_override(conn: "ConnectionHandler", original_text, report_text) -> None:
    if original_text == report_text:
        return
    _append_report_marker(
        conn,
        "_asr_report_text_overrides",
        {
            "original": str(original_text),
            "replacement": str(report_text),
        },
    )


def mark_skip_memory_event(conn: "ConnectionHandler", chat_type, text, reason: str = "") -> None:
    content, _payload, _speaker_id = _memory_event_content(chat_type, text)
    _append_report_marker(
        conn,
        "_skip_memory_events",
        {
            "chat_type": chat_type,
            "text": str(text),
            "content": str(content),
            "reason": reason,
        },
    )


def _append_report_marker(conn, attr: str, marker: dict) -> None:
    lock = _report_marker_lock(conn)
    with lock:
        values = list(getattr(conn, attr, []) or [])
        values.append(marker)
        setattr(conn, attr, values[-100:])


def _report_marker_lock(conn):
    lock = getattr(conn, "_report_marker_lock", None)
    if lock is None:
        lock = threading.Lock()
        setattr(conn, "_report_marker_lock", lock)
    return lock


def _consume_asr_report_text_override(conn, text):
    lock = _report_marker_lock(conn)
    with lock:
        values = list(getattr(conn, "_asr_report_text_overrides", []) or [])
        for index, item in enumerate(values):
            if item.get("original") == str(text):
                replacement = item.get("replacement") or text
                del values[index]
                setattr(conn, "_asr_report_text_overrides", values)
                return replacement
    return text


def _consume_skip_memory_event(conn, chat_type, text) -> bool:
    content, _payload, _speaker_id = _memory_event_content(chat_type, text)
    lock = _report_marker_lock(conn)
    with lock:
        values = list(getattr(conn, "_skip_memory_events", []) or [])
        for index, item in enumerate(values):
            if item.get("chat_type") != chat_type:
                continue
            if item.get("text") == str(text) or item.get("content") == str(content):
                del values[index]
                setattr(conn, "_skip_memory_events", values)
                return True
    return False


def opus_to_wav(conn: "ConnectionHandler", opus_data):
    """将Opus数据转换为WAV格式的字节流

    Args:
        output_dir: 输出目录（保留参数以保持接口兼容）
        opus_data: opus音频数据

    Returns:
        bytes: WAV格式的音频数据
    """
    decoder = None
    try:
        decoder = opuslib_next.Decoder(16000, 1)  # 16kHz, 单声道
        pcm_data = []

        for opus_packet in opus_data:
            try:
                pcm_frame = decoder.decode(opus_packet, 960)  # 960 samples = 60ms
                pcm_data.append(pcm_frame)
            except opuslib_next.OpusError as e:
                conn.logger.bind(tag=TAG).error(f"Opus解码错误: {e}", exc_info=True)

        if not pcm_data:
            raise ValueError("没有有效的PCM数据")

        # 创建WAV文件头
        pcm_data_bytes = b"".join(pcm_data)
        num_samples = len(pcm_data_bytes) // 2  # 16-bit samples

        # WAV文件头
        wav_header = bytearray()
        wav_header.extend(b"RIFF")  # ChunkID
        wav_header.extend((36 + len(pcm_data_bytes)).to_bytes(4, "little"))  # ChunkSize
        wav_header.extend(b"WAVE")  # Format
        wav_header.extend(b"fmt ")  # Subchunk1ID
        wav_header.extend((16).to_bytes(4, "little"))  # Subchunk1Size
        wav_header.extend((1).to_bytes(2, "little"))  # AudioFormat (PCM)
        wav_header.extend((1).to_bytes(2, "little"))  # NumChannels
        wav_header.extend((16000).to_bytes(4, "little"))  # SampleRate
        wav_header.extend((32000).to_bytes(4, "little"))  # ByteRate
        wav_header.extend((2).to_bytes(2, "little"))  # BlockAlign
        wav_header.extend((16).to_bytes(2, "little"))  # BitsPerSample
        wav_header.extend(b"data")  # Subchunk2ID
        wav_header.extend(len(pcm_data_bytes).to_bytes(4, "little"))  # Subchunk2Size

        # 返回完整的WAV数据
        return bytes(wav_header) + pcm_data_bytes
    finally:
        if decoder is not None:
            try:
                del decoder
            except Exception as e:
                conn.logger.bind(tag=TAG).debug(f"释放decoder资源时出错: {e}")


def enqueue_tts_report(conn: "ConnectionHandler", text, opus_data, metrics=None):
    if not conn.read_config_from_api or conn.need_bind or not conn.report_tts_enable:
        return
    if conn.chat_history_conf == 0:
        return
    """将TTS数据加入上报队列

    Args:
        conn: 连接对象
        text: 合成文本
        opus_data: opus音频数据
    """
    try:
        # 使用连接对象的队列，传入文本和二进制数据而非文件路径
        if conn.chat_history_conf == 2:
            conn.report_queue.put((2, text, opus_data, int(time.time() * 1000), metrics))
            conn.logger.bind(tag=TAG).debug(
                f"TTS数据已加入上报队列: {conn.device_id}, 音频大小: {len(opus_data)} "
            )
        else:
            conn.report_queue.put((2, text, None, int(time.time() * 1000), metrics))
            conn.logger.bind(tag=TAG).debug(
                f"TTS数据已加入上报队列: {conn.device_id}, 不上报音频"
            )
    except Exception as e:
        conn.logger.bind(tag=TAG).error(f"加入TTS上报队列失败: {text}, {e}")


def enqueue_tool_report(
    conn: "ConnectionHandler",
    tool_name: str,
    tool_input: dict,
    tool_result: str = None,
    report_tool_call: bool = True,
    metrics=None,
    turn_id=None,
):
    """将工具调用数据加入上报队列

    Args:
        conn: 连接对象
        tool_name: 工具名称
        tool_input: 工具输入参数
        tool_result: 工具执行结果（可选）
        report_tool_call: 是否上报工具调用本身，默认True；仅上报结果时设为False
    """
    if not conn.read_config_from_api or conn.need_bind:
        return
    if conn.chat_history_conf == 0:
        return
    if _is_stateful_game_tool(tool_name):
        return

    try:
        timestamp = int(time.time() * 1000)

        # 构建工具调用内容
        if report_tool_call:
            call_metrics = metrics or _build_link_metrics(
                conn, "tool", turn_id=turn_id
            )
            tool_text = json.dumps(
                [
                    {
                        "type": "tool",
                        "text": f"{tool_name}({json.dumps(tool_input, ensure_ascii=False)})",
                    }
                ]
            )
            if _is_meeting_tool(tool_name):
                mark_skip_memory_event(conn, 3, tool_text, "meeting_tool_call")
            conn.report_queue.put((3, tool_text, None, timestamp, call_metrics))

        # 构建工具结果内容
        if tool_result:
            result_metrics = metrics or _build_link_metrics(
                conn, "tool", turn_id=turn_id
            )
            try:
                result_display_value = json.loads(tool_result) if isinstance(tool_result, str) else tool_result
            except (TypeError, json.JSONDecodeError):
                result_display_value = {"result": str(tool_result)}
            result_display = json.dumps(result_display_value, ensure_ascii=False)
            result_content = json.dumps([{"type": "tool_result", "text": result_display}], ensure_ascii=False)
            if _is_meeting_tool(tool_name):
                mark_skip_memory_event(conn, 3, result_content, "meeting_tool_result")
            conn.report_queue.put(
                (3, result_content, None, timestamp + 1, result_metrics)
            )
    except Exception as e:
        conn.logger.bind(tag=TAG).error(f"加入工具上报队列失败: {e}")


def _build_link_metrics(conn, message_role, turn_id=None, sentence_id=None):
    turn_metrics = getattr(conn, "turn_metrics", None)
    if turn_metrics is None:
        return None
    return turn_metrics.build_link_metrics(
        message_role,
        turn_id=turn_id,
        sentence_id=sentence_id,
    )


def _is_stateful_game_tool(tool_name):
    return (
        tool_name in GameSessionManager.GUESS_MY_WORD_TOOLS
        or tool_name in GameSessionManager.USER_DESCRIBES_TOOLS
        or tool_name in GameSessionManager.IDIOM_CHAIN_TOOLS
    )


def _is_meeting_tool(tool_name):
    return str(tool_name or "") == "meeting_minutes"


def enqueue_asr_report(conn: "ConnectionHandler", text, opus_data, metrics=None):
    if not conn.read_config_from_api or conn.need_bind or not conn.report_asr_enable:
        return
    if conn.chat_history_conf == 0:
        return
    """将ASR数据加入上报队列

    Args:
        conn: 连接对象
        text: 合成文本
        opus_data: opus音频数据
    """
    try:
        text = _consume_asr_report_text_override(conn, text)
        # 使用连接对象的队列，传入文本和二进制数据而非文件路径
        if conn.chat_history_conf == 2:
            conn.report_queue.put((1, text, opus_data, int(time.time() * 1000), metrics))
            conn.logger.bind(tag=TAG).debug(
                f"ASR数据已加入上报队列: {conn.device_id}, 音频大小: {len(opus_data)} "
            )
        else:
            conn.report_queue.put((1, text, None, int(time.time() * 1000), metrics))
            conn.logger.bind(tag=TAG).debug(
                f"ASR数据已加入上报队列: {conn.device_id}, 不上报音频"
            )
    except Exception as e:
        conn.logger.bind(tag=TAG).debug(
            "加入ASR上报队列失败: "
            f"text_length={len(str(text or ''))}, error={e}"
        )
