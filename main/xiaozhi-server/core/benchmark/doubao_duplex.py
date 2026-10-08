"""Real S2S probe with a bundled, non-private 16 kHz speech sample."""

import asyncio
import base64
import time
import wave
from pathlib import Path

from core.providers.realtime.doubao_duplex_transport import DoubaoDuplexTransport


async def run_duplex_samples(config, iterations, transport_factory=DoubaoDuplexTransport):
    path = Path(__file__).resolve().parents[2] / "config/assets/wakeup_words_short.wav"
    with wave.open(str(path), "rb") as sample:
        if (sample.getnchannels(), sample.getsampwidth(), sample.getframerate()) != (1, 2, 16000):
            raise ValueError("全双工测试样本必须为16kHz单声道PCM16")
        pcm = sample.readframes(sample.getnframes())
    samples = []
    for iteration in range(iterations):
        transport = transport_factory(config)
        done = asyncio.Event()
        started = time.perf_counter()
        result = {
            "iteration": iteration + 1, "sample_type": "realtime_audio",
            "input_summary": "内置语音样本 wakeup_words_short.wav（非文字评测）",
            "success": False, "measured": False, "message": "未完成语音推理",
            "ttft_ms": None, "first_audio_ms": None,
            "output_detail": {"assistant_text": "", "audio_bytes": 0},
        }
        error = None
        transcript = ""
        transcription = ""

        async def on_event(event):
            nonlocal error, transcript, transcription
            kind = event.get("type")
            elapsed = int((time.perf_counter() - started) * 1000)
            if kind == "conversation.item.input_audio_transcription.delta":
                transcription += str(event.get("delta") or "")
            elif kind == "conversation.item.input_audio_transcription.completed":
                transcript = str(event.get("transcript") or event.get("text") or transcription).strip()
            elif kind == "response.output_text.delta":
                result["output_detail"]["assistant_text"] += str(event.get("delta") or "")
                if result["ttft_ms"] is None:
                    result["ttft_ms"] = elapsed
            elif kind == "response.output_audio.delta":
                audio = base64.b64decode(event.get("delta") or "", validate=True)
                if len(audio) % 2:
                    raise ValueError("无效PCM16")
                result["output_detail"]["audio_bytes"] += len(audio)
                if audio and result["first_audio_ms"] is None:
                    result["first_audio_ms"] = elapsed
            elif kind == "response.output_audio.done":
                done.set()
            elif kind in {"error", "transport.closed"}:
                error = "豆包3.0语音测试上游失败"
                done.set()

        try:
            await transport.connect(on_event)
            await transport.configure("请用简短中文回应用户的语音。", [])
            result["connect_ms"] = int((time.perf_counter() - started) * 1000)
            started = time.perf_counter()
            await transport.send_audio(pcm)
            await transport.mute(commit=True)
            await asyncio.wait_for(done.wait(), min(300, max(1, float(config.get("response_timeout_seconds", 120)))))
            if error:
                raise RuntimeError(error)
            if not transcript or result["output_detail"]["audio_bytes"] < 960:
                raise RuntimeError("豆包3.0未返回有效输入转写及回复音频")
            result.update(success=True, measured=True, message="真实语音输入及音频响应完成")
        except Exception as exc:
            result["message"] = f"豆包3.0语音测试失败：{type(exc).__name__}"
        finally:
            result["latency_ms"] = int((time.perf_counter() - started) * 1000)
            result["output_summary"] = result["output_detail"]["assistant_text"]
            await transport.close()
        samples.append(result)
    return samples
