import asyncio
import base64
import gzip
import json
import os
import time
import uuid
import wave
from pathlib import Path

import websockets

from core.benchmark.metrics import char_error_rate, word_error_rate
from core.utils.asr import create_instance as create_asr_instance


STANDARD_ASR_TEST_AUDIO = "wakeup_words_short.wav"
SERVER_ROOT = Path(__file__).resolve().parents[2]
ASR_TEST_AUDIO_DIR = SERVER_ROOT / "config" / "assets"


class AsrTestAudioNotFound(Exception):
    pass


async def run_asr_samples(model, provider_type, config, iterations):
    audio_files = _load_audio_files(model.get("test_audio_name") or STANDARD_ASR_TEST_AUDIO)
    if not audio_files:
        raise AsrTestAudioNotFound("未找到 ASR 测试音频")

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
            text = await _recognize_asr_sample(asr, provider_type, pcm)
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
    return samples


async def _recognize_asr_sample(asr, provider_type, pcm):
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


def _load_audio_files(preferred_name=None):
    root = _find_audio_root()
    if not root:
        return []
    references = _load_audio_references(root)
    files = []
    names = sorted(os.listdir(root))
    if preferred_name and preferred_name in names:
        names = [preferred_name]
    for name in names:
        if not name.lower().endswith((".wav", ".pcm")):
            continue
        path = os.path.join(str(root), name)
        with open(path, "rb") as file:
            files.append({"name": name, "path": path, "data": file.read(), "reference": references.get(name)})
    return files


def _find_audio_root():
    candidates = [
        ASR_TEST_AUDIO_DIR,
        Path.cwd() / "config" / "assets",
        Path.cwd() / "main" / "xiaozhi-server" / "config" / "assets",
    ]
    seen = set()
    for candidate in candidates:
        resolved = candidate.resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        if resolved.exists():
            return resolved
    return None


def _load_audio_references(root):
    path = os.path.join(str(root), "references.json")
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
