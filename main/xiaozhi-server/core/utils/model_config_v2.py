from copy import deepcopy
from typing import Any, Dict, Optional


class ModelConfigV2Error(RuntimeError):
    """Raised when manager-api did not provide the required v2 runtime contract."""


def get_typed_config(config: Dict[str, Any], module_type: str, module_id: str) -> Optional[Dict[str, Any]]:
    """Return normalized v2 typed_config for a selected model, if manager-api supplied it."""
    if not module_id:
        return None
    v2_models = config.get("model_config_v2") or {}
    model_entry = v2_models.get(module_id)
    if not isinstance(model_entry, dict):
        return None
    if module_type and str(model_entry.get("model_type", "")).lower() != module_type.lower():
        return None
    typed_config = model_entry.get("typed_config")
    return typed_config if isinstance(typed_config, dict) else None


def get_runtime_config(config: Dict[str, Any], module_type: str, module_id: str) -> Dict[str, Any]:
    """Return the v2 typed runtime contract for the selected model."""
    typed_config = get_typed_config(config, module_type, module_id)
    if typed_config is not None:
        return typed_config
    raise ModelConfigV2Error(
        f"缺少 model_config_v2.{module_id}.typed_config，无法初始化 {module_type} 模型"
    )


def has_runtime_config(config: Dict[str, Any], module_type: str, module_id: str) -> bool:
    return get_typed_config(config, module_type, module_id) is not None


def get_runtime_type(config: Dict[str, Any], module_type: str, module_id: str) -> str:
    typed_config = get_runtime_config(config, module_type, module_id)
    return str(typed_config.get("type") or module_id)


def get_selected_runtime_config(config: Dict[str, Any], module_type: str) -> Dict[str, Any]:
    module_id = (config.get("selected_module") or {}).get(module_type)
    if not module_id:
        raise ModelConfigV2Error(f"未选择 {module_type} 模型")
    return get_runtime_config(config, module_type, module_id)


def build_legacy_runtime_view(config: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    """Build a read-only compatibility view from model_config_v2 for old call sites."""
    view: Dict[str, Dict[str, Any]] = {}
    for model_id, entry in (config.get("model_config_v2") or {}).items():
        if not isinstance(entry, dict):
            continue
        module_type = entry.get("model_type")
        typed_config = entry.get("typed_config")
        if not module_type or not isinstance(typed_config, dict):
            continue
        view.setdefault(str(module_type), {})[str(model_id)] = deepcopy(typed_config)
    return view


def apply_legacy_runtime_view(config: Dict[str, Any]) -> Dict[str, Any]:
    """Expose typed v2 configs under legacy module keys without requiring API legacy payloads."""
    if not isinstance(config.get("model_config_v2"), dict):
        config["model_config_v2"] = build_model_config_v2_from_legacy(config)
    for module_type, module_configs in build_legacy_runtime_view(config).items():
        config[module_type] = module_configs
    return config


def build_model_config_v2_from_legacy(config: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    """Normalize local config.yaml style module maps into the v2 runtime contract."""
    selected = config.get("selected_module") or {}
    model_config_v2: Dict[str, Dict[str, Any]] = {}
    for module_type, module_id in selected.items():
        module_configs = config.get(module_type) or {}
        typed_config = module_configs.get(module_id)
        if not isinstance(typed_config, dict):
            continue
        model_config_v2[str(module_id)] = {
            "id": str(module_id),
            "model_type": str(module_type),
            "model_code": str(module_id),
            "model_name": str(module_id),
            "provider_code": str(typed_config.get("type") or module_id),
            "typed_config": deepcopy(typed_config),
        }
    return model_config_v2
