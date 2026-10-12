import importlib
import logging
import os
import sys
import time
import wave
import uuid
from abc import ABC, abstractmethod
from typing import Optional, Tuple, List
from core.providers.asr.base import ASRProviderBase
from config.logger import setup_logging

TAG = __name__
logger = setup_logging()


def list_available_providers():
    """List all available ASR provider types under core/providers/asr/"""
    provider_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'providers', 'asr')
    if not os.path.isdir(provider_dir):
        return []
    return sorted([
        entry[:-3] for entry in os.listdir(provider_dir)
        if entry.endswith('.py') and not entry.startswith('_')
    ])


def create_instance(class_name: str, *args, **kwargs) -> ASRProviderBase:
    """工厂方法创建ASR实例"""
    from core.metering.admission import validate_adapter
    validate_adapter("ASR", class_name, args[0] if args else kwargs.get("config", {}))
    if os.path.exists(os.path.join('core', 'providers', 'asr', f'{class_name}.py')):
        lib_name = f'core.providers.asr.{class_name}'
        if lib_name not in sys.modules:
            sys.modules[lib_name] = importlib.import_module(f'{lib_name}')
        return sys.modules[lib_name].ASRProvider(*args, **kwargs)

    raise ValueError(f"不支持的ASR类型: {class_name}。可用类型: {', '.join(list_available_providers())}")
