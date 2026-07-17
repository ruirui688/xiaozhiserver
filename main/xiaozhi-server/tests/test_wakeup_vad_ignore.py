import asyncio
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings

settings.config_file_valid = True

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

    def info(self, message):
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
    reports = []

    async def _send_stt(conn, text):
        messages.append(("stt", text))

    async def _send_tts(conn, state, text):
        messages.append(("tts", state, text))

    monkeypatch.setattr(listen_handler, "send_stt_message", _send_stt)
    monkeypatch.setattr(listen_handler, "send_tts_message", _send_tts)
    monkeypatch.setattr(
        listen_handler,
        "enqueue_asr_report",
        lambda conn, text, opus_data: reports.append((text, opus_data)),
    )
    conn = _Conn({"wakeup_words": ["你好小智"]})
    conn.client_is_speaking = True

    asyncio.run(
        ListenTextMessageHandler().handle(
            conn,
            {"type": "listen", "state": "detect", "text": "你好小智"},
        )
    )

    assert messages == [("stt", "你好小智"), ("tts", "stop", None)]
    assert reports == [("你好小智", [])]
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


def test_detected_wakeup_word_reports_original_device_text_before_reply(monkeypatch):
    import core.handle.textHandler.listenMessageHandler as listen_handler

    events = []

    async def _start_chat(conn, text):
        events.append(("chat", text))
        return True

    monkeypatch.setattr(listen_handler, "startToChat", _start_chat)
    monkeypatch.setattr(
        listen_handler,
        "enqueue_asr_report",
        lambda conn, text, opus_data: events.append(("report", text, opus_data)),
    )
    conn = _Conn(
        {
            "enable_greeting": True,
            "wakeup_words": ["你好小智"],
        }
    )

    asyncio.run(
        ListenTextMessageHandler().handle(
            conn,
            {"type": "listen", "state": "detect", "text": "你好小智"},
        )
    )

    assert events == [
        ("report", "你好小智", []),
        ("chat", "嘿，你好呀"),
    ]


def test_cached_wakeup_reply_is_reported_after_successful_playback(monkeypatch):
    import core.handle.helloHandle as hello_handle

    events = []
    opus_packets = [b"opus-one", b"opus-two"]

    async def _send_tts(conn, state, text=None):
        events.append(("tts", state, text))

    async def _send_audio(conn, sentence_type, audio, text, sentence_id=None):
        events.append(("audio", sentence_type, audio, text))

    async def _audio_to_data(file_path, use_cache=False):
        assert file_path == "cached-wakeup.wav"
        assert use_cache is False
        return opus_packets

    monkeypatch.setattr(hello_handle, "send_tts_message", _send_tts)
    monkeypatch.setattr(hello_handle, "sendAudioMessage", _send_audio)
    monkeypatch.setattr(hello_handle, "audio_to_data", _audio_to_data)
    monkeypatch.setattr(
        hello_handle.wakeup_words_config,
        "get_wakeup_response",
        lambda voice: {
            "voice": voice,
            "file_path": "cached-wakeup.wav",
            "time": hello_handle.time.time(),
            "text": "我在呢，请讲。",
        },
    )
    monkeypatch.setattr(
        hello_handle,
        "enqueue_tts_report",
        lambda conn, text, audio: events.append(("report", text, audio)),
    )

    dialogue = []
    conn = _Conn(
        {
            "enable_wakeup_words_response_cache": True,
            "wakeup_words": ["嘿你好呀"],
        }
    )
    conn.tts = SimpleNamespace(voice="test-voice")
    conn.client_abort = True
    conn.sentence_id = "old-sentence"
    conn.dialogue = SimpleNamespace(put=dialogue.append)

    assert asyncio.run(checkWakeupWords(conn, "嘿，你好呀")) is True

    report_index = events.index(("report", "我在呢，请讲。", opus_packets))
    last_audio_index = next(
        index
        for index, event in enumerate(events)
        if event[0] == "audio" and event[1] == hello_handle.SentenceType.LAST
    )
    assert report_index > last_audio_index
    assert dialogue[0].role == "assistant"
    assert dialogue[0].content == "我在呢，请讲。"
