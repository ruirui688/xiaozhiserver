import asyncio
import json
import queue
import sys
import threading
from concurrent.futures import Future
from pathlib import Path
from types import SimpleNamespace


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings

settings.config_file_valid = True

from core.handle.helloHandle import handleHelloMessage
from core.handle.receiveAudioHandle import handleAudioMessage, startToChat
from core.handle.sendAudioHandle import send_stt_message


class _Logger:
    def bind(self, **kwargs):
        return self

    def debug(self, message):
        pass

    def info(self, message):
        pass

    def warning(self, message):
        pass

    def error(self, message):
        pass


class _WebSocket:
    def __init__(self, events=None):
        self.messages = []
        self.events = events

    async def send(self, message):
        payload = json.loads(message)
        self.messages.append(payload)
        if self.events is not None and payload.get("type") == "tts":
            self.events.append("tts_stop")


class _VAD:
    def __init__(self, have_voice):
        self.have_voice = have_voice

    def is_vad(self, conn, audio):
        return self.have_voice


class _GatedVAD(_VAD):
    def __init__(self, have_voice, speech_started):
        super().__init__(have_voice)
        self.decision = SimpleNamespace(speech_started=speech_started)

    def is_speech_gate_enforced(self, conn=None):
        return True

    def get_last_decision(self, conn):
        return self.decision


class _ASR:
    def __init__(self, events):
        self.events = events
        self.received = []

    async def receive_audio(self, conn, audio, have_voice):
        self.events.append("asr")
        self.received.append((audio, have_voice))


class _Executor:
    def __init__(self):
        self.submitted = []

    def submit(self, fn, *args):
        self.submitted.append((fn, args))
        future = Future()
        future.set_result(None)
        return future


class _TurnContext:
    def __init__(self):
        self.clear_calls = 0

    def clear(self):
        self.clear_calls += 1


class _Conn:
    def __init__(self):
        self.config = {"wakeup_vad_ignore_ms": 0, "wakeup_words": []}
        self.logger = _Logger()
        self.events = []
        self.websocket = _WebSocket(self.events)
        self.vad = _VAD(True)
        self.asr = _ASR(self.events)
        self.turn_metrics = None
        self.client_aec = False
        self.client_is_speaking = True
        self.client_listen_mode = "auto"
        self.last_activity_time = 0
        self.client_abort = False
        self.close_after_chat = False
        self.abort_generation = 0
        self._abort_lock = threading.Lock()
        self.session_id = "s1"
        self.sentence_id = "sentence-1"
        self.asr_audio_queue = queue.Queue()
        self.turn_context = _TurnContext()
        self.clear_queue_calls = []
        self.reset_audio_calls = 0

        self.executor = _Executor()
        self.headers = {"device-id": "d1"}
        self.need_bind = False
        self.max_output_size = 0
        self.current_speaker = None
        self.current_speaker_id = None
        self.correct_word_replacements = {}
        self.semantic_center = None
        self.voiceprint_enrollment = None
        self.meeting_minutes = None
        self.llm = object()
        self._tts_started_at = 0

    def clear_queues(self, preserve_asr=False):
        self.clear_queue_calls.append(preserve_asr)

    def reset_audio_states(self):
        self.reset_audio_calls += 1

    def clearSpeakStatus(self):
        self.client_is_speaking = False

    def chat(self, text):
        return text


def test_aec_voice_stops_tts_before_asr_and_preserves_user_audio():
    conn = _Conn()
    conn.client_aec = True

    asyncio.run(handleAudioMessage(conn, b"voice"))

    assert conn.events == ["tts_stop", "asr"]
    assert conn.abort_generation == 1
    assert conn.clear_queue_calls == [True]
    assert conn.reset_audio_calls == 0
    assert conn.turn_context.clear_calls == 0
    assert conn.asr.received == [(b"voice", True)]


def test_aec_acoustic_gate_aborts_only_on_confirmed_start_edge():
    conn = _Conn()
    conn.client_aec = True
    conn.vad = _GatedVAD(True, speech_started=False)

    asyncio.run(handleAudioMessage(conn, b"continuation"))

    assert conn.events == ["asr"]
    assert conn.abort_generation == 0

    conn.vad.decision = SimpleNamespace(speech_started=True)
    asyncio.run(handleAudioMessage(conn, b"confirmed-start"))

    assert conn.events == ["asr", "tts_stop", "asr"]
    assert conn.abort_generation == 1


