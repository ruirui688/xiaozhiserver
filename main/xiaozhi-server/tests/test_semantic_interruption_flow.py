import asyncio
import json
import queue
import sys
import threading
import time
from pathlib import Path
from concurrent.futures import Future


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.handle.receiveAudioHandle import startToChat
from core.handle.sendAudioHandle import send_stt_message
from core.handle.textHandler.listenMessageHandler import ListenTextMessageHandler
from core.utils.dialogue import Dialogue, Message


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
    def __init__(self):
        self.messages = []

    async def send(self, message):
        self.messages.append(json.loads(message))


class _LLM:
    def __init__(self, decision):
        self.decision = decision
        self.calls = []

    def response_no_stream(self, **kwargs):
        self.calls.append(kwargs)
        return json.dumps(self.decision, ensure_ascii=False)


class _Executor:
    def __init__(self):
        self.submitted = []

    def submit(self, fn, *args):
        self.submitted.append((fn, args))
        future = Future()
        future.set_result(None)
        return future


class _Conn:
    def __init__(self, decision):
        self.config = {
            "semantic_interruption": {"timeout_seconds": 1.0},
            "wakeup_words": [],
        }
        self.llm = _LLM(decision)
        self.logger = _Logger()
        self.dialogue = Dialogue()
        self.dialogue.put(Message(role="user", content="介绍一下这个展品"))
        self.dialogue.put(Message(role="assistant", content="这个展品来自明代。"))
        self.websocket = _WebSocket()
        self.executor = _Executor()
        self.headers = {"device-id": "d1"}
        self.asr_audio_queue = queue.Queue()
        self.need_bind = False
        self.max_output_size = 0
        self.client_is_speaking = True
        self.client_listen_mode = "auto"
        self.client_abort = False
        self.close_after_chat = False
        self.abort_generation = 0
        self._abort_lock = threading.Lock()
        self.session_id = "s1"
        self.current_speaker = None
        self.correct_word_replacements = {}
        self.semantic_center = None
        self._tts_started_at = 0

    def clear_queues(self):
        self.queues_cleared = True

    def reset_audio_states(self):
        self.audio_states_reset = True

    def clearSpeakStatus(self):
        self.client_is_speaking = False

    def chat(self, text):
        return text


async def _start_to_chat_with_intent_patch(monkeypatch, text, decision):
    import core.handle.receiveAudioHandle as receive_handle

    async def _no_intent(conn, actual_text):
        return False

    monkeypatch.setattr(receive_handle, "handle_user_intent", _no_intent)
    conn = _Conn(decision)
    accepted = await startToChat(conn, text)
    return conn, accepted


def test_overlap_continue_does_not_abort_or_submit_chat(monkeypatch):
    conn, accepted = asyncio.run(
        _start_to_chat_with_intent_patch(
            monkeypatch,
            "嗯",
            {"decision": "continue", "reason": "短反馈"},
        )
    )

    assert accepted is False
    assert conn.abort_generation == 0
    assert conn.executor.submitted == []
    assert all(message.get("state") != "stop" for message in conn.websocket.messages)


def test_stt_does_not_mark_speaking_before_tts_has_audio():
    conn = _Conn({"decision": "continue", "reason": "短反馈"})
    conn.client_is_speaking = False

    asyncio.run(send_stt_message(conn, "第二个正常问题"))

    assert conn.client_is_speaking is False
    assert conn._tts_started_at == 0
    assert [message["type"] for message in conn.websocket.messages] == ["stt"]


def test_overlap_json_content_is_used_for_llm_classification(monkeypatch):
    conn, accepted = asyncio.run(
        _start_to_chat_with_intent_patch(
            monkeypatch,
            json.dumps(
                {"speaker": "user-a", "language": "zh", "content": "嗯"},
                ensure_ascii=False,
            ),
            {"decision": "continue", "reason": "短反馈"},
        )
    )

    assert accepted is False
    payload = json.loads(conn.llm.calls[0]["user_prompt"])
    assert payload["overlap_user_text"] == "嗯"
    assert conn.current_speaker == "user-a"
    assert conn.abort_generation == 0
    assert conn.executor.submitted == []


def test_overlap_new_turn_aborts_then_submits_new_chat(monkeypatch):
    conn, accepted = asyncio.run(
        _start_to_chat_with_intent_patch(
            monkeypatch,
            "等等我问另一个问题",
            {"decision": "new_turn", "reason": "新问题"},
        )
    )

    assert accepted is True
    assert conn.abort_generation == 1
    assert conn.executor.submitted
    assert conn.executor.submitted[0][1] == ("等等我问另一个问题",)
    assert conn.websocket.messages[0]["type"] == "tts"
    assert conn.websocket.messages[0]["state"] == "stop"
    assert any(message.get("type") == "stt" for message in conn.websocket.messages)


def test_overlap_new_turn_is_not_suppressed_when_tts_just_started(monkeypatch):
    conn = _Conn({"decision": "new_turn", "reason": "新问题"})
    conn._tts_started_at = time.time() * 1000

    import core.handle.receiveAudioHandle as receive_handle

    async def _no_intent(conn, actual_text):
        return False

    monkeypatch.setattr(receive_handle, "handle_user_intent", _no_intent)

    accepted = asyncio.run(startToChat(conn, "等一下我问个问题"))

    assert accepted is True
    assert conn.llm.calls
    assert conn.abort_generation == 1
    assert conn.executor.submitted[0][1] == ("等一下我问个问题",)


