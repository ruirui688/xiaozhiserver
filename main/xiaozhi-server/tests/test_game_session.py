import sys
import types
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.game import GameSessionManager


def test_freeform_games_do_not_force_stateful_routing():
    manager = GameSessionManager()

    assert GameSessionManager.GUESS_MY_WORD_TOOLS == set()
    assert GameSessionManager.USER_DESCRIBES_TOOLS == set()
    assert GameSessionManager.IDIOM_CHAIN_TOOLS == set()

    for text in (
        "玩个游戏",
        "成语接龙",
        "你想一个词我来猜",
        "我已经想了一个词了，请你猜吧",
        "你说我猜",
    ):
        assert manager.get_requested_game_tool(text) is None


def test_enabled_freeform_game_builds_editable_prompt():
    manager = GameSessionManager()
    prompt = manager.build_freeform_game_prompt(
        "我们来玩你说我猜",
        {
            "guess_what_i_say": {
                "custom_plugin": True,
                "instructions": "用户想好答案后，小智每轮只问一个是/否问题，逐步缩小范围。",
                "opening_message": "想好了就说一声，我来问。",
            }
        },
    )

    assert prompt is not None
    assert "guess_what_i_say" in prompt
    assert "每轮只问一个是/否问题" in prompt
    assert "不要调用同名工具" in prompt


def test_guess_what_i_say_legacy_description_rules_are_corrected_to_questions():
    manager = GameSessionManager()
    prompt = manager.build_freeform_game_prompt(
        "猜物游戏开始",
        {
            "guess_what_i_say": {
                "custom_plugin": True,
                "instructions": "首轮请用户描述线索，并提醒不要直接说出答案。",
                "opening_message": "你描述，我来猜。",
            }
        },
    )

    assert prompt is not None
    assert "二十问/猜物模式" in prompt
    assert "不要让用户描述第一条线索" in prompt
    assert "每轮只问一个是/否问题" in prompt
    assert "玩法规则：首轮请用户描述线索" not in prompt
    assert "建议开场：你描述" not in prompt


def test_explicit_freeform_game_uses_default_prompt_without_enabled_plugin():
    manager = GameSessionManager()
    prompt = manager.build_freeform_game_prompt("我们来猜人物t挑战", {})

    assert prompt is not None
    assert "guess_what_i_say" in prompt
    assert "二十问/猜物模式" in prompt
    assert "每轮只问一个是/否问题" in prompt
    assert "禁止要求用户描述这个人的事迹" in prompt
    assert "答案是什么" in prompt


def test_guess_what_i_say_prompt_keeps_assistant_as_questioner():
    manager = GameSessionManager()
    prompt = manager.build_freeform_game_prompt("来你说我猜", {})

    assert prompt is not None
    assert "不是小智心里默想东西让用户来问" in prompt
    assert "小智不能自选、暗藏或虚构目标答案" in prompt
    assert "如果用户反过来问" in prompt
    assert "不要回答这些问题" in prompt
    assert "不要说“继续问、接着问、再猜猜”" in prompt


def test_guess_what_i_say_accepts_asr_missing_you_alias():
    manager = GameSessionManager()
    prompt = manager.build_freeform_game_prompt("我们来做个游戏，说我猜", {})

    assert prompt is not None
    assert "guess_what_i_say" in prompt
    assert manager.freeform_game_state["role_mode"] == "user_thinks_assistant_guesses"
    assert "小智负责主动提问" in prompt


def test_guess_what_i_say_user_already_thought_object_starts_questions():
    manager = GameSessionManager()
    prompt = manager.build_freeform_game_prompt("我已经想了一个物品", {})

    assert prompt is not None
    assert "guess_what_i_say" in prompt
    assert "下一句必须直接提问" in prompt
    assert "禁止再说“开始描述，我来猜”" in prompt
    assert "不能要求用户描述线索" in prompt


def test_assistant_thinks_game_persists_hidden_answer_in_prompt():
    manager = GameSessionManager()
    prompt = manager.build_freeform_game_prompt("你想一个词我来猜", {})
    state = manager.freeform_game_state

    assert prompt is not None
    assert state["role_mode"] == "assistant_thinks_user_guesses"
    assert state["target_word"]
    assert f"固定答案：{state['target_word']}" in prompt
    assert "不要更换答案" in prompt

    next_prompt = manager.build_freeform_game_prompt("是不是冰箱", {})

    assert f"固定答案：{state['target_word']}" in next_prompt
    assert manager.freeform_game_state["target_word"] == state["target_word"]


def test_user_thinks_game_has_role_state_without_hidden_answer():
    manager = GameSessionManager()
    prompt = manager.build_freeform_game_prompt("来你说我猜", {})

    assert prompt is not None
    assert manager.freeform_game_state["role_mode"] == "user_thinks_assistant_guesses"
    assert "固定答案：" not in prompt
    assert "小智不知道答案" in prompt


def test_freeform_game_end_clears_state():
    manager = GameSessionManager()
    assert manager.build_freeform_game_prompt("你想一个词我来猜", {}) is not None

    assert manager.build_freeform_game_prompt("不玩了", {}) is None
    assert manager.active_freeform_game is None
    assert manager.freeform_game_state is None


def test_disabled_freeform_game_config_removes_default_prompt():
    manager = GameSessionManager()

    assert (
        manager.build_freeform_game_prompt(
            "我们来玩你说我猜",
            {
                "guess_what_i_say": {
                    "custom_plugin": False,
                    "instructions": "disabled",
                }
            },
        )
        is None
    )


def _install_plugin_executor_stubs(monkeypatch):
    register_mod = types.ModuleType("plugins_func.register")
    register_mod.all_function_registry = {}

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


def test_freeform_games_are_not_registered_as_tools(monkeypatch):
    _install_plugin_executor_stubs(monkeypatch)

    from core.providers.tools.server_plugins.plugin_executor import ServerPluginExecutor

    freeform_games = sorted(GameSessionManager.FREEFORM_GAME_TOOLS)
    conn = types.SimpleNamespace(
        config={
            "selected_module": {"Intent": "Intent_test"},
            "Intent": {"Intent_test": {"functions": freeform_games}},
            "plugins": {
                name: {"custom_plugin": True, "description": "legacy game"}
                for name in freeform_games
            },
        },
        game_session_manager=GameSessionManager(),
    )

    executor = ServerPluginExecutor(conn)
    tools = executor.get_tools()

    for name in freeform_games:
        assert name not in tools
        assert executor.has_tool(name) is False
