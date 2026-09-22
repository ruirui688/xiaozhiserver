import sys
import asyncio
import json
import os
import queue
import types
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.providers.tts.dto.dto import ContentType, SentenceType
from plugins_func.functions import play_music


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


def _music_library_conn(music_dir, refresh_time=300):
    return types.SimpleNamespace(
        config={
            "plugins": {
                "play_music": {
                    "music_dir": str(music_dir),
                    "music_ext": [".mp3"],
                    "refresh_time": refresh_time,
                }
            }
        }
    )


def _touch_forward(path):
    """把 mtime 明确推到未来。

    测试在同一毫秒内建文件再删文件，而目录 mtime 的粒度约几毫秒，改动会被粒度吃掉；
    生产里人工删除是秒级操作，这里显式推动时间以便确定性验证"mtime 变了就重建缓存"。
    """
    current = os.path.getmtime(path)
    os.utime(path, (current + 10, current + 10))


def test_prompt_entries_drop_deleted_song_without_playback(tmp_path):
    """删除歌曲后曲库提示词必须立即不再包含它，不必先播放一次或重启进程。"""
    (tmp_path / "brand-song.mp3").write_bytes(b"fake audio")
    (tmp_path / "ambient-track.mp3").write_bytes(b"fake audio")
    (tmp_path / play_music.MUSIC_METADATA_FILE_NAME).write_text(
        json.dumps(
            {
                "brand-song.mp3": {
                    "description": "品牌主题曲。",
                    "aliases": ["品牌星光"],
                }
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    play_music.MUSIC_CACHE.clear()
    conn = _music_library_conn(tmp_path)

    entries = play_music.list_music_prompt_entries(conn)
    assert any("brand-song" in entry for entry in entries)
    assert any("品牌星光" in entry for entry in entries)

    (tmp_path / "brand-song.mp3").unlink()
    _touch_forward(tmp_path)

    entries = play_music.list_music_prompt_entries(conn)
    assert not any("brand-song" in entry for entry in entries)
    assert any("ambient-track" in entry for entry in entries)


def test_prompt_entries_drop_song_after_metadata_removal(tmp_path):
    """元数据文件被改写或删除时（目录 mtime 不变）也要按 metadata mtime 重建缓存。"""
    (tmp_path / "brand-song.mp3").write_bytes(b"fake audio")
    metadata_path = tmp_path / play_music.MUSIC_METADATA_FILE_NAME
    metadata_path.write_text(
        json.dumps({"brand-song.mp3": {"aliases": ["品牌星光"]}}, ensure_ascii=False),
        encoding="utf-8",
    )
    play_music.MUSIC_CACHE.clear()
    conn = _music_library_conn(tmp_path)
    assert any("品牌星光" in entry for entry in play_music.list_music_prompt_entries(conn))

    metadata_path.write_text("{}", encoding="utf-8")
    _touch_forward(metadata_path)

    entries = play_music.list_music_prompt_entries(conn)
    assert any("brand-song" in entry for entry in entries)
    assert not any("品牌星光" in entry for entry in entries)


def test_nested_song_is_picked_up_by_refresh_time_fallback(tmp_path):
    """子目录增删不改变顶层目录 mtime，必须靠 refresh_time 兜底重扫。"""
    nested = tmp_path / "nested"
    nested.mkdir()
    (tmp_path / "brand-song.mp3").write_bytes(b"fake audio")
    play_music.MUSIC_CACHE.clear()
    conn = _music_library_conn(tmp_path, refresh_time=-1)

    assert not any(
        "nested-song" in entry for entry in play_music.list_music_prompt_entries(conn)
    )

    (nested / "nested-song.mp3").write_bytes(b"fake audio")

    assert any(
        "nested-song" in entry for entry in play_music.list_music_prompt_entries(conn)
    )


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
