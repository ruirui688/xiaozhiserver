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
    NaturalTextChunkBuffer,
)


class CapturingTTS:
    def __init__(self):
        self.tts_text_queue = queue.Queue()
        self.previews = []

    def store_tts_preview(self, sentence_id, text):
        self.previews.append((sentence_id, text))


def test_small_deltas_are_aggregated_into_natural_chunks():
    config = DirectAnswerStreamConfig(
        json_tail_chars=5,
        first_min_chars=12,
        next_min_chars=6,
        target_chars=16,
        max_chars=24,
    )
    buffer = NaturalTextChunkBuffer(config)
    text = ""
    emitted = []

    for delta in ["惠易", "公司", "专注", "新能源", "装备", "制造，", "并提供", "智能物流。"]:
        text += delta
        emitted.extend(buffer.update(text))

    emitted.extend(buffer.update(text, final=True))

    assert "".join(emitted) == text
    assert emitted[0].endswith("，")
    assert len(emitted[0]) >= config.first_min_chars
    assert all(chunk for chunk in emitted)


def test_stream_keeps_json_tail_and_stores_preview_before_first_queue_item():
    tts = CapturingTTS()
    config = DirectAnswerStreamConfig(
        json_tail_chars=5,
        first_min_chars=8,
        next_min_chars=6,
        target_chars=12,
        max_chars=20,
    )
    stream = DirectAnswerTTSStream(
        tts, "sentence-1", config, generation=7
    )

    assert stream.update("这是第一段自然短句。尾部") == []
    chunks = stream.update("这是第一段自然短句。后续内容还在生成")

    assert chunks == ["这是第一段自然短句。"]
    queued = tts.tts_text_queue.get_nowait()
    assert tts.previews == [("sentence-1", chunks[0])]
    assert queued.content_detail == chunks[0]
    assert queued.generation == 7


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
    config = DirectAnswerStreamConfig(
        first_min_chars=6,
        next_min_chars=4,
        target_chars=8,
        max_chars=12,
    )
    buffer = NaturalTextChunkBuffer(config)
    buffer.update("已经确认的回答。后续内容")

    with pytest.raises(ValueError, match="发送后发生变化"):
        buffer.update("已经篡改的回答。后续内容")


@pytest.mark.parametrize(
    "value,match",
    [
        ({"first_min_chars": 0}, "first_min_chars"),
        ({"first_min_chars": 30, "target_chars": 20}, "first_min_chars"),
        (
            {"first_min_chars": 10, "next_min_chars": 30, "target_chars": 20},
            "next_min_chars",
        ),
        ({"target_chars": 80, "max_chars": 60}, "target_chars"),
    ],
)
def test_invalid_stream_configuration_is_rejected(value, match):
    with pytest.raises(ValueError, match=match):
        DirectAnswerStreamConfig.from_mapping(value)
