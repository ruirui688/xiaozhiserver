import asyncio
import threading
from types import SimpleNamespace

import pytest

from core.providers.tts import base as tts_base
from core.providers.tts.base import TTSProviderBase
from core.providers.tts.dto.dto import SentenceType
from core.utils.turn_metrics import TurnMetrics


class _TestTTSProvider(TTSProviderBase):
    async def text_to_speak(self, text, output_file):
        return None


def test_interrupted_partial_assistant_report_is_enqueued_exactly_once(monkeypatch):
    reports = []
    monkeypatch.setattr(
        tts_base,
        "enqueue_tts_report",
        lambda conn, text, audio, metrics=None: reports.append(
            (text, audio, metrics)
        )
        or True,
    )

    metrics = TurnMetrics()
    turn_id = metrics.ensure_text_turn()
    metrics.bind_sentence("sentence-1", turn_id)
    metrics.build_user_metrics(turn_id)
    metrics.record_tts_start("sentence-1")
    metrics.record_tts_audio_frame(20, "sentence-1")
    metrics.record_interruption("sentence-1", "aec_barge_in")

    provider = _TestTTSProvider({}, delete_audio_file=True)
    provider.conn = SimpleNamespace(turn_metrics=metrics, _tts_started_at=1)
    provider.store_tts_text("sentence-1", "已经说出的部分回答")
    provider.mark_report_started("sentence-1")

    assert provider.finalize_interrupted_report("sentence-1") is True
    assert provider.finalize_interrupted_report("sentence-1") is False
    assert len(reports) == 1
    assert reports[0][0] == "已经说出的部分回答"
    assert reports[0][1] == []
    assert reports[0][2]["turn_id"] == turn_id
    assert reports[0][2]["message_role"] == "assistant"
    assert reports[0][2]["message_sequence"] == 2
    assert reports[0][2]["outcome"]["status"] == "aborted"


def test_pending_reply_without_tts_output_is_not_saved_as_heard_answer(monkeypatch):
    reports = []
    monkeypatch.setattr(
        tts_base,
        "enqueue_tts_report",
        lambda *args, **kwargs: reports.append((args, kwargs)),
    )

    metrics = TurnMetrics()
    turn_id = metrics.ensure_text_turn()
    metrics.bind_sentence("sentence-pending", turn_id)
    metrics.record_interruption("sentence-pending", "aec_barge_in")

    provider = _TestTTSProvider({}, delete_audio_file=True)
    provider.conn = SimpleNamespace(turn_metrics=metrics, _tts_started_at=0)
    provider.store_tts_text("sentence-pending", "尚未播放的模型文本")

    assert provider.finalize_interrupted_report("sentence-pending") is False
    assert provider.finalize_interrupted_report("sentence-pending") is False
    assert reports == []
    assert metrics.build_user_metrics(turn_id) is None


def test_provider_subtitle_cleanup_does_not_drop_interrupted_report(monkeypatch):
    reports = []
    monkeypatch.setattr(
        tts_base,
        "enqueue_tts_report",
        lambda conn, text, audio, metrics=None: reports.append(
            (text, audio, metrics)
        )
        or True,
    )

    metrics = TurnMetrics()
    turn_id = metrics.ensure_text_turn()
    metrics.bind_sentence("sentence-stream", turn_id)
    metrics.build_user_metrics(turn_id)
    metrics.record_tts_start("sentence-stream")

    provider = _TestTTSProvider({}, delete_audio_file=True)
    provider.conn = SimpleNamespace(turn_metrics=metrics, _tts_started_at=1)
    provider.store_tts_text("sentence-stream", "这是一段尚未播放完成的长回复")
    provider.mark_report_started("sentence-stream")

    provider.clear_tts_text("sentence-stream")
    assert provider.get_tts_text("sentence-stream") is None
    assert provider.get_report_text("sentence-stream") == "这是一段尚未播放完成的长回复"

    assert provider.finalize_interrupted_report("sentence-stream") is True
    assert provider.finalize_interrupted_report("sentence-stream") is False
    assert [item[0] for item in reports] == ["这是一段尚未播放完成的长回复"]


