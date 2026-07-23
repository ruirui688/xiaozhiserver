from types import SimpleNamespace

from core.providers.tts import base as tts_base
from core.providers.tts.base import TTSProviderBase
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
        ),
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
