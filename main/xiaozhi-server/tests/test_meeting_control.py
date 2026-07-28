import concurrent.futures
import sys
from pathlib import Path
from types import SimpleNamespace

SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings

settings.config_file_valid = True

from core.meeting import (
    MeetingMinutesRuntime,
    build_meeting_runtime_context,
    get_meeting_entry_prompt,
    is_meeting_mode_enabled,
)
from core.game import GameSessionManager
from core.providers.tools.server_plugins.plugin_executor import ServerPluginExecutor
from plugins_func.functions.meeting_minutes import meeting_minutes
from plugins_func.register import Action


class _Logger:
    def bind(self, **_kwargs):
        return self

    def info(self, *_args, **_kwargs):
        pass

    def warning(self, *_args, **_kwargs):
        pass

    def error(self, *_args, **_kwargs):
        pass


class _Executor:
    def submit(self, fn, *args, **kwargs):
        future = concurrent.futures.Future()
        future.set_result(None)
        return future


class _ToolHandler:
    def __init__(self):
        self.refresh_count = 0

    def upload_functions_desc(self):
        self.refresh_count += 1


class _MainLLM:
    supports_native_function_call = True


def _conn(enabled=True):
    plugins = {
        "meeting_minutes": {
            "entry_prompt": "会议记录开始。",
        }
    } if enabled else {}
    return SimpleNamespace(
        read_config_from_api=True,
        config={"plugins": plugins},
        meeting_minutes=MeetingMinutesRuntime(),
        logger=_Logger(),
        executor=_Executor(),
        func_handler=_ToolHandler(),
        llm=_MainLLM(),
        intent_type="function_call",
    )


def test_manager_runtime_requires_agent_meeting_plugin():
    disabled = _conn(enabled=False)
    enabled = _conn(enabled=True)

    assert is_meeting_mode_enabled(disabled) is False
    assert is_meeting_mode_enabled(enabled) is True
    assert get_meeting_entry_prompt(enabled) == "会议记录开始。"


def test_runtime_context_keeps_state_claims_authoritative():
    disabled_context = build_meeting_runtime_context(_conn(enabled=False))
    enabled = _conn(enabled=True)
    enabled_context = build_meeting_runtime_context(enabled)
    enabled.meeting_minutes.start("发布评审")
    active_context = build_meeting_runtime_context(enabled)

    assert disabled_context == ""
    assert "active=false" in enabled_context
    assert "只有成功调用工具才代表会议已开始" in enabled_context
    assert "active=true" in active_context
    assert "action=record" in active_context
    assert "action=end" in active_context


def test_main_llm_tool_executes_start_record_and_end(monkeypatch):
    conn = _conn(enabled=True)
    finished = []
    monkeypatch.setattr(
        "plugins_func.functions.meeting_minutes.finish_meeting",
        lambda current: (finished.append(current), current.meeting_minutes.end()),
    )

    started = meeting_minutes(conn, "start", "发布评审")
    assert started.action == Action.RESPONSE
    assert started.response == "会议记录开始。"
    assert conn.meeting_minutes.is_active() is True

    conn.meeting_minutes.prepare_turn(
        {
            "utterances": [
                {
                    "text": "先完成回归测试。",
                    "provider_speaker": "spk_1",
                    "resolved_speaker": "张敏",
                    "start_time": 0,
                    "end_time": 1200,
                }
            ]
        },
        "先完成回归测试。",
        "张敏",
    )
    recorded = meeting_minutes(conn, "record")
    assert recorded.action == Action.RECORD
    assert conn.meeting_minutes.build_transcript()[0]["displaySpeaker"] == "张敏"

    conn.meeting_minutes.prepare_turn(None, "现在结束并生成纪要", "张敏")
    ended = meeting_minutes(conn, "end")
    assert ended.action == Action.RECORD
    assert conn.meeting_minutes.is_active() is False
    assert conn.meeting_minutes.has_pending_turn() is False
    assert finished == [conn]
    assert conn.func_handler.refresh_count == 2


def test_disabled_tool_cannot_change_server_state():
    conn = _conn(enabled=False)

    result = meeting_minutes(conn, "start", "不应开始")

    assert result.action == Action.RESPONSE
    assert "没有启用会议纪要功能" in result.response
    assert conn.meeting_minutes.is_active() is False


def test_enabled_meeting_is_registered_as_real_main_llm_tool():
    conn = _conn(enabled=True)
    conn.config.update(
        {
            "selected_module": {"Intent": "Intent_function_call"},
            "Intent": {
                "Intent_function_call": {
                    "functions": ["meeting_minutes"],
                }
            },
        }
    )
    conn.game_session_manager = GameSessionManager()

    tools = ServerPluginExecutor(conn).get_tools()

    assert "meeting_minutes" in tools
    function = tools["meeting_minutes"].description["function"]
    assert function["parameters"]["properties"]["action"]["enum"] == [
        "start",
        "record",
        "end",
    ]


def test_meeting_tool_is_not_delegated_to_intent_llm():
    conn = _conn(enabled=True)
    conn.intent_type = "intent_llm"
    conn.config.update(
        {
            "selected_module": {"Intent": "Intent_intent_llm"},
            "Intent": {
                "Intent_intent_llm": {
                    "functions": ["meeting_minutes"],
                }
            },
        }
    )
    conn.game_session_manager = GameSessionManager()

    tools = ServerPluginExecutor(conn).get_tools()

    assert "meeting_minutes" not in tools


def test_disabled_meeting_cannot_leak_from_stale_intent_functions():
    conn = _conn(enabled=False)
    conn.config.update(
        {
            "selected_module": {"Intent": "Intent_function_call"},
            "Intent": {
                "Intent_function_call": {
                    "functions": ["meeting_minutes"],
                }
            },
        }
    )
    conn.game_session_manager = GameSessionManager()

    executor = ServerPluginExecutor(conn)

    assert "meeting_minutes" not in executor.get_tools()
    assert executor.has_tool("meeting_minutes") is False