def test_overlap_interrupt_aborts_without_submitting_chat(monkeypatch):
    conn, accepted = asyncio.run(
        _start_to_chat_with_intent_patch(
            monkeypatch,
            "别说了",
            {"decision": "interrupt", "reason": "停止播报"},
        )
    )

    assert accepted is True
    assert conn.abort_generation == 1
    assert conn.executor.submitted == []
    assert conn.websocket.messages[0]["type"] == "tts"
    assert conn.websocket.messages[0]["state"] == "stop"


def test_overlap_interrupt_control_stops_after_semantic_center(monkeypatch):
    import core.handle.receiveAudioHandle as receive_handle

    class _SemanticCenter:
        def __init__(self):
            self.calls = 0
            self.state_manager = self

        def get_context(self, conn):
            return None

        async def handle_user_text(self, conn, text):
            self.calls += 1
            return True

    async def _no_intent(conn, actual_text):
        return False

    monkeypatch.setattr(receive_handle, "handle_user_intent", _no_intent)
    conn = _Conn({"decision": "interrupt", "reason": "停止播报"})
    conn.semantic_center = _SemanticCenter()

    accepted = asyncio.run(startToChat(conn, "先停一下"))

    assert accepted is True
    assert conn.abort_generation == 1
    assert conn.semantic_center.calls == 1
    assert conn.executor.submitted == []


def test_overlap_interrupt_with_new_request_falls_through_to_chat(monkeypatch):
    import core.handle.receiveAudioHandle as receive_handle

    class _SemanticCenter:
        def __init__(self):
            self.calls = 0
            self.state_manager = self

        def get_context(self, conn):
            return None

        async def handle_user_text(self, conn, text):
            self.calls += 1
            return False

    async def _no_intent(conn, actual_text):
        return False

    monkeypatch.setattr(receive_handle, "handle_user_intent", _no_intent)
    conn = _Conn({"decision": "interrupt", "reason": "用户明确要求自己唱"})
    conn.semantic_center = _SemanticCenter()

    accepted = asyncio.run(startToChat(conn, "要你自己唱"))

    assert accepted is True
    assert conn.abort_generation == 1
    assert conn.semantic_center.calls == 1
    assert conn.executor.submitted
    assert conn.executor.submitted[0][1] == ("要你自己唱",)
    assert any(message.get("type") == "stt" for message in conn.websocket.messages)


def test_listen_detect_continue_is_not_reported(monkeypatch):
    import core.handle.textHandler.listenMessageHandler as listen_handler
    import core.handle.receiveAudioHandle as receive_handle

    reports = []

    async def _no_intent(conn, actual_text):
        return False

    monkeypatch.setattr(receive_handle, "handle_user_intent", _no_intent)
    monkeypatch.setattr(
        listen_handler,
        "enqueue_asr_report",
        lambda conn, text, opus_data: reports.append((text, opus_data)),
    )

    conn = _Conn({"decision": "continue", "reason": "短反馈"})
    asyncio.run(
        ListenTextMessageHandler().handle(
            conn,
            {"type": "listen", "state": "detect", "text": "？"},
        )
    )

    assert reports == []
    assert conn.executor.submitted == []


def test_listen_detect_new_turn_is_reported(monkeypatch):
    import core.handle.textHandler.listenMessageHandler as listen_handler
    import core.handle.receiveAudioHandle as receive_handle

    reports = []

    async def _no_intent(conn, actual_text):
        return False

    monkeypatch.setattr(receive_handle, "handle_user_intent", _no_intent)
    monkeypatch.setattr(
        listen_handler,
        "enqueue_asr_report",
        lambda conn, text, opus_data: reports.append((text, opus_data)),
    )

    conn = _Conn({"decision": "new_turn", "reason": "新问题"})
    asyncio.run(
        ListenTextMessageHandler().handle(
            conn,
            {"type": "listen", "state": "detect", "text": "不是这个"},
        )
    )

    assert reports == [("不是这个", [])]
    assert conn.executor.submitted[0][1] == ("不是这个",)


def test_listen_detect_new_turn_is_not_suppressed_when_tts_just_started(monkeypatch):
    import core.handle.textHandler.listenMessageHandler as listen_handler
    import core.handle.receiveAudioHandle as receive_handle

    reports = []

    async def _no_intent(conn, actual_text):
        return False

    monkeypatch.setattr(receive_handle, "handle_user_intent", _no_intent)
    monkeypatch.setattr(
        listen_handler,
        "enqueue_asr_report",
        lambda conn, text, opus_data: reports.append((text, opus_data)),
    )

    conn = _Conn({"decision": "new_turn", "reason": "新问题"})
    conn._tts_started_at = time.time() * 1000
    asyncio.run(
        ListenTextMessageHandler().handle(
            conn,
            {"type": "listen", "state": "detect", "text": "等一下我问个问题"},
        )
    )

    assert conn.llm.calls
    assert reports == [("等一下我问个问题", [])]
    assert conn.abort_generation == 1
    assert conn.executor.submitted[0][1] == ("等一下我问个问题",)
