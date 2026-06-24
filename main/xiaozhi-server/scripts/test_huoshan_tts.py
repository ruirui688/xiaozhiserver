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
    resource_id = args.resource_id or env("HUOSHAN_TTS_RESOURCE_ID", "seed-tts-2.0")
    return {
        "type": "huoshan_double_stream",
        "ws_url": args.ws_url or env("HUOSHAN_TTS_WS_URL", "wss://openspeech.bytedance.com/api/v3/tts/bidirection"),
        "appid": args.appid or env("HUOSHAN_TTS_APPID"),
        "access_token": args.access_token or env("HUOSHAN_TTS_ACCESS_TOKEN"),
        "resource_id": resource_id,
        "speaker": args.speaker or env("HUOSHAN_TTS_SPEAKER"),
        "enable_ws_reuse": False,
        "sample_rate": args.sample_rate,
        "tts_timeout": args.timeout,
        "audio_params": {
            "speech_rate": 0,
            "loudness_rate": 0,
        },
        "additions": {
            "post_process": {
                "pitch": 0,
            },
        },
    }


def validate_config(config):
    missing = [
        key
        for key in ("appid", "access_token", "resource_id", "speaker")
        if not config.get(key)
    ]
    if missing:
        raise SystemExit("缺少环境变量或参数: " + ", ".join(missing))


async def main():
    parser = argparse.ArgumentParser(description="Test Huoshan/Doubao bidirectional streaming TTS.")
    parser.add_argument("--appid")
    parser.add_argument("--access-token")
    parser.add_argument("--resource-id")
    parser.add_argument("--speaker")
    parser.add_argument("--ws-url")
    parser.add_argument("--iterations", type=int, default=1)
    parser.add_argument("--sample-rate", type=int, default=16000)
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
