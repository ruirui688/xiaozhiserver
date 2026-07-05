import asyncio
import base64
import os
import queue
import threading
import time

from core.utils.util import opus_datas_to_wav_bytes
from core.providers.tts.dto.dto import InterfaceType
from core.providers.tts.dto.dto import TTSMessageDTO
from core.utils.opus_encoder_utils import OpusEncoderUtils
from core.utils.tts import create_instance as create_tts_instance


AUDIO_VALID_BYTES = 256
MIN_PLAYABLE_AUDIO_MS = 500
STREAM_DRAIN_TIMEOUT_SECONDS = 5
STREAM_AUDIO_QUIET_SECONDS = 0.25
DEFAULT_TTS_SAMPLES = [
    "你好，我是小智。",
    "现在是上午八点三十分，天气晴。",
    "请注意，门口有人来了。",
]


async def run_tts_samples(provider_type, config, iterations):
    tts = create_tts_instance(provider_type, config, delete_audio_file=True)
    tts.conn = _BenchmarkTtsConn(sample_rate=int(config.get("sample_rate") or 24000))
    tts.tts_audio_queue = _BenchmarkAudioQueue()
    if not hasattr(tts, "opus_encoder") or tts.opus_encoder is None:
        tts.opus_encoder = OpusEncoderUtils(
            sample_rate=tts.conn.sample_rate,
            channels=1,
            frame_size_ms=60,
        )
    samples = []
    try:
        for index in range(iterations):
            text = DEFAULT_TTS_SAMPLES[index % len(DEFAULT_TTS_SAMPLES)]
            started = time.perf_counter()
            success = False
            message = "OK"
            audio_size = 0
            audio_data_url = ""
            audio_preview_format = ""
            audio_preview_playable = False
            audio_duration_ms = 0
            first_audio_ms = None
            output_file = None
            try:
                result, output_file, first_audio_ms = await _synthesize_tts_sample(tts, text, started)
                latency_ms = int((time.perf_counter() - started) * 1000)
                audio_result = _collect_tts_audio_result(result, output_file, tts)
                audio_size = audio_result["audio_size"]
                audio_data_url = audio_result["audio_data_url"]
                audio_preview_format = audio_result["audio_preview_format"]
                audio_preview_playable = audio_result["audio_preview_playable"]
                audio_duration_ms = audio_result["audio_duration_ms"]
                success = audio_size > AUDIO_VALID_BYTES and audio_preview_playable
                if audio_size <= AUDIO_VALID_BYTES:
                    message = "TTS 音频为空或过短"
                elif not audio_preview_playable:
                    message = "TTS 音频预览不可播放或时长过短"
            except Exception as exc:
                latency_ms = int((time.perf_counter() - started) * 1000)
                message = str(exc)
            finally:
                _remove_file_quietly(output_file)
            samples.append({
                "iteration": index + 1,
                "sample_type": "tts_audio",
                "success": success,
                "measured": success,
                "latency_ms": latency_ms,
                "first_audio_ms": first_audio_ms if success else None,
                "input_summary": text,
                "output_summary": f"音频字节数：{audio_size}",
                "process_steps": [
                    "使用固定中文播报文本调用 TTS provider",
                    "从返回值、输出文件和音频队列统计生成音频字节数",
                    "记录总耗时、首音频耗时和音频有效性",
                ],
                "input_detail": {
                    "text": text,
                    "sample_rate": getattr(tts.conn, "sample_rate", None),
                    "audio_format": getattr(tts.conn, "audio_format", None),
                    "resource_id": getattr(tts, "resource_id", None) or config.get("resource_id"),
                    "speaker": getattr(tts, "voice", None) or config.get("speaker") or config.get("voice") or config.get("voice_id"),
                    "audio_params": getattr(tts, "audio_params", None) or config.get("audio_params"),
                },
                "output_detail": {
                    "audio_bytes": audio_size,
                    "output_file_type": getattr(tts, "audio_file_type", None),
                    "audio_preview_format": audio_preview_format,
                    "audio_preview_playable": audio_preview_playable,
                    "audio_duration_ms": audio_duration_ms,
                    "audio_data_url": audio_data_url,
                    "valid_audio": success,
                    "first_audio_ms": first_audio_ms if success else None,
                },
                "message": message,
                "error_type": None if success else "EMPTY_AUDIO_OR_ERROR",
            })
    finally:
        tts.conn.stop_event.set()
        if hasattr(tts, "close"):
            try:
                await tts.close()
            except Exception:
                pass
    return samples


