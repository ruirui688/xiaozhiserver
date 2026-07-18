import asyncio
import concurrent.futures
import queue
import sys
import threading
from pathlib import Path
from types import SimpleNamespace

SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings

settings.config_file_valid = True

from core.handle.receiveAudioHandle import startToChat
from core.handle.reportHandle import _consume_skip_memory_event, enqueue_tool_report
from core.meeting import MeetingMinutesRuntime
from core.semantic.frames import ASRResult, RobotContext
from core.semantic.normalizer import TextNormalizer
from core.semantic.router import SemanticRouter
from core.semantic.semantic_center import SemanticCenter


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


class _TTS:
    def __init__(self):
        self.tts_text_queue = queue.Queue()


class _ToolHandler:
    def upload_functions_desc(self):
        pass


def _conn(chat_handler=None):
    return SimpleNamespace(
        session_id="s1",
        device_id="d1",
        headers={"device-id": "d1"},
        voiceprint_enrollment=None,
        correct_word_replacements={},
        current_speaker=None,
        current_speaker_id=None,
        meeting_minutes=MeetingMinutesRuntime(),
        semantic_center=SemanticCenter(),
        need_bind=False,
        max_output_size=0,
        client_is_speaking=False,
        client_listen_mode="auto",
        client_abort=False,
        _meeting_turn_lock=asyncio.Lock(),
        _abort_lock=threading.Lock(),
        logger=_Logger(),
        tts=_TTS(),
        sentence_id="",
        config={"meeting": {"enabled": True}},
        read_config_from_api=False,
        func_handler=_ToolHandler(),
        llm=object(),
        executor=concurrent.futures.ThreadPoolExecutor(max_workers=1),
        chat=chat_handler or (lambda _query: True),
        report_asr_enable=True,
        report_tts_enable=True,
        chat_history_conf=1,
        report_queue=queue.Queue(),
    )


def _asr(text):
    normalizer = TextNormalizer()
    conn = _conn()
    try:
        normalized = normalizer.normalize(conn, text)
    finally:
        conn.executor.shutdown(wait=True)
    return ASRResult(
        session_id="s1",
        device_id="d1",
        raw_text=text,
        normalized_text=normalized.normalized_text,
        display_text=normalized.display_text,
    )


def _context(current_skill=None):
    return RobotContext(
        device_id="d1",
        session_id="s1",
        state="meeting" if current_skill == "meeting" else "idle",
        current_skill=current_skill,
    )


def test_wake_address_is_stripped_only_at_utterance_head():
    normalizer = TextNormalizer()

    assert normalizer.strip_leading_wake_address("你好小智进入会议模式") == "进入会议模式"
    assert normalizer.strip_leading_wake_address("你好小志进入会议模式") == "进入会议模式"
    assert normalizer.strip_leading_wake_address("你好小知进入会议模式") == "进入会议模式"
    assert normalizer.strip_leading_wake_address("好，小智进入会议模式") == "进入会议模式"
    assert normalizer.strip_leading_wake_address("小智，你在吗") == "你在吗"
    assert normalizer.strip_leading_wake_address("小智服务器怎么部署") == "小智服务器怎么部署"


def test_voiceprint_payload_preserves_speaker_metadata_when_cleaned():
    normalizer = TextNormalizer()
    payload = '{"speaker":"高瑞瑞","speaker_id":"u1","content":"你好小智进入会议模式"}'

    cleaned = normalizer.normalize_runtime_text(payload)

    assert '"speaker": "高瑞瑞"' in cleaned
    assert '"speaker_id": "u1"' in cleaned
    assert '"content": "进入会议模式"' in cleaned


def test_semantic_router_does_not_predefine_meeting_start_or_end_phrases():
    router = SemanticRouter()

    for text, context in (
        ("进入会议模式", _context()),
        ("开始帮我记录", _context()),
        ("结束会议模式", _context(current_skill="meeting")),
        ("退出会议模式", _context(current_skill="meeting")),
        ("会议到这里吧", _context(current_skill="meeting")),
    ):
        frame = router.route(_asr(text), context)
        assert frame.full_name == "chat.normal"


