import sys
import asyncio
import json
import os
import queue
import types
import time
from unittest.mock import Mock
from pathlib import Path

import pytest

SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.providers.tts.dto.dto import ContentType, SentenceType
from plugins_func.functions import play_music


def test_legacy_intent_handler_does_not_directly_dispatch_music():
    source = (SERVER_ROOT / "core/handle/intentHandler.py").read_text(encoding="utf-8")

    assert "route_music_intent" not in source
    assert '"name": "play_music"' not in source


def test_music_library_context_is_visible_to_llm_without_pre_dispatch(monkeypatch):
    from core.session.prompt_context import with_music_library

    def unexpected_playback(*args, **kwargs):
        raise AssertionError("介绍曲库不能直接执行播放")

    monkeypatch.setattr(play_music, "play_music", unexpected_playback)
    monkeypatch.setattr(play_music, "list_music_prompt_entries",
                        lambda _conn, limit: ["晚风：轻音乐"])
    conn = types.SimpleNamespace(func_handler=types.SimpleNamespace(
        has_tool=lambda name: name == "play_music",
    ))
    original = [{"role": "user", "content": "你能播放哪些歌？"}]
    enabled = with_music_library(conn, original, [{"function": {"name": "play_music"}}])
    context = next(m["content"] for m in enabled if m["role"] == "system")
    assert "<music_library>" in context and "晚风：轻音乐" in context
    assert "用户询问曲库时直接依据曲目回答" in context
    assert "仅当用户明确要求播放时才调用 play_music" in context
    assert enabled[-1] == original[0] and len(original) == 1

    disabled = with_music_library(conn, original, [])
    context = next(m["content"] for m in disabled if m["role"] == "system")
    assert "本次请求不允许播放操作" in context
    assert "仅当用户明确要求播放时才调用 play_music" not in context
    conn.func_handler.has_tool = lambda _name: False
    assert with_music_library(conn, original, []) == original


def test_music_metadata_aliases_match_local_song():
    metadata = {
        "brand-song.mp3": {
            "description": "品牌主题曲。",
            "aliases": ["品牌星光", "星光之歌", "主题歌"],
        }
    }

    music_files = ["brand-song.mp3", "ambient-track.mp3"]

    assert play_music._find_best_match("品牌星光", music_files, metadata) == "brand-song.mp3"
    assert play_music._find_best_match("星光之歌", music_files, metadata) == "brand-song.mp3"


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
    assert response.result == play_music.PlaybackResult.QUEUED.value
    assert conn.last_tool_context == "music"


def _playback_conn(music_dir):
    conn = _music_library_conn(music_dir)
    conn.logger = Mock()
    conn.logger.bind.return_value = conn.logger
    conn.tts = types.SimpleNamespace(tts_text_queue=queue.Queue(), store_tts_text=Mock())
    conn.dialogue = types.SimpleNamespace(put=Mock())
    conn.sentence_id = "original"
    conn.last_tool_context = None
    return conn


def _call_music(conn, song="random"):
    async def run():
        conn.loop = asyncio.get_running_loop()
        return await asyncio.to_thread(play_music.play_music, conn, song)
    return asyncio.run(run())


def _assert_no_playback(conn):
    assert conn.tts.tts_text_queue.empty()
    conn.tts.store_tts_text.assert_not_called()
    conn.dialogue.put.assert_not_called()
    assert conn.sentence_id == "original"
    assert conn.last_tool_context is None


@pytest.mark.parametrize("song", ["random", "不存在的歌"])
def test_empty_library_never_claims_playback(tmp_path, song):
    conn = _playback_conn(tmp_path)
    response = _call_music(conn, song)
    assert response.action.name == "RESPONSE"
    assert response.result == response.response == play_music.PlaybackResult.EMPTY.value
    _assert_no_playback(conn)


