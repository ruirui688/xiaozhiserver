import sys
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SERVER_ROOT.parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.benchmark.asr_executor import (
    STANDARD_ASR_TEST_AUDIO,
    _build_audio_data_url,
    _latency_after_audio,
    _load_audio_files,
    _pcm_duration_ms,
    _wav_to_pcm,
)


def test_load_standard_asr_audio_from_repo_root(monkeypatch):
    monkeypatch.chdir(REPO_ROOT)

    audio_files = _load_audio_files(STANDARD_ASR_TEST_AUDIO)

    assert [item["name"] for item in audio_files] == [STANDARD_ASR_TEST_AUDIO]
    assert audio_files[0]["data"].startswith(b"RIFF")
    assert "main/xiaozhi-server/config/assets" in audio_files[0]["path"]


def test_load_asr_audio_falls_back_when_preferred_missing(monkeypatch):
    monkeypatch.chdir(REPO_ROOT)

    audio_files = _load_audio_files("missing.wav")

    assert audio_files
    assert any(item["name"] == STANDARD_ASR_TEST_AUDIO for item in audio_files)


def test_build_audio_preview_data_url(monkeypatch):
    monkeypatch.chdir(REPO_ROOT)

    audio = _load_audio_files(STANDARD_ASR_TEST_AUDIO)[0]
    data_url = _build_audio_data_url(audio)

    assert data_url.startswith("data:audio/wav;base64,")


def test_standard_asr_audio_duration_is_reported(monkeypatch):
    monkeypatch.chdir(REPO_ROOT)

    audio = _load_audio_files(STANDARD_ASR_TEST_AUDIO)[0]
    pcm = _wav_to_pcm(audio["path"], audio["data"])

    assert _pcm_duration_ms(pcm) == 1560


def test_latency_after_audio_uses_tail_latency():
    assert _latency_after_audio(2161, 1560) == 601
    assert _latency_after_audio(1200, 1560) == 0
    assert _latency_after_audio(None, 1560) is None
