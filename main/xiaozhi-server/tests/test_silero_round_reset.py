import sys
from pathlib import Path
from types import SimpleNamespace

import numpy as np

SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings

settings.config_file_valid = True

from core.providers.vad.silero import VADProvider


class _Activity:
    def __init__(self):
        self.reset_calls = []

    def reset_activity(self, preserve_noise_floor=True):
        self.reset_calls.append(preserve_noise_floor)


def _provider_and_conn():
    provider = VADProvider.__new__(VADProvider)
    conn = SimpleNamespace(
        _vad_state=np.full((2, 1, 128), 3.0, dtype=np.float32),
        _vad_context=np.full((1, 64), 4.0, dtype=np.float32),
        _speech_activity_state=_Activity(),
        _vad_last_decision=object(),
    )
    return provider, conn


def test_new_round_reset_clears_silero_recurrent_state_and_context():
    provider, conn = _provider_and_conn()

    provider.reset_conn_state(conn, reset_model=True)

    assert np.count_nonzero(conn._vad_state) == 0
    assert np.count_nonzero(conn._vad_context) == 0
    assert conn._speech_activity_state.reset_calls == [True]
    assert conn._vad_last_decision is None


def test_lightweight_endpoint_reset_preserves_silero_recurrent_state():
    provider, conn = _provider_and_conn()

    provider.reset_conn_state(conn)

    assert np.all(conn._vad_state == 3.0)
    assert np.all(conn._vad_context == 4.0)
    assert conn._speech_activity_state.reset_calls == [True]


def test_repeated_new_round_resets_are_idempotent():
    provider, conn = _provider_and_conn()

    provider.reset_conn_state(conn, reset_model=True)
    conn._vad_state.fill(7.0)
    conn._vad_context.fill(8.0)
    provider.reset_conn_state(conn, reset_model=True)

    assert np.count_nonzero(conn._vad_state) == 0
    assert np.count_nonzero(conn._vad_context) == 0
    assert len(conn._speech_activity_state.reset_calls) == 2


def test_legacy_vad_hook_remains_compatible_with_full_reset_request():
    class LegacyVAD:
        def __init__(self):
            self.calls = []

        def reset_conn_state(self, conn):
            self.calls.append(conn)

    legacy = LegacyVAD()
    conn = SimpleNamespace(vad=legacy, asr_audio=[], client_audio_buffer=bytearray(),
                           client_have_voice=True, client_voice_stop=True,
                           client_voice_window=[], last_is_voice=True,
                           vad_last_voice_time=1.0, voiceprint_provider=None,
                           logger=SimpleNamespace(bind=lambda **_: SimpleNamespace(debug=lambda *_: None)),
                           asr=None)
    # Exercise only the compatibility hook through the production method.
    from core.connection import ConnectionHandler
    ConnectionHandler.reset_audio_states(conn, reset_vad_model=True)
    assert legacy.calls == [conn]
