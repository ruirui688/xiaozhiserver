"""Build connection-scoped runtime configuration from manager-api payloads."""

import copy
import json
from hashlib import sha256

from core.utils.correct_words import parse_correct_words
from core.utils.model_config import (
    has_runtime_config,
    normalize_runtime_config,
)


MANAGED_CONFIG_KEYS = (
    "agent_id",
    "agent_name",
    "prompt",
    "voiceprint",
    "voiceprint_enrollment",
    "summaryMemory",
    "memory_policy",
    "mcp_endpoint",
    "context_providers",
    "semantic_skills",
    "speech_routing",
    "device_max_output_size",
    "chat_history_conf",
    "tts_voice_catalog",
    "billing_context",
)


def normalize_plugins(value):
    if not isinstance(value, dict):
        return {}
    plugins = {}
    for name, plugin_config in value.items():
        if isinstance(plugin_config, str):
            try:
                plugins[name] = json.loads(plugin_config)
            except json.JSONDecodeError:
                plugins[name] = {}
        else:
            plugins[name] = copy.deepcopy(plugin_config)
    return plugins


def build_managed_runtime_config(common_config, private_config):
    """Return a fresh runtime config without retaining stale managed values."""
    target = copy.deepcopy(common_config or {})
    private = copy.deepcopy(private_config or {})
    # A snapshot is authoritative per-device data, never a common-config default.
    target.pop("managed_memory_snapshot", None)
    target.pop("billing_context", None)
    if "managed_memory_snapshot" in private:
        target["managed_memory_snapshot"] = copy.deepcopy(private["managed_memory_snapshot"])
    normalize_runtime_config(private)

    private_models = private.get("model_config")
    if isinstance(private_models, dict):
        target.setdefault("model_config", {}).update(copy.deepcopy(private_models))

    target.setdefault("selected_module", {})
    selected = private.get("selected_module") or {}
    for module_type in (
        "VAD",
        "ASR",
        "TTS",
        "LLM",
        "VLLM",
        "Memory",
        "Intent",
        "SLM",
        "RAG",
    ):
        module_id = selected.get(module_type)
        if module_id and has_runtime_config(private, module_type, module_id):
            target["selected_module"][module_type] = module_id

    normalize_runtime_config(target)

    for key in MANAGED_CONFIG_KEYS:
        if key in private:
            target[key] = copy.deepcopy(private[key])

    # The resource-center catalog is a complete snapshot, including deletion.
    # Never retain common/previous descriptions when an older API omits it.
    overrides = private.get("tool_prompt_overrides")
    target["tool_prompt_overrides"] = {
        name: description.strip()
        for name, description in (overrides.items() if isinstance(overrides, dict) else ())
        if isinstance(name, str) and isinstance(description, str) and description.strip()
    }
    # Java omits this field when the agent has no sources. Do not fall back to
    # another role's/common sources after removal or a scene switch.
    sources = private.get("context_providers")
    target["context_providers"] = copy.deepcopy(sources) if isinstance(sources, list) else []

    plugins = normalize_plugins(private.get("plugins"))
    target["plugins"] = plugins
    selected_intent = target.get("selected_module", {}).get("Intent")
    intent_config = target.get("Intent", {}).get(selected_intent)
    if isinstance(intent_config, dict):
        intent_config["functions"] = list(plugins)

    if "correct_words" in private:
        correct_words = copy.deepcopy(private.get("correct_words"))
        target["correct_words"] = correct_words
        selected_tts = target.get("selected_module", {}).get("TTS")
        legacy_tts_config = target.get("TTS", {}).get(selected_tts)
        if isinstance(legacy_tts_config, dict):
            legacy_tts_config["correct_words"] = copy.deepcopy(correct_words)
        typed_tts_config = (
            target.get("model_config", {})
            .get(selected_tts, {})
            .get("typed_config")
        )
        if isinstance(typed_tts_config, dict):
            typed_tts_config["correct_words"] = copy.deepcopy(correct_words)

    return target


def runtime_config_fingerprint(private_config):
    # Memory leases refresh independently without rebuilding audio/model clients.
    provider_config = dict(private_config or {})
    provider_config.pop("managed_memory_snapshot", None)
    billing = provider_config.get("billing_context")
    if isinstance(billing, dict):
        # A newly issued capability is not a model/configuration change.
        provider_config["billing_context"] = {
            key: value for key, value in billing.items()
            if key not in {"context_id", "context_token", "token", "issued_at"}
        }
    payload = json.dumps(
        provider_config,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    ).encode("utf-8")
    return sha256(payload).hexdigest()


def apply_connection_runtime_state(conn):
    """Apply non-provider state after ``conn.config`` has been replaced."""
    config = conn.config
    semantic_center = getattr(conn, "semantic_center", None)
    if semantic_center is not None:
        semantic_center.skill_registry.replace(config.get("semantic_skills"))

    conn.max_output_size = int(config.get("device_max_output_size") or 0)
    chat_history_conf = int(config.get("chat_history_conf") or 0)
    report_manager = getattr(conn, "report_manager", None)
    if report_manager is not None:
        report_manager.update_chat_history_config(conn, chat_history_conf)
    else:
        conn.chat_history_conf = chat_history_conf

    conn.correct_word_replacements = parse_correct_words(
        config.get("correct_words")
    )