def test_missing_library_does_not_fall_back_to_default_music(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    default = tmp_path / "music"
    default.mkdir()
    (default / "default.mp3").write_bytes(b"test audio")
    conn = _playback_conn(tmp_path / "missing")
    response = _call_music(conn)
    assert response.result == play_music.PlaybackResult.UNAVAILABLE.value
    _assert_no_playback(conn)


def test_orphan_optimized_audio_is_not_a_song(tmp_path):
    cache = tmp_path / play_music.MUSIC_CACHE_DIR_NAME
    cache.mkdir()
    (cache / "removed.mp3.p3").write_bytes(b"test cached audio")
    conn = _playback_conn(tmp_path)
    conn.config["plugins"]["play_music"]["music_ext"] = [".mp3", ".p3"]
    assert play_music.list_music_prompt_entries(conn) == []
    assert _call_music(conn).result == play_music.PlaybackResult.EMPTY.value
    _assert_no_playback(conn)


def test_deleted_nested_song_is_rechecked_before_enqueue(tmp_path):
    nested = tmp_path / "nested"
    nested.mkdir()
    song = nested / "removed.mp3"
    song.write_bytes(b"test audio")
    conn = _playback_conn(tmp_path)
    assert play_music.list_available_music(conn) == ["removed"]
    song.unlink()  # 顶层 mtime 没变、TTL 没到，命中旧快照。
    assert _call_music(conn).result == play_music.PlaybackResult.MISSING.value
    _assert_no_playback(conn)


def test_connections_do_not_share_the_first_library(tmp_path):
    full = tmp_path / "full"
    empty = tmp_path / "empty"
    full.mkdir()
    empty.mkdir()
    song = full / "first-library.mp3"
    song.write_bytes(b"test audio")
    first = _playback_conn(full)
    second = _playback_conn(empty)
    assert play_music.list_available_music(first) == ["first-library"]
    assert play_music.list_available_music(second) == []
    assert _call_music(second).result == play_music.PlaybackResult.EMPTY.value
    _assert_no_playback(second)
    assert _call_music(first).result == play_music.PlaybackResult.QUEUED.value
    assert list(first.tts.tts_text_queue.queue)[2].content_file == str(song)


def test_configuration_refresh_changes_library_and_extensions(tmp_path):
    song = tmp_path / "only.mp3"
    song.write_bytes(b"test audio")
    conn = _playback_conn(tmp_path)
    assert play_music.list_available_music(conn) == ["only"]
    conn.config["plugins"]["play_music"]["music_ext"] = [".wav"]
    assert play_music.list_available_music(conn) == []
    assert _call_music(conn).result == play_music.PlaybackResult.EMPTY.value
    conn.config["plugins"]["play_music"]["music_dir"] = str(tmp_path / "missing")
    assert _call_music(conn).result == play_music.PlaybackResult.UNAVAILABLE.value
    _assert_no_playback(conn)


def test_library_cache_is_bounded(tmp_path, monkeypatch):
    play_music.MUSIC_CACHE.clear()
    monkeypatch.setattr(play_music, "MUSIC_CACHE_LIMIT", 2)
    first = play_music.initialize_music_handler(_music_library_conn(tmp_path / "a"))
    play_music.initialize_music_handler(_music_library_conn(tmp_path / "b"))
    play_music.initialize_music_handler(_music_library_conn(tmp_path / "c"))
    assert len(play_music.MUSIC_CACHE) == 2
    assert first["music_dir"] == str(tmp_path / "a")


def test_library_scan_failure_is_reported_without_internal_details(tmp_path, monkeypatch):
    def fail_scan(*args):
        raise OSError("private internal directory")
    monkeypatch.setattr(play_music, "get_music_files", fail_scan)
    conn = _playback_conn(tmp_path)
    response = _call_music(conn)
    assert response.result == play_music.PlaybackResult.FAILED.value
    assert "private" not in response.result
    _assert_no_playback(conn)


def test_queue_failure_is_not_reported_as_success(tmp_path):
    (tmp_path / "song.mp3").write_bytes(b"test audio")
    conn = _playback_conn(tmp_path)
    conn.tts.tts_text_queue.put = Mock(side_effect=RuntimeError("queue unavailable"))
    response = _call_music(conn)
    assert response.action.name == "RESPONSE"
    assert response.result == play_music.PlaybackResult.FAILED.value
    assert conn.last_tool_context is None
    conn.dialogue.put.assert_not_called()


def test_expired_submission_cannot_enqueue_music(tmp_path):
    (tmp_path / "song.mp3").write_bytes(b"test audio")
    conn = _playback_conn(tmp_path)
    result = asyncio.run(play_music.handle_music_command(
        conn, "随机播放音乐", deadline=time.monotonic() - 1,
    ))
    assert result is play_music.PlaybackResult.EXPIRED
    _assert_no_playback(conn)


def test_timeout_cancels_pending_music_task(tmp_path, monkeypatch):
    future = Mock()
    future.result.side_effect = play_music.concurrent.futures.TimeoutError()

    def submit(coro, loop):
        coro.close()
        return future

    monkeypatch.setattr(play_music.asyncio, "run_coroutine_threadsafe", submit)
    conn = _playback_conn(tmp_path)
    conn.loop = Mock()
    response = play_music.play_music(conn, "random")
    assert response.action.name == "RESPONSE"
    assert "超时" in response.result
    future.cancel.assert_called_once_with()
    _assert_no_playback(conn)


def test_empty_library_context_overrides_old_music_history(tmp_path):
    from core.session.prompt_context import with_music_library

    conn = _music_library_conn(tmp_path)
    conn.func_handler = types.SimpleNamespace(has_tool=lambda name: name == "play_music")
    history = [{"role": "assistant", "content": "正在为您播放音乐"}]
    messages = with_music_library(conn, history, [{"function": {"name": "play_music"}}])
    context = next(m["content"] for m in messages if m["role"] == "system")
    assert "当前曲库为空" in context
    assert "不要调用 play_music" in context
    assert len(history) == 1
