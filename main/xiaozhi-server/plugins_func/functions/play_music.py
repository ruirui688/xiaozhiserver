import os
import re
import time
import random
import difflib
import json
import traceback
import asyncio
import concurrent.futures
import uuid
from pathlib import Path
from plugins_func.register import register_function, ToolType, ActionResponse, Action
from core.utils.dialogue import Message
from core.providers.tts.dto.dto import TTSMessageDTO, SentenceType, ContentType
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__

MUSIC_CACHE = {}
MUSIC_CACHE_DIR_NAME = ".xiaozhi-music-cache"
MUSIC_METADATA_FILE_NAME = ".xiaozhi-music-metadata.json"
MUSIC_PROMPT_DESCRIPTION_LIMIT = 120

play_music_function_desc = {
    "type": "function",
    "function": {
        "name": "play_music",
        "description": "当用户要求播放音乐、歌曲时调用。",
        "parameters": {
            "type": "object",
            "properties": {
                "song_name": {
                    "type": "string",
                    "description": "歌曲名称，如果用户没有指定具体歌名则为'random', 明确指定的时返回音乐的名字 示例: ```用户:播放两只老虎\n参数：两只老虎``` ```用户:播放音乐 \n参数：random ```",
                }
            },
            "required": ["song_name"],
        },
    },
}


@register_function("play_music", play_music_function_desc, ToolType.SYSTEM_CTL)
def play_music(conn: "ConnectionHandler", song_name: str):
    try:
        music_intent = (
            f"播放音乐 {song_name}" if song_name != "random" else "随机播放音乐"
        )

        # 检查事件循环状态
        if not conn.loop.is_running():
            conn.logger.bind(tag=TAG).error("事件循环未运行，无法提交任务")
            return ActionResponse(
                action=Action.RESPONSE, result="系统繁忙", response="请稍后再试"
            )

        future = asyncio.run_coroutine_threadsafe(
            handle_music_command(conn, music_intent),
            conn.loop,
        )
        future.result(timeout=int(conn.config.get("tool_call_timeout", 30)))
        conn.logger.bind(tag=TAG).info("音乐播放任务已入队")

        return ActionResponse(
            action=Action.RECORD, result="正在为您播放音乐", response="正在为您播放音乐"
        )
    except concurrent.futures.TimeoutError:
        conn.logger.bind(tag=TAG).error("音乐播放任务提交超时")
        return ActionResponse(
            action=Action.RESPONSE,
            result="音乐播放任务提交超时",
            response="播放音乐超时了，请稍后再试",
        )
    except Exception as e:
        conn.logger.bind(tag=TAG).error(f"处理音乐意图错误: {e}")
        return ActionResponse(
            action=Action.RESPONSE, result=str(e), response="播放音乐时出错了"
        )


def _extract_song_name(text):
    """从用户输入中提取歌名"""
    for keyword in ["播放音乐", "播放歌曲", "播放", "放一下", "放", "听一下", "听"]:
        if keyword in text:
            parts = text.split(keyword, 1)
            if len(parts) > 1:
                song_name = parts[1].strip()
                song_name = re.sub(r"^(一首|首|一下|个|一个)", "", song_name).strip()
                song_name = re.sub(r"(这首歌|这首|这歌|歌曲|音乐)$", "", song_name).strip()
                return song_name
    return None


def _find_best_match(potential_song, music_files):
    """查找最匹配的歌曲"""
    best_match = None
    highest_ratio = 0
    clean_potential_song = _normalize_match_text(potential_song)

    for music_file in music_files:
        song_name = os.path.splitext(music_file)[0]
        match_texts = [song_name, music_file]
        metadata = MUSIC_CACHE.get("music_metadata", {}).get(music_file, {})
        description = metadata.get("description", "")
        if description:
            match_texts.append(description)
        aliases = metadata.get("aliases", [])
        if isinstance(aliases, list):
            match_texts.extend(str(alias) for alias in aliases if alias)

        ratio = 0
        for match_text in match_texts:
            clean_match_text = _normalize_match_text(match_text)
            if clean_potential_song and (
                clean_potential_song in clean_match_text
                or clean_match_text in clean_potential_song
            ):
                ratio = max(ratio, 1)
            else:
                ratio = max(
                    ratio,
                    difflib.SequenceMatcher(
                        None, clean_potential_song, clean_match_text
                    ).ratio(),
                )
        if ratio > highest_ratio and ratio > 0.4:
            highest_ratio = ratio
            best_match = music_file
    return best_match


