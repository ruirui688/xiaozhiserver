import asyncio
import concurrent.futures
import json
import threading
from types import SimpleNamespace

import pytest

from config import settings

settings.config_file_valid = True

import core.handle.receiveAudioHandle as receive_handle
import core.handle.textHandler.listenMessageHandler as listen_handler_module
from core.handle.receiveAudioHandle import startToChat
from core.handle.textHandler.listenMessageHandler import ListenTextMessageHandler
from core.interaction import (
    AddressingDecision,
    AddressingInput,
    AttentionMode,
    InteractionAttentionConfig,
    InteractionAttentionManager,
    InteractionState,
    InteractionTrigger,
)
from core.meeting import MeetingMinutesRuntime
from core.session.turn_context import TurnContextBuffer
from core.speech.routing import resolve_speech_route
from core.speech.runtime import SpeechRuntime


class _Logger:
    def bind(self, **_kwargs):
        return self

    def debug(self, *_args, **_kwargs):
        pass

    def info(self, *_args, **_kwargs):
        pass

    def warning(self, *_args, **_kwargs):
        pass

    def error(self, *_args, **_kwargs):
        pass


class _WebSocket:
    def __init__(self):
        self.messages = []

    async def send(self, payload):
        self.messages.append(json.loads(payload))


class _RecordingAttentionManager(InteractionAttentionManager):
    def __init__(self, mode=AttentionMode.ENFORCE):
        super().__init__(InteractionAttentionConfig(enabled=True, mode=mode))
        self.activations = []
        self.evaluations = []
        self.results = []

    def activate(self, trigger, now=None):
        self.activations.append(InteractionTrigger(trigger))
        return super().activate(trigger, now=now)

    def evaluate(self, signals, now=None):
        self.evaluations.append(signals)
        result = super().evaluate(signals, now=now)
        self.results.append(result)
        return result


class _SemanticSpy:
    def __init__(self, *, handled=True, side_effects=None):
        self.handled = handled
        self.calls = []
        self.side_effects = side_effects

    async def handle_user_text(self, _conn, text, **kwargs):
        self.calls.append((text, kwargs))
        if self.side_effects is not None:
            self.side_effects.extend(["tool", "chat_history", "long_term_memory"])
        return self.handled


class _Enrollment:
    status = SimpleNamespace(active=True)


def _base_conn(*, attention, semantic=None):
    return SimpleNamespace(
        session_id="session-1",
        device_id="device-1",
        headers={"device-id": "device-1"},
        interaction_attention=attention,
        voiceprint_enrollment=None,
        correct_word_replacements={},
        current_speaker=None,
        current_speaker_id=None,
        meeting_minutes=None,
        semantic_center=semantic or _SemanticSpy(),
        need_bind=False,
        max_output_size=0,
        client_is_speaking=False,
        client_listen_mode="auto",
        client_abort=False,
        _abort_lock=threading.Lock(),
        logger=_Logger(),
        llm=object(),
        executor=concurrent.futures.ThreadPoolExecutor(max_workers=1),
        chat=lambda *_args, **_kwargs: True,
        reply_lifecycle=None,
        read_config_from_api=False,
        report_asr_enable=False,
        report_tts_enable=False,
        chat_history_conf=0,
    )


def _close_conn(conn):
    executor = getattr(conn, "executor", None)
    if executor is not None:
        executor.shutdown(wait=True)


def _device_asr_conn(attention):
    conn = SimpleNamespace(
        interaction_attention=attention,
        client_listen_mode="auto",
        conversation_router=None,
        conversation_ready=SimpleNamespace(is_set=lambda: True),
        initialization_failed=False,
        logger=_Logger(),
        client_have_voice=False,
        turn_context=TurnContextBuffer(),
        last_activity_time=0,
        config={
            "wakeup_words": ["小智"],
            "enable_greeting": False,
            "interaction_attention": {
                "enabled": True,
                "mode": attention.config.mode.value,
            },
        },
        websocket=_WebSocket(),
        session_id="session-1",
        read_config_from_api=False,
        need_bind=False,
        report_asr_enable=False,
        chat_history_conf=0,
        reset_audio_states=lambda **_kwargs: None,
    )
    conn.speech_runtime = SpeechRuntime(conn)
    conn.speech_runtime.route = resolve_speech_route(
        {"speech_routing": {"input": "device", "output": "server"}},
        {
            "speech": {
                "version": 1,
                "input": ["device_asr"],
                "output": [],
                "asr_final_text": True,
                "tts_playback_ack": False,
            }
        },
    )
    return conn


