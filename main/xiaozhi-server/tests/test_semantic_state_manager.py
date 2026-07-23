from types import SimpleNamespace

from core.semantic.state_manager import StateManager


def test_pending_reply_is_interruptible_but_not_reported_as_tts_playing():
    manager = StateManager()
    conn = SimpleNamespace(
        device_id="device-1",
        session_id="session-1",
        client_is_speaking=True,
        client_listen_mode="auto",
        _tts_started_at=0,
        game_session_manager=None,
        meeting_minutes=None,
    )

    pending = manager.get_context(conn)
    assert pending.state == "speaking"
    assert pending.client_is_speaking is True
    assert pending.tts_playing is False

    conn._tts_started_at = 1
    playing = manager.get_context(conn)
    assert playing.tts_playing is True
