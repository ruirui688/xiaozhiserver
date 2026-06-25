"""Policy decisions for semantic intents."""

from core.semantic.frames import Decision, IntentFrame, RobotContext


class PolicyEngine:
    """Decide whether a routed intent can execute in the current state."""

    def decide(self, intent: IntentFrame, context: RobotContext) -> Decision:
        if intent.confidence < 0.6:
            return Decision(
                decision="fallback_chat",
                reason="low_confidence",
            )

        if intent.full_name == "interrupt.stop_all":
            return Decision(
                decision="execute",
                priority=100,
                reply="好的，已停止。",
                next_state="idle",
            )

        if intent.full_name == "music.stop":
            return Decision(
                decision="execute",
                priority=95,
                reply="好的，已停止播放。",
                next_state="idle",
            )

        if intent.full_name == "game.exit":
            return Decision(
                decision="execute",
                priority=90,
                reply="好的，游戏先到这。",
                next_state="idle",
            )

        if intent.full_name == "game.answer":
            tool_name = intent.slots.get("tool") or context.active_game_tool
            if tool_name:
                return Decision(
                    decision="execute",
                    priority=80,
                    executor=tool_name,
                    arguments={"user_message": intent.slots.get("user_message", "")},
                    next_state="game",
                )
            return Decision(
                decision="reject",
                priority=10,
                reply="现在没有正在进行的游戏。",
                next_state="idle",
                reason="no_active_game",
            )

        if intent.full_name == "device.volume_down":
            return Decision(
                decision="execute",
                priority=90,
                executor="self_audio_speaker_volume_down",
                reply="好的，音量已调小。",
                next_state="idle",
            )

        if intent.full_name == "device.volume_up":
            return Decision(
                decision="execute",
                priority=90,
                executor="self_audio_speaker_volume_up",
                reply="好的，音量已调大。",
                next_state="idle",
            )

        if intent.full_name == "context.continue":
            if context.state == "idle":
                return Decision(
                    decision="reject",
                    priority=20,
                    reply="你想继续什么？",
                    next_state="idle",
                    reason="no_context",
                )
            return Decision(
                decision="fallback_chat",
                priority=20,
                reason="unsupported_context_continue",
            )

        return Decision(decision="fallback_chat", reason="normal_chat")
