"""Connection-scoped TTS overrides controlled by the main LLM."""

import math
import threading
from dataclasses import dataclass
from typing import Any, Callable, Dict, Optional

from core.utils.tts import convert_percentage_to_range


class TTSOptionError(ValueError):
    """Raised when the active provider cannot apply a requested option."""


@dataclass(frozen=True)
class TTSOptionResult:
    applied: Dict[str, Any]
    capabilities: tuple[str, ...]


class SessionTTSOptions:
    """Apply temporary TTS parameters without mutating manager-owned config."""

    _PERCENTAGE_KEYS = {
        "speed": ("ttsRate", "speed"),
        "volume": ("ttsVolume", "loudness_rate"),
        "pitch": ("ttsPitch",),
    }

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._provider_identity: Optional[str] = None
        self._overrides: Dict[str, Any] = {}

    @staticmethod
    def provider_identity(conn) -> str:
        selected = getattr(conn, "config", {}).get("selected_module", {})
        return str(selected.get("TTS") or "")

    def apply(
        self,
        provider,
        *,
        provider_identity: str = "",
        voice: Any = None,
        speed: Any = None,
        volume: Any = None,
        pitch: Any = None,
    ) -> TTSOptionResult:
        requested = {
            key: value
            for key, value in {
                "voice": voice,
                "speed": speed,
                "volume": volume,
                "pitch": pitch,
            }.items()
            if value is not None
        }
        if not requested:
            raise TTSOptionError("至少需要提供音色、语速、音量或音调中的一项")

        normalized = self._normalize_requested(requested)
        with self._lock:
            mutations = {
                name: self._resolve_mutation(provider, name, value)
                for name, value in normalized.items()
            }
            for mutation in mutations.values():
                mutation()
            self._provider_identity = str(provider_identity or "")
            self._overrides.update(normalized)
            return TTSOptionResult(
                applied=dict(normalized),
                capabilities=self.capabilities(provider),
            )

    def reapply(self, provider, *, provider_identity: str = "") -> TTSOptionResult:
        """Reapply overrides after a same-model runtime hot reload."""
        with self._lock:
            identity = str(provider_identity or "")
            if self._provider_identity and identity != self._provider_identity:
                self._provider_identity = None
                self._overrides.clear()
            if not self._overrides:
                return TTSOptionResult({}, self.capabilities(provider))

            retained: Dict[str, Any] = {}
            for name, value in self._overrides.items():
                try:
                    mutation = self._resolve_mutation(provider, name, value)
                except TTSOptionError:
                    continue
                mutation()
                retained[name] = value
            self._overrides = retained
            self._provider_identity = identity if retained else None
            return TTSOptionResult(dict(retained), self.capabilities(provider))

    def capabilities(self, provider) -> tuple[str, ...]:
        supported = []
        for name, sample in (
            ("voice", "1"),
            ("speed", 0),
            ("volume", 0),
            ("pitch", 0),
        ):
            try:
                self._resolve_mutation(provider, name, sample, dry_run=True)
            except TTSOptionError:
                continue
            supported.append(name)
        return tuple(supported)

    def snapshot(self) -> Dict[str, Any]:
        with self._lock:
            return dict(self._overrides)

    @staticmethod
    def _normalize_requested(requested: Dict[str, Any]) -> Dict[str, Any]:
        normalized: Dict[str, Any] = {}
        for name, value in requested.items():
            if name == "voice":
                voice = str(value or "").strip()
                if not voice:
                    raise TTSOptionError("音色 ID 不能为空")
                if len(voice) > 128:
                    raise TTSOptionError("音色 ID 过长")
                normalized[name] = voice
                continue
            if isinstance(value, bool):
                raise TTSOptionError(f"{name} 必须是 -100 到 100 的数字")
            try:
                percentage = float(value)
            except (TypeError, ValueError) as exc:
                raise TTSOptionError(f"{name} 必须是 -100 到 100 的数字") from exc
            if not math.isfinite(percentage) or percentage < -100 or percentage > 100:
                raise TTSOptionError(f"{name} 必须在 -100 到 100 之间")
            normalized[name] = int(percentage) if percentage.is_integer() else percentage
        return normalized

    def _resolve_mutation(
        self,
        provider,
        name: str,
        value: Any,
        *,
        dry_run: bool = False,
    ) -> Callable[[], None]:
        if provider is None:
            raise TTSOptionError("当前语音合成服务尚未就绪")

        provider_name = provider.__class__.__module__.rsplit(".", 1)[-1]
        if name == "voice":
            return self._voice_mutation(provider, value, dry_run=dry_run)

        if provider_name == "huoshan_double_stream":
            return self._huoshan_mutation(provider, name, value, dry_run=dry_run)
        if provider_name == "minimax_httpstream":
            return self._minimax_mutation(provider, name, value, dry_run=dry_run)
        if provider_name in {"gpt_sovits_v2", "gpt_sovits_v3"} and name == "speed":
            attr_name = "speed_factor" if provider_name == "gpt_sovits_v2" else "speed"
            converted = round(convert_percentage_to_range(value, 0.5, 2.0, 1.0), 2)
            return lambda: None if dry_run else setattr(provider, attr_name, converted)

        for config_key, attr_name, min_val, max_val, base_val, transform in getattr(
            provider, "TTS_PARAM_CONFIG", ()
        ):
            if config_key not in self._PERCENTAGE_KEYS[name]:
                continue
            converted = convert_percentage_to_range(value, min_val, max_val, base_val)
            converted = transform(converted) if transform else converted
            return lambda: None if dry_run else setattr(provider, attr_name, converted)

        raise TTSOptionError(f"当前语音服务不支持调整{name}")

    @staticmethod
    def _voice_mutation(provider, value: str, *, dry_run: bool) -> Callable[[], None]:
        if hasattr(provider, "voice"):
            current = getattr(provider, "voice")
            converted: Any = value
            if isinstance(current, int) and not isinstance(current, bool):
                try:
                    converted = int(value)
                except ValueError as exc:
                    raise TTSOptionError("当前语音服务要求音色 ID 为整数") from exc

            def apply_voice() -> None:
                if dry_run:
                    return
                provider.voice = converted
                voice_setting = getattr(provider, "voice_setting", None)
                if isinstance(voice_setting, dict):
                    voice_setting["voice_id"] = converted

            return apply_voice

        if hasattr(provider, "spk_id"):
            try:
                speaker_id = int(value)
            except ValueError as exc:
                raise TTSOptionError("当前语音服务要求音色 ID 为整数") from exc
            return lambda: None if dry_run else setattr(provider, "spk_id", speaker_id)

        raise TTSOptionError("当前语音服务不支持运行时切换音色")

    @staticmethod
    def _huoshan_mutation(provider, name: str, value: float, *, dry_run: bool) -> Callable[[], None]:
        if name == "speed":
            target, key = provider.audio_params, "speech_rate"
            converted = int(convert_percentage_to_range(value, -50, 100, 0))
        elif name == "volume":
            target, key = provider.audio_params, "loudness_rate"
            converted = int(convert_percentage_to_range(value, -50, 100, 0))
        elif name == "pitch":
            key = "pitch"
            converted = int(convert_percentage_to_range(value, -12, 12, 0))
            if dry_run:
                return lambda: None
            target = provider.additions.setdefault("post_process", {})
        else:
            raise TTSOptionError(f"当前语音服务不支持调整{name}")
        return lambda: None if dry_run else target.__setitem__(key, converted)

    @staticmethod
    def _minimax_mutation(provider, name: str, value: float, *, dry_run: bool) -> Callable[[], None]:
        if name == "speed":
            key = "speed"
            converted = round(convert_percentage_to_range(value, 0.5, 2.0, 1.0), 1)
        elif name == "volume":
            key = "vol"
            converted = round(convert_percentage_to_range(value, 0.1, 10.0, 1.0), 1)
        elif name == "pitch":
            key = "pitch"
            converted = int(convert_percentage_to_range(value, -12, 12, 0))
        else:
            raise TTSOptionError(f"当前语音服务不支持调整{name}")
        return lambda: None if dry_run else provider.voice_setting.__setitem__(key, converted)
