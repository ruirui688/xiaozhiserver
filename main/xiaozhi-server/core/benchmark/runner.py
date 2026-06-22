import asyncio
import base64
import gzip
import json
import os
import queue
import tempfile
import threading
import time
import uuid
import wave
from concurrent.futures import ThreadPoolExecutor

import websockets

from core.benchmark.metrics import (
    char_error_rate,
    percentile,
    round_number,
    word_error_rate,
)
from core.benchmark.llm_cases import LLM_BENCHMARK_CASES
from core.benchmark.llm_judge import (
    build_case_dialogue_with_history,
    evaluate_llm_case_batch,
    get_case_turns,
    summarize_llm_judge,
)
from core.providers.tts.dto.dto import InterfaceType
from core.utils.asr import create_instance as create_asr_instance
from core.utils.llm import create_instance as create_llm_instance
from core.utils.tts import create_instance as create_tts_instance
from core.utils.opus_encoder_utils import OpusEncoderUtils


DEFAULT_TTS_SAMPLES = [
    "你好，我是小智。",
    "现在是上午八点三十分，天气晴。",
    "请注意，门口有人来了。",
]

BENCHMARK_RUNTIME_DIR = os.path.join(tempfile.gettempdir(), "xiaozhi-benchmark")
AUDIO_VALID_BYTES = 256
STANDARD_ASR_TEST_AUDIO = "wakeup_words_short.wav"


async def run_benchmark(payload):
    models = payload.get("models") or []
    iterations = int(payload.get("iterations") or 3)
    concurrency = max(1, min(int(payload.get("concurrency") or 3), 8))
    judge_config = payload.get("judge_config") or {}
    agent_context = payload.get("agent_context") or {}
    loop = asyncio.get_running_loop()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        tasks = [
            loop.run_in_executor(
                executor,
                lambda item=model: asyncio.run(_run_model(item, iterations, judge_config, agent_context)),
            )
            for model in models
        ]
        results = await asyncio.gather(*tasks, return_exceptions=True)

    normalized_results = []
    for model, result in zip(models, results):
        if isinstance(result, Exception):
            normalized_results.append(_failed_result(model, str(result)))
        else:
            normalized_results.append(result)
    return {"task_id": payload.get("task_id"), "results": normalized_results}


async def _run_model(model, iterations, judge_config=None, agent_context=None):
    model_type = str(model.get("model_type") or "").upper()
    provider_type = str(model.get("provider_type") or model.get("config", {}).get("type") or "")
    config = _prepare_runtime_config(model.get("config") or {})
    if model_type in ("LLM", "SLM"):
        return await _run_llm(model, provider_type, config, iterations, judge_config or {}, agent_context or {})
    if model_type == "ASR":
        return await _run_asr(model, provider_type, config, iterations)
    if model_type == "TTS":
        return await _run_tts(model, provider_type, config, iterations)
    return _unsupported_result(model, "当前专业评测暂未覆盖该模型类型")


async def _run_llm(model, provider_type, config, iterations, judge_config=None, agent_context=None):
    llm = create_llm_instance(provider_type, config)
    samples = []
    case_iteration = 0
    for repeat in range(iterations):
        batch_inputs = []
        for case in LLM_BENCHMARK_CASES:
            case_iteration += 1
            conversation = []
            total_latency_ms = 0
            first_token_ms = None
            final_output = ""
            message = "OK"
            token_count = 0
            try:
                turns = get_case_turns(case)
                if not turns:
                    raise RuntimeError("LLM评测样本缺少多轮用户输入")
                for turn in turns:
                    conversation.append({"role": "user", "content": turn})
                    started = time.perf_counter()
                    turn_output = ""
                    dialogue = build_case_dialogue_with_history(case, conversation, agent_context)
                    for chunk in llm.response("benchmark", dialogue):
                        if chunk:
                            if first_token_ms is None:
                                first_token_ms = int((time.perf_counter() - started) * 1000)
                            turn_output += str(chunk)
                            token_count += 1
                    turn_latency_ms = int((time.perf_counter() - started) * 1000)
                    total_latency_ms += turn_latency_ms
                    final_output = turn_output
                    conversation.append({"role": "assistant", "content": turn_output})
                    if not turn_output.strip():
                        message = "模型返回为空"
                        break
                tokens_per_second = round_number(token_count / max(total_latency_ms / 1000, 0.001), 2)
            except Exception as exc:
                total_latency_ms = total_latency_ms or 0
                tokens_per_second = None
                message = str(exc)
            batch_inputs.append({
                "case": case,
                "output": final_output,
                "conversation": conversation,
                "latency_ms": total_latency_ms,
                "ttft_ms": first_token_ms,
                "tokens_per_second": tokens_per_second,
                "iteration": case_iteration,
                "message": message,
            })
        judged_samples = evaluate_llm_case_batch(batch_inputs, judge_config or {}, agent_context or {})
        for item, sample in zip(batch_inputs, judged_samples):
            sample["iteration"] = item.get("iteration")
            sample["sample_type"] = f"llm_judge_batch:{item.get('case', {}).get('scenario')}"
            if item.get("message") != "OK" and not str(item.get("output") or "").strip():
                sample["message"] = item.get("message")
                sample["error_type"] = "EMPTY_OR_ERROR"
            samples.append(sample)
    return _summarize(model, "REAL_INFERENCE", "真实推理", samples)


