"""Startup-time provider validation. Warns about configured-but-missing providers at server startup."""

from config.logger import setup_logging

TAG = __name__


def validate_all_providers(config):
    """Check all selected providers in config exist. Returns list of warning messages."""
    from core.utils.model_config import has_runtime_config

    warnings = []

    # Map module types to their factory modules and provider type names
    provider_map = {
        "LLM": ("LLM", "core.utils.llm"),
        "TTS": ("TTS", "core.utils.tts"),
        "ASR": ("ASR", "core.utils.asr"),
        "VAD": ("VAD", "core.utils.vad"),
        "Memory": ("Memory", "core.utils.memory"),
        "Intent": ("Intent", "core.utils.intent"),
    }

    selected = config.get("selected_module", {})

    import importlib

    for module_type, module_id in selected.items():
        if module_type not in provider_map:
            continue

        provider_name, factory_module_path = provider_map[module_type]

        # Get the type field from the selected module's config
        module_config = config.get(provider_name, {}).get(module_id, {})
        runtime_type = module_config.get("type", module_id)

        if not runtime_type:
            continue

        # Import factory and check availability
        try:
            factory = importlib.import_module(factory_module_path)
            available = factory.list_available_providers()

            if runtime_type not in available:
                warnings.append(
                    f"[{provider_name}] Provider '{runtime_type}' not found. "
                    f"Available: {', '.join(available) if available else '(none)'}"
                )
        except Exception as e:
            warnings.append(f"[{provider_name}] Could not validate providers: {e}")

    return warnings