def _normalize_match_text(text):
    return re.sub(r"[\s_\-+（）()《》【】\[\]，,。.!！?？:：;；、\"'“”‘’]", "", text or "").lower()


def list_available_music(conn: "ConnectionHandler", limit=20):
    """Return visible music names from the local library for user-facing replies."""
    music_config = initialize_music_handler(conn)
    names = []
    for relative_path in music_config.get("music_files", []):
        names.append(os.path.splitext(os.path.basename(relative_path))[0])
    return names[:limit]


def list_music_prompt_entries(conn: "ConnectionHandler", limit=30):
    """Return model-facing music entries including aliases and descriptions."""
    music_config = initialize_music_handler(conn)
    entries = music_config.get("music_file_names", [])
    return entries[:limit]


def _config_bool(value, default=False):
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"1", "true", "yes", "on", "是", "开启"}:
            return True
        if normalized in {"0", "false", "no", "off", "否", "关闭"}:
            return False
    return bool(value)


def _load_music_metadata(music_dir):
    metadata_path = os.path.join(music_dir, MUSIC_METADATA_FILE_NAME)
    if not os.path.exists(metadata_path):
        return {}

    try:
        with open(metadata_path, "r", encoding="utf-8") as f:
            raw_metadata = json.load(f)
    except Exception:
        return {}

    if not isinstance(raw_metadata, dict):
        return {}

    metadata = {}
    for path, item in raw_metadata.items():
        if not isinstance(path, str):
            continue
        normalized_path = path.replace("\\", "/").strip()
        description = ""
        aliases = []
        if isinstance(item, dict):
            description = str(item.get("description") or "").strip()
            raw_aliases = item.get("aliases") or []
            if isinstance(raw_aliases, str):
                aliases = [alias.strip() for alias in raw_aliases.split(";")]
            elif isinstance(raw_aliases, list):
                aliases = [str(alias).strip() for alias in raw_aliases]
        elif isinstance(item, str):
            description = item.strip()
        aliases = [alias for alias in aliases if alias]
        if normalized_path and (description or aliases):
            metadata[normalized_path] = {
                "description": description,
                "aliases": aliases,
            }
    return metadata


def _build_music_prompt_name(relative_path, metadata):
    song_name = os.path.splitext(relative_path)[0]
    description = metadata.get(relative_path, {}).get("description", "")
    aliases = metadata.get(relative_path, {}).get("aliases", [])
    alias_text = "、".join(aliases[:5]) if isinstance(aliases, list) else ""
    if not description and not alias_text:
        return song_name
    prompt_parts = [part for part in (alias_text, description) if part]
    prompt_text = "；".join(prompt_parts)
    short_description = prompt_text[:MUSIC_PROMPT_DESCRIPTION_LIMIT]
    if len(prompt_text) > MUSIC_PROMPT_DESCRIPTION_LIMIT:
        short_description += "..."
    return f"{song_name}：{short_description}"


def get_music_files(music_dir, music_ext):
    music_dir = Path(music_dir)
    metadata = _load_music_metadata(str(music_dir))
    music_files = []
    music_file_names = []
    for file in music_dir.rglob("*"):
        relative_parts = file.relative_to(music_dir).parts
        if any(part.startswith(".") for part in relative_parts):
            continue
        # 判断是否是文件
        if file.is_file():
            # 获取文件扩展名
            ext = file.suffix.lower()
            # 判断扩展名是否在列表中
            if ext in music_ext:
                # 添加相对路径
                relative_path = str(file.relative_to(music_dir)).replace("\\", "/")
                music_files.append(relative_path)
                music_file_names.append(
                    _build_music_prompt_name(relative_path, metadata)
                )
    return music_files, music_file_names, metadata


def _music_dir_mtimes(music_dir):
    """返回音乐目录与元数据文件的 mtime，用于判断缓存是否落后于磁盘。"""
    metadata_path = os.path.join(music_dir, MUSIC_METADATA_FILE_NAME)
    dir_mtime = os.path.getmtime(music_dir) if os.path.exists(music_dir) else 0
    metadata_mtime = (
        os.path.getmtime(metadata_path) if os.path.exists(metadata_path) else 0
    )
    return dir_mtime, metadata_mtime


