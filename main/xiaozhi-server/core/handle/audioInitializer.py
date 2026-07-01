"""Audio pipeline initialization for connections."""
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

from core.providers.tts.default import DefaultTTS
from core.utils.modules_initialize import (
    initialize_tts as modules_init_tts,
    initialize_asr as modules_init_asr,
)
from core.providers.asr.dto.dto import InterfaceType

TAG = __name__


def initialize_tts(conn: "ConnectionHandler"):
    """初始化TTS"""
    tts = None
    if not conn.need_bind:
        tts = modules_init_tts(conn.config)

    if tts is None:
        tts = DefaultTTS(conn.config, delete_audio_file=True)

    return tts


def initialize_asr(conn: "ConnectionHandler"):
    """初始化ASR"""
    selected_asr = conn.config.get("selected_module", {}).get("ASR")
    if not selected_asr or not conn.config.get("ASR", {}).get(selected_asr):
        return None
    common_selected_asr = conn.common_config.get("selected_module", {}).get("ASR")
    if (
            conn._asr is not None
            and hasattr(conn._asr, "interface_type")
            and conn._asr.interface_type == InterfaceType.LOCAL
            and selected_asr == common_selected_asr
    ):
        asr = conn._asr
    else:
        asr = modules_init_asr(conn.config)

    return asr


def initialize_voiceprint(conn: "ConnectionHandler"):
    """为当前连接初始化声纹识别"""
    from core.utils.voiceprint_provider import VoiceprintProvider

    try:
        voiceprint_config = conn.config.get("voiceprint", {})
        if voiceprint_config:
            voiceprint_provider = VoiceprintProvider(voiceprint_config)
            if voiceprint_provider is not None and voiceprint_provider.enabled:
                conn.voiceprint_provider = voiceprint_provider
                conn.logger.bind(tag=TAG).info("声纹识别功能已在连接时动态启用")
            else:
                conn.logger.bind(tag=TAG).warning("声纹识别功能启用但配置不完整")
        else:
            conn.logger.bind(tag=TAG).info("声纹识别功能未启用")
    except Exception as e:
        conn.logger.bind(tag=TAG).warning(f"声纹识别初始化失败: {str(e)}")
