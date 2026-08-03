import asyncio
import json
import queue
import threading
from types import SimpleNamespace

from config import settings

settings.config_file_valid = True

from core.external_tts import ExternalTTSCoordinator
from core.handle.textMessageProcessor import TextMessageProcessor
from core.handle.reportThreadManager import ReportQueue
from core.handle import reportHandle
from core.providers.tts import base as tts_base
from core.providers.tts.base import TTSProviderBase
from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO


class _Logger:
    def __init__(self):
        self.info_messages = []

    def bind(self, **_kwargs):
        return self

    def debug(self, *_args, **_kwargs):
        pass

    def info(self, message, *_args, **_kwargs):
        self.info_messages.append(str(message))

    def warning(self, *_args, **_kwargs):
        pass

    def error(self, *_args, **_kwargs):
        pass


class _WebSocket:
    def __init__(self):
        self.messages = []

    async def send(self, value):
        self.messages.append(value)


class _TTS:
    def __init__(self):
        self.tts_text_queue = queue.Queue()
        self.tts_audio_queue = queue.Queue()
        self.stored = []
        self.exact = set()
        self.skip_memory = set()

    def store_tts_text(self, sentence_id, text):
        self.stored.append((sentence_id, text))

    def mark_exact_text(self, sentence_id):
        self.exact.add(sentence_id)

    def clear_exact_text(self, sentence_id):
        self.exact.discard(sentence_id)

    def mark_skip_memory_event(self, sentence_id):
        self.skip_memory.add(sentence_id)

    def finalize_interrupted_report(self, _sentence_id):
        return False


def _connection(device_id="AA:BB:CC:DD:EE:01", **overrides):
    modules_ready = threading.Event()
    modules_ready.set()
    conn = SimpleNamespace(
        device_id=device_id,
        config={"external_tts": {"rate_limit_per_minute": 20}},
        tts=_TTS(),
        websocket=_WebSocket(),
        loop=None,
        _modules_ready=modules_ready,
        initialization_failed=False,
        need_bind=False,
        closing=False,
        closed=False,
        calling=False,
        meeting_minutes=None,
        voiceprint_enrollment=None,
        client_is_speaking=False,
        client_have_voice=False,
        client_abort=False,
        _abort_lock=threading.Lock(),
        sentence_id=None,
        session_id="session-1",
        logger=_Logger(),
        read_config_from_api=True,
        report_tts_enable=True,
        chat_history_conf=2,
        close_after_chat=False,
        abort_generation=0,
        turn_metrics=None,
        turn_context=None,
        _tts_protocol_sentence_id=None,
        _tts_protocol_start_sent=False,
        _tts_protocol_stop_sent=False,
    )

    def clear_speak_status():
        conn.client_is_speaking = False

    def clear_queues(preserve_asr=False):
        del preserve_asr
        for value_queue in (conn.tts.tts_text_queue, conn.tts.tts_audio_queue):
            while True:
                try:
                    value_queue.get_nowait()
                except queue.Empty:
                    break

    def reset_audio_states():
        conn.client_have_voice = False

    conn.clearSpeakStatus = clear_speak_status
    conn.clear_queues = clear_queues
    conn.reset_audio_states = reset_audio_states
    for key, value in overrides.items():
        setattr(conn, key, value)
    return conn


def _external_messages(conn):
    return [
        json.loads(value)
        for value in conn.websocket.messages
        if isinstance(value, str) and json.loads(value).get("type") == "external_tts"
    ]


