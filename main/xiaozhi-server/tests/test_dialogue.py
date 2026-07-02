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
