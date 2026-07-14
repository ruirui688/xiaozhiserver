import asyncio
import concurrent.futures
import json
import queue
import sys
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
from core.meeting import (
    MEETING_STARTED_PROMPT,
    MeetingMinutesRuntime,
    is_meeting_start_command,
)
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


def test_summary_prompt_contains_meeting_fields_and_transcript_text():
    runtime = MeetingMinutesRuntime()
    runtime.start("发布评审", timestamp="2026-07-13 09:00")
    runtime.append_utterance(
        "本周五前完成回归测试。",
        speaker_label="说话人1",
        resolved_speaker="张敏",
        timestamp="2026-07-13 09:05",
    )
    runtime.end(timestamp="2026-07-13 09:30")

    prompt = runtime.build_summary_prompt()

    for expected in (
        "会议主题：发布评审",
        "会议时间：2026-07-13 09:00至2026-07-13 09:30",
        "参会人：张敏",
        "本周五前完成回归测试。",
        "议题总结",
        "关键结论",
        "待办事项",
        "风险与待确认",
    ):
        assert expected in prompt


def test_meeting_command_detection():
    assert is_meeting_start_command("小智，开始会议纪要")
    assert is_meeting_start_command("进入会议模式，主题是产品评审")


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


def test_start_to_chat_records_meeting_until_end_only():
    conn = _fake_conn(
        control_responses=[
            '{"decision":"continue","confidence":0.98,"reason":"会议内容"}',
            '{"decision":"end","confidence":0.97,"reason":"明确结束"}',
        ]
    )

    asyncio.run(startToChat(conn, "开始会议纪要，主题是周会"))
    assert conn.meeting_minutes.is_active() is True
    assert conn.llm.calls == []
    assert conn.slm.control_calls == []
    assert conn.tts.spoken == [MEETING_STARTED_PROMPT]

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
    asyncio.run(startToChat(conn, json.dumps(payload, ensure_ascii=False)))

    assert conn.llm.calls == []
    assert len(conn.slm.control_calls) == 1
    assert conn.tts.spoken == [MEETING_STARTED_PROMPT]
    transcript = conn.meeting_minutes.build_transcript()
    assert [item["displaySpeaker"] for item in transcript] == ["说话人1", "高瑞瑞"]

    asyncio.run(startToChat(conn, "好了，今天先到这里，帮我整理一下"))

    assert conn.meeting_minutes.is_active() is False
    assert len(conn.llm.calls) == 1
    assert len(conn.slm.control_calls) == 2
    assert "先看发布计划。" in conn.llm.calls[0][1]
    assert "我负责验收。" in conn.llm.calls[0][1]
    assert "好了，今天先到这里" not in conn.llm.calls[0][1]
    assert conn.tts.spoken == [MEETING_STARTED_PROMPT, "整理后的会议纪要"]


def test_low_confidence_end_decision_keeps_full_meeting_context():
    conn = _fake_conn(
        control_responses=[
            '{"decision":"end","confidence":0.62,"reason":"含糊提到结束"}',
        ]
    )

    asyncio.run(startToChat(conn, "进入会议模式"))
    asyncio.run(startToChat(conn, "会议结束后我们再安排发布"))

    assert conn.meeting_minutes.is_active() is True
    transcript = conn.meeting_minutes.build_transcript()
    assert [item["text"] for item in transcript] == ["会议结束后我们再安排发布"]
    assert conn.llm.calls == []


def test_meeting_control_falls_back_to_main_llm_when_slm_is_missing():
    conn = _fake_conn(
        control_responses=[
            '{"decision":"continue","confidence":0.99,"reason":"继续讨论"}',
        ],
        use_slm=False,
    )

    asyncio.run(startToChat(conn, "进入会议模式"))
    asyncio.run(startToChat(conn, "继续讨论发布计划"))

    assert conn.meeting_minutes.is_active() is True
    assert len(conn.llm.control_calls) == 1
    assert conn.meeting_minutes.build_transcript()[0]["text"] == "继续讨论发布计划"


def test_asr_segment_time_supports_seconds_and_milliseconds():
    assert ASRProviderBase._extract_utterance_time_ms(
        {"start": 1, "end": 2.5}
    ) == (1000, 2500)
    assert ASRProviderBase._extract_utterance_time_ms(
        {"start_time": 1000, "end_time": 2500}
    ) == (1000, 2500)


class _NoopLogger:
    def bind(self, **_kwargs):
        return self

    def info(self, *_args, **_kwargs):
        pass

    def debug(self, *_args, **_kwargs):
        pass

    def warning(self, *_args, **_kwargs):
        pass

    def error(self, *_args, **_kwargs):
        pass


class _ImmediateExecutor:
    def submit(self, fn, *args, **kwargs):
        future = concurrent.futures.Future()
        try:
            future.set_result(fn(*args, **kwargs))
        except Exception as exc:
            future.set_exception(exc)
        return future


class _FakeLLM:
    def __init__(self, control_responses=None, summary_response="整理后的会议纪要"):
        self.calls = []
        self.control_calls = []
        self.control_responses = list(control_responses or [])
        self.summary_response = summary_response

    def response_no_stream(self, system_prompt, user_prompt, **_kwargs):
        if "会议记录模式的控制意图裁决器" in system_prompt:
            self.control_calls.append((system_prompt, user_prompt))
            if self.control_responses:
                return self.control_responses.pop(0)
            return '{"decision":"continue","confidence":1.0,"reason":"默认继续"}'
        self.calls.append((system_prompt, user_prompt))
        return self.summary_response


class _FakeTTS:
    def __init__(self):
        self.spoken = []
        self.tts_text_queue = queue.Queue()

    def store_tts_text(self, _sentence_id, text):
        self.spoken.append(text)

    def tts_one_sentence(self, *_args, **_kwargs):
        pass


def _fake_conn(control_responses=None, use_slm=True):
    main_llm = _FakeLLM(control_responses=None)
    slm = _FakeLLM(control_responses=control_responses) if use_slm else None
    if not use_slm:
        main_llm.control_responses = list(control_responses or [])
    return SimpleNamespace(
        logger=_NoopLogger(),
        config={
            "meeting": {
                "enabled": True,
                "control_timeout_seconds": 1.0,
                "end_confidence_threshold": 0.9,
            }
        },
        read_config_from_api=False,
        correct_word_replacements={},
        introduced_speakers=set(),
        current_speaker=None,
        current_speaker_id=None,
        meeting_minutes=MeetingMinutesRuntime(),
        executor=_ImmediateExecutor(),
        llm=main_llm,
        slm=slm,
        tts=_FakeTTS(),
        sentence_id=None,
        client_abort=False,
        _abort_lock=__import__("threading").Lock(),
    )