def test_submit_preserves_exact_text_and_queues_one_device_tts_turn():
    async def scenario():
        coordinator = ExternalTTSCoordinator()
        conn = _connection()
        conn.loop = asyncio.get_running_loop()
        text = "  **原文**，第一行。\n第二行！  "

        await coordinator.handle_message(
            conn,
            {
                "type": "external_tts",
                "requestId": "task-1",
                "text": text,
                "interrupt": False,
            },
        )

        queued = _external_messages(conn)[0]
        assert queued["status"] == "queued"
        assert queued["voiceSource"] == "device_agent"
        assert queued["exactText"] is True
        assert queued["chatHistory"] is True
        assert queued["chatHistoryAudio"] is True
        assert queued["completionBoundary"] == "server_audio_sent"
        assert queued["devicePlaybackConfirmed"] is False

        items = [conn.tts.tts_text_queue.get_nowait() for _ in range(3)]
        assert [item.sentence_type for item in items] == [
            SentenceType.FIRST,
            SentenceType.MIDDLE,
            SentenceType.LAST,
        ]
        assert items[1].content_type is ContentType.TEXT
        assert items[1].content_detail == text
        assert conn.tts.stored == [(queued["sentenceId"], text)]
        assert queued["sentenceId"] in conn.tts.exact
        assert queued["sentenceId"] in conn.tts.skip_memory

    asyncio.run(scenario())


def test_submit_refreshes_runtime_config_before_using_tts_queue():
    async def scenario():
        coordinator = ExternalTTSCoordinator()
        conn = _connection(device_id="AA:BB:CC:DD:EE:10")
        conn.loop = asyncio.get_running_loop()
        old_tts = conn.tts
        new_tts = _TTS()

        class _Reloader:
            async def refresh_if_due(self, *, reason):
                assert reason == "external_tts_start"
                conn.tts = new_tts
                return True

        conn.runtime_config_reloader = _Reloader()
        await coordinator.handle_message(
            conn,
            {
                "type": "external_tts",
                "requestId": "refresh-before-submit",
                "text": "使用新音色播报",
            },
        )

        response = _external_messages(conn)[-1]
        assert response["status"] == "queued"
        assert old_tts.tts_text_queue.empty()
        assert new_tts.tts_text_queue.qsize() == 3
        assert new_tts.stored == [(response["sentenceId"], "使用新音色播报")]

    asyncio.run(scenario())


def test_submit_keeps_old_tts_when_runtime_refresh_fails():
    async def scenario():
        coordinator = ExternalTTSCoordinator()
        conn = _connection(device_id="AA:BB:CC:DD:EE:11")
        conn.loop = asyncio.get_running_loop()
        old_tts = conn.tts

        class _FailingReloader:
            async def refresh_if_due(self, *, reason):
                assert reason == "external_tts_start"
                raise RuntimeError("refresh failed")

        conn.runtime_config_reloader = _FailingReloader()
        await coordinator.handle_message(
            conn,
            {
                "type": "external_tts",
                "requestId": "refresh-failure-fallback",
                "text": "刷新失败也不能丢失",
            },
        )

        response = _external_messages(conn)[-1]
        assert response["status"] == "queued"
        assert conn.tts is old_tts
        assert old_tts.tts_text_queue.qsize() == 3
        assert old_tts.stored == [
            (response["sentenceId"], "刷新失败也不能丢失")
        ]

    asyncio.run(scenario())


def test_duplicate_request_returns_original_job_without_requeueing():
    async def scenario():
        coordinator = ExternalTTSCoordinator()
        conn = _connection(device_id="AA:BB:CC:DD:EE:02")
        conn.loop = asyncio.get_running_loop()
        request = {
            "type": "external_tts",
            "requestId": "same-request",
            "text": "只应播报一次",
        }

        await coordinator.handle_message(conn, request)
        first_job_id = _external_messages(conn)[-1]["jobId"]
        assert conn.tts.tts_text_queue.qsize() == 3

        await coordinator.handle_message(conn, dict(request, text="不得覆盖原任务"))
        duplicate = _external_messages(conn)[-1]
        assert duplicate["jobId"] == first_job_id
        assert duplicate["idempotent"] is True
        assert conn.tts.tts_text_queue.qsize() == 3

    asyncio.run(scenario())


def test_submit_reports_existing_chat_history_configuration():
    async def scenario():
        coordinator = ExternalTTSCoordinator()
        conn = _connection(
            device_id="AA:BB:CC:DD:EE:09",
            chat_history_conf=0,
        )
        conn.loop = asyncio.get_running_loop()

        await coordinator.handle_message(
            conn,
            {
                "type": "external_tts",
                "requestId": "history-config",
                "text": "仍可播报但不覆盖隐私设置",
            },
        )

        response = _external_messages(conn)[-1]
        assert response["status"] == "queued"
        assert response["chatHistory"] is False
        assert response["chatHistoryAudio"] is False

    asyncio.run(scenario())


