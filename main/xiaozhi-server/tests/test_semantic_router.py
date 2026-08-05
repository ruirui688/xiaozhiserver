from core.semantic.frames import ASRResult, RobotContext
from core.semantic.normalizer import TextNormalizer
from core.semantic.router import SemanticRouter


def _asr(text):
    normalizer = TextNormalizer()
    return ASRResult(
        session_id="s1",
        device_id="d1",
        raw_text=text,
        normalized_text=normalizer.normalize(type("Conn", (), {"session_id": "s1", "device_id": "d1"})(), text).normalized_text,
        display_text=text,
    )


def _context(state="idle", active_game_tool=None, current_skill=None, last_intent=None):
    return RobotContext(
        device_id="d1",
        session_id="s1",
        state=state,
        current_skill=current_skill,
        active_game_tool=active_game_tool,
        last_intent=last_intent,
    )


def test_stop_like_text_does_not_use_server_phrase_rules():
    router = SemanticRouter()

    for text in [
        "停",
        "别说了",
        "闭嘴，小子",
        "你先别演啦",
        "别放了",
        "停止播放",
    ]:
        frame = router.route(_asr(text), _context(state="idle"))
        assert frame.full_name == "chat.normal"
        assert frame.source == "fallback"


def test_continue_in_idle_routes_to_context_continue():
    frame = SemanticRouter().route(_asr("继续"), _context(state="idle"))

    assert frame.full_name == "context.continue"


def test_context_reset_request_is_left_to_main_llm_tool_selection():
    frame = SemanticRouter().route(
        _asr("小艺，清空当前上下文记忆。"),
        _context(state="idle"),
    )

    assert frame.full_name == "chat.normal"
    assert frame.source == "fallback"


def test_active_game_routes_user_message_to_game_answer():
    frame = SemanticRouter().route(
        _asr("是不是冰箱"),
        _context(state="game", active_game_tool="guess_xiaozhi_word"),
    )

    assert frame.full_name == "game.answer"
    assert frame.slots["tool"] == "guess_xiaozhi_word"
    assert frame.slots["user_message"] == "是不是冰箱"


def test_active_game_exit_does_not_become_connection_exit():
    frame = SemanticRouter().route(
        _asr("退出游戏"),
        _context(state="game", active_game_tool="idiom_chain"),
    )

    assert frame.full_name == "game.exit"
    assert frame.slots["tool"] == "idiom_chain"


def test_drink_recommendation_falls_back_to_normal_chat():
    frame = SemanticRouter().route(_asr("帮我推荐一杯拍照好看的酒"), _context())

    assert frame.full_name == "chat.normal"
    assert frame.source == "fallback"


def test_unknown_text_falls_back_to_normal_chat():
    frame = SemanticRouter().route(_asr("今天天气怎么样"), _context())

    assert frame.full_name == "chat.normal"
    assert frame.source == "fallback"
