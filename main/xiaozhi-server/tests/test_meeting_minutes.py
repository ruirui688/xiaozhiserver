import asyncio
import concurrent.futures
import sys
import threading
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace

import pytest

SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings

settings.config_file_valid = True

from core.handle.receiveAudioHandle import startToChat
from core.meeting import MeetingMinutesRuntime
from core.providers.asr.base import ASRProviderBase


def test_start_and_end_change_meeting_activity():
    runtime = MeetingMinutesRuntime()

    assert runtime.is_active() is False
    started = runtime.start("产品评审", timestamp=datetime(2026, 7, 13, 9, 0))
    assert started["active"] is True
    assert started["topic"] == "产品评审"

    ended = runtime.end(timestamp=datetime(2026, 7, 13, 10, 0))
    assert runtime.is_active() is False
    assert ended["endedAt"] == "2026-07-13T10:00:00"

    with pytest.raises(RuntimeError):
        runtime.append_utterance("会议已结束后的发言")


def test_anonymous_speakers_receive_numbered_labels():
    runtime = MeetingMinutesRuntime()
    runtime.start()
    runtime.append_utterance("先讨论发布计划。")
    runtime.append_utterance("我负责准备公告。")

    transcript = runtime.build_transcript()

    assert [item["speakerLabel"] for item in transcript] == ["说话人1", "说话人2"]
    assert [item["displaySpeaker"] for item in transcript] == ["说话人1", "说话人2"]


def test_resolved_speaker_overrides_anonymous_label_in_display():
    runtime = MeetingMinutesRuntime()
    runtime.start()
    runtime.append_utterance(
        "发布前要完成回归测试。",
        speaker_label="说话人1",
        resolved_speaker="张敏",
        audio=b"audio",
        duration_ms=1250,
    )

    utterance = runtime.build_transcript()[0]

    assert utterance["speakerLabel"] == "说话人1"
    assert utterance["resolvedSpeaker"] == "张敏"
    assert utterance["displaySpeaker"] == "张敏"
    assert utterance["audioBytes"] == 5
    assert utterance["durationMs"] == 1250


def test_pending_asr_turn_is_recorded_only_after_main_llm_tool_action():
    runtime = MeetingMinutesRuntime()
    runtime.start("发布评审")
    payload = {
        "content": "先看发布计划。我负责验收。",
        "utterances": [
            {
                "text": "先看发布计划。",
                "provider_speaker": "spk_1",
                "start_time": 0,
                "end_time": 1200,
            },
            {
                "text": "我负责验收。",
                "provider_speaker": "spk_2",
                "resolved_speaker": "高瑞瑞",
                "start_time": 1300,
                "end_time": 2400,
            },
        ],
    }

    runtime.prepare_turn(payload, payload["content"], "高瑞瑞")

    assert runtime.has_pending_turn() is True
    assert runtime.build_transcript() == []
    assert runtime.record_pending_turn() == 2
    assert runtime.has_pending_turn() is False
    transcript = runtime.build_transcript()
    assert [item["displaySpeaker"] for item in transcript] == ["说话人1", "高瑞瑞"]
    assert [item["durationMs"] for item in transcript] == [1200, 1100]


def test_end_discards_control_utterance_from_full_transcript():
    runtime = MeetingMinutesRuntime()
    runtime.start("发布评审")
    runtime.prepare_turn(None, "先完成回归测试。", "张敏")
    runtime.record_pending_turn()
    runtime.prepare_turn(None, "现在结束会议并生成纪要", "张敏")

    runtime.discard_pending_turn()
    runtime.end(timestamp="2026-07-13 09:30")

    transcript = runtime.build_transcript()
    assert [item["text"] for item in transcript] == ["先完成回归测试。"]
    assert runtime.has_pending_turn() is False


def test_active_meeting_turn_waits_for_main_llm_tool_decision(monkeypatch):
    async def fake_send_stt(_conn, _text):
        return None

    monkeypatch.setattr(
        "core.handle.receiveAudioHandle.send_stt_message",
        fake_send_stt,
    )
    runtime = MeetingMinutesRuntime()
    runtime.start("发布评审")
    chat_calls = []

    def chat(query):
        chat_calls.append(query)
        return True

    async def run_turn():
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            conn = SimpleNamespace(
                voiceprint_enrollment=None,
                correct_word_replacements={},
                current_speaker=None,
                current_speaker_id=None,
                meeting_minutes=runtime,
                llm=object(),
                _meeting_turn_lock=asyncio.Lock(),
                _abort_lock=threading.Lock(),
                client_abort=False,
                executor=executor,
                chat=chat,
                read_config_from_api=True,
                need_bind=False,
                report_asr_enable=True,
                report_tts_enable=True,
                chat_history_conf=1,
            )
            assert await startToChat(conn, "先完成回归测试。") is True

    asyncio.run(run_turn())

    assert chat_calls == ["先完成回归测试。"]
    assert runtime.has_pending_turn() is True
    assert runtime.build_transcript() == []


def test_summary_prompt_contains_complete_meeting_transcript():
    runtime = MeetingMinutesRuntime()
    runtime.start("发布评审", timestamp="2026-07-13 09:00")
    runtime.append_utterance(
        "本周五前完成回归测试。",
        speaker_label="说话人1",
        resolved_speaker="张敏",
        timestamp="2026-07-13 09:05",
    )
    runtime.append_utterance(
        "公告由我负责。",
        speaker_label="说话人2",
        resolved_speaker="李明",
        timestamp="2026-07-13 09:10",
    )
    runtime.end(timestamp="2026-07-13 09:30")

    prompt = runtime.build_summary_prompt()

    for expected in (
        "会议主题：发布评审",
        "会议时间：2026-07-13 09:00至2026-07-13 09:30",
        "参会人：张敏、李明",
        "本周五前完成回归测试。",
        "公告由我负责。",
        "议题总结",
        "关键结论",
        "待办事项",
        "风险与待确认",
    ):
        assert expected in prompt


def test_provider_speaker_labels_are_normalized():
    runtime = MeetingMinutesRuntime()
    runtime.start()
    runtime.append_utterance("第一句", speaker_label="spk_1")
    runtime.append_utterance("第二句", speaker_label="speaker1")
    runtime.append_utterance("第三句", speaker_label="guest")

    transcript = runtime.build_transcript()

    assert transcript[0]["displaySpeaker"] == "说话人1"
    assert transcript[1]["displaySpeaker"] == "说话人1"
    assert transcript[2]["displaySpeaker"] == "说话人2"


def test_asr_segment_time_supports_seconds_and_milliseconds():
    assert ASRProviderBase._extract_utterance_time_ms(
        {"start": 1, "end": 2.5}
    ) == (1000, 2500)
    assert ASRProviderBase._extract_utterance_time_ms(
        {"start_time": 1000, "end_time": 2500}
    ) == (1000, 2500)