def test_enforce_dormant_rejects_ordinary_text_before_semantic_intent_and_llm(
    monkeypatch,
):
    calls = []
    attention = _RecordingAttentionManager()
    semantic = _SemanticSpy(handled=False)
    conn = _base_conn(attention=attention, semantic=semantic)

    async def fake_intent(*_args, **_kwargs):
        calls.append("intent")
        return False

    async def fake_send_stt(*_args, **_kwargs):
        return None

    monkeypatch.setattr(receive_handle, "handle_user_intent", fake_intent)
    monkeypatch.setattr(receive_handle, "send_stt_message", fake_send_stt)
    conn.chat = lambda *_args, **_kwargs: calls.append("llm") or True

    try:
        accepted = asyncio.run(startToChat(conn, "今晚我们去哪里吃饭"))
    finally:
        _close_conn(conn)

    assert accepted is False
    assert len(attention.evaluations) == 1
    assert attention.results[0].decision == AddressingDecision.UNDIRECTED
    assert semantic.calls == []
    assert calls == []


def test_shadow_records_decision_without_changing_legacy_route(monkeypatch):
    calls = []
    attention = _RecordingAttentionManager(AttentionMode.SHADOW)
    semantic = _SemanticSpy(handled=False)
    conn = _base_conn(attention=attention, semantic=semantic)

    async def fake_intent(_conn, text, **_kwargs):
        calls.append(("intent", text))
        return True

    monkeypatch.setattr(receive_handle, "handle_user_intent", fake_intent)

    try:
        accepted = asyncio.run(startToChat(conn, "今晚我们去哪里吃饭"))
    finally:
        _close_conn(conn)

    assert accepted is True
    assert [item.decision for item in attention.results] == [
        AddressingDecision.UNDIRECTED
    ]
    assert [item[0] for item in semantic.calls] == ["今晚我们去哪里吃饭"]
    assert calls == [("intent", "今晚我们去哪里吃饭")]


@pytest.mark.parametrize("trigger", ["wake_word", "steering_button", "screen_button"])
def test_explicit_trigger_activates_first_turn(monkeypatch, trigger):
    attention = _RecordingAttentionManager()
    conn = _device_asr_conn(attention)
    routed = []

    async def fake_start_to_chat(_conn, text, turn_context=None, turn_id=None, input_source=None, **kwargs):
        routed.append((text, turn_context, turn_id))
        return True

    monkeypatch.setattr(listen_handler_module, "startToChat", fake_start_to_chat)
    monkeypatch.setattr(listen_handler_module, "enqueue_asr_report", lambda *_args: None)

    asyncio.run(
        ListenTextMessageHandler().handle(
            conn,
            {
                "type": "listen",
                "state": "detect",
                "source": "device_asr",
                "final": True,
                "turn_id": f"turn-{trigger}",
                "text": "打开空调",
                "interaction": {"trigger": trigger},
            },
        )
    )

    assert attention.state == InteractionState.ARMED
    assert attention.activations == [InteractionTrigger(trigger)]
    assert routed == [("打开空调", None, f"turn-{trigger}")]


def test_awaiting_reply_short_answer_is_allowed_before_semantic_route():
    attention = _RecordingAttentionManager()
    attention.mark_assistant_finished(asked_question=True)
    semantic = _SemanticSpy(handled=True)
    conn = _base_conn(attention=attention, semantic=semantic)

    try:
        accepted = asyncio.run(startToChat(conn, "二十四度"))
    finally:
        _close_conn(conn)

    assert accepted is True
    assert len(attention.evaluations) == 1
    assert attention.results[0].state_before == InteractionState.AWAITING_REPLY
    assert attention.results[0].decision == AddressingDecision.DIRECTED
    assert [item[0] for item in semantic.calls] == ["二十四度"]


