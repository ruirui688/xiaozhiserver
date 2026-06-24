"""Shared pytest fixtures for xiaozhi-server tests."""
import sys
import os
from pathlib import Path

import pytest

# Add project root to path (do this once, not in every test file)
SERVER_ROOT = Path(__file__).resolve().parent
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))


@pytest.fixture
def server_root():
    """Return the xiaozhi-server root path."""
    return SERVER_ROOT


@pytest.fixture
def sample_conn():
    """Create a minimal mock connection object for testing.

    Returns a SimpleNamespace with the minimum attributes needed
    by most semantic/handler tests.
    """
    from types import SimpleNamespace
    conn = SimpleNamespace()
    conn.session_id = "test-session-001"
    conn.client_id = "test-client-001"
    conn.config = {}
    conn.dialogue = SimpleNamespace()
    conn.dialogue.put = lambda x: None
    conn.dialogue.get_llm_dialogue_with_memory = lambda *a, **kw: []
    conn.tts = SimpleNamespace()
    conn.tts.state = "idle"
    conn.game_session = None
    conn.music_play_state = SimpleNamespace()
    conn.music_play_state.is_playing = False
    conn.client_abort = False
    conn.asr_text_buffer = ""
    conn.tts_queue = []
    return conn
