import asyncio
import json
import sys
import types
from pathlib import Path

import pytest


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.game_session import GameSessionManager


def _seed_guess_my_word(manager, tool_name="guess_xiaozhi_word"):
    manager.sessions[tool_name] = {
        "active": True,
        "game_type": "guess_my_word",
        "phase": "waiting_guess",
        "target_word": "冰箱",
        "category": "家用电器",
        "hints": ["它通常在厨房或餐厅", "它能让东西保持低温", "水果、饮料和剩菜常放在里面"],
        "hints_given": ["它通常在厨房或餐厅"],
        "user_guesses": [],
        "turn_count": 0,
        "started_at": 0,
    }
    return tool_name


def _seed_user_describes(manager, tool_name="guess_what_i_say"):
    manager.sessions[tool_name] = {
        "active": True,
        "game_type": "user_describes_assistant_guesses",
        "tool_name": tool_name,
        "target_type": "",
        "facts": {},
        "asked": [],
        "excluded_names": set(),
        "last_question": None,
        "turn_count": 0,
        "started_at": 0,
    }
    return tool_name


def test_guess_my_word_negative_mention_does_not_count_as_correct():
    manager = GameSessionManager()
    tool_name = _seed_guess_my_word(manager)

    response = manager.handle_guess_my_word(tool_name, "不是冰箱吧")

    assert "猜对了" not in response
    assert tool_name in manager.sessions
    assert manager.sessions[tool_name]["turn_count"] == 1


def test_guess_my_word_positive_guess_still_counts_as_correct():
    manager = GameSessionManager()
    tool_name = _seed_guess_my_word(manager)

    response = manager.handle_guess_my_word(tool_name, "是不是冰箱")

    assert response == "猜对了，就是冰箱。要不要再来一局？"
    assert tool_name not in manager.sessions


def test_guess_my_word_hint_give_up_and_end_behaviors():
    manager = GameSessionManager()
    tool_name = _seed_guess_my_word(manager)

    hint_response = manager.handle_guess_my_word(tool_name, "提示")
    assert hint_response == "再给你一个提示：它能让东西保持低温。你猜是什么？"
    assert tool_name in manager.sessions

    give_up_response = manager.handle_guess_my_word(tool_name, "我放弃")
    assert give_up_response == "答案是冰箱。这局先到这，要不要再来一局？"
    assert tool_name not in manager.sessions

    _seed_guess_my_word(manager, tool_name)
    end_response = manager.handle_guess_my_word(tool_name, "结束")
    assert end_response == "这局先到这。想再玩时直接说“你想一个词我来猜”。"
    assert tool_name not in manager.sessions


def test_user_describes_first_historical_clue_is_used():
    manager = GameSessionManager()
    tool_name = _seed_user_describes(manager)

    response = manager.handle_user_describes_guess(tool_name, "他是中国古代诗人")
    state = manager.sessions[tool_name]

    assert "人物、物品、地点，还是动物" not in response
    assert state["target_type"] == "historical_person"
    assert state["facts"]["region"] == "china"
    assert state["facts"]["period"] == "ancient"
    assert state["facts"]["poet"] is True


def test_user_describes_clue_after_guess_is_not_treated_as_yes():
    manager = GameSessionManager()
    tool_name = _seed_user_describes(manager)
    state = manager.sessions[tool_name]
    state["target_type"] = "historical_person"
    state["last_question"] = {"type": "guess", "name": "秦始皇"}

    manager.handle_user_describes_guess(tool_name, "他是诗人")

    assert not manager.sessions[tool_name].get("solved")
    assert "秦始皇" not in manager.sessions[tool_name]["excluded_names"]
    assert manager.sessions[tool_name]["facts"]["poet"] is True


@pytest.mark.parametrize(
    ("message", "key"),
    [
        ("不是诗人", "poet"),
        ("不是皇帝", "ruler"),
    ],
)
def test_user_describes_negative_clues_do_not_become_positive_facts(message, key):
    manager = GameSessionManager()
    tool_name = _seed_user_describes(manager)

    manager.handle_user_describes_guess(tool_name, message)

    assert manager.sessions[tool_name]["facts"][key] is False