async def _run_asr(model, provider_type, config, iterations):
    audio_files = _load_audio_files(model.get("test_audio_name") or STANDARD_ASR_TEST_AUDIO)
    if not audio_files:
        return _unsupported_result(model, "未找到 ASR 测试音频")
    asr = create_asr_instance(provider_type, config, delete_audio_file=True)
    asr.audio_format = "pcm"
    samples = []
    for index in range(iterations):
        audio = audio_files[index % len(audio_files)]
        started = time.perf_counter()
        text = ""
        success = False
        message = "OK"
        try:
            pcm = _wav_to_pcm(audio["path"], audio["data"])
            text = await _recognize_asr_sample(asr, provider_type, pcm, audio["data"])
            latency_ms = int((time.perf_counter() - started) * 1000)
            success = bool(str(text or "").strip())
            if not success:
                message = "识别文本为空"
        except Exception as exc:
            latency_ms = int((time.perf_counter() - started) * 1000)
            message = str(exc)
        reference = audio.get("reference")
        samples.append({
            "iteration": index + 1,
            "sample_type": "asr_audio",
            "success": success,
            "measured": success,
            "latency_ms": latency_ms,
            "cer": char_error_rate(reference, text) if reference else None,
            "wer": word_error_rate(reference, text) if reference else None,
            "input_summary": audio["name"],
            "output_summary": str(text or "")[:500],
            "reference_output": reference,
            "process_steps": [
                "读取内置 ASR 测试音频",
                "按 provider 类型调用真实 ASR 协议",
                "记录识别文本、耗时，并在存在参考文本时计算 CER/WER",
            ],
            "input_detail": {
                "audio_name": audio["name"],
                "audio_format": "pcm",
                "reference_text": reference,
                "audio_bytes": len(audio.get("data") or b""),
            },
            "output_detail": {
                "recognized_text": str(text or ""),
                "cer": char_error_rate(reference, text) if reference else None,
                "wer": word_error_rate(reference, text) if reference else None,
            },
            "message": message,
            "error_type": None if success else "EMPTY_OR_ERROR",
        })
    return _summarize(model, "REAL_INFERENCE", "真实推理", samples)


async def _recognize_asr_sample(asr, provider_type, pcm, original_audio):
    provider_type = str(provider_type or "").lower()
    if provider_type == "doubao_stream":
        return await _recognize_doubao_stream(asr, pcm)
    if provider_type == "aliyun_stream":
        return await _recognize_aliyun_stream(asr, pcm)
    if provider_type == "xunfei_stream":
        return await _recognize_xunfei_stream(asr, pcm)
    text, _ = await asr.speech_to_text_wrapper([pcm], "benchmark", "pcm")
    return text


async def _recognize_doubao_stream(asr, pcm):
    headers = asr.token_auth() if getattr(asr, "auth_method", "token") == "token" else None
    async with websockets.connect(
        asr.ws_url,
        additional_headers=headers,
        max_size=1000000000,
        ping_interval=None,
        ping_timeout=None,
        close_timeout=10,
    ) as ws:
        request_params = asr.construct_request(str(uuid.uuid4()))
        payload = gzip.compress(json.dumps(request_params).encode("utf-8"))
        request = bytearray(asr.generate_header())
        request.extend(len(payload).to_bytes(4, "big"))
        request.extend(payload)
        await ws.send(request)

        init_result = asr.parse_response(await asyncio.wait_for(ws.recv(), timeout=10))
        if "code" in init_result and init_result.get("code") != 1000:
            raise RuntimeError(str(init_result.get("payload_msg") or init_result))

        audio_payload = gzip.compress(pcm)
        audio_request = bytearray(asr.generate_last_audio_default_header())
        audio_request.extend(len(audio_payload).to_bytes(4, "big"))
        audio_request.extend(audio_payload)
        await ws.send(audio_request)

        for _ in range(20):
            result = asr.parse_response(await asyncio.wait_for(ws.recv(), timeout=10))
            text = _extract_doubao_text(result)
            if text:
                return text
        return ""


