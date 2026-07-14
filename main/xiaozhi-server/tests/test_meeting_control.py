import asyncio
import json
import time
from types import SimpleNamespace

from core.meeting import (
    MeetingControlClassifier,
    MeetingMinutesRuntime,
    get_meeting_entry_prompt,
    is_meeting_mode_enabled,
)


class _Logger:
    def bind(self, **_kwargs):
        return self

    def warning(self, *_args, **_kwargs):
        pass


class _Model:
    def __init__(self, response, delay=0):
        self.response = response
        self.delay = delay
        self.calls = []

    def response_no_stream(self, system_prompt, user_prompt):
        self.calls.append((system_prompt, user_prompt))
        if self.delay:
            time.sleep(self.delay)
        return self.response


def _meeting():
    meeting = MeetingMinutesRuntime()
    meeting.start("发布评审")
    meeting.append_utterance("先确认发布时间。", resolved_speaker="张敏")
    return meeting


def _conn(slm=None, llm=None, timeout=1.0, threshold=0.9):
    return SimpleNamespace(
        slm=slm,
        llm=llm,
        logger=_Logger(),
        config={
            "meeting": {
                "control_timeout_seconds": timeout,
                "end_confidence_threshold": threshold,
            }
        },
    )


def test_classifier_uses_slm_and_recent_transcript():
    slm = _Model('{"decision":"end","confidence":0.96,"reason":"明确结束"}')
    llm = _Model('{"decision":"continue","confidence":1,"reason":"不应调用"}')

    result = asyncio.run(
        MeetingControlClassifier().classify(
            _conn(slm=slm, llm=llm),
            "今天先到这里，整理一下纪要",
            _meeting(),
        )
    )

    assert result.should_end is True
    assert result.model_source == "slm"
    assert llm.calls == []
    payload = json.loads(slm.calls[0][1])
    assert payload["current_asr_text"] == "今天先到这里，整理一下纪要"
    assert payload["recent_transcript"][0]["text"] == "先确认发布时间。"


def test_classifier_rejects_low_confidence_end_decision():
    slm = _Model('{"decision":"end","confidence":0.72,"reason":"可能是讨论内容"}')

    result = asyncio.run(
        MeetingControlClassifier().classify(
            _conn(slm=slm),
            "会议结束后再发布",
            _meeting(),
        )
    )

    assert result.should_end is False
    assert result.decision == "continue"
    assert result.reason.startswith("low_confidence:")


def test_classifier_timeout_defaults_to_continue():
    slm = _Model('{"decision":"end","confidence":1,"reason":"结束"}', delay=0.05)

    result = asyncio.run(
        MeetingControlClassifier().classify(
            _conn(slm=slm, timeout=0.01),
            "结束记录",
            _meeting(),
        )
    )

    assert result.should_end is False
    assert result.reason == "model_timeout"


def test_manager_runtime_requires_agent_meeting_plugin():
    disabled = SimpleNamespace(
        read_config_from_api=True,
        config={"meeting": {"enabled": True}, "plugins": {}},
    )
    enabled = SimpleNamespace(
        read_config_from_api=True,
        config={
            "meeting": {"enabled": False},
            "plugins": {
                "meeting_minutes": {
                    "entry_prompt": "会议记录开始。",
                }
            },
        },
    )

    assert is_meeting_mode_enabled(disabled) is False
    assert is_meeting_mode_enabled(enabled) is True
    assert get_meeting_entry_prompt(enabled) == "会议记录开始。"
