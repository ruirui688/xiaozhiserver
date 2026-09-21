from typing import Dict, Any
from config.logger import setup_logging
from core.utils import tts, llm, intent, memory, vad, asr
from core.observability.session_telemetry import latency_span, mark_latency
from core.utils.model_config import (
    get_runtime_config,
    get_runtime_type,
    has_runtime_config,
    is_realtime_model_config,
)

TAG = __name__
logger = setup_logging()


def has_selected_module(config: Dict[str, Any], module_type: str) -> bool:
    selected_module = config.get("selected_module", {})
    selected_name = selected_module.get(module_type)
    return bool(selected_name and has_runtime_config(config, module_type, selected_name))


def initialize_modules(
    logger,
    config: Dict[str, Any],
    init_vad=False,
    init_asr=False,
    init_llm=False,
    init_tts=False,
    init_memory=False,
    init_intent=False,
    observer=None,
) -> Dict[str, Any]:
    """
    初始化所有模块组件

    Args:
        config: 配置字典

    Returns:
        Dict[str, Any]: 包含所有初始化后的模块的字典
    """
    mark_latency(observer, "init.create_enter")
    modules = {}

    def create(name, factory, *args):
        with latency_span(observer, "init.create." + name):
            return factory(*args)

    # 初始化TTS模块
    realtime_conversation = is_realtime_model_config(config)

    if init_tts and not realtime_conversation and has_selected_module(config, "TTS"):
        select_tts_module = config["selected_module"]["TTS"]
        modules["tts"] = create("tts", initialize_tts, config)
        logger.bind(tag=TAG).info(f"初始化组件: tts成功 {select_tts_module}")

    # 初始化LLM模块
    if init_llm and not realtime_conversation and has_selected_module(config, "LLM"):
        select_llm_module = config["selected_module"]["LLM"]
        llm_type = get_runtime_type(config, "LLM", select_llm_module)
        modules["llm"] = create("llm", llm.create_instance,
            llm_type,
            get_runtime_config(config, "LLM", select_llm_module),
        )
        logger.bind(tag=TAG).info(f"初始化组件: llm成功 {select_llm_module}")

    # 初始化Intent模块
    if init_intent and has_selected_module(config, "Intent"):
        select_intent_module = config["selected_module"]["Intent"]
        intent_type = get_runtime_type(config, "Intent", select_intent_module)
        modules["intent"] = create("intent", intent.create_instance,
            intent_type,
            get_runtime_config(config, "Intent", select_intent_module),
        )
        logger.bind(tag=TAG).info(f"初始化组件: intent成功 {select_intent_module}")

    # 初始化Memory模块
    if init_memory and has_selected_module(config, "Memory"):
        select_memory_module = config["selected_module"]["Memory"]
        memory_type = get_runtime_type(config, "Memory", select_memory_module)
        modules["memory"] = create("memory", memory.create_instance,
            memory_type,
            get_runtime_config(config, "Memory", select_memory_module),
            config.get("summaryMemory", None),
        )
        logger.bind(tag=TAG).info(f"初始化组件: memory成功 {select_memory_module}")

    # 初始化VAD模块
    if init_vad and not realtime_conversation and has_selected_module(config, "VAD"):
        select_vad_module = config["selected_module"]["VAD"]
        vad_type = get_runtime_type(config, "VAD", select_vad_module)
        modules["vad"] = create("vad", vad.create_instance,
            vad_type,
            get_runtime_config(config, "VAD", select_vad_module),
        )
        logger.bind(tag=TAG).info(f"初始化组件: vad成功 {select_vad_module}")

    # 初始化ASR模块
    if init_asr and not realtime_conversation and has_selected_module(config, "ASR"):
        select_asr_module = config["selected_module"]["ASR"]
        modules["asr"] = create("asr", initialize_asr, config)
        logger.bind(tag=TAG).info(f"初始化组件: asr成功 {select_asr_module}")
    return modules


def initialize_tts(config):
    if not has_selected_module(config, "TTS"):
        return None
    select_tts_module = config["selected_module"]["TTS"]
    tts_type = get_runtime_type(config, "TTS", select_tts_module)
    new_tts = tts.create_instance(
        tts_type,
        get_runtime_config(config, "TTS", select_tts_module),
        str(config.get("delete_audio", True)).lower() in ("true", "1", "yes"),
    )
    return new_tts


def initialize_asr(config):
    if not has_selected_module(config, "ASR"):
        return None
    select_asr_module = config["selected_module"]["ASR"]
    asr_type = get_runtime_type(config, "ASR", select_asr_module)
    new_asr = asr.create_instance(
        asr_type,
        get_runtime_config(config, "ASR", select_asr_module),
        str(config.get("delete_audio", True)).lower() in ("true", "1", "yes"),
    )
    logger.bind(tag=TAG).info("ASR模块初始化完成")
    return new_asr


def initialize_voiceprint(asr_instance, config):
    """初始化声纹识别功能"""
    voiceprint_config = config.get("voiceprint")
    if not voiceprint_config:
        return False  

    # 应用配置
    if not voiceprint_config.get("url") or not voiceprint_config.get("speakers"):
        logger.bind(tag=TAG).warning("声纹识别配置不完整")
        return False
        
    try:
        asr_instance.init_voiceprint(voiceprint_config)
        logger.bind(tag=TAG).info("ASR模块声纹识别功能已动态启用")
        logger.bind(tag=TAG).info(f"配置说话人数量: {len(voiceprint_config['speakers'])}")
        return True
    except Exception as e:
        logger.bind(tag=TAG).error(f"动态初始化声纹识别功能失败: {str(e)}")
        return False
