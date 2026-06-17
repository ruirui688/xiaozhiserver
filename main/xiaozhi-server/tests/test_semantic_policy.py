import sys
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.semantic.frames import IntentFrame, RobotContext
from core.semantic.policy_engine import PolicyEngine


def _context(state="idle", active_game_tool=None):
    return RobotContext(
        device_id="d1",
        session_id="s1",
        state=state,
        active_game_tool=active_game_tool,
    )


def test_interrupt_stop_all_is_always_executable():
    decision = PolicyEngine().decide(
        IntentFrame("interrupt", "stop_all", 0.99),
        _context(state="game", active_game_tool="idiom_chain"),
    )

    assert decision.decision == "execute"
    assert decision.priority == 100
    assert decision.next_state == "idle"


def test_game_answer_executes_active_game_tool():
    decision = PolicyEngine().decide(
        IntentFrame(
            "game",
            "answer",
            0.92,
            {"tool": "idiom_chain", "user_message": "一心一意"},
        ),
        _context(state="game", active_game_tool="idiom_chain"),
    )

    assert decision.decision == "execute"
    assert decision.executor == "idiom_chain"
    assert decision.arguments == {"user_message": "一心一意"}


def test_continue_without_context_is_rejected_with_clarification():
    decision = PolicyEngine().decide(
        IntentFrame("context", "continue", 0.9),
        _context(state="idle"),
    )

    assert decision.decision == "reject"
    assert "继续" in decision.reply


def test_low_confidence_falls_back_to_chat():
    decision = PolicyEngine().decide(
        IntentFrame("motion", "dance", 0.4),
        _context(state="idle"),
    )

    assert decision.decision == "fallback_chat"
    assert decision.reason == "low_confidence"
