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
STREAM_DRAIN_TIMEOUT_SECONDS = 5
DEFAULT_TTS_SAMPLES = [
    "你好，我是小智。",
    "现在是上午八点三十分，天气晴。",
    "请注意，门口有人来了。",
]


async def run_tts_samples(provider_type, config, iterations):
    tts = create_tts_instance(provider_type, config, delete_audio_file=True)
    tts.conn = _BenchmarkTtsConn(sample_rate=int(config.get("sample_rate") or 16000))
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
            output_file = None
            try:
                result, output_file = await _synthesize_tts_sample(tts, text)
                latency_ms = int((time.perf_counter() - started) * 1000)
                audio_result = _collect_tts_audio_result(result, output_file, tts)
                audio_size = audio_result["audio_size"]
                audio_data_url = audio_result["audio_data_url"]
                audio_preview_format = audio_result["audio_preview_format"]
                success = audio_size > AUDIO_VALID_BYTES
                if not success:
                    message = "TTS 音频为空或过短"
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
                "first_audio_ms": latency_ms if success else None,
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
                },
                "output_detail": {
                    "audio_bytes": audio_size,
                    "output_file_type": getattr(tts, "audio_file_type", None),
                    "audio_preview_format": audio_preview_format,
                    "audio_data_url": audio_data_url,
                    "valid_audio": success,
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
        self.tts_audio_queue = queue.Queue()
        try:
            self.loop = asyncio.get_running_loop()
        except RuntimeError:
            self.loop = None


async def _synthesize_tts_sample(tts, text):
    interface_type = getattr(tts, "interface_type", None)
    if interface_type in (InterfaceType.DUAL_STREAM, InterfaceType.SINGLE_STREAM):
        return await _synthesize_stream_tts_sample(tts, text), None

    output_file = _generate_tts_output_file(tts)
    if output_file:
        os.makedirs(os.path.dirname(output_file), exist_ok=True)
    result = await tts.text_to_speak(text, output_file)
    return result, output_file


async def _synthesize_stream_tts_sample(tts, text):
    if hasattr(tts, "start_session") and hasattr(tts, "finish_session"):
        await tts.start_session(tts.conn.sentence_id)
        if hasattr(tts, "store_tts_text"):
            tts.store_tts_text(tts.conn.sentence_id, text)
        await tts.text_to_speak(text, None)
        await tts.finish_session(tts.conn.sentence_id)
        await _wait_for_tts_audio(tts)
        return b""

    result = await tts.text_to_speak(text, True)
    await _wait_for_tts_audio(tts)
    return result


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

    queue_audio = _drain_tts_audio_queue_audio(getattr(tts, "tts_audio_queue", None))
    queue_audio += _drain_tts_audio_queue_audio(getattr(getattr(tts, "conn", None), "tts_audio_queue", None))
    queue_audio += _before_stop_play_files_audio(getattr(tts, "before_stop_play_files", None))
    queue_audio_size = sum(len(chunk) for chunk in queue_audio)
    if queue_audio_size > len(audio_bytes):
        preview_bytes = _opus_preview_to_wav(queue_audio, getattr(getattr(tts, "conn", None), "sample_rate", 16000))
        audio_bytes = preview_bytes or b"".join(queue_audio)
        audio_format = "wav" if preview_bytes else ""

    return {
        "audio_size": len(audio_bytes),
        "audio_preview_format": audio_format,
        "audio_data_url": _build_audio_data_url(audio_bytes, audio_format),
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


async def _wait_for_tts_audio(tts, timeout=STREAM_DRAIN_TIMEOUT_SECONDS):
    started = time.perf_counter()
    while time.perf_counter() - started < timeout:
        if _has_tts_audio(tts):
            return
        await asyncio.sleep(0.05)


def _has_tts_audio(tts):
    queues = [
        getattr(tts, "tts_audio_queue", None),
        getattr(getattr(tts, "conn", None), "tts_audio_queue", None),
    ]
    if any(_peek_tts_audio_queue_size(audio_queue) > 0 for audio_queue in queues):
        return True
    return _before_stop_play_files_size(getattr(tts, "before_stop_play_files", None)) > 0


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


def _opus_preview_to_wav(opus_chunks, sample_rate):
    try:
        return opus_datas_to_wav_bytes(opus_chunks, sample_rate=int(sample_rate or 16000), channels=1)
    except Exception:
        return b""


def _remove_file_quietly(path):
    try:
        if path and os.path.exists(path):
            os.remove(path)
    except Exception:
        pass
