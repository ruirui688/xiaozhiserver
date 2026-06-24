import sys
import asyncio
import queue
import types
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.semantic.frames import ASRResult, RobotContext
from core.semantic.music_intent import parse_music_intent
from core.semantic.router import SemanticRouter
from core.providers.tts.dto.dto import ContentType, SentenceType
from plugins_func.functions import play_music


def test_music_play_requests_fall_back_to_llm_tool_dispatch():
    assert parse_music_intent("嗯你唱一遍品牌星光") is None
    assert parse_music_intent("给我放首歌") is None
    assert parse_music_intent("你这里有什么歌") is None


def test_parse_non_music_control_is_not_music():
    assert parse_music_intent("你可以把音量放大一点吗") is None


def test_parse_negative_music_action_as_stop():
    for text in ["我不听了", "我说我不听了", "别放了", "不要唱了", "不想听歌了"]:
        intent = parse_music_intent(text, is_music_context=True)

        assert intent is not None
        assert intent.action == "stop"
    assert intent.song_name == "random"


def test_negative_music_action_requires_music_scope_without_context():
    assert parse_music_intent("你怎么不听话", is_music_context=False) is None
    assert parse_music_intent("我不听歌了", is_music_context=False).action == "stop"


def test_parse_explicit_music_stop_as_stop():
    for text in ["停止播放", "停止音乐", "暂停播放", "停掉这首歌", "关闭音乐"]:
        intent = parse_music_intent(text, is_music_context=True)

        assert intent is not None
        assert intent.action == "stop"


def test_parse_short_stop_in_music_context_only():
    assert parse_music_intent("算了", is_music_context=True).action == "stop"
    assert parse_music_intent("算了", is_music_context=False) is None


def test_negative_music_action_does_not_extract_particle_as_song_name():
    intent = parse_music_intent("我不听了", is_music_context=True)

    assert intent is not None
    assert intent.action != "play"
    assert intent.song_name != "了"


def test_semantic_router_routes_music_stop_before_chat_or_play():
    router = SemanticRouter()
    frame = router.route(
        ASRResult(
            session_id="s1",
            device_id="d1",
            raw_text="我说我不听了",
            normalized_text="我说我不听了",
            display_text="我说我不听了",
        ),
        RobotContext(
            device_id="d1",
            session_id="s1",
            state="speaking",
            current_skill="music",
        ),
    )

    assert frame.full_name == "music.stop"
    assert frame.confidence >= 0.99


def test_legacy_intent_handler_does_not_directly_dispatch_music():
    source = (SERVER_ROOT / "core/handle/intentHandler.py").read_text(encoding="utf-8")

    assert "route_music_intent" not in source
    assert '"name": "play_music"' not in source


def test_music_library_context_is_visible_to_llm_without_pre_dispatch():
    connection_source = (SERVER_ROOT / "core/connection.py").read_text(encoding="utf-8")
    plugin_source = (
        SERVER_ROOT / "core/providers/tools/server_plugins/plugin_executor.py"
    ).read_text(encoding="utf-8")

    assert "_with_music_library_context" in connection_source
    assert "<music_library>" in connection_source
    assert "不要调用 play_music" in connection_source
    assert "list_music_prompt_entries" in connection_source
    assert "_init_music_description" in plugin_source
    assert "当前本地曲库" in plugin_source
    assert "list_music_prompt_entries" in plugin_source


def test_music_metadata_aliases_match_local_song():
    play_music.MUSIC_CACHE.clear()
    play_music.MUSIC_CACHE["music_metadata"] = {
        "brand-song.mp3": {
            "description": "品牌主题曲。",
            "aliases": ["品牌星光", "星光之歌", "主题歌"],
        }
    }

    music_files = ["brand-song.mp3", "ambient-track.mp3"]

    assert play_music._find_best_match("品牌星光", music_files) == "brand-song.mp3"
    assert play_music._find_best_match("星光之歌", music_files) == "brand-song.mp3"


def test_play_music_waits_until_file_playback_messages_are_enqueued(tmp_path):
    music_file = tmp_path / "brand-song.mp3"
    music_file.write_bytes(b"fake audio")
    play_music.MUSIC_CACHE.clear()

    class FakeLogger:
        def bind(self, **kwargs):
            return self

        def info(self, message):
            pass

        def error(self, message):
            pass

        def warning(self, message):
            pass

        def debug(self, message):
            pass

    class FakeDialogue:
        def __init__(self):
            self.messages = []

        def put(self, message):
            self.messages.append(message)

    class FakeTTS:
        def __init__(self):
            self.tts_text_queue = queue.Queue()
            self.text_by_sentence = {}

        def store_tts_text(self, sentence_id, text):
            self.text_by_sentence[sentence_id] = text

    async def run_call():
        loop = asyncio.get_running_loop()
        conn = types.SimpleNamespace(
            loop=loop,
            config={
                "tool_call_timeout": 5,
                "plugins": {
                    "play_music": {
                        "music_dir": str(tmp_path),
                        "music_ext": [".mp3"],
                        "refresh_time": 300,
                    }
                },
            },
            sentence_id="sentence-1",
            logger=FakeLogger(),
            tts=FakeTTS(),
            dialogue=FakeDialogue(),
            read_config_from_api=False,
            need_bind=False,
            report_tts_enable=False,
            chat_history_conf=0,
            last_tool_context=None,
        )
        response = await asyncio.to_thread(play_music.play_music, conn, "品牌星光")
        queued_messages = list(conn.tts.tts_text_queue.queue)
        return response, queued_messages, conn

    response, queued_messages, conn = asyncio.run(run_call())

    assert response.action.name == "RECORD", (response.result, response.response)
    assert queued_messages[0].sentence_type == SentenceType.FIRST
    assert queued_messages[0].content_type == ContentType.ACTION
    assert queued_messages[1].content_type == ContentType.TEXT
    assert "brand-song" in queued_messages[1].content_detail
    assert queued_messages[2].content_type == ContentType.FILE
    assert queued_messages[2].content_file == str(music_file)
    assert queued_messages[3].sentence_type == SentenceType.LAST
    assert queued_messages[3].content_type == ContentType.ACTION
    assert all(message.sentence_id == conn.sentence_id for message in queued_messages)
    assert any(
        message.sentence_type == SentenceType.FIRST
        and message.content_type == ContentType.ACTION
        for message in queued_messages
    )
    assert any(
        message.content_type == ContentType.FILE
        and message.content_file == str(music_file)
        for message in queued_messages
    )
    assert any(
        message.sentence_type == SentenceType.LAST
        and message.content_type == ContentType.ACTION
        for message in queued_messages
    )
