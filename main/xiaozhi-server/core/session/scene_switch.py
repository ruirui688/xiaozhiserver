"""Transactional connection-state isolation for cross-agent scene switches."""

import uuid
from dataclasses import dataclass

from core.game import GameSessionManager
from core.meeting import MeetingMinutesRuntime
from core.session.reply_lifecycle import ReplyLifecycle
from core.session.turn_context import TurnContextBuffer
from core.utils.dialogue import Dialogue
from core.utils.voiceprint_enrollment import VoiceprintEnrollmentCollector


@dataclass
class SceneSessionSnapshot:
    session_id: str
    dialogue: object
    game_session_manager: object
    meeting_minutes: object
    turn_context: object
    reply_lifecycle: object
    voiceprint_enrollment: object
    tts_overrides: dict
    current_speaker: object
    current_speaker_id: object
    last_tool_context: object
    sentence_id: object
    calling: bool
    tts_protocol_sentence_id: object
    tts_protocol_start_sent: bool
    tts_protocol_stop_sent: bool


class SceneSwitchIsolation:
    """Reset scene-owned state and restore it if provider activation fails."""

    @staticmethod
    def begin(conn) -> SceneSessionSnapshot:
        options = getattr(conn, "tts_session_options", None)
        snapshot = SceneSessionSnapshot(
            session_id=getattr(conn, "session_id", ""),
            dialogue=getattr(conn, "dialogue", None),
            game_session_manager=getattr(conn, "game_session_manager", None),
            meeting_minutes=getattr(conn, "meeting_minutes", None),
            turn_context=getattr(conn, "turn_context", None),
            reply_lifecycle=getattr(conn, "reply_lifecycle", None),
            voiceprint_enrollment=getattr(conn, "voiceprint_enrollment", None),
            tts_overrides=options.snapshot() if options is not None else {},
            current_speaker=getattr(conn, "current_speaker", None),
            current_speaker_id=getattr(conn, "current_speaker_id", None),
            last_tool_context=getattr(conn, "last_tool_context", None),
            sentence_id=getattr(conn, "sentence_id", None),
            calling=bool(getattr(conn, "calling", False)),
            tts_protocol_sentence_id=getattr(
                conn, "_tts_protocol_sentence_id", None
            ),
            tts_protocol_start_sent=bool(
                getattr(conn, "_tts_protocol_start_sent", False)
            ),
            tts_protocol_stop_sent=bool(
                getattr(conn, "_tts_protocol_stop_sent", False)
            ),
        )

        max_turns = int(
            getattr(snapshot.dialogue, "max_history_turns", 0) or 0
        )
        conn.session_id = str(uuid.uuid4())
        conn.dialogue = Dialogue(max_history_turns=max_turns)
        conn.game_session_manager = GameSessionManager()
        conn.meeting_minutes = MeetingMinutesRuntime()
        conn.turn_context = TurnContextBuffer()
        conn.reply_lifecycle = ReplyLifecycle()
        conn.voiceprint_enrollment = VoiceprintEnrollmentCollector()
        conn.current_speaker = None
        conn.current_speaker_id = None
        conn.last_tool_context = None
        conn.sentence_id = None
        conn.calling = False
        conn._tts_protocol_sentence_id = None
        conn._tts_protocol_start_sent = False
        conn._tts_protocol_stop_sent = False
        if options is not None:
            options.reset()
        return snapshot

    @staticmethod
    def rollback(conn, snapshot: SceneSessionSnapshot) -> None:
        conn.session_id = snapshot.session_id
        conn.dialogue = snapshot.dialogue
        conn.game_session_manager = snapshot.game_session_manager
        conn.meeting_minutes = snapshot.meeting_minutes
        conn.turn_context = snapshot.turn_context
        conn.reply_lifecycle = snapshot.reply_lifecycle
        conn.voiceprint_enrollment = snapshot.voiceprint_enrollment
        conn.current_speaker = snapshot.current_speaker
        conn.current_speaker_id = snapshot.current_speaker_id
        conn.last_tool_context = snapshot.last_tool_context
        conn.sentence_id = snapshot.sentence_id
        conn.calling = snapshot.calling
        conn._tts_protocol_sentence_id = snapshot.tts_protocol_sentence_id
        conn._tts_protocol_start_sent = snapshot.tts_protocol_start_sent
        conn._tts_protocol_stop_sent = snapshot.tts_protocol_stop_sent
        options = getattr(conn, "tts_session_options", None)
        if options is not None:
            options.restore(snapshot.tts_overrides)