def _refresh_music_cache():
    (
        MUSIC_CACHE["music_files"],
        MUSIC_CACHE["music_file_names"],
        MUSIC_CACHE["music_metadata"],
    ) = get_music_files(
        MUSIC_CACHE["music_dir"], MUSIC_CACHE["music_ext"]
    )
    MUSIC_CACHE["scan_time"] = time.time()
    dir_mtime, metadata_mtime = _music_dir_mtimes(MUSIC_CACHE["music_dir"])
    MUSIC_CACHE["dir_mtime"] = dir_mtime
    MUSIC_CACHE["metadata_mtime"] = metadata_mtime


def _music_cache_is_stale():
    music_dir = MUSIC_CACHE.get("music_dir")
    if not music_dir:
        return False
    dir_mtime, metadata_mtime = _music_dir_mtimes(music_dir)
    if dir_mtime != MUSIC_CACHE.get("dir_mtime"):
        return True
    if metadata_mtime != MUSIC_CACHE.get("metadata_mtime"):
        return True
    # 子目录内的增删不会改变顶层目录 mtime，因此仍需按 refresh_time 兜底重扫。
    return (
        time.time() - MUSIC_CACHE.get("scan_time", 0)
        > MUSIC_CACHE.get("refresh_time", 60)
    )


def _refresh_music_cache_if_stale():
    """磁盘曲库变化后重建缓存。

    曲库提示词（<music_library>、工具描述）和播放共用这份进程内缓存。如果只在播放时
    才检查目录 mtime，删除歌曲后模型仍会看到旧曲库，持续回复"给你放一首《…》"，
    而真正播放时又报"未找到MP3音乐文件"。
    """
    if not _music_cache_is_stale():
        return False
    _refresh_music_cache()
    return True


def initialize_music_handler(conn: "ConnectionHandler"):
    global MUSIC_CACHE
    if MUSIC_CACHE == {}:
        plugins_config = conn.config.get("plugins", {})
        if "play_music" in plugins_config:
            MUSIC_CACHE["music_config"] = plugins_config["play_music"]
            MUSIC_CACHE["music_dir"] = os.path.abspath(
                MUSIC_CACHE["music_config"].get("music_dir", "./music")  # 默认路径修改
            )
            MUSIC_CACHE["music_cache_dir"] = os.path.join(
                MUSIC_CACHE["music_dir"], MUSIC_CACHE_DIR_NAME
            )
            MUSIC_CACHE["music_ext"] = MUSIC_CACHE["music_config"].get(
                "music_ext", (".mp3", ".wav", ".p3")
            )
            MUSIC_CACHE["refresh_time"] = MUSIC_CACHE["music_config"].get(
                "refresh_time", 60
            )
            MUSIC_CACHE["announce_before_play"] = _config_bool(
                MUSIC_CACHE["music_config"].get("announce_before_play"), False
            )
        else:
            MUSIC_CACHE["music_dir"] = os.path.abspath("./music")
            MUSIC_CACHE["music_cache_dir"] = os.path.join(
                MUSIC_CACHE["music_dir"], MUSIC_CACHE_DIR_NAME
            )
            MUSIC_CACHE["music_ext"] = (".mp3", ".wav", ".p3")
            MUSIC_CACHE["refresh_time"] = 60
            MUSIC_CACHE["announce_before_play"] = False
        # 获取音乐文件列表
        _refresh_music_cache()
    # 提示词与播放共用同一份缓存，每次取用前都按磁盘现状刷新，
    # 保证删除/新增歌曲后曲库上下文立即生效，不必等一次播放或重启进程。
    _refresh_music_cache_if_stale()
    return MUSIC_CACHE


async def handle_music_command(conn: "ConnectionHandler", text):
    # initialize_music_handler 已按磁盘现状刷新缓存，这里不再重复检查 mtime。
    initialize_music_handler(conn)
    global MUSIC_CACHE
    conn.last_tool_context = "music"

    """处理音乐播放指令"""
    clean_text = re.sub(r"[^\w\s]", "", text).strip()
    conn.logger.bind(tag=TAG).debug(f"检查是否是音乐命令: {clean_text}")

    # 尝试匹配具体歌名
    if os.path.exists(MUSIC_CACHE["music_dir"]):
        potential_song = _extract_song_name(clean_text)
        if potential_song:
            best_match = _find_best_match(potential_song, MUSIC_CACHE["music_files"])
            if best_match:
                conn.logger.bind(tag=TAG).info(f"找到最匹配的歌曲: {best_match}")
                await play_local_music(conn, specific_file=best_match)
                return True
    # 检查是否是通用播放音乐命令
    await play_local_music(conn)
    return True


