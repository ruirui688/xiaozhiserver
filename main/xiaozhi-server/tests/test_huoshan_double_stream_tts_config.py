import sys
from pathlib import Path

import pytest


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.providers.tts.huoshan_double_stream import TTSProvider


def _base_config(**overrides):
    config = {
        "type": "huoshan_double_stream",
        "ws_url": "wss://openspeech.bytedance.com/api/v3/tts/bidirection",
        "api_key": "test-api-key",
        "resource_id": "seed-tts-2.0",
        "speaker": "zh_female_sophie_uranus_bigtts",
        "output_dir": "tmp/",
    }
    config.update(overrides)
    return config


def test_doubao_tts_accepts_only_seed_tts_2_resource():
    TTSProvider(_base_config(resource_id="seed-tts-2.0"), True)

    with pytest.raises(ValueError, match="仅支持火山 Seed TTS 2.0 双向流式服务"):
        TTSProvider(_base_config(resource_id="legacy-resource"), True)

    with pytest.raises(ValueError, match="仅支持火山 Seed TTS 2.0 双向流式服务"):
        TTSProvider(_base_config(resource_id="legacy-http-tts"), True)


def test_doubao_tts_merges_flat_audio_fields():
    provider = TTSProvider(
        _base_config(
            speech_rate=20,
            loudness_rate=10,
            pitch=2,
            emotion="happy",
            emotion_scale=4,
            format="wav",
        ),
        True,
    )

    assert provider.audio_params["speech_rate"] == 20
    assert provider.audio_params["loudness_rate"] == 10
    assert provider.audio_params["emotion"] == "happy"
    assert provider.audio_params["emotion_scale"] == 4
    assert provider.additions["post_process"]["pitch"] == 2
    assert provider.audio_file_type == "wav"


def test_doubao_tts_accepts_json_string_advanced_fields():
    provider = TTSProvider(
        _base_config(
            audio_params='{"speech_rate": 15, "loudness_rate": 8}',
            additions='{"post_process": {"pitch": 3}}',
        ),
        True,
    )

    assert provider.audio_params["speech_rate"] == 15
    assert provider.audio_params["loudness_rate"] == 8
    assert provider.additions["post_process"]["pitch"] == 3


def test_doubao_tts_requires_api_key():
    with pytest.raises(ValueError, match="需要配置 api_key"):
        TTSProvider(_base_config(api_key=""), True)


def test_doubao_tts_rejects_invalid_json_string_advanced_fields():
    with pytest.raises(ValueError, match="audio_params 必须是 JSON 对象字符串"):
        TTSProvider(_base_config(audio_params="not-json"), True)