class _BenchmarkTtsConn:
    def __init__(self, sample_rate):
        self.sample_rate = sample_rate
        self.audio_format = "pcm"
        self.session_id = "benchmark"
        self.sentence_id = "benchmark"
        self.stop_event = threading.Event()
        self.client_abort = False
        self.tts_audio_queue = _BenchmarkAudioQueue()
        try:
            self.loop = asyncio.get_running_loop()
        except RuntimeError:
            self.loop = None


class _BenchmarkAudioQueue(queue.Queue):
    def __init__(self):
        super().__init__()
        self.first_audio_at = None

    def put(self, item, block=True, timeout=None):
        if self.first_audio_at is None and _nested_audio_size(item) > 0:
            self.first_audio_at = time.perf_counter()
        return super().put(item, block, timeout)


async def _synthesize_tts_sample(tts, text, started):
    interface_type = getattr(tts, "interface_type", None)
    if interface_type in (InterfaceType.DUAL_STREAM, InterfaceType.SINGLE_STREAM):
        result, first_audio_ms = await _synthesize_stream_tts_sample(tts, text, started)
        return result, None, first_audio_ms

    output_file = _generate_tts_output_file(tts)
    if output_file:
        os.makedirs(os.path.dirname(output_file), exist_ok=True)
    result = await tts.text_to_speak(text, output_file)
    return result, output_file, None


async def _synthesize_stream_tts_sample(tts, text, started):
    if hasattr(tts, "start_session") and hasattr(tts, "finish_session"):
        await tts.start_session(tts.conn.sentence_id)
        if hasattr(tts, "store_tts_text"):
            tts.store_tts_text(tts.conn.sentence_id, text)
        await tts.text_to_speak(text, None)
        await tts.finish_session(tts.conn.sentence_id)
        await _wait_for_stream_tts_audio(tts)
        session_error = getattr(tts, "_session_error", None)
        if session_error:
            raise RuntimeError(session_error)
        return b"", _first_tts_audio_ms(tts, started)

    result = await tts.text_to_speak(text, True)
    await _wait_for_stream_tts_audio(tts)
    return result, _first_tts_audio_ms(tts, started)


async def _wait_for_stream_tts_audio(tts):
    deadline = time.perf_counter() + STREAM_DRAIN_TIMEOUT_SECONDS
    started = time.perf_counter()
    last_size = 0
    last_change = started
    saw_audio = False
    while time.perf_counter() < deadline:
        now = time.perf_counter()
        current_size = _current_tts_audio_size(tts)
        if current_size > last_size:
            saw_audio = True
            last_size = current_size
            last_change = now

        session_active = getattr(tts, "activate_session", False)
        if saw_audio and not session_active and now - last_change >= STREAM_AUDIO_QUIET_SECONDS:
            return
        if not saw_audio and not session_active and now - started >= STREAM_AUDIO_QUIET_SECONDS:
            return
        await asyncio.sleep(0.05)


def _generate_tts_output_file(tts):
    if not hasattr(tts, "generate_filename"):
        return None
    extension = "." + str(getattr(tts, "audio_file_type", "wav") or "wav").lstrip(".")
    try:
        return tts.generate_filename(extension)
    except TypeError:
        return tts.generate_filename()


