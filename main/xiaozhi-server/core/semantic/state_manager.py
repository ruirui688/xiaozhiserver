"""Connection-scoped robot state projection."""

from core.semantic.frames import RobotContext


class StateManager:
    """Project existing connection fields into a unified robot context."""

    def __init__(self):
        self.state = "idle"
        self.current_skill = None
        self.last_intent = None

    def get_context(self, conn) -> RobotContext:
        active_game_tool = None
        if hasattr(conn, "game_session_manager") and conn.game_session_manager:
            active_game_tool = conn.game_session_manager.get_active_tool()

        if active_game_tool:
            state = "game"
            current_skill = active_game_tool
        elif getattr(conn, "client_is_speaking", False):
            state = "speaking"
            current_skill = self.current_skill
        else:
            state = self.state if self.state not in ("speaking", "thinking") else "idle"
            current_skill = self.current_skill

        return RobotContext(
            device_id=getattr(conn, "device_id", None),
            session_id=getattr(conn, "session_id", ""),
            state=state,
            current_skill=current_skill,
            tts_playing=getattr(conn, "client_is_speaking", False),
            motion_running=False,
            active_game_tool=active_game_tool,
            client_is_speaking=getattr(conn, "client_is_speaking", False),
            client_listen_mode=getattr(conn, "client_listen_mode", "auto"),
            last_intent=self.last_intent,
        )

    def record_intent(self, intent_name: str) -> None:
        self.last_intent = intent_name

    def transition(self, next_state: str | None, current_skill: str | None = None) -> None:
        if next_state:
            self.state = next_state
        if current_skill is not None:
            self.current_skill = current_skill