def _extract_doubao_text(result):
    payload = result.get("payload_msg") if isinstance(result, dict) else None
    if not isinstance(payload, dict):
        return ""
    result_body = payload.get("result")
    if not isinstance(result_body, dict):
        return ""
    utterances = result_body.get("utterances") or []
    definite = [item.get("text", "") for item in utterances if item.get("definite") and item.get("text")]
    if definite:
        return "".join(definite)
    return result_body.get("text") or ""


async def _recognize_aliyun_stream(asr, pcm):
    if hasattr(asr, "_is_token_expired") and asr._is_token_expired():
        asr._refresh_token()
    async with websockets.connect(
        asr.ws_url,
        additional_headers={"X-NLS-Token": asr.token},
        max_size=1000000000,
        ping_interval=None,
        ping_timeout=None,
        close_timeout=5,
    ) as ws:
        task_id = uuid.uuid4().hex
        await ws.send(json.dumps({
            "header": {
                "namespace": "SpeechTranscriber",
                "name": "StartTranscription",
                "message_id": uuid.uuid4().hex,
                "task_id": task_id,
                "appkey": asr.appkey,
            },
            "payload": {
                "format": "pcm",
                "sample_rate": 16000,
                "enable_intermediate_result": True,
                "enable_punctuation_prediction": True,
                "enable_inverse_text_normalization": True,
                "max_sentence_silence": getattr(asr, "max_sentence_silence", None),
                "enable_voice_detection": False,
            },
        }, ensure_ascii=False))

        await _wait_aliyun_started(ws)
        await _send_pcm_frames(ws, pcm)
        await ws.send(json.dumps({
            "header": {
                "namespace": "SpeechTranscriber",
                "name": "StopTranscription",
                "message_id": uuid.uuid4().hex,
                "task_id": task_id,
                "appkey": asr.appkey,
            }
        }, ensure_ascii=False))

        texts = []
        for _ in range(30):
            message = await asyncio.wait_for(ws.recv(), timeout=10)
            result = json.loads(message)
            header = result.get("header", {})
            payload = result.get("payload", {})
            if header.get("status") not in (None, 20000000):
                raise RuntimeError(header.get("status_text") or str(header))
            if header.get("name") == "SentenceEnd" and payload.get("result"):
                texts.append(payload.get("result"))
            if header.get("name") in ("TranscriptionCompleted", "TaskFailed"):
                break
        return "".join(texts)


async def _wait_aliyun_started(ws):
    for _ in range(10):
        result = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        header = result.get("header", {})
        if header.get("status") not in (None, 20000000):
            raise RuntimeError(header.get("status_text") or str(header))
        if header.get("name") == "TranscriptionStarted":
            return
    raise TimeoutError("阿里云 ASR 未进入可发送音频状态")


async def _send_pcm_frames(ws, pcm, frame_size=3200, delay=0.02):
    for start in range(0, len(pcm), frame_size):
        await ws.send(pcm[start:start + frame_size])
        if delay:
            await asyncio.sleep(delay)


async def _recognize_xunfei_stream(asr, pcm):
    async with websockets.connect(
        asr.create_url(),
        max_size=1000000000,
        ping_interval=None,
        ping_timeout=None,
        close_timeout=10,
    ) as ws:
        frame_size = 1280
        first = pcm[:frame_size]
        await ws.send(json.dumps({
            "common": {"app_id": asr.app_id},
            "business": {
                "domain": "iat",
                "language": "zh_cn",
                "accent": "mandarin",
                "dwa": "wpgs",
                "vad_eos": 5000,
            },
            "data": {
                "status": 0,
                "format": "audio/L16;rate=16000",
                "encoding": "raw",
                "audio": base64.b64encode(first).decode(),
            },
        }, ensure_ascii=False))

        pos = frame_size
        while pos < len(pcm):
            chunk = pcm[pos:pos + frame_size]
            status = 2 if pos + frame_size >= len(pcm) else 1
            await ws.send(json.dumps({
                "data": {
                    "status": status,
                    "format": "audio/L16;rate=16000",
                    "encoding": "raw",
                    "audio": base64.b64encode(chunk).decode(),
                }
            }, ensure_ascii=False))
            pos += frame_size
            await asyncio.sleep(0.04)

        text_parts = []
        async for message in ws:
            data = json.loads(message)
            if data.get("code") != 0:
                raise RuntimeError(data.get("message") or str(data))
            ws_result = data.get("data", {}).get("result", {}).get("ws") or []
            text_parts.extend(cw.get("w", "") for seg in ws_result for cw in seg.get("cw", []))
            if data.get("data", {}).get("status") == 2:
                break
        return "".join(text_parts)


async def _run_tts(model, provider_type, config, iterations):
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
    return _summarize(model, "REAL_INFERENCE", "真实推理", samples)


