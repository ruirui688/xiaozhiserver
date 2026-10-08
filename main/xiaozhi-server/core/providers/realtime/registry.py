"""Realtime provider registry shared by device routing and benchmarks."""

from core.providers.realtime.doubao_realtime import (
    CAPABILITIES as DOUBAO_CAPABILITIES,
    DoubaoRealtimeSession,
    DoubaoRealtimeTextProbe,
    SUPPORTED_ADAPTERS as DOUBAO_ADAPTERS,
)
from core.providers.realtime.qwen_omni_realtime import (
    CAPABILITIES as QWEN_CAPABILITIES,
    QwenOmniRealtimeSession,
    QwenRealtimeTextProbe,
    SUPPORTED_ADAPTERS as QWEN_ADAPTERS,
)
from core.providers.realtime.doubao_duplex import (
    CAPABILITIES as DUPLEX_CAPABILITIES,
    DoubaoDuplexSession,
    SUPPORTED_ADAPTERS as DUPLEX_ADAPTERS,
)


SESSION_FACTORIES = {
    **{adapter: DoubaoDuplexSession for adapter in DUPLEX_ADAPTERS},
    **{adapter: QwenOmniRealtimeSession for adapter in QWEN_ADAPTERS},
    **{adapter: DoubaoRealtimeSession for adapter in DOUBAO_ADAPTERS},
}
PROBE_FACTORIES = {
    **{adapter: QwenRealtimeTextProbe for adapter in QWEN_ADAPTERS},
    **{adapter: DoubaoRealtimeTextProbe for adapter in DOUBAO_ADAPTERS},
}
ADAPTER_CAPABILITIES = {
    **{adapter: DUPLEX_CAPABILITIES for adapter in DUPLEX_ADAPTERS},
    **{adapter: QWEN_CAPABILITIES for adapter in QWEN_ADAPTERS},
    **{adapter: DOUBAO_CAPABILITIES for adapter in DOUBAO_ADAPTERS},
}


def normalize_realtime_adapter(config, fallback="") -> str:
    return str(
        (config or {}).get("type")
        or (config or {}).get("adapter_code")
        or fallback
        or ""
    ).strip().lower()


def supported_realtime_adapters():
    return frozenset(SESSION_FACTORIES)


def realtime_adapter_capabilities(config, fallback_adapter=""):
    adapter = normalize_realtime_adapter(config, fallback_adapter)
    return ADAPTER_CAPABILITIES.get(adapter)


def create_realtime_session(conn, config):
    adapter = normalize_realtime_adapter(config)
    factory = SESSION_FACTORIES.get(adapter)
    if factory is None:
        raise ValueError(f"不支持的 Realtime adapter: {adapter or '<empty>'}")
    return factory(conn, config)


def create_realtime_probe(config, fallback_adapter=""):
    adapter = normalize_realtime_adapter(config, fallback_adapter)
    factory = PROBE_FACTORIES.get(adapter)
    if factory is None:
        raise ValueError(f"不支持的 Realtime adapter: {adapter or '<empty>'}")
    return factory(config)
