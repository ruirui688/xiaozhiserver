import asyncio
import sys
from pathlib import Path

import pytest
import yaml


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.handle.receiveAudioHandle import handleAudioMessage
from core.handle.helloHandle import checkWakeupWords
from core.handle.textHandler.listenMessageHandler import ListenTextMessageHandler
from core.handle.wakeupHandle import (
    arm_wakeup_vad_ignore,
    get_wakeup_vad_ignore_ms,
)


class _Logger:
    def bind(self, **kwargs):
        return self

    def warning(self, message):
        pass


class _Vad:
    def __init__(self):
        self.calls = []

    def is_vad(self, conn, audio):
        self.calls.append(audio)
        return True


class _Asr:
    def __init__(self):
        self.calls = []

    async def receive_audio(self, conn, audio, have_voice):
        self.calls.append((audio, have_voice))


class _Conn:
    def __init__(self, config=None):
        self.config = config or {}
        self.logger = _Logger()
        self.vad = _Vad()
        self.asr = _Asr()
        self.last_activity_time = 0

    def reset_audio_states(self):
        pass


def test_default_zero_processes_first_audio_packet():
    conn = _Conn()
    arm_wakeup_vad_ignore(conn)

    asyncio.run(handleAudioMessage(conn, b"first"))

    assert conn.just_woken_up is False
    assert conn.vad.calls == [b"first"]
    assert conn.asr.calls == [(b"first", True)]


def test_compatibility_window_skips_vad_and_asr_until_expired(monkeypatch):
    import core.handle.wakeupHandle as wakeup_handle

    now = [100.0]
    monkeypatch.setattr(wakeup_handle.time, "monotonic", lambda: now[0])
    conn = _Conn({"wakeup_vad_ignore_ms": 2000})
    arm_wakeup_vad_ignore(conn)

    asyncio.run(handleAudioMessage(conn, b"echo"))
    assert conn.vad.calls == []
    assert conn.asr.calls == []

    now[0] = 102.0
    asyncio.run(handleAudioMessage(conn, b"speech"))
    assert conn.just_woken_up is False
    assert conn.vad.calls == [b"speech"]
    assert conn.asr.calls == [(b"speech", True)]


@pytest.mark.parametrize("raw_value", [None, -1, "invalid", True, float("inf")])
def test_invalid_ignore_duration_falls_back_to_zero(raw_value):
    conn = _Conn({"wakeup_vad_ignore_ms": raw_value})

    assert get_wakeup_vad_ignore_ms(conn) == 0


def test_default_config_disables_greeting_and_audio_ignore():
    with (SERVER_ROOT / "config.yaml").open(encoding="utf-8") as config_file:
        config = yaml.safe_load(config_file)

    assert config["enable_greeting"] is False
    assert config["wakeup_vad_ignore_ms"] == 0


def test_missing_greeting_config_defaults_to_no_wakeup_reply(monkeypatch):
    import core.handle.textHandler.listenMessageHandler as listen_handler

    messages = []

    async def _send_stt(conn, text):
        messages.append(("stt", text))

    async def _send_tts(conn, state, text):
        messages.append(("tts", state, text))

    monkeypatch.setattr(listen_handler, "send_stt_message", _send_stt)
    monkeypatch.setattr(listen_handler, "send_tts_message", _send_tts)
    conn = _Conn({"wakeup_words": ["你好小智"]})
    conn.client_is_speaking = True

    asyncio.run(
        ListenTextMessageHandler().handle(
            conn,
            {"type": "listen", "state": "detect", "text": "你好小智"},
        )
    )

    assert messages == [("stt", "你好小智"), ("tts", "stop", None)]
    assert conn.client_is_speaking is False


def test_disabled_wakeup_cache_does_not_wait_for_tts(monkeypatch):
    import core.handle.helloHandle as hello_handle

    async def _unexpected_sleep(delay):
        raise AssertionError("disabled cache must not wait for TTS")

    monkeypatch.setattr(hello_handle.asyncio, "sleep", _unexpected_sleep)
    conn = _Conn(
        {
            "enable_wakeup_words_response_cache": False,
            "wakeup_words": ["你好小智"],
        }
    )
    conn.tts = None

    assert asyncio.run(checkWakeupWords(conn, "你好小智")) is False


def test_non_wakeup_text_does_not_wait_for_tts(monkeypatch):
    import core.handle.helloHandle as hello_handle

    async def _unexpected_sleep(delay):
        raise AssertionError("ordinary text must not wait for TTS")

    monkeypatch.setattr(hello_handle.asyncio, "sleep", _unexpected_sleep)
    conn = _Conn(
        {
            "enable_wakeup_words_response_cache": True,
            "wakeup_words": ["你好小智"],
        }
    )
    conn.tts = None

    assert asyncio.run(checkWakeupWords(conn, "今天天气怎么样")) is False
