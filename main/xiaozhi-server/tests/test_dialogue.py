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
