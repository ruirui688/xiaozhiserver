#!/usr/bin/env python
import argparse
import asyncio
import json
import os
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from core.benchmark.tts_executor import run_tts_samples


def env(name, default=None):
    value = os.environ.get(name)
    return value if value not in (None, "") else default


def build_config(args):
    config = {
        "type": "huoshan_double_stream",
        "api_key": args.api_key or env("VOLC_TTS_API_KEY") or env("HUOSHAN_TTS_API_KEY"),
        "speaker": args.speaker or env("HUOSHAN_TTS_SPEAKER", "zh_female_sophie_uranus_bigtts"),
        "sample_rate": args.sample_rate,
        "tts_timeout": args.timeout,
        "speech_rate": args.speech_rate,
        "loudness_rate": args.loudness_rate,
        "pitch": args.pitch,
    }
    if args.emotion:
        config["emotion"] = args.emotion
    if args.emotion_scale is not None:
        config["emotion_scale"] = args.emotion_scale
    return config


def validate_config(config):
    missing = [key for key in ("api_key", "speaker") if not config.get(key)]
    if missing:
        raise SystemExit("缺少环境变量或参数: " + ", ".join(missing))


async def main():
    parser = argparse.ArgumentParser(description="Test Huoshan/Doubao bidirectional streaming TTS.")
    parser.add_argument("--api-key")
    parser.add_argument("--speaker")
    parser.add_argument("--speech-rate", type=int, default=0)
    parser.add_argument("--loudness-rate", type=int, default=0)
    parser.add_argument("--pitch", type=int, default=0)
    parser.add_argument("--emotion")
    parser.add_argument("--emotion-scale", type=float)
    parser.add_argument("--iterations", type=int, default=1)
    parser.add_argument("--sample-rate", type=int, default=24000)
    parser.add_argument("--timeout", type=int, default=20)
    args = parser.parse_args()

    config = build_config(args)
    validate_config(config)
    samples = await run_tts_samples("huoshan_double_stream", config, max(1, args.iterations))
    safe_samples = []
    for sample in samples:
        output_detail = dict(sample.get("output_detail") or {})
        output_detail.pop("audio_data_url", None)
        safe_sample = dict(sample)
        safe_sample["output_detail"] = output_detail
        safe_samples.append(safe_sample)
    print(json.dumps(safe_samples, ensure_ascii=False, indent=2))

    if not all(sample.get("success") for sample in samples):
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