def _collect_tts_audio_result(result, output_file, tts):
    audio_bytes = b""
    audio_format = ""
    if output_file and os.path.exists(output_file):
        audio_bytes = _read_file_bytes(output_file)
        audio_format = _infer_audio_format(output_file, getattr(tts, "audio_file_type", None))
    elif isinstance(result, (bytes, bytearray)):
        audio_bytes = bytes(result)
        audio_format = _normalize_audio_format(getattr(tts, "audio_file_type", None))
    elif isinstance(result, str) and os.path.exists(result):
        audio_bytes = _read_file_bytes(result)
        audio_format = _infer_audio_format(result, getattr(tts, "audio_file_type", None))
    elif isinstance(result, (list, tuple, dict, TTSMessageDTO)) or (
        result is not None and (hasattr(result, "content_file") or hasattr(result, "content_detail"))
    ):
        result_audio = _nested_audio_chunks(result)
        if result_audio:
            preview_bytes = _opus_preview_to_wav(
                result_audio,
                getattr(getattr(tts, "conn", None), "sample_rate", 24000),
            )
            audio_bytes = preview_bytes or b"".join(result_audio)
            audio_format = "wav" if preview_bytes else "opus"

    queue_audio = _drain_tts_audio_queue_audio(getattr(tts, "tts_audio_queue", None))
    queue_audio += _drain_tts_audio_queue_audio(getattr(getattr(tts, "conn", None), "tts_audio_queue", None))
    queue_audio += _before_stop_play_files_audio(getattr(tts, "before_stop_play_files", None))
    queue_audio_size = sum(len(chunk) for chunk in queue_audio)
    if queue_audio_size > len(audio_bytes):
        preview_bytes = _opus_preview_to_wav(queue_audio, getattr(getattr(tts, "conn", None), "sample_rate", 24000))
        audio_bytes = preview_bytes or b"".join(queue_audio)
        audio_format = "wav" if preview_bytes else "opus"

    preview = _audio_preview_info(audio_bytes, audio_format)

    return {
        "audio_size": len(audio_bytes),
        "audio_preview_format": audio_format,
        "audio_preview_playable": preview["playable"],
        "audio_duration_ms": preview["duration_ms"],
        "audio_data_url": _build_audio_data_url(audio_bytes, audio_format) if preview["playable"] else "",
    }


def _drain_tts_audio_queue_audio(audio_queue):
    if audio_queue is None:
        return []
    chunks = []
    while True:
        try:
            item = audio_queue.get_nowait()
        except queue.Empty:
            break
        chunks.extend(_nested_audio_chunks(item))
    return chunks


def _nested_audio_size(value):
    return sum(len(item) for item in _nested_audio_chunks(value))


def _nested_audio_chunks(value):
    if isinstance(value, (bytes, bytearray)):
        return [bytes(value)]
    if isinstance(value, str):
        return [_read_file_bytes(value)] if os.path.exists(value) else []
    if isinstance(value, TTSMessageDTO):
        return _nested_audio_chunks(value.content_file) + _nested_audio_chunks(value.content_detail)
    if isinstance(value, (list, tuple)):
        chunks = []
        for item in value:
            chunks.extend(_nested_audio_chunks(item))
        return chunks
    if isinstance(value, dict):
        chunks = []
        for item in value.values():
            chunks.extend(_nested_audio_chunks(item))
        return chunks
    if value is not None and (hasattr(value, "content_file") or hasattr(value, "content_detail")):
        return (
            _nested_audio_chunks(getattr(value, "content_file", None))
            + _nested_audio_chunks(getattr(value, "content_detail", None))
        )
    return []


def _has_tts_audio(tts):
    return _current_tts_audio_size(tts) > 0


def _current_tts_audio_size(tts):
    queues = [
        getattr(tts, "tts_audio_queue", None),
        getattr(getattr(tts, "conn", None), "tts_audio_queue", None),
    ]
    queue_size = sum(_peek_tts_audio_queue_size(audio_queue) for audio_queue in queues)
    return queue_size + _before_stop_play_files_size(getattr(tts, "before_stop_play_files", None))


def _first_tts_audio_ms(tts, benchmark_started):
    first_timestamp = getattr(getattr(tts, "tts_audio_queue", None), "first_audio_at", None)
    conn_audio_queue = getattr(getattr(tts, "conn", None), "tts_audio_queue", None)
    conn_timestamp = getattr(conn_audio_queue, "first_audio_at", None)
    if conn_timestamp is not None and (first_timestamp is None or conn_timestamp < first_timestamp):
        first_timestamp = conn_timestamp
    if first_timestamp is None:
        return None
    return max(0, int((first_timestamp - benchmark_started) * 1000))


