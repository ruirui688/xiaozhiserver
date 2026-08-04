from copy import deepcopy
from typing import Any, Dict, Optional


MODULE_TYPE_ALIASES = {
    "asr": "ASR",
    "tts": "TTS",
    "llm": "LLM",
    "slm": "SLM",
    "vllm": "VLLM",
    "vad": "VAD",
    "memory": "Memory",
    "mem": "Memory",
    "intent": "Intent",
    "rag": "RAG",
}

CANONICAL_MODULE_TYPES = {
    "ASR",
    "TTS",
    "LLM",
    "SLM",
    "VLLM",
    "VAD",
    "Memory",
    "Intent",
    "RAG",
}


class ModelConfigError(RuntimeError):
    """Raised when manager-api did not provide the required runtime contract."""


def normalize_module_type(module_type: Any) -> str:
    key = str(module_type or "").strip()
    return MODULE_TYPE_ALIASES.get(key.lower(), key)


def get_typed_config(config: Dict[str, Any], module_type: str, module_id: str) -> Optional[Dict[str, Any]]:
    """Return normalized model_config typed_config for a selected model."""
    if not module_id:
        return None
    model_entry = _get_model_entry(config, module_type, module_id)
    if not isinstance(model_entry, dict):
        return None
    entry_module_type = model_entry.get("model_type") or module_type
    if module_type and normalize_module_type(entry_module_type) != normalize_module_type(module_type):
        return None
    typed_config = model_entry.get("typed_config") or model_entry.get("config")
    return typed_config if isinstance(typed_config, dict) else None


def get_runtime_config(config: Dict[str, Any], module_type: str, module_id: str) -> Dict[str, Any]:
    """Return the selected model's typed runtime config."""
    typed_config = get_typed_config(config, module_type, module_id)
    if typed_config is not None:
        return typed_config
    raise ModelConfigError(
        f"缺少 model_config.{module_id}.typed_config，无法初始化 {module_type} 模型"
    )


def has_runtime_config(config: Dict[str, Any], module_type: str, module_id: str) -> bool:
    return get_typed_config(config, module_type, module_id) is not None


def get_runtime_type(config: Dict[str, Any], module_type: str, module_id: str) -> str:
    typed_config = get_runtime_config(config, module_type, module_id)
    return str(typed_config.get("type") or module_id)


def get_selected_runtime_config(config: Dict[str, Any], module_type: str) -> Dict[str, Any]:
    module_id = (normalize_selected_module(config).get(module_type))
    if not module_id:
        raise ModelConfigError(f"未选择 {module_type} 模型")
    return get_runtime_config(config, module_type, module_id)


def is_realtime_model_config(config: Dict[str, Any], module_type: str = "LLM") -> bool:
    """Return whether the selected model opts into the realtime conversation path."""
    try:
        typed_config = get_selected_runtime_config(config, module_type)
    except ModelConfigError:
        return False
    return str(typed_config.get("conversation_mode") or "").strip().lower() == "realtime"


def normalize_selected_module(config: Dict[str, Any]) -> Dict[str, Any]:
    selected = config.get("selected_module")
    if not isinstance(selected, dict):
        selected = {}
    normalized = {}
    for module_type, module_id in selected.items():
        normalized[normalize_module_type(module_type)] = module_id
    config["selected_module"] = normalized
    return normalized


def normalize_model_config(config: Dict[str, Any]) -> Dict[str, Any]:
    model_config = config.get("model_config")
    if not isinstance(model_config, dict):
        return {}
    config["model_config"] = _normalize_model_config_map(model_config)
    return config["model_config"]


def _normalize_model_config_map(model_config: Dict[str, Any]) -> Dict[str, Any]:
    normalized = {}
    for model_id, entry in model_config.items():
        if not isinstance(entry, dict):
            continue
        normalized_entry = dict(entry)
        normalized_entry["model_type"] = normalize_module_type(
            normalized_entry.get("model_type")
        )
        if "typed_config" not in normalized_entry and isinstance(
            normalized_entry.get("config"), dict
        ):
            normalized_entry["typed_config"] = normalized_entry["config"]
        normalized[str(model_id)] = normalized_entry
    return normalized


def _get_model_entry(
    config: Dict[str, Any], module_type: str, module_id: str
) -> Optional[Dict[str, Any]]:
    model_config = config.get("model_config") or {}
    if isinstance(model_config, dict) and isinstance(model_config.get(module_id), dict):
        return model_config.get(module_id)
    legacy_maps = config.get(normalize_module_type(module_type)) or {}
    if isinstance(legacy_maps, dict) and isinstance(legacy_maps.get(module_id), dict):
        return {
            "id": str(module_id),
            "model_type": normalize_module_type(module_type),
            "typed_config": legacy_maps.get(module_id),
        }
    return None


def build_legacy_runtime_view(config: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    """Build compatibility module maps from model_config for old call sites."""
    view: Dict[str, Dict[str, Any]] = {}
    for model_id, entry in (config.get("model_config") or {}).items():
        if not isinstance(entry, dict):
            continue
        module_type = normalize_module_type(entry.get("model_type"))
        typed_config = entry.get("typed_config") or entry.get("config")
        if not module_type or not isinstance(typed_config, dict):
            continue
        view.setdefault(str(module_type), {})[str(model_id)] = deepcopy(typed_config)
    return view


def normalize_runtime_config(config: Dict[str, Any]) -> Dict[str, Any]:
    """Normalize selected_module + model_config and rebuild legacy maps for old call sites."""
    normalize_selected_module(config)
    has_legacy_maps = any(
        isinstance(config.get(module_type), dict)
        for module_type in CANONICAL_MODULE_TYPES
    )
    if not isinstance(config.get("model_config"), dict):
        if has_legacy_maps:
            config["model_config"] = build_model_config_from_legacy(config)
        else:
            config["model_config"] = {}
    elif not config.get("model_config") and has_legacy_maps:
        config["model_config"] = build_model_config_from_legacy(config)
    normalize_model_config(config)
    if has_legacy_maps:
        _merge_missing_model_config_from_legacy(config)
    for module_type in CANONICAL_MODULE_TYPES:
        config.pop(module_type, None)
    for module_type, module_configs in build_legacy_runtime_view(config).items():
        config[module_type] = module_configs
    return config


def apply_legacy_runtime_view(config: Dict[str, Any]) -> Dict[str, Any]:
    """Backward-compatible alias for the canonical runtime normalization entry."""
    return normalize_runtime_config(config)


def build_model_config_from_legacy(config: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    """Normalize local config.yaml style module maps into model_config."""
    selected = config.get("selected_module") or {}
    model_config: Dict[str, Dict[str, Any]] = {}
    for module_type, module_id in selected.items():
        normalized_module_type = normalize_module_type(module_type)
        module_configs = config.get(normalized_module_type) or config.get(module_type) or {}
        typed_config = module_configs.get(module_id)
        if not isinstance(typed_config, dict):
            continue
        model_config[str(module_id)] = {
            "id": str(module_id),
            "model_type": str(normalized_module_type),
            "model_code": str(module_id),
            "model_name": str(module_id),
            "provider_code": str(typed_config.get("type") or module_id),
            "typed_config": deepcopy(typed_config),
        }
    return model_config


def _merge_missing_model_config_from_legacy(config: Dict[str, Any]) -> None:
    model_config = config.setdefault("model_config", {})
    if not isinstance(model_config, dict):
        return
    for model_id, entry in build_model_config_from_legacy(config).items():
        model_config.setdefault(model_id, entry)
