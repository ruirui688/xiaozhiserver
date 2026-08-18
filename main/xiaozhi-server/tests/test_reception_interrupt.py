from types import SimpleNamespace

from core.reception_interrupt import (
    clear_reception_reply_lock,
    is_explicit_stop,
    lock_reception_reply,
    reception_reply_locked,
    defer_reception_turn,
    pop_next_reception_turn,
)


def test_only_explicit_stop_phrases_are_accepted():
    assert is_explicit_stop("闭嘴！") is True
    assert is_explicit_stop("停止播报") is True
    assert is_explicit_stop("我想问一个问题") is False


def test_lock_requires_active_reply_and_can_be_cleared():
    conn = SimpleNamespace(client_is_speaking=True)
    lock_reception_reply(conn, 15.0)
    assert reception_reply_locked(conn) is True
    clear_reception_reply_lock(conn)
    assert reception_reply_locked(conn) is False


def test_deferred_speakers_are_deduplicated_and_randomly_selected():
    conn = SimpleNamespace()
    assert defer_reception_turn(conn, "问题一", "speaker-a") is True
    assert defer_reception_turn(conn, "问题一", "speaker-a") is False
    assert defer_reception_turn(conn, "问题二", "speaker-b") is True

    selected = pop_next_reception_turn(conn, chooser=lambda speakers: speakers[-1])

    assert selected["speaker_id"] == "speaker-b"
    assert selected["text"] == "问题二"
    assert pop_next_reception_turn(conn, chooser=lambda speakers: speakers[0])["speaker_id"] == "speaker-a"
