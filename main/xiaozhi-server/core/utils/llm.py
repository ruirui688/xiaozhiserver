import os
import sys

# 添加项目根目录到Python路径
current_dir = os.path.dirname(os.path.abspath(__file__))
project_root = os.path.abspath(os.path.join(current_dir, "..", ".."))
sys.path.insert(0, project_root)

from config.logger import setup_logging
from core.observability.session_telemetry import contextual_latency_span
import importlib

logger = setup_logging()


def list_available_providers():
    """List all available LLM provider types under core/providers/llm/"""
    provider_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'providers', 'llm')
    if not os.path.isdir(provider_dir):
        return []
    types = []
    for entry in sorted(os.listdir(provider_dir)):
        entry_path = os.path.join(provider_dir, entry)
        if os.path.isdir(entry_path) and not entry.startswith('_'):
            provider_file = os.path.join(entry_path, f"{entry}.py")
            if os.path.isfile(provider_file):
                types.append(entry)
    return types


def create_instance(class_name, *args, **kwargs):
    from core.metering.admission import validate_adapter
    validate_adapter("LLM", class_name, args[0] if args else kwargs.get("config", {}))
    # 创建LLM实例
    if os.path.exists(os.path.join('core', 'providers', 'llm', class_name, f'{class_name}.py')):
        lib_name = f'core.providers.llm.{class_name}.{class_name}'
        with contextual_latency_span("init.llm.provider_import", cache_hit=lib_name in sys.modules):
            if lib_name not in sys.modules:
                sys.modules[lib_name] = importlib.import_module(f'{lib_name}')
        with contextual_latency_span("init.llm.provider_construct"):
            return sys.modules[lib_name].LLMProvider(*args, **kwargs)

    raise ValueError(f"不支持的LLM类型: {class_name}。可用类型: {', '.join(list_available_providers())}")
