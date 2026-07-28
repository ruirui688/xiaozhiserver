"""Runtime refresh helpers for connection-scoped voiceprint providers."""

import asyncio
from typing import Any, Dict

from config.manage_api_client import get_agent_models
from core.utils.voiceprint_provider import VoiceprintProvider


async def refresh_voiceprint_provider(conn) -> Dict[str, Any]:
    """Reload and atomically replace one online connection's voiceprint provider."""
    if getattr(conn, "closing", False):
        raise RuntimeError("连接正在关闭")
    if not getattr(conn, "read_config_from_api", False):
        raise RuntimeError("当前连接未启用智控台配置")

    lock = getattr(conn, "_voiceprint_runtime_refresh_lock", None)
    if lock is None:
        lock = asyncio.Lock()
        conn._voiceprint_runtime_refresh_lock = lock

    async with lock:
        headers = getattr(conn, "headers", {}) or {}
        device_id = headers.get("device-id") or getattr(conn, "device_id", None)
        client_id = headers.get("client-id") or device_id
        if not device_id:
            raise RuntimeError("当前连接缺少device-id")

        runtime_config = await get_agent_models(
            device_id,
            client_id,
            (getattr(conn, "config", {}) or {}).get("selected_module", {}),
        )
        if not isinstance(runtime_config, dict):
            raise RuntimeError("智控台未返回有效运行时配置")

        voiceprint_config = runtime_config.get("voiceprint")
        new_provider = None
        if voiceprint_config:
            new_provider = await asyncio.to_thread(VoiceprintProvider, voiceprint_config)
            if not new_provider.enabled:
                await new_provider.close()
                raise RuntimeError("刷新后的声纹配置不完整")

        old_provider = getattr(conn, "voiceprint_provider", None)
        if voiceprint_config:
            conn.config["voiceprint"] = voiceprint_config
        else:
            conn.config.pop("voiceprint", None)
        conn.voiceprint_provider = new_provider
        conn.current_speaker = None
        conn.current_speaker_id = None

        if not voiceprint_config:
            dialogue = getattr(conn, "dialogue", None)
            if dialogue is not None and hasattr(dialogue, "clear_voiceprint_metadata"):
                dialogue.clear_voiceprint_metadata()

        rebuild_prompt = getattr(conn, "_init_prompt_enhancement", None)
        if callable(rebuild_prompt):
            rebuild_prompt()

        if old_provider is not None and old_provider is not new_provider:
            await old_provider.close()

        return {
            "enabled": new_provider is not None,
            "speakerCount": len(new_provider.speaker_map) if new_provider else 0,
        }