def test_failed_report_enqueue_releases_final_claim(monkeypatch):
    enqueue_results = iter([False, True])
    reports = []

    def fake_enqueue(conn, text, audio, metrics=None):
        accepted = next(enqueue_results)
        reports.append((accepted, text, metrics))
        return accepted

    monkeypatch.setattr(tts_base, "enqueue_tts_report", fake_enqueue)

    metrics = TurnMetrics()
    turn_id = metrics.ensure_text_turn()
    metrics.bind_sentence("sentence-retry", turn_id)
    metrics.build_user_metrics(turn_id)
    metrics.record_tts_start("sentence-retry")

    provider = _TestTTSProvider({}, delete_audio_file=True)
    provider.conn = SimpleNamespace(turn_metrics=metrics, _tts_started_at=1)
    provider.store_tts_text("sentence-retry", "需要重试上报的回复")
    provider.mark_report_started("sentence-retry")

    assert provider.finalize_interrupted_report("sentence-retry") is False
    assert provider.get_report_text("sentence-retry") == "需要重试上报的回复"
    assert provider.finalize_interrupted_report("sentence-retry") is True
    assert [(accepted, text) for accepted, text, _ in reports] == [
        (False, "需要重试上报的回复"),
        (True, "需要重试上报的回复"),
    ]
    assert reports[0][2] is reports[1][2]
    assert reports[1][2]["turn_id"] == turn_id


def test_missing_abort_text_does_not_block_delayed_normal_last(monkeypatch):
    reports = []
    monkeypatch.setattr(
        tts_base,
        "enqueue_tts_report",
        lambda conn, text, audio, metrics=None: reports.append(text) or True,
    )

    provider = _TestTTSProvider({}, delete_audio_file=True)
    provider.conn = SimpleNamespace(turn_metrics=None, _tts_started_at=1)
    provider.mark_report_started("sentence-delayed")

    assert provider.finalize_interrupted_report("sentence-delayed") is False
    assert provider._enqueue_final_report(
        "sentence-delayed",
        "正常LAST携带的迟到文本",
        [],
    ) is True
    assert reports == ["正常LAST携带的迟到文本"]


def test_normal_last_and_late_abort_share_one_final_report(monkeypatch):
    reports = []
    monkeypatch.setattr(
        tts_base,
        "enqueue_tts_report",
        lambda conn, text, audio, metrics=None: reports.append(text) or True,
    )

    provider = _TestTTSProvider({}, delete_audio_file=True)
    provider.conn = SimpleNamespace(turn_metrics=None, _tts_started_at=1)
    provider.store_tts_text("sentence-complete", "已经正常结束的回答")
    provider.mark_report_started("sentence-complete")

    assert provider._enqueue_final_report(
        "sentence-complete",
        "已经正常结束的回答",
        [],
    ) is True
    assert provider.finalize_interrupted_report("sentence-complete") is False
    assert reports == ["已经正常结束的回答"]


@pytest.mark.parametrize("first_preview", ['"', "这个"])
def test_normal_last_prefers_complete_report_text_over_first_preview(
    monkeypatch, first_preview
):
    reports = []
    report_ready = threading.Event()

    def fake_enqueue(conn, text, audio, metrics=None):
        reports.append((text, audio, metrics))
        report_ready.set()
        return True

    async def fake_send_audio_message(*args, **kwargs):
        return None

    monkeypatch.setattr(tts_base, "enqueue_tts_report", fake_enqueue)
    monkeypatch.setattr(tts_base, "sendAudioMessage", fake_send_audio_message)

    loop = asyncio.new_event_loop()
    loop_thread = threading.Thread(target=loop.run_forever, daemon=True)
    loop_thread.start()

    stop_event = threading.Event()
    provider = _TestTTSProvider({}, delete_audio_file=True)
    provider.report_on_last = True
    provider.conn = SimpleNamespace(
        stop_event=stop_event,
        _abort_lock=threading.Lock(),
        client_abort=False,
        loop=loop,
        turn_metrics=None,
        max_output_size=0,
    )
    provider._audio_message_timeout = lambda sentence_type: 2.0
    provider.store_tts_text("sentence-stream", "这是模型生成并实际播报的完整回答。")

    audio_thread = threading.Thread(
        target=provider._audio_play_priority_thread, daemon=True
    )
    audio_thread.start()
    try:
        provider.tts_audio_queue.put(
            (SentenceType.FIRST, None, first_preview, "sentence-stream")
        )
        provider.tts_audio_queue.put(
            (SentenceType.MIDDLE, b"audio-frame-1", None, "sentence-stream")
        )
        provider.tts_audio_queue.put(
            (SentenceType.MIDDLE, b"audio-frame-2", None, "sentence-stream")
        )
        provider.tts_audio_queue.put(
            (SentenceType.LAST, [], None, "sentence-stream")
        )

        assert report_ready.wait(timeout=2)
    finally:
        stop_event.set()
        audio_thread.join(timeout=1)
        loop.call_soon_threadsafe(loop.stop)
        loop_thread.join(timeout=1)
        loop.close()

    assert len(reports) == 1
    assert reports[0][0] == "这是模型生成并实际播报的完整回答。"
    assert reports[0][1] == [b"audio-frame-1", b"audio-frame-2"]