def test_busy_device_rejects_non_interrupting_request():
    async def scenario():
        coordinator = ExternalTTSCoordinator()
        conn = _connection(
            device_id="AA:BB:CC:DD:EE:03",
            client_is_speaking=True,
        )
        conn.loop = asyncio.get_running_loop()

        await coordinator.handle_message(
            conn,
            {
                "type": "external_tts",
                "requestId": "busy-request",
                "text": "不能插播",
                "interrupt": False,
            },
        )

        response = _external_messages(conn)[-1]
        assert response["success"] is False
        assert response["status"] == "busy"
        assert conn.tts.tts_text_queue.empty()

    asyncio.run(scenario())


def test_unbound_device_cannot_submit_external_tts():
    async def scenario():
        coordinator = ExternalTTSCoordinator()
        conn = _connection(
            device_id="AA:BB:CC:DD:EE:08",
            need_bind=True,
        )
        conn.loop = asyncio.get_running_loop()

        await coordinator.handle_message(
            conn,
            {
                "type": "external_tts",
                "requestId": "unbound-request",
                "text": "不能播报",
            },
        )

        response = _external_messages(conn)[-1]
        assert response["success"] is False
        assert response["status"] == "unavailable"
        assert conn.tts.tts_text_queue.empty()

    asyncio.run(scenario())


def test_interrupting_request_uses_abort_generation_before_new_turn():
    async def scenario():
        coordinator = ExternalTTSCoordinator()
        conn = _connection(
            device_id="AA:BB:CC:DD:EE:04",
            client_is_speaking=True,
            sentence_id="old-sentence",
        )
        conn.loop = asyncio.get_running_loop()

        await coordinator.handle_message(
            conn,
            {
                "type": "external_tts",
                "requestId": "interrupt-request",
                "text": "新的播报",
                "interrupt": True,
            },
        )

        response = _external_messages(conn)[-1]
        assert response["status"] == "queued"
        assert conn.abort_generation == 1
        assert conn.sentence_id == response["sentenceId"]
        assert conn.client_abort is False
        assert conn.tts.tts_text_queue.qsize() == 3

    asyncio.run(scenario())


def test_status_transitions_end_at_server_audio_sent_boundary():
    async def scenario():
        coordinator = ExternalTTSCoordinator()
        conn = _connection(device_id="AA:BB:CC:DD:EE:05")
        conn.loop = asyncio.get_running_loop()
        await coordinator.handle_message(
            conn,
            {
                "type": "external_tts",
                "requestId": "status-request",
                "text": "状态播报",
            },
        )
        sentence_id = _external_messages(conn)[0]["sentenceId"]

        coordinator.mark_synthesizing(conn, sentence_id)
        coordinator.mark_streaming(conn, sentence_id)
        coordinator.mark_completed(conn, sentence_id)
        await asyncio.sleep(0)

        statuses = [item["status"] for item in _external_messages(conn)]
        assert statuses == ["queued", "synthesizing", "streaming", "completed"]
        assert _external_messages(conn)[-1]["progressPercent"] == 100
        assert _external_messages(conn)[-1]["devicePlaybackConfirmed"] is False

        job_id = _external_messages(conn)[-1]["jobId"]
        await coordinator.handle_message(
            conn,
            {"type": "external_tts", "action": "status", "jobId": job_id},
        )
        assert _external_messages(conn)[-1]["status"] == "completed"

    asyncio.run(scenario())


def test_external_tts_chat_report_skips_long_term_memory(monkeypatch):
    calls = {"chat": [], "memory": []}

    async def fake_chat_report(**kwargs):
        calls["chat"].append(kwargs)

    async def fake_memory_report(**kwargs):
        calls["memory"].append(kwargs)

    monkeypatch.setattr(reportHandle, "manage_report", fake_chat_report)
    monkeypatch.setattr(reportHandle, "report_memory_event", fake_memory_report)
    conn = SimpleNamespace(
        device_id="AA:BB:CC:DD:EE:06",
        session_id="session-1",
        config={"agent_id": "agent-1"},
        logger=_Logger(),
    )

    asyncio.run(
        reportHandle.report(
            conn,
            2,
            "写入聊天记录的主动播报",
            [],
            100,
            skip_memory_event=True,
        )
    )

    assert [item["content"] for item in calls["chat"]] == [
        "写入聊天记录的主动播报"
    ]
    assert calls["memory"] == []