def test_active_meeting_submits_every_turn_to_main_llm(monkeypatch):
    sent_stt = []
    chat_calls = []

    async def fake_send_stt(_conn, text):
        sent_stt.append(text)

    monkeypatch.setattr("core.handle.receiveAudioHandle.send_stt_message", fake_send_stt)
    conn = _conn(chat_calls.append)
    conn.meeting_minutes.start("发布评审")

    try:
        accepted = asyncio.run(startToChat(conn, "今天讨论机器人控制链路。"))
    finally:
        conn.executor.shutdown(wait=True)

    assert accepted is True
    assert sent_stt == ["今天讨论机器人控制链路。"]
    assert chat_calls == ["今天讨论机器人控制链路。"]
    assert conn.meeting_minutes.is_active() is True
    assert conn.meeting_minutes.has_pending_turn() is True
    assert conn.meeting_minutes.build_transcript() == []


def test_exit_phrase_does_not_end_meeting_without_main_llm_tool_call(monkeypatch):
    sent_stt = []
    chat_calls = []

    async def fake_send_stt(_conn, text):
        sent_stt.append(text)

    monkeypatch.setattr("core.handle.receiveAudioHandle.send_stt_message", fake_send_stt)
    conn = _conn(chat_calls.append)
    conn.meeting_minutes.start("发布评审")

    try:
        accepted = asyncio.run(startToChat(conn, "退出会议模式。"))
    finally:
        conn.executor.shutdown(wait=True)

    assert accepted is True
    assert sent_stt == ["退出会议模式。"]
    assert chat_calls == ["退出会议模式。"]
    assert conn.meeting_minutes.is_active() is True
    assert conn.meeting_minutes.has_pending_turn() is True


def test_active_meeting_turns_are_serialized_until_main_llm_decides(monkeypatch):
    async def fake_send_stt(_conn, _text):
        return None

    monkeypatch.setattr("core.handle.receiveAudioHandle.send_stt_message", fake_send_stt)
    first_started = threading.Event()
    release_first = threading.Event()
    chat_calls = []
    conn = _conn()

    def chat(query):
        chat_calls.append(query)
        if len(chat_calls) == 1:
            first_started.set()
            assert release_first.wait(timeout=2)
        conn.meeting_minutes.record_pending_turn()
        return True

    conn.chat = chat
    conn.meeting_minutes.start("发布评审")

    async def scenario():
        first = asyncio.create_task(startToChat(conn, "第一段。"))
        assert await asyncio.to_thread(first_started.wait, 1)
        second = asyncio.create_task(startToChat(conn, "第二段。"))
        await asyncio.sleep(0.05)
        assert chat_calls == ["第一段。"]
        release_first.set()
        await asyncio.gather(first, second)

    try:
        asyncio.run(scenario())
    finally:
        conn.executor.shutdown(wait=True)

    assert chat_calls == ["第一段。", "第二段。"]
    assert [item["text"] for item in conn.meeting_minutes.build_transcript()] == [
        "第一段。",
        "第二段。",
    ]


def test_meeting_tool_reports_skip_memory_event_but_keep_report_queue():
    conn = _conn()
    conn.read_config_from_api = True
    try:
        enqueue_tool_report(conn, "meeting_minutes", {"action": "start"})
        enqueue_tool_report(
            conn,
            "meeting_minutes",
            {"action": "start"},
            '{"result":"已进入会议模式"}',
            report_tool_call=False,
        )

        call_type, call_text, *_ = conn.report_queue.get_nowait()
        result_type, result_text, *_ = conn.report_queue.get_nowait()
        assert call_type == 3
        assert result_type == 3
        assert _consume_skip_memory_event(conn, call_type, call_text) is True
        assert _consume_skip_memory_event(conn, result_type, result_text) is True
    finally:
        conn.executor.shutdown(wait=True)