def _get_random_play_prompt(song_name):
    """生成随机播放引导语"""
    # 移除文件扩展名
    clean_name = os.path.splitext(song_name)[0]
    prompts = [
        f"正在为您播放 {clean_name}",
        f"请欣赏歌曲 {clean_name}",
        f"即将为您播放 {clean_name}",
        f"现在为您带来 {clean_name}",
        f"让我们一起聆听 {clean_name}",
        f"接下来请欣赏 {clean_name}",
        f"此刻为您献上 {clean_name}",
    ]
    # 直接使用random.choice，不设置seed
    return random.choice(prompts)


def _get_optimized_music_path(selected_music, music_path):
    if os.path.splitext(selected_music)[1].lower() == ".p3":
        return music_path

    cache_dir = MUSIC_CACHE.get("music_cache_dir")
    if not cache_dir:
        return music_path

    optimized_path = os.path.join(cache_dir, selected_music + ".p3")
    if not os.path.exists(optimized_path):
        return music_path

    try:
        if os.path.getmtime(optimized_path) < os.path.getmtime(music_path):
            return music_path
    except OSError:
        return music_path

    return optimized_path


def _enqueue_music_file(conn: "ConnectionHandler", playback_path):
    conn.tts.tts_text_queue.put(
        TTSMessageDTO(
            sentence_id=conn.sentence_id,
            sentence_type=SentenceType.MIDDLE,
            content_type=ContentType.FILE,
            content_file=playback_path,
        )
    )


def _enqueue_playback_boundary(conn: "ConnectionHandler", sentence_type: SentenceType):
    conn.tts.tts_text_queue.put(
        TTSMessageDTO(
            sentence_id=conn.sentence_id,
            sentence_type=sentence_type,
            content_type=ContentType.ACTION,
        )
    )


async def play_local_music(conn: "ConnectionHandler", specific_file=None):
    global MUSIC_CACHE
    """播放本地音乐文件"""
    try:
        if not os.path.exists(MUSIC_CACHE["music_dir"]):
            conn.logger.bind(tag=TAG).error(
                f"音乐目录不存在: " + MUSIC_CACHE["music_dir"]
            )
            return

        # 确保路径正确性
        if specific_file:
            selected_music = specific_file
            music_path = os.path.join(MUSIC_CACHE["music_dir"], specific_file)
        else:
            if not MUSIC_CACHE["music_files"]:
                conn.logger.bind(tag=TAG).error("未找到MP3音乐文件")
                return
            selected_music = random.choice(MUSIC_CACHE["music_files"])
            music_path = os.path.join(MUSIC_CACHE["music_dir"], selected_music)

        if not os.path.exists(music_path):
            conn.logger.bind(tag=TAG).error(f"选定的音乐文件不存在: {music_path}")
            return
        playback_path = _get_optimized_music_path(selected_music, music_path)
        if playback_path != music_path:
            conn.logger.bind(tag=TAG).info(f"使用低延时音乐缓存: {playback_path}")
        text = _get_random_play_prompt(selected_music)
        conn.sentence_id = str(uuid.uuid4().hex)

        _enqueue_playback_boundary(conn, SentenceType.FIRST)
        conn.tts.store_tts_text(conn.sentence_id, text)
        conn.dialogue.put(Message(role="assistant", content=text))
        conn.tts.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id=conn.sentence_id,
                sentence_type=SentenceType.MIDDLE,
                content_type=ContentType.TEXT,
                content_detail=text,
            )
        )
        _enqueue_music_file(conn, playback_path)
        _enqueue_playback_boundary(conn, SentenceType.LAST)

    except Exception as e:
        conn.logger.bind(tag=TAG).error(f"播放音乐失败: {str(e)}")
        conn.logger.bind(tag=TAG).error(f"详细错误: {traceback.format_exc()}")
