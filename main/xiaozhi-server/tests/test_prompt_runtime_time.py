from datetime import datetime
from pathlib import Path
from unittest.mock import patch

import pytest

from core.utils import current_time
from core.utils.dialogue import Dialogue, Message
from core.utils.prompt_manager import PromptManager


class NullLogger:
    def bind(self, **kwargs):
        return self

    def __getattr__(self, name):
        return lambda *args, **kwargs: None


class Clock(datetime):
    value = datetime(2026, 9, 22, 20, 44, tzinfo=current_time.BEIJING_TZ)
    calls = 0

    @classmethod
    def now(cls, tz=None):
        cls.calls += 1
        return cls.value.astimezone(tz) if tz else cls.value.replace(tzinfo=None)


def build_prompt():
    manager = PromptManager({}, logger=NullLogger())
    manager.base_prompt_template = (
        Path(__file__).resolve().parents[1] / "agent-base-prompt.txt"
    ).read_text(encoding="utf-8")
    return manager.build_enhanced_prompt("离线测试助手", "offline-time-test")


def system_text(messages):
    return "\n".join(m["content"] for m in messages if m["role"] == "system")


def test_same_connection_time_refreshes_on_every_request():
    with patch.object(current_time, "datetime", Clock):
        Clock.value = datetime(2026, 9, 22, 20, 44, tzinfo=current_time.BEIJING_TZ)
        prompt = build_prompt()
        assert "{{current_time}}" in prompt
        dialogue = Dialogue()
        dialogue.update_system_message(prompt)
        initial = dialogue.get_llm_dialogue()
        for minute in range(4, 9):
            Clock.value = datetime(2026, 9, 22, 21, minute, tzinfo=current_time.BEIJING_TZ)
            result = dialogue.get_llm_dialogue_with_memory()
            text = system_text(result)
            assert f"21:{minute:02d} 北京时间" in text
            assert "20:44" not in text
            assert initial[0] == result[0]  # Static prefix unchanged.
        assert dialogue.dialogue[0].content == prompt  # Not frozen by first request.


def test_midnight_refreshes_date_weekday_and_lunar_from_one_snapshot():
    with patch.object(current_time, "datetime", Clock), patch.object(
        current_time, "_lunar_for_date", side_effect=lambda day: f"lunar:{day}"
    ):
        prompt = build_prompt()
        dialogue = Dialogue()
        dialogue.update_system_message(prompt)
        Clock.value = datetime(2026, 9, 23, 0, 1, tzinfo=current_time.BEIJING_TZ)
        Clock.calls = 0
        result = system_text(dialogue.get_llm_dialogue())
        assert Clock.calls == 1
        assert "2026-09-23 00:01 北京时间" in result
        assert "2026-09-23（星期三）" in result
        assert "lunar:2026-09-23" in result
        assert "{{" not in result


@pytest.mark.parametrize("template", [
    "当前时间：{{ current_time }}；日期：{{ today_date }}",
    "角色<context>当前时间：{{current_time}}</context>",
])
def test_custom_template_with_or_without_context_boundary(template):
    manager = PromptManager({}, logger=NullLogger())
    manager.base_prompt_template = template
    dialogue = Dialogue()
    dialogue.update_system_message(manager.build_enhanced_prompt("", "offline-custom"))
    with patch.object(current_time, "datetime", Clock):
        Clock.value = datetime(2026, 9, 23, 1, 2, tzinfo=current_time.BEIJING_TZ)
        assert "2026-09-23 01:02 北京时间" in system_text(dialogue.get_llm_dialogue())


def test_user_history_and_memory_are_not_rewritten_or_template_evaluated():
    dialogue = Dialogue()
    dialogue.update_system_message(build_prompt().replace("</context>", "<memory></memory></context>"))
    original = "昨天20:44说的 {{current_time}} {{7*7}}"
    dialogue.put(Message("user", original))
    dialogue.put(Message("assistant", "历史回复20:44"))
    result = dialogue.get_llm_dialogue_with_memory(memory_str=original)
    assert next(m["content"] for m in result if m["role"] == "user") == original
    assert next(m["content"] for m in result if m["role"] == "assistant") == "历史回复20:44"
    assert original in system_text(result)


def test_concurrent_connections_keep_their_roles_and_reuse_only_calendar_cache():
    from concurrent.futures import ThreadPoolExecutor
    prompt = build_prompt()

    def request(index):
        dialogue = Dialogue()
        dialogue.update_system_message(f"role-{index}\n" + prompt)
        return system_text(dialogue.get_llm_dialogue())

    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(request, range(8)))
    for index, text in enumerate(results):
        assert text.startswith(f"role-{index}\n")
        assert "{{current_time}}" not in text
    assert current_time._lunar_for_date.cache_info().currsize <= 4


@pytest.mark.parametrize("provider", ["qwen", "doubao"])
def test_realtime_session_prompt_does_not_leak_unresolved_tokens(provider):
    from types import SimpleNamespace
    from core.providers.realtime.qwen_omni_realtime import QwenOmniRealtimeSession
    from core.providers.realtime.doubao_realtime import DoubaoRealtimeSession

    cls = QwenOmniRealtimeSession if provider == "qwen" else DoubaoRealtimeSession
    session = cls.__new__(cls)
    session.conn = SimpleNamespace(prompt=build_prompt(), config={})
    with patch.object(current_time, "datetime", Clock):
        Clock.value = datetime(2026, 9, 23, 1, 2, tzinfo=current_time.BEIJING_TZ)
        result = session._session_prompt()
    assert "2026-09-23 01:02 北京时间" in result
    assert "{{current_time}}" in session.conn.prompt
    assert "{{current_time}}" not in result
