import config.settings

config.settings.config_file_valid = True

from core.utils.dialogue import Dialogue, Message


def test_update_system_message_adds_first_system_message_without_deadlock():
    dialogue = Dialogue()

    dialogue.update_system_message("system prompt")

    messages = dialogue.get_llm_dialogue()
    assert messages[0] == {"role": "system", "content": "system prompt"}


def test_update_system_message_replaces_existing_system_message():
    dialogue = Dialogue()
    dialogue.put(Message(role="system", content="old prompt"))

    dialogue.update_system_message("new prompt")

    messages = dialogue.get_llm_dialogue()
    assert messages[0] == {"role": "system", "content": "new prompt"}


def test_history_turn_limit_physically_releases_old_messages():
    dialogue = Dialogue(max_history_turns=20)
    dialogue.put(Message(role="system", content="system prompt"))
    dialogue.put(Message(role="user", content="temporary", is_temporary=True))

    for index in range(10_000):
        dialogue.put(Message(role="user", content=f"question-{index}"))
        dialogue.put(Message(role="assistant", content=f"answer-{index}"))

    stored = dialogue.dialogue
    assert len(stored) == 202
    assert stored[0].role == "system"
    assert stored[1].is_temporary is True
    assert stored[2].content == "question-9900"
    assert stored[-1].content == "answer-9999"


def test_setting_storage_limit_prunes_existing_storage_immediately():
    dialogue = Dialogue(max_stored_messages=200)
    for index in range(15):
        dialogue.put(Message(role="user", content=f"question-{index}"))
        dialogue.put(Message(role="assistant", content=f"answer-{index}"))

    assert dialogue.set_max_stored_messages(20) == 10
    assert [message.content for message in dialogue.dialogue] == [
        item
        for index in range(5, 15)
        for item in (f"question-{index}", f"answer-{index}")
    ]


def test_storage_limit_cannot_exceed_process_hard_ceiling():
    dialogue = Dialogue(max_stored_messages=100_000)

    assert dialogue.max_stored_messages == 200


def _calls(*ids):
    return Message("assistant", tool_calls=[{
        "id": call_id, "type": "function",
        "function": {"name": "lookup", "arguments": "{}"},
    } for call_id in ids])


def test_interrupted_tool_is_completed_before_next_user_without_mutating_evidence():
    dialogue = Dialogue()
    for message in (Message("user", "先帮我查询"), _calls("interrupted"),
                    Message("user", "不查了，我们换个话题")):
        dialogue.put(message)
    original = list(dialogue.dialogue)
    result = dialogue.get_llm_dialogue()
    assert [m["role"] for m in result] == ["user", "assistant", "tool", "user"]
    assert result[2]["tool_call_id"] == "interrupted"
    assert '"status": "interrupted"' in result[2]["content"]
    assert result[-1]["content"] == "不查了，我们换个话题"
    assert dialogue.dialogue == original
    assert len(dialogue.snapshot_for_memory()) == 3


def test_late_orphan_and_duplicate_tools_are_omitted_only_from_outgoing_copy():
    dialogue = Dialogue()
    for message in (Message("user", "先查一下"), _calls("old"),
                    Message("user", "现在查另一项"),
                    Message("tool", "迟到的旧结果", tool_call_id="old"),
                    _calls("new"), Message("tool", "当前结果", tool_call_id="new"),
                    Message("tool", "重复结果", tool_call_id="new"),
                    Message("tool", "孤立结果", tool_call_id="missing")):
        dialogue.put(message)
    original = list(dialogue.dialogue)
    result = dialogue.get_llm_dialogue()
    tools = [m for m in result if m["role"] == "tool"]
    assert [m["tool_call_id"] for m in tools] == ["old", "new"]
    assert '"status": "interrupted"' in tools[0]["content"]
    assert tools[1]["content"] == "当前结果"
    assert dialogue.dialogue == original
    assert any(m.content == "迟到的旧结果" for m in dialogue.snapshot_for_memory())


def test_multiple_tool_results_keep_valid_order_and_fill_only_missing_response():
    dialogue = Dialogue()
    for message in (Message("user", "同时查三项"), _calls("a", "b", "c"),
                    Message("tool", "结果 B", tool_call_id="b"),
                    Message("tool", "结果 A", tool_call_id="a"),
                    Message("assistant", "第三项被取消，先给你前两项。")):
        dialogue.put(message)
    original = list(dialogue.dialogue)
    result = dialogue.get_llm_dialogue()
    assert [m["role"] for m in result] == ["user", "assistant", "tool", "tool", "tool", "assistant"]
    assert [m["tool_call_id"] for m in result if m["role"] == "tool"] == ["b", "a", "c"]
    assert result[2]["content"] == "结果 B" and result[3]["content"] == "结果 A"
    assert '"status": "interrupted"' in result[4]["content"]
    assert dialogue.dialogue == original
