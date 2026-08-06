import time
import json
import uuid
import random
import asyncio
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
from core.utils.dialogue import Message
from core.utils.util import audio_to_data
from core.providers.tts.dto.dto import SentenceType
from core.utils.wakeup_word import WakeupWordsConfig
from core.handle.reportHandle import enqueue_tts_report
from core.handle.sendAudioHandle import sendAudioMessage, send_tts_message
from core.utils.util import remove_punctuation_and_length, opus_datas_to_wav_bytes
from core.providers.tools.device_mcp import MCPClient, send_mcp_initialize_message
from core.handle.wakeupHandle import arm_wakeup_vad_ignore
from core.device_face_profiles import schedule_face_profile_sync

TAG = __name__

WAKEUP_CONFIG = {
    "refresh_time": 10,
    "responses": [
        "我一直都在呢，您请说。",
        "在的呢，请随时吩咐我。",
        "来啦来啦，请告诉我吧。",
        "您请说，我正听着。",
        "请您讲话，我准备好了。",
        "请您说出指令吧。",
        "我认真听着呢，请讲。",
        "请问您需要什么帮助？",
        "我在这里，等候您的指令。",
    ],
}

# 创建全局的唤醒词配置管理器
wakeup_words_config = WakeupWordsConfig()

# 用于防止并发调用wakeupWordsResponse的锁
_wakeup_response_lock = asyncio.Lock()


async def handleHelloMessage(conn: "ConnectionHandler", msg_json):
    """处理hello消息"""
    audio_params = msg_json.get("audio_params")
    if audio_params:
        audio_format = audio_params.get("format")
        conn.logger.bind(tag=TAG).debug(f"客户端输入音频格式: {audio_format}")
        if audio_format:
            conn.audio_format = audio_format
        # 回给设备的 audio_params 会影响设备端录音通道配置。
        # ASR/VAD 当前按 16k 单声道 Opus 处理，因此这里保留客户端上报的输入参数，
        # 避免把服务端 TTS 输出采样率误下发成设备录音采样率。
        conn.welcome_msg["audio_params"] = audio_params
    features = msg_json.get("features")
    speech_runtime = getattr(conn, "speech_runtime", None)
    if speech_runtime is not None:
        speech_runtime.record_hello(features)
    if isinstance(features, dict):
        conn.logger.bind(tag=TAG).info(
            f"收到hello特性: device-id={conn.headers.get('device-id') if conn.headers else ''}, "
            f"client-id={conn.headers.get('client-id') if conn.headers else ''}, features={features}"
        )
        conn.features = features
        conn.client_aec = bool(features.get("aec"))
        if conn.client_aec:
            conn.logger.bind(tag=TAG).info("客户端声明支持AEC，启用开口立即打断")
        if features.get("mcp"):
            conn.logger.bind(tag=TAG).info("客户端声明支持MCP，创建MCPClient并启动初始化")
            conn.mcp_client = MCPClient()
            # 发送初始化
            asyncio.create_task(send_mcp_initialize_message(conn))
    conn.logger.bind(tag=TAG).info(
        f"发送hello消息，客户端输入音频参数: {conn.welcome_msg.get('audio_params')}, "
        f"服务端输出音频采样率: {conn.sample_rate}"
    )
    await conn.websocket.send(json.dumps(conn.welcome_msg))
    if (conn.features or {}).get("face_profiles"):
        schedule_face_profile_sync(conn)


async def checkWakeupWords(conn: "ConnectionHandler", text):
    enable_wakeup_words_response_cache = conn.config.get(
        "enable_wakeup_words_response_cache", False
    )
    if not enable_wakeup_words_response_cache:
        return False

    _, filtered_text = remove_punctuation_and_length(text)
    if filtered_text not in (conn.config.get("wakeup_words") or []):
        return False

    # 仅真正命中缓存唤醒词时等待TTS，普通用户文本不应被初始化过程阻塞。
    start_time = time.time()
    while time.time() - start_time < 3:
        if conn.tts:
            break
        await asyncio.sleep(0.1)
    else:
        return False

    arm_wakeup_vad_ignore(conn)
    await send_tts_message(conn, "start")

    # 获取当前音色
    voice = getattr(conn.tts, "voice", "default")
    if not voice:
        voice = "default"

    # 获取唤醒词回复配置
    response = wakeup_words_config.get_wakeup_response(voice)
    if not response or not response.get("file_path"):
        response = {
            "voice": "default",
            "file_path": "config/assets/wakeup_words_short.wav",
            "time": 0,
            "text": "我在这里哦！",
        }

    # 获取音频数据
    opus_packets = await audio_to_data(response.get("file_path"), use_cache=False)
    # 播放唤醒词回复
    conn.client_abort = False

    # 将唤醒词回复视为新会话，生成新的 sentence_id，确保流控器重置
    conn.sentence_id = str(uuid.uuid4().hex)

    conn.logger.bind(tag=TAG).info(f"播放唤醒词回复: {response.get('text')}")
    await sendAudioMessage(conn, SentenceType.FIRST, opus_packets, response.get("text"))
    await sendAudioMessage(conn, SentenceType.LAST, [], None)

    # 唤醒加速直接发送缓存音频，不经过 TTS provider 的统一上报点。
    # 设备已经真实播放的助手回复仍应如实进入聊天记录。
    response_text = str(response.get("text") or "").strip()
    if response_text:
        enqueue_tts_report(conn, response_text, opus_packets)

    # 补充对话
    conn.dialogue.put(Message(role="assistant", content=response.get("text")))

    # 检查是否需要更新唤醒词回复
    if time.time() - response.get("time", 0) > WAKEUP_CONFIG["refresh_time"]:
        if not _wakeup_response_lock.locked():
            asyncio.create_task(wakeupWordsResponse(conn))
    return True


async def wakeupWordsResponse(conn: "ConnectionHandler"):
    if not conn.tts:
        return

    try:
        # 尝试获取锁，如果获取不到就返回
        if not await _wakeup_response_lock.acquire():
            return

        # 从预定义回复列表中随机选择一个回复
        result = random.choice(WAKEUP_CONFIG["responses"])
        if not result or len(result) == 0:
            return

        # 生成TTS音频
        tts_result = await asyncio.to_thread(conn.tts.to_tts, result)
        if not tts_result:
            return

        # 获取当前音色
        voice = getattr(conn.tts, "voice", "default")

        # 使用链接的sample_rate
        wav_bytes = opus_datas_to_wav_bytes(tts_result, sample_rate=conn.sample_rate)
        file_path = wakeup_words_config.generate_file_path(voice)
        with open(file_path, "wb") as f:
            f.write(wav_bytes)
        # 更新配置
        wakeup_words_config.update_wakeup_response(voice, file_path, result)
    finally:
        # 确保在任何情况下都释放锁
        if _wakeup_response_lock.locked():
            _wakeup_response_lock.release()