def test_unknown_addressing_executes_no_tool_and_writes_no_history_or_memory():
    side_effects = []
    attention = _RecordingAttentionManager()
    attention.evaluate(
        AddressingInput(
            text="小智",
            trigger=InteractionTrigger.WAKE_WORD,
        )
    )
    attention.evaluations.clear()
    attention.results.clear()
    semantic = _SemanticSpy(handled=True, side_effects=side_effects)
    conn = _base_conn(attention=attention, semantic=semantic)

    try:
        accepted = asyncio.run(startToChat(conn, "你把窗户关一下"))
    finally:
        _close_conn(conn)

    assert accepted is False
    assert len(attention.evaluations) == 1
    assert attention.results[0].decision == AddressingDecision.UNKNOWN_ADDRESSING
    assert semantic.calls == []
    assert side_effects == []


def test_meeting_and_voiceprint_enrollment_preserve_existing_protected_semantics(
    monkeypatch,
):
    attention = _RecordingAttentionManager()

    enrollment_conn = _base_conn(attention=attention)
    enrollment_conn.voiceprint_enrollment = _Enrollment()
    try:
        enrollment_accepted = asyncio.run(startToChat(enrollment_conn, "我是张三"))
    finally:
        _close_conn(enrollment_conn)

    meeting_calls = []
    meeting_conn = _base_conn(attention=attention)
    meeting_conn.meeting_minutes = MeetingMinutesRuntime()
    meeting_conn.meeting_minutes.start("项目评审")
    meeting_conn._meeting_turn_lock = asyncio.Lock()
    meeting_conn.chat = lambda text: meeting_calls.append(text) or True

    async def fake_send_stt(_conn, _text):
        return None

    monkeypatch.setattr(receive_handle, "send_stt_message", fake_send_stt)
    try:
        meeting_accepted = asyncio.run(startToChat(meeting_conn, "记录这一段讨论"))
    finally:
        _close_conn(meeting_conn)

    assert enrollment_accepted is False
    assert meeting_accepted is True
    assert meeting_calls == ["记录这一段讨论"]
    assert attention.evaluations == []


def test_invalid_device_asr_interaction_metadata_fails_closed_in_enforce_mode(
    monkeypatch,
):
    attention = _RecordingAttentionManager()
    conn = _device_asr_conn(attention)
    routed = []

    async def fake_start_to_chat(*args, **kwargs):
        routed.append((args, kwargs))
        return True

    monkeypatch.setattr(listen_handler_module, "startToChat", fake_start_to_chat)
    monkeypatch.setattr(listen_handler_module, "enqueue_asr_report", lambda *_args: None)

    asyncio.run(
        ListenTextMessageHandler().handle(
            conn,
            {
                "type": "listen",
                "state": "detect",
                "source": "device_asr",
                "final": True,
                "turn_id": "turn-invalid-interaction",
                "text": "打开空调",
                "interaction": {
                    "trigger": ["wake_word"],
                    "speaker_match": "yes",
                    "unexpected": {"nested": "payload"},
                },
            },
        )
    )

    assert routed == []
    assert attention.activations == []
    assert conn.websocket.messages[-1]["accepted"] is False
    assert conn.websocket.messages[-1]["reason"] == "invalid_interaction_metadata"


def test_speaker_match_alone_cannot_unlock_dormant_without_zone_or_trigger():
    attention = _RecordingAttentionManager()
    semantic = _SemanticSpy(handled=True)
    conn = _base_conn(attention=attention, semantic=semantic)
    text = json.dumps(
        {
            "speaker": "驾驶员",
            "speaker_id": "driver",
            "content": "把温度调低一点",
        },
        ensure_ascii=False,
    )

    try:
        accepted = asyncio.run(startToChat(conn, text))
    finally:
        _close_conn(conn)

    assert accepted is False
    assert len(attention.evaluations) == 1
    assert attention.evaluations[0].speaker_identity == "driver"
    assert attention.results[0].decision == AddressingDecision.UNDIRECTED
    assert semantic.calls == []