def test_idiom_chain_repeated_idiom_does_not_score_but_normal_chain_still_works():
    manager = GameSessionManager()
    tool_name = "idiom_chain"
    manager.sessions[tool_name] = {
        "active": True,
        "game_type": "idiom_chain",
        "last_idiom": "一心一意",
        "expected_char": "意",
        "used": {"一心一意", "意气风发"},
        "user_correct": 0,
        "hints_given": 0,
        "started_at": 0,
    }

    repeated = manager.handle_idiom_chain(tool_name, "我接意气风发")
    assert "用过" in repeated or "重复" in repeated or "已经" in repeated
    assert manager.sessions[tool_name]["user_correct"] == 0

    normal = manager.handle_idiom_chain(tool_name, "意味深长")
    assert normal.startswith("接得上。")
    assert manager.sessions[tool_name]["user_correct"] == 1

    end = manager.handle_idiom_chain(tool_name, "结束")
    assert end == "成语接龙先到这。你刚才接对了1个。"
    assert tool_name not in manager.sessions


def _install_plugin_executor_stubs(monkeypatch):
    register_mod = types.ModuleType("plugins_func.register")

    class Action:
        RESPONSE = object()
        REQLLM = object()
        NOTFOUND = object()
        ERROR = object()

    class ActionResponse:
        def __init__(self, action, result=None, response=None):
            self.action = action
            self.result = result
            self.response = response

    register_mod.Action = Action
    register_mod.ActionResponse = ActionResponse
    register_mod.all_function_registry = {}
    monkeypatch.setitem(sys.modules, "plugins_func.register", register_mod)

    base_mod = types.ModuleType("core.providers.tools.base")

    class ToolType:
        SERVER_PLUGIN = object()

    class ToolDefinition:
        def __init__(self, name, description, tool_type, parameters=None):
            self.name = name
            self.description = description
            self.tool_type = tool_type
            self.parameters = parameters

    class ToolExecutor:
        pass

    base_mod.ToolType = ToolType
    base_mod.ToolDefinition = ToolDefinition
    base_mod.ToolExecutor = ToolExecutor
    monkeypatch.setitem(sys.modules, "core.providers.tools.base", base_mod)
    monkeypatch.delitem(
        sys.modules,
        "core.providers.tools.server_plugins.plugin_executor",
        raising=False,
    )
    return Action


def _install_intent_handler_stubs(monkeypatch):
    hello_mod = types.ModuleType("core.handle.helloHandle")

    async def checkWakeupWords(conn, text):
        return False

    hello_mod.checkWakeupWords = checkWakeupWords
    monkeypatch.setitem(sys.modules, "core.handle.helloHandle", hello_mod)

    send_audio_mod = types.ModuleType("core.handle.sendAudioHandle")

    async def send_stt_message(conn, text):
        return None

    send_audio_mod.send_stt_message = send_stt_message
    monkeypatch.setitem(sys.modules, "core.handle.sendAudioHandle", send_audio_mod)

    report_mod = types.ModuleType("core.handle.reportHandle")
    report_mod.enqueue_tool_report = lambda *args, **kwargs: None
    monkeypatch.setitem(sys.modules, "core.handle.reportHandle", report_mod)

    util_mod = types.ModuleType("core.utils.util")
    util_mod.remove_punctuation_and_length = lambda text: (None, text)
    monkeypatch.setitem(sys.modules, "core.utils.util", util_mod)

    dialogue_mod = types.ModuleType("core.utils.dialogue")

    class Message:
        def __init__(self, role=None, content=None):
            self.role = role
            self.content = content

    dialogue_mod.Message = Message
    monkeypatch.setitem(sys.modules, "core.utils.dialogue", dialogue_mod)

    tts_mod = types.ModuleType("core.providers.tts.dto.dto")

    class ContentType:
        ACTION = object()
        TEXT = object()

    class SentenceType:
        FIRST = object()
        LAST = object()

    class TTSMessageDTO:
        def __init__(self, sentence_id=None, sentence_type=None, content_type=None):
            self.sentence_id = sentence_id
            self.sentence_type = sentence_type
            self.content_type = content_type

    tts_mod.ContentType = ContentType
    tts_mod.SentenceType = SentenceType
    tts_mod.TTSMessageDTO = TTSMessageDTO
    monkeypatch.setitem(sys.modules, "core.providers.tts.dto.dto", tts_mod)

    register_mod = types.ModuleType("plugins_func.register")
    register_mod.Action = type("Action", (), {})
    register_mod.ActionResponse = type("ActionResponse", (), {})
    monkeypatch.setitem(sys.modules, "plugins_func.register", register_mod)

    monkeypatch.delitem(sys.modules, "core.handle.intentHandler", raising=False)
    return register_mod


def test_stateful_game_executor_routes_active_game_tool(monkeypatch):
    Action = _install_plugin_executor_stubs(monkeypatch)

    from core.providers.tools.server_plugins.plugin_executor import ServerPluginExecutor

    manager = GameSessionManager()
    tool_name = _seed_guess_my_word(manager)
    conn = types.SimpleNamespace(
        config={},
        game_session_manager=manager,
    )
    executor = ServerPluginExecutor(conn)

    response = asyncio.run(
        executor.execute(conn, tool_name, {"user_message": "是不是冰箱"})
    )

    assert response.action is Action.RESPONSE
    assert response.response == "猜对了，就是冰箱。要不要再来一局？"