class _BaseProvider(TTSProviderBase):
    async def text_to_speak(self, text, output_file):
        return None


def test_exact_mode_bypasses_markdown_correction_and_server_segmentation():
    provider = _BaseProvider(
        {"correct_words": ["原词|替换词"]},
        delete_audio_file=True,
    )
    provider.current_sentence_id = "sentence-exact"
    provider.mark_exact_text("sentence-exact")
    original = "**原词**，保持标点！\n"
    provider.tts_text_buff = [original]
    provider.processed_chars = 0

    assert provider._get_segment_text() is None
    assert provider._text_for_synthesis(original) == original
    assert provider._prepare_synthesis_text(original) == original
    assert provider._clean_synthesis_text(original) == original
    assert provider._match_stream_text(original) == ([original], "")
    assert provider._text_for_log(original) == (
        f"<external_tts:{len(original)} chars>"
    )


def test_normal_stream_cleaning_does_not_apply_correction_twice():
    provider = _BaseProvider(
        {"correct_words": ["原词|原词扩展"]},
        delete_audio_file=True,
    )

    assert provider._clean_synthesis_text("**原词**") == "原词"
    assert provider._match_stream_text("原词") == (["原词扩展"], "")


def test_non_finite_numeric_config_uses_safe_default():
    assert ExternalTTSCoordinator._number("nan", 20.0) == 20.0
    assert ExternalTTSCoordinator._number("inf", 20.0) == 20.0
    assert ExternalTTSCoordinator._number("12", 20.0) == 12.0


def test_tts_report_queue_carries_explicit_skip_memory_flag():
    conn = SimpleNamespace(
        read_config_from_api=True,
        need_bind=False,
        report_tts_enable=True,
        chat_history_conf=1,
        report_queue=ReportQueue(),
        device_id="AA:BB:CC:DD:EE:07",
        logger=_Logger(),
    )

    assert reportHandle.enqueue_tts_report(
        conn,
        "主动播报",
        [],
        skip_memory_event=True,
    ) is True
    queued = conn.report_queue.get_nowait()
    assert queued[1] == "主动播报"
    assert queued[5] is True


def test_provider_report_state_forwards_skip_memory_flag(monkeypatch):
    calls = []

    def fake_enqueue(conn, text, audio, metrics=None, skip_memory_event=False):
        calls.append((conn, text, audio, metrics, skip_memory_event))
        return True

    monkeypatch.setattr(tts_base, "enqueue_tts_report", fake_enqueue)
    provider = _BaseProvider({}, delete_audio_file=True)
    provider.conn = SimpleNamespace(turn_metrics=None)
    provider.store_tts_text("sentence-report", "主动播报正文")
    provider.mark_skip_memory_event("sentence-report")

    assert provider._enqueue_final_report(
        "sentence-report",
        "主动播报正文",
        [],
    ) is True
    assert calls[0][1] == "主动播报正文"
    assert calls[0][4] is True


def test_external_tts_message_log_redacts_customer_text():
    class Handler:
        async def handle(self, _conn, _message):
            return None

    class Registry:
        def get_handler(self, message_type):
            return Handler() if message_type == "external_tts" else None

    logger = _Logger()
    conn = SimpleNamespace(logger=logger, websocket=_WebSocket())
    secret_text = "这段客户正文不能进入运行日志"
    message = json.dumps(
        {
            "type": "external_tts",
            "requestId": "log-test",
            "text": secret_text,
        },
        ensure_ascii=False,
    )

    asyncio.run(TextMessageProcessor(Registry()).process_message(conn, message))

    combined = "\n".join(logger.info_messages)
    assert secret_text not in combined
    assert f"<redacted:{len(secret_text)} chars>" in combined
