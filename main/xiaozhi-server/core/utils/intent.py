import os
import sys
from config.logger import setup_logging
import importlib

logger = setup_logging()


def list_available_providers():
    """List all available Intent provider types under core/providers/intent/"""
    provider_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'providers', 'intent')
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
    # 创建intent实例
    if os.path.exists(os.path.join('core', 'providers', 'intent', class_name, f'{class_name}.py')):
        lib_name = f'core.providers.intent.{class_name}.{class_name}'
        if lib_name not in sys.modules:
            sys.modules[lib_name] = importlib.import_module(f'{lib_name}')
        return sys.modules[lib_name].IntentProvider(*args, **kwargs)

    raise ValueError(f"不支持的意图类型: {class_name}。可用类型: {', '.join(list_available_providers())}")