def test_active_game_route_builds_function_call(monkeypatch):
    _install_intent_handler_stubs(monkeypatch)

    import core.handle.intentHandler as ih

    recorded = {}

    async def fake_process_intent_result(conn, intent_result, original_text):
        recorded["intent_result"] = intent_result
        recorded["original_text"] = original_text
        return True

    monkeypatch.setattr(ih, "process_intent_result", fake_process_intent_result)

    manager = GameSessionManager()
    tool_name = _seed_guess_my_word(manager)
    conn = types.SimpleNamespace(
        cmd_exit=["退出"],
        game_session_manager=manager,
        func_handler=types.SimpleNamespace(has_tool=lambda name: True),
        intent_type="other",
        intent=None,
        logger=types.SimpleNamespace(bind=lambda **kwargs: types.SimpleNamespace(info=lambda *a, **k: None, warning=lambda *a, **k: None, error=lambda *a, **k: None, debug=lambda *a, **k: None)),
    )

    result = asyncio.run(ih.handle_user_intent(conn, "是不是冰箱"))

    assert result is True
    assert recorded["original_text"] == "是不是冰箱"
    intent_data = json.loads(recorded["intent_result"])
    assert intent_data["function_call"]["name"] == "guess_xiaozhi_word"
    assert intent_data["function_call"]["arguments"]["user_message"] == "是不是冰箱"


def test_generic_game_request_defaults_to_idiom_chain(monkeypatch):
    _install_intent_handler_stubs(monkeypatch)

    import core.handle.intentHandler as ih

    recorded = {}

    async def fake_process_intent_result(conn, intent_result, original_text):
        recorded["intent_result"] = json.loads(intent_result)
        recorded["original_text"] = original_text
        return True

    monkeypatch.setattr(ih, "process_intent_result", fake_process_intent_result)

    manager = GameSessionManager()
    conn = types.SimpleNamespace(
        cmd_exit=["退出"],
        game_session_manager=manager,
        func_handler=types.SimpleNamespace(has_tool=lambda name: True),
        intent_type="other",
        intent=None,
        logger=types.SimpleNamespace(bind=lambda **kwargs: types.SimpleNamespace(info=lambda *a, **k: None, warning=lambda *a, **k: None, error=lambda *a, **k: None, debug=lambda *a, **k: None)),
    )

    result = asyncio.run(ih.handle_user_intent(conn, "玩个游戏"))

    assert result is True
    assert recorded["original_text"] == "玩个游戏"
    assert recorded["intent_result"]["function_call"]["name"] == "idiom_chain"
    assert recorded["intent_result"]["function_call"]["arguments"]["user_message"] == "玩个游戏"


def test_stateful_game_tool_reports_are_skipped(monkeypatch):
    Action = _install_plugin_executor_stubs(monkeypatch)

    from core.providers.tools.server_plugins.plugin_executor import ServerPluginExecutor

    opus_mod = types.ModuleType("opuslib_next")

    class Decoder:
        def __init__(self, *args, **kwargs):
            pass

    opus_mod.Decoder = Decoder
    opus_mod.OpusError = Exception
    monkeypatch.setitem(sys.modules, "opuslib_next", opus_mod)

    manage_api_mod = types.ModuleType("config.manage_api_client")
    manage_api_mod.report = lambda **kwargs: None
    monkeypatch.setitem(sys.modules, "config.manage_api_client", manage_api_mod)
    monkeypatch.delitem(sys.modules, "core.handle.reportHandle", raising=False)

    import core.handle.reportHandle as rh

    reported = []
    monkeypatch.setattr(
        rh,
        "manage_report",
        lambda **kwargs: reported.append(kwargs),
    )

    manager = GameSessionManager()
    tool_name = _seed_guess_my_word(manager)
    conn = types.SimpleNamespace(
        config={},
        game_session_manager=manager,
        read_config_from_api=True,
        need_bind=False,
        chat_history_conf=1,
        report_queue=types.SimpleNamespace(put=lambda *args, **kwargs: reported.append(args)),
    )
    executor = ServerPluginExecutor(conn)

    response = asyncio.run(
        executor.execute(conn, tool_name, {"user_message": "是不是冰箱"})
    )

    assert response.action is Action.RESPONSE
    assert response.response == "猜对了，就是冰箱。要不要再来一局？"
    assert reported == []
