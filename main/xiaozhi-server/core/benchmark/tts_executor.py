import asyncio
import os
import queue
import threading
import time

from core.providers.tts.dto.dto import InterfaceType
from core.utils.opus_encoder_utils import OpusEncoderUtils
from core.utils.tts import create_instance as create_tts_instance


AUDIO_VALID_BYTES = 256
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
            output_file = None
            try:
                result, output_file = await _synthesize_tts_sample(tts, text)
                latency_ms = int((time.perf_counter() - started) * 1000)
                audio_size = _measure_tts_audio_size(result, output_file, tts)
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
        return b""

    result = await tts.text_to_speak(text, True)
    return result


def _generate_tts_output_file(tts):
    if not hasattr(tts, "generate_filename"):
        return None
    extension = "." + str(getattr(tts, "audio_file_type", "wav") or "wav").lstrip(".")
    try:
        return tts.generate_filename(extension)
    except TypeError:
        return tts.generate_filename()


def _measure_tts_audio_size(result, output_file, tts):
    size = 0
    if output_file and os.path.exists(output_file):
        size += os.path.getsize(output_file)
    elif isinstance(result, (bytes, bytearray)):
        size += len(result)
    elif isinstance(result, str) and os.path.exists(result):
        size += os.path.getsize(result)

    queue_size = _drain_tts_audio_queue_size(getattr(tts, "tts_audio_queue", None))
    queue_size += _drain_tts_audio_queue_size(getattr(getattr(tts, "conn", None), "tts_audio_queue", None))
    return max(size, queue_size)


def _drain_tts_audio_queue_size(audio_queue):
    if audio_queue is None:
        return 0
    size = 0
    while True:
        try:
            item = audio_queue.get_nowait()
        except queue.Empty:
            break
        if isinstance(item, (bytes, bytearray)):
            size += len(item)
        elif isinstance(item, (list, tuple)):
            for value in item:
                size += _nested_audio_size(value)
    return size


def _nested_audio_size(value):
    if isinstance(value, (bytes, bytearray)):
        return len(value)
    if isinstance(value, (list, tuple)):
        return sum(_nested_audio_size(item) for item in value)
    return 0


def _remove_file_quietly(path):
    try:
        if path and os.path.exists(path):
            os.remove(path)
    except Exception:
        pass
