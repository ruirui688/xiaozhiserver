import importlib
import os
import sys
from core.providers.vad.base import VADProviderBase
from config.logger import setup_logging

TAG = __name__
logger = setup_logging()


def list_available_providers():
    """List all available VAD provider types under core/providers/vad/"""
    provider_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'providers', 'vad')
    if not os.path.isdir(provider_dir):
        return []
    return sorted([
        entry[:-3] for entry in os.listdir(provider_dir)
        if entry.endswith('.py') and not entry.startswith('_')
    ])


def create_instance(class_name: str, *args, **kwargs) -> VADProviderBase:
    """工厂方法创建VAD实例"""
    if os.path.exists(os.path.join("core", "providers", "vad", f"{class_name}.py")):
        lib_name = f"core.providers.vad.{class_name}"
        if lib_name not in sys.modules:
            sys.modules[lib_name] = importlib.import_module(f"{lib_name}")
        return sys.modules[lib_name].VADProvider(*args, **kwargs)

    raise ValueError(f"不支持的VAD类型: {class_name}。可用类型: {', '.join(list_available_providers())}")
