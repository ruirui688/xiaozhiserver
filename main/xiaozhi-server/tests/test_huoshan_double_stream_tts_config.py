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
        "api_key": "test-api-key",
        "speaker": "zh_female_sophie_uranus_bigtts",
        "output_dir": "tmp/",
    }
    config.update(overrides)
    return config


def test_doubao_tts_uses_seed_tts_2_default_protocol_defaults():
    provider = TTSProvider(_base_config(ws_url="wss://example.invalid"), True)

    assert provider.resource_id == "seed-tts-2.0"
    assert provider.ws_url == "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
    assert provider.audio_format == "pcm"
    assert provider.enable_ws_reuse is True


def test_doubao_tts_accepts_biggtts_resource_id():
    provider = TTSProvider(_base_config(resource_id="volc.service_type.10029", speaker=""), True)

    assert provider.resource_id == "volc.service_type.10029"
    assert provider.voice == "zh_female_wanwanxiaohe_moon_bigtts"


def test_doubao_tts_rejects_unknown_resource_id():
    with pytest.raises(ValueError, match="不支持的豆包 TTS resource_id"):
        TTSProvider(_base_config(resource_id="legacy-resource"), True)


def test_doubao_tts_merges_flat_audio_fields():
    provider = TTSProvider(
        _base_config(
            speech_rate=20,
            loudness_rate=10,
            pitch=2,
            emotion="happy",
            emotion_scale=4,
        ),
        True,
    )

    assert provider.audio_params["speech_rate"] == 20
    assert provider.audio_params["loudness_rate"] == 10
    assert provider.audio_params["emotion"] == "happy"
    assert provider.audio_params["emotion_scale"] == 4
    assert provider.additions["post_process"]["pitch"] == 2
    assert provider.audio_file_type == "pcm"


def test_doubao_tts_coerces_string_audio_numbers():
    provider = TTSProvider(
        _base_config(
            speech_rate="0",
            loudness_rate="0",
            sample_rate="24000",
            pitch="0",
            emotion_scale="4",
        ),
        True,
    )

    assert provider.audio_params["speech_rate"] == 0
    assert provider.audio_params["loudness_rate"] == 0
    assert provider.audio_params["sample_rate"] == 24000
    assert provider.audio_params["emotion_scale"] == 4
    assert provider.additions["post_process"]["pitch"] == 0


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