def test_enforced_balanced_attention_defers_dormant_aec_barge_in_until_asr():
    conn = _Conn()
    conn.client_aec = True
    conn.config["interaction_attention"] = {
        "enabled": True,
        "mode": "enforce",
        "barge_in_policy": "balanced",
    }

    asyncio.run(handleAudioMessage(conn, b"background-conversation"))

    assert conn.events == ["asr"]
    assert conn.abort_generation == 0


def test_non_aec_voice_waits_for_complete_asr_before_stopping():
    conn = _Conn()

    asyncio.run(handleAudioMessage(conn, b"voice"))

    assert conn.events == ["asr"]
    assert conn.abort_generation == 0
    assert conn.client_is_speaking is True


def test_manual_mode_does_not_auto_stop_even_with_aec():
    conn = _Conn()
    conn.client_aec = True
    conn.client_listen_mode = "manual"

    asyncio.run(handleAudioMessage(conn, b"voice"))

    assert conn.events == ["asr"]
    assert conn.abort_generation == 0


def test_hello_enables_aec_barge_in_from_device_feature():
    conn = _Conn()
    conn.client_is_speaking = False
    conn.headers = {"device-id": "d1", "client-id": "c1"}
    conn.welcome_msg = {"type": "hello", "audio_params": {}}
    conn.sample_rate = 24000
    conn.audio_format = "opus"

    asyncio.run(handleHelloMessage(conn, {"features": {"aec": True}}))

    assert conn.client_aec is True
    assert conn.features == {"aec": True}
    assert conn.websocket.messages[-1]["type"] == "hello"


def test_complete_asr_stops_non_aec_playback_then_processes_new_turn(monkeypatch):
    import core.handle.receiveAudioHandle as receive_handle

    async def _no_intent(conn, actual_text):
        return False

    monkeypatch.setattr(receive_handle, "handle_user_intent", _no_intent)
    conn = _Conn()

    accepted = asyncio.run(startToChat(conn, "嗯"))

    assert accepted is True
    assert conn.abort_generation == 1
    assert conn.clear_queue_calls == [False]
    assert conn.reset_audio_calls == 1
    assert conn.turn_context.clear_calls == 1
    assert conn.executor.submitted[0][1] == ("嗯",)
    assert conn.websocket.messages[0]["state"] == "stop"
    assert any(message.get("type") == "stt" for message in conn.websocket.messages)


def test_manual_complete_asr_does_not_auto_stop(monkeypatch):
    import core.handle.receiveAudioHandle as receive_handle

    async def _no_intent(conn, actual_text):
        return False

    monkeypatch.setattr(receive_handle, "handle_user_intent", _no_intent)
    conn = _Conn()
    conn.client_listen_mode = "manual"

    accepted = asyncio.run(startToChat(conn, "新的问题"))

    assert accepted is True
    assert conn.abort_generation == 0
    assert conn.clear_queue_calls == []
    assert conn.executor.submitted[0][1] == ("新的问题",)


def test_stt_marks_reply_pending_as_interruptible_like_upstream():
    conn = _Conn()
    conn.client_is_speaking = False

    asyncio.run(send_stt_message(conn, "第二个正常问题"))

    assert conn.client_is_speaking is True
    assert conn._tts_started_at == 0
    assert [message["type"] for message in conn.websocket.messages] == ["stt", "tts"]
    assert conn.websocket.messages[-1]["state"] == "start"


def test_recognized_speaker_json_is_submitted_on_every_turn(monkeypatch):
    import core.handle.receiveAudioHandle as receive_handle

    async def _no_intent(conn, actual_text):
        return False

    monkeypatch.setattr(receive_handle, "handle_user_intent", _no_intent)
    conn = _Conn()
    conn.client_is_speaking = False
    payload = json.dumps(
        {"speaker": "user-a", "speaker_id": "voice-a", "content": "你好"},
        ensure_ascii=False,
    )

    asyncio.run(startToChat(conn, payload))
    asyncio.run(startToChat(conn, payload))

    assert conn.executor.submitted[0][1] == (payload,)
    assert conn.executor.submitted[1][1] == (payload,)
    assert conn.current_speaker == "user-a"
    assert conn.current_speaker_id == "voice-a"