def _peek_tts_audio_queue_size(audio_queue):
    if audio_queue is None:
        return 0
    try:
        return sum(_nested_audio_size(item) for item in list(audio_queue.queue))
    except Exception:
        return 0


def _before_stop_play_files_size(items):
    if not items:
        return 0
    return _nested_audio_size(items)


def _before_stop_play_files_audio(items):
    if not items:
        return []
    return _nested_audio_chunks(items)


def _read_file_bytes(path):
    try:
        with open(path, "rb") as file:
            return file.read()
    except Exception:
        return b""


def _infer_audio_format(path, fallback):
    extension = os.path.splitext(str(path or ""))[1].lstrip(".")
    return _normalize_audio_format(extension or fallback)


def _normalize_audio_format(value):
    value = str(value or "").lower().lstrip(".")
    if value in ("mp3", "mpeg"):
        return "mp3"
    if value in ("wav", "wave"):
        return "wav"
    if value in ("pcm", "raw", "opus", "p3"):
        return value
    return value or "wav"


def _build_audio_data_url(audio_bytes, audio_format):
    if not audio_bytes:
        return ""
    audio_format = _normalize_audio_format(audio_format)
    media_type = {
        "mp3": "audio/mpeg",
        "wav": "audio/wav",
        "opus": "audio/ogg",
        "p3": "audio/ogg",
    }.get(audio_format, "audio/wav")
    return f"data:{media_type};base64,{base64.b64encode(audio_bytes).decode('ascii')}"


def _audio_preview_info(audio_bytes, audio_format):
    audio_format = _normalize_audio_format(audio_format)
    duration_ms = _audio_duration_ms(audio_bytes, audio_format)
    playable = (
        len(audio_bytes) > AUDIO_VALID_BYTES
        and duration_ms >= MIN_PLAYABLE_AUDIO_MS
        and audio_format in ("wav", "mp3")
        and _has_playable_audio_header(audio_bytes, audio_format)
    )
    return {
        "playable": playable,
        "duration_ms": duration_ms,
    }


def _audio_duration_ms(audio_bytes, audio_format):
    if not audio_bytes:
        return 0
    audio_format = _normalize_audio_format(audio_format)
    if audio_format == "wav":
        return _wav_duration_ms(audio_bytes)
    if audio_format == "mp3":
        return MIN_PLAYABLE_AUDIO_MS if _has_playable_audio_header(audio_bytes, audio_format) else 0
    return 0


def _wav_duration_ms(audio_bytes):
    if not _has_playable_audio_header(audio_bytes, "wav"):
        return 0
    try:
        channels = int.from_bytes(audio_bytes[22:24], "little")
        sample_rate = int.from_bytes(audio_bytes[24:28], "little")
        bits_per_sample = int.from_bytes(audio_bytes[34:36], "little")
        data_index = audio_bytes.find(b"data")
        if channels <= 0 or sample_rate <= 0 or bits_per_sample <= 0 or data_index < 0:
            return 0
        data_size = int.from_bytes(audio_bytes[data_index + 4:data_index + 8], "little")
        bytes_per_second = sample_rate * channels * bits_per_sample / 8
        if bytes_per_second <= 0:
            return 0
        return int(data_size * 1000 / bytes_per_second)
    except Exception:
        return 0


def _has_playable_audio_header(audio_bytes, audio_format):
    if not audio_bytes:
        return False
    audio_format = _normalize_audio_format(audio_format)
    if audio_format == "wav":
        return len(audio_bytes) > 44 and audio_bytes[:4] == b"RIFF" and audio_bytes[8:12] == b"WAVE"
    if audio_format == "mp3":
        return audio_bytes.startswith(b"ID3") or (
            len(audio_bytes) > 2 and audio_bytes[0] == 0xFF and (audio_bytes[1] & 0xE0) == 0xE0
        )
    return False


def _opus_preview_to_wav(opus_chunks, sample_rate):
    try:
        return opus_datas_to_wav_bytes(opus_chunks, sample_rate=int(sample_rate or 24000), channels=1)
    except Exception:
        return b""


def _remove_file_quietly(path):
    try:
        if path and os.path.exists(path):
            os.remove(path)
    except Exception:
        pass