def _summarize(model, level, level_text, samples):
    success_count = sum(1 for sample in samples if sample.get("success"))
    failure_count = len(samples) - success_count
    latencies = [sample.get("latency_ms") for sample in samples if sample.get("success") and sample.get("measured")]
    qualities = [sample.get("quality_score") for sample in samples if sample.get("quality_score") is not None]
    result = {
        "model_id": model.get("id"),
        "model_name": model.get("model_name"),
        "model_type": model.get("model_type"),
        "provider_type": model.get("provider_type"),
        "benchmark_level": level,
        "benchmark_level_text": level_text,
        "success_count": success_count,
        "failure_count": failure_count,
        "success_rate": round_number(success_count / len(samples), 4) if samples else 0,
        "avg_latency_ms": round_number(sum(latencies) / len(latencies), 2) if latencies else None,
        "p50_latency_ms": percentile(latencies, 0.50),
        "p90_latency_ms": percentile(latencies, 0.90),
        "p95_latency_ms": percentile(latencies, 0.95),
        "quality_score": round_number(sum(qualities) / len(qualities), 2) if qualities else None,
        "summary": f"{level_text}，成功 {success_count}/{len(samples)}",
        "error_summary": "; ".join(sorted({sample.get("message", "") for sample in samples if not sample.get("success")}))[:1000],
        "samples": samples,
    }
    numeric_fields = ("ttft_ms", "first_audio_ms", "tokens_per_second", "rtf", "cer", "wer")
    for field in numeric_fields:
        values = [sample.get(field) for sample in samples if sample.get(field) is not None]
        result[field] = round_number(sum(values) / len(values), 4) if values else None
    result["logic_score"] = result.get("quality_score")
    result["context_score"] = result.get("quality_score")
    if str(model.get("model_type") or "").upper() in ("LLM", "SLM"):
        judge_summary = summarize_llm_judge(samples)
        result["judge_mode"] = judge_summary.get("judge_mode")
        result["overall_score"] = judge_summary.get("overall_score")
        result["dimension_scores"] = judge_summary.get("dimension_scores")
        result["scenario_scores"] = judge_summary.get("scenario_scores")
        result["p0p1_failure_count"] = judge_summary.get("p0p1_failure_count")
        result["recommendation"] = judge_summary.get("recommendation")
        result["quality_score"] = judge_summary.get("overall_score")
        dimension_scores = judge_summary.get("dimension_scores") or {}
        result["logic_score"] = dimension_scores.get("task_completion")
        result["context_score"] = dimension_scores.get("context")
        result["summary"] = (
            f"LLMJudge {judge_summary.get('judge_mode') or 'unknown'}，"
            f"综合分 {judge_summary.get('overall_score') if judge_summary.get('overall_score') is not None else '-'}，"
            f"P0/P1 {judge_summary.get('p0p1_failure_count') or 0} 个，"
            f"{judge_summary.get('recommendation') or ''}"
        )
    return result


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


def _prepare_runtime_config(config):
    normalized = dict(config or {})
    output_dir = normalized.get("output_dir") or os.path.join(BENCHMARK_RUNTIME_DIR, "tmp")
    os.makedirs(output_dir, exist_ok=True)
    normalized["output_dir"] = output_dir
    return normalized


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


def _failed_result(model, message):
    return _unsupported_result(model, message, level="FAILED", level_text="评测失败")


def _unsupported_result(model, message, level="UNSUPPORTED", level_text="未实测"):
    return {
        "model_id": model.get("id"),
        "model_name": model.get("model_name"),
        "model_type": model.get("model_type"),
        "provider_type": model.get("provider_type"),
        "benchmark_level": level,
        "benchmark_level_text": level_text,
        "success_count": 0,
        "failure_count": 0,
        "success_rate": 0,
        "summary": message,
        "error_summary": message,
        "samples": [],
    }


def _load_audio_files(preferred_name=None):
    root = os.path.join(os.getcwd(), "config", "assets")
    references = _load_audio_references(root)
    files = []
    if not os.path.exists(root):
        return files
    names = sorted(os.listdir(root))
    if preferred_name and preferred_name in names:
        names = [preferred_name]
    for name in names:
        if not name.lower().endswith((".wav", ".pcm")):
            continue
        path = os.path.join(root, name)
        with open(path, "rb") as file:
            files.append({"name": name, "path": path, "data": file.read(), "reference": references.get(name)})
    return files


def _load_audio_references(root):
    path = os.path.join(root, "references.json")
    if not os.path.exists(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8") as file:
            return json.load(file)
    except Exception:
        return {}


def _wav_to_pcm(path, data):
    if data.startswith(b"RIFF"):
        with wave.open(path, "rb") as wav_file:
            return wav_file.readframes(wav_file.getnframes())
    return data
