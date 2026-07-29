import queue
import sys
from pathlib import Path

import pytest


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.providers.tts.direct_answer_stream import (
    DirectAnswerStreamConfig,
    DirectAnswerTTSStream,
)


class CapturingTTS:
    def __init__(self):
        self.tts_text_queue = queue.Queue()
        self.previews = []

    def store_tts_preview(self, sentence_id, text):
        self.previews.append((sentence_id, text))


def test_stream_keeps_json_tail_and_stores_preview_before_first_queue_item():
    tts = CapturingTTS()
    config = DirectAnswerStreamConfig(json_tail_chars=5)
    stream = DirectAnswerTTSStream(
        tts, "sentence-1", config, generation=7
    )

    assert stream.update("惠易") == []
    assert stream.update("惠易新能源") == []
    assert stream.update("惠易新能源专") == ["惠"]
    chunks = stream.update("惠易新能源专注于")

    assert chunks == ["易新"]
    queued = list(tts.tts_text_queue.queue)
    assert tts.previews == [("sentence-1", "惠")]
    assert [item.content_detail for item in queued] == ["惠", "易新"]
    assert all(item.generation == 7 for item in queued)


def test_final_flush_preserves_complete_answer_without_json_suffix():
    tts = CapturingTTS()
    config = DirectAnswerStreamConfig()
    stream = DirectAnswerTTSStream(tts, "sentence-1", config)
    answer = "惠易新能源提供自动化冲压、智能物流和A12焊接生产线等完整解决方案。"

    stream.update(answer[:-3])
    stream.finish(answer)

    queued = list(tts.tts_text_queue.queue)
    assert "".join(item.content_detail for item in queued) == answer
    assert all(not item.content_detail.endswith('"}') for item in queued)


def test_emitted_prefix_cannot_be_rewritten_by_later_snapshot():
    stream = DirectAnswerTTSStream(
        CapturingTTS(),
        "sentence-1",
        DirectAnswerStreamConfig(json_tail_chars=5),
    )
    stream.update("已经确认的回答。后续内容")

    with pytest.raises(ValueError, match="发送后发生变化"):
        stream.update("已经篡改的回答。后续内容")


@pytest.mark.parametrize(
    "value,match",
    [
        ({"json_tail_chars": 0}, "json_tail_chars"),
        ({"json_tail_chars": "invalid"}, "json_tail_chars"),
    ],
)
def test_invalid_stream_configuration_is_rejected(value, match):
    with pytest.raises(ValueError, match=match):
        DirectAnswerStreamConfig.from_mapping(value)


def test_legacy_natural_chunk_settings_are_ignored():
    config = DirectAnswerStreamConfig.from_mapping(
        {
            "enabled": True,
            "json_tail_chars": 5,
            "first_min_chars": 24,
            "next_min_chars": 12,
            "target_chars": 36,
            "max_chars": 60,
        }
    )

    assert config == DirectAnswerStreamConfig(enabled=True, json_tail_chars=5)
