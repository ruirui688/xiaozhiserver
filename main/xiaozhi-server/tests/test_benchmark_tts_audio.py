import io
import sys
import wave
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.benchmark import tts_executor


def _silent_wav(duration_ms):
    sample_rate = 16000
    frames = int(sample_rate * duration_ms / 1000)
    pcm = b"\x00\x00" * frames
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(pcm)
    return buffer.getvalue()


def test_tts_preview_requires_playable_wav_duration():
    preview = tts_executor._audio_preview_info(_silent_wav(1000), "wav")

    assert preview["playable"] is True
    assert preview["duration_ms"] == 1000


def test_tts_preview_rejects_short_or_invalid_audio():
    assert tts_executor._audio_preview_info(_silent_wav(100), "wav")["playable"] is False
    assert tts_executor._audio_preview_info(b"not a wav" * 1000, "wav")["playable"] is False
    assert tts_executor._audio_preview_info(b"\xff\xf1" * 1000, "opus")["playable"] is False

