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
from core.handle.reportHandle import (
    _consume_skip_memory_event,
    enqueue_tool_report,
    enqueue_asr_report,
    mark_skip_memory_event,
)
from core.meeting import MeetingMinutesRuntime
from core.meeting.control import MeetingControlParser
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
        self.stored = []
        self.spoken = []

    def store_tts_text(self, sentence_id, text):
        self.stored.append((sentence_id, text))

    def tts_one_sentence(self, _conn, _content_type, content_detail, sentence_id):
        self.spoken.append((sentence_id, content_detail))


class _ToolHandler:
    def __init__(self):
        self.refresh_count = 0

    def upload_functions_desc(self):
        self.refresh_count += 1


def _conn():
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
        chat=lambda _query: (_ for _ in ()).throw(AssertionError("chat must not run")),
        report_asr_enable=True,
        report_tts_enable=True,
        chat_history_conf=1,
        report_queue=queue.Queue(),
    )


def _asr(text):
    normalizer = TextNormalizer()
    normalized = normalizer.normalize(_conn(), text)
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


def test_meeting_control_routes_direct_commands_and_avoids_discussion():
    router = SemanticRouter()

    start = router.route(_asr("好，小智进入会议模式"), _context())
    assert start.full_name == "meeting.start"

    end = router.route(_asr("会议结束，生成纪要"), _context(current_skill="meeting"))
    assert end.full_name == "meeting.end"

    for text in ("我们以后可能需要会议模式", "他说进入会议模式这个命令不好用"):
        frame = router.route(_asr(text), _context())
        assert frame.full_name == "chat.normal"


def test_meeting_control_parser_rejects_end_when_meeting_is_inactive():
    parser = MeetingControlParser()

    assert parser.parse("结束会议", active=False).action == "end"
    assert parser.parse("我们以后可能需要会议模式", active=False) is None
    assert parser.parse("他说进入会议模式这个命令不好用", active=False) is None


def test_deterministic_start_enters_meeting_without_main_llm(monkeypatch):
    sent_stt = []

    async def fake_send_stt(_conn, text):
        sent_stt.append(text)

    monkeypatch.setattr("core.handle.receiveAudioHandle.send_stt_message", fake_send_stt)
    monkeypatch.setattr("core.handle.sendAudioHandle.send_stt_message", fake_send_stt)
    conn = _conn()

    try:
        accepted = asyncio.run(startToChat(conn, "好，小智进入会议模式"))
    finally:
        conn.executor.shutdown(wait=True)

    assert accepted is True
    assert conn.meeting_minutes.is_active() is True
    assert sent_stt == ["进入会议模式"]
    assert conn.tts.spoken[-1][1] == "已进入会议模式，我会静默记录。需要结束时，直接告诉我即可。"
    assert conn.func_handler.refresh_count == 1


def test_wake_alias_start_is_cleaned_and_skips_memory(monkeypatch):
    sent_stt = []

    async def fake_send_stt(_conn, text):
        sent_stt.append(text)

    monkeypatch.setattr("core.handle.receiveAudioHandle.send_stt_message", fake_send_stt)
    monkeypatch.setattr("core.handle.sendAudioHandle.send_stt_message", fake_send_stt)
    conn = _conn()

    try:
        accepted = asyncio.run(startToChat(conn, "你好小志进入会议模式"))
    finally:
        conn.executor.shutdown(wait=True)

    assert accepted is True
    assert conn.meeting_minutes.is_active() is True
    assert sent_stt == ["进入会议模式"]
    assert _consume_skip_memory_event(conn, 1, "进入会议模式") is True


def test_active_meeting_records_ordinary_asr_silently_without_llm(monkeypatch):
    sent_stt = []

    async def fake_send_stt(_conn, text):
        sent_stt.append(text)

    monkeypatch.setattr("core.handle.receiveAudioHandle.send_stt_message", fake_send_stt)
    conn = _conn()
    conn.meeting_minutes.start("发布评审")
    chat_calls = []
    conn.chat = lambda query: chat_calls.append(query)

    try:
        accepted = asyncio.run(startToChat(conn, "今天讨论机器人控制链路。"))
    finally:
        conn.executor.shutdown(wait=True)

    assert accepted is True
    assert chat_calls == []
    assert sent_stt == ["今天讨论机器人控制链路。"]
    assert [item["text"] for item in conn.meeting_minutes.build_transcript()] == [
        "今天讨论机器人控制链路。"
    ]


def test_active_meeting_end_generates_summary_without_recording_control(monkeypatch):
    sent_stt = []
    finished = []

    async def fake_send_stt(_conn, text):
        sent_stt.append(text)

    def fake_finish(conn):
        finished.append(conn)
        conn.meeting_minutes.end()

    monkeypatch.setattr("core.handle.receiveAudioHandle.send_stt_message", fake_send_stt)
    monkeypatch.setattr("core.handle.receiveAudioHandle.finish_meeting", fake_finish)
    conn = _conn()
    conn.meeting_minutes.start("发布评审")
    conn.meeting_minutes.append_utterance("先完成回归测试。")

    try:
        accepted = asyncio.run(startToChat(conn, "会议结束，生成纪要"))
    finally:
        conn.executor.shutdown(wait=True)

    assert accepted is True
    assert conn.meeting_minutes.is_active() is False
    assert finished == [conn]
    assert sent_stt == ["会议结束，生成纪要"]
    assert conn.func_handler.refresh_count == 1
    assert [item["text"] for item in conn.meeting_minutes.build_transcript()] == [
        "先完成回归测试。"
    ]


def test_asr_report_uses_cleaned_text_and_can_skip_memory_event():
    conn = _conn()
    conn.read_config_from_api = True
    try:
        original = "你好小智进入会议模式"
        cleaned = "进入会议模式"
        from core.handle.reportHandle import mark_asr_report_text_override

        mark_asr_report_text_override(conn, original, cleaned)
        mark_skip_memory_event(conn, 1, cleaned, "meeting_control")

        enqueue_asr_report(conn, original, [])

        report_type, report_text, _audio, _time, _metrics = conn.report_queue.get_nowait()
        assert report_type == 1
        assert report_text == cleaned
        assert _consume_skip_memory_event(conn, report_type, report_text) is True
    finally:
        conn.executor.shutdown(wait=True)


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


def test_interrupt_meeting_control_marks_skip_memory_event(monkeypatch):
    sent_stt = []

    class _Classification:
        should_abort = True
        category = "interrupt"
        reason = "test"

    async def fake_classify(self, _conn, _text, _context):
        return _Classification()

    async def fake_abort(_conn):
        return None

    async def fake_send_stt(_conn, text):
        sent_stt.append(text)

    monkeypatch.setattr(
        "core.handle.receiveAudioHandle.SemanticInterruptionClassifier.classify",
        fake_classify,
    )
    monkeypatch.setattr("core.handle.receiveAudioHandle.handleAbortMessage", fake_abort)
    monkeypatch.setattr("core.handle.receiveAudioHandle.send_stt_message", fake_send_stt)
    monkeypatch.setattr("core.handle.sendAudioHandle.send_stt_message", fake_send_stt)
    conn = _conn()
    conn.client_is_speaking = True

    try:
        accepted = asyncio.run(startToChat(conn, "进入会议模式"))
    finally:
        conn.executor.shutdown(wait=True)

    assert accepted is True
    assert conn.meeting_minutes.is_active() is True
    assert sent_stt == ["进入会议模式"]
    assert _consume_skip_memory_event(conn, 1, "进入会议模式") is True
