"""Hot-reload manager-owned runtime configuration for an online device."""

import asyncio
import copy
import queue
import threading
import time

from config.config_loader import get_private_config_from_api
from config.manage_api_client import report_device_scene_switch_result
from config.logger import build_module_string, create_connection_logger
from core.providers.tts.default import DefaultTTS
from core.session.runtime_config import (
    apply_connection_runtime_state,
    build_managed_runtime_config,
    runtime_config_fingerprint,
)
from core.session.scene_switch import SceneSwitchIsolation
from core.utils.modules_initialize import initialize_modules
from core.utils.prompt_manager import PromptManager
from core.conversation import ConversationRouter
from core.conversation.mode import is_realtime_conversation
from core.speech import DeviceTTSProvider


class RuntimeConfigReloader:
    """Replace connection providers at an idle turn boundary without reconnecting."""

    MIN_CHECK_INTERVAL_SECONDS = 2.0
    WORKER_JOIN_TIMEOUT_SECONDS = 3.0

    def __init__(self, conn):
        self.conn = conn
        self._lock = asyncio.Lock()
        self._pending = False
        self._pending_reason = ""
        self._pending_scene_switch = None
        self._fingerprint = None
        self._last_checked_at = 0.0

    def mark_applied(self, private_config):
        self._fingerprint = runtime_config_fingerprint(private_config)
        self._pending = False
        self._pending_reason = ""
        self._last_checked_at = time.monotonic()

    def request_refresh(self, reason="manager_update", scene_switch=None):
        self._pending = True
        self._pending_reason = str(reason or "manager_update")[:64]
        normalized = self._normalize_scene_switch(scene_switch)
        if normalized is not None:
            current = self._pending_scene_switch
            if current is None or normalized["configVersion"] >= current["configVersion"]:
                self._pending_scene_switch = normalized

    async def refresh_if_due(self, force=False, reason="turn_boundary"):
        conn = self.conn
        if (
            getattr(conn, "closing", False)
            or getattr(conn, "closed", False)
            or not getattr(conn, "read_config_from_api", False)
            or (getattr(conn, "need_bind", False) and not force)
        ):
            return False
        if getattr(conn, "client_is_speaking", False):
            self.request_refresh(reason)
            return False

        now = time.monotonic()
        if (
            not force
            and not self._pending
            and now - self._last_checked_at < self.MIN_CHECK_INTERVAL_SECONDS
        ):
            return False

        async with self._lock:
            now = time.monotonic()
            if (
                not force
                and not self._pending
                and now - self._last_checked_at < self.MIN_CHECK_INTERVAL_SECONDS
            ):
                return False
            self._last_checked_at = now
            scene_switch = self._pending_scene_switch

            headers = getattr(conn, "headers", {}) or {}
            device_id = headers.get("device-id") or getattr(conn, "device_id", None)
            client_id = headers.get("client-id") or device_id
            if not device_id:
                return False

            try:
                private_config = await get_private_config_from_api(
                    conn.config, device_id, client_id
                )
                if not isinstance(private_config, dict):
                    raise RuntimeError("智控台未返回有效运行时配置")
                private_config["delete_audio"] = bool(
                    conn.common_config.get("delete_audio", True)
                )
                fingerprint = runtime_config_fingerprint(private_config)
                if scene_switch is not None and str(
                    private_config.get("agent_id") or ""
                ) != scene_switch["toAgentId"]:
                    raise RuntimeError("场景切换目标已被更新请求替代")
                if fingerprint == self._fingerprint:
                    self._clear_completed_request(scene_switch)
                    if scene_switch is not None:
                        await self._report_scene_status(scene_switch, "APPLIED")
                    return False

                candidate_config = build_managed_runtime_config(
                    conn.common_config, private_config
                )
                refresh_reason = self._pending_reason or reason
                old_agent_id = str(conn.config.get("agent_id") or "")
                new_agent_id = str(candidate_config.get("agent_id") or "")
                scene_changed = bool(old_agent_id and new_agent_id != old_agent_id)
                await self._replace_pipeline(
                    candidate_config, isolate_scene=scene_changed
                )
                if getattr(conn, "need_bind", False):
                    conn.need_bind = False
                    conn.bind_code = None
                    conn.bind_completed_event.set()
                    conn.binding_ready.set()
                self._fingerprint = fingerprint
                self._clear_completed_request(scene_switch)
                if scene_switch is not None:
                    await self._report_scene_status(scene_switch, "APPLIED")
                conn.logger.bind(tag=__name__).info(
                    "智能体运行配置已热更新: deviceId={}, agentId={}, reason={}, sceneChanged={}, modules={}",
                    getattr(conn, "device_id", None),
                    candidate_config.get("agent_id"),
                    refresh_reason,
                    scene_changed,
                    build_module_string(candidate_config.get("selected_module", {})),
                )
                return True
            except Exception as error:
                if scene_switch is not None:
                    self._clear_completed_request(scene_switch)
                    await self._report_scene_status(
                        scene_switch,
                        "FAILED",
                        failure_code=type(error).__name__,
                    )
                raise

    async def _replace_pipeline(self, candidate_config, isolate_scene=False):
        conn = self.conn
        old_config = copy.deepcopy(conn.config)
        old_route = getattr(getattr(conn, "speech_runtime", None), "route", None)
        scene_snapshot = None
        try:
            candidate_route = conn.speech_runtime.resolve_candidate(
                candidate_config,
                realtime=is_realtime_conversation(candidate_config),
            )
        except Exception as error:
            await conn.speech_runtime.publish_route_error(error)
            raise
        new_modules = await self._build_modules(candidate_config, candidate_route)
        if getattr(conn, "closing", False) or getattr(conn, "closed", False):
            await self._close_unactivated_modules(new_modules)
            raise RuntimeError("连接正在关闭，取消运行配置热更新")
        try:
            await self._deactivate_pipeline()
        except Exception:
            await self._close_unactivated_modules(new_modules)
            raise
        try:
            if isolate_scene:
                scene_snapshot = SceneSwitchIsolation.begin(conn)
            await self._activate_pipeline(
                candidate_config, new_modules, candidate_route
            )
        except Exception as error:
            conn.logger.bind(tag=__name__).error(
                "运行配置热更新激活失败，尝试恢复旧配置: errorType={}",
                type(error).__name__,
            )
            await self._deactivate_pipeline()
            if scene_snapshot is not None:
                SceneSwitchIsolation.rollback(conn, scene_snapshot)
            rollback_route = old_route or conn.speech_runtime.resolve_candidate(
                old_config,
                realtime=is_realtime_conversation(old_config),
            )
            rollback_modules = await self._build_modules(
                old_config, rollback_route
            )
            await self._activate_pipeline(
                old_config, rollback_modules, rollback_route
            )
            raise

    def _clear_completed_request(self, scene_switch):
        if (
            scene_switch is None
            and self._pending_scene_switch is None
        ) or self._pending_scene_switch is scene_switch:
            self._pending = False
            self._pending_reason = ""
            self._pending_scene_switch = None

    async def _report_scene_status(
        self, scene_switch, status, failure_code=None
    ):
        payload = {
            "deviceId": scene_switch["deviceId"],
            "switchRequestId": scene_switch["switchRequestId"],
            "agentId": scene_switch["toAgentId"],
            "configVersion": scene_switch["configVersion"],
            "status": status,
        }
        if failure_code:
            payload["failureCode"] = str(failure_code)[:64]
        try:
            await report_device_scene_switch_result(payload)
        except Exception as error:
            self.conn.logger.bind(tag=__name__).warning(
                "场景切换状态回传失败: requestId={}, status={}, errorType={}",
                scene_switch["switchRequestId"],
                status,
                type(error).__name__,
            )

    @staticmethod
    def _normalize_scene_switch(value):
        if not isinstance(value, dict):
            return None
        normalized = {
            "deviceId": str(value.get("deviceId") or "").strip(),
            "fromAgentId": str(value.get("fromAgentId") or "").strip(),
            "toAgentId": str(value.get("toAgentId") or "").strip(),
            "switchRequestId": str(value.get("switchRequestId") or "").strip(),
        }
        try:
            normalized["configVersion"] = int(value.get("configVersion"))
        except (TypeError, ValueError):
            return None
        if not all(
            normalized[key]
            for key in ("deviceId", "toAgentId", "switchRequestId")
        ) or normalized["configVersion"] < 0:
            return None
        return normalized

    async def _build_modules(self, config, speech_route=None):
        init_asr = not bool(
            speech_route is not None and speech_route.uses_device_asr
        )
        init_tts = not bool(
            speech_route is not None and speech_route.uses_device_tts
        )
        modules = await asyncio.to_thread(
            initialize_modules,
            self.conn.logger,
            config,
            init_asr,
            init_asr,
            True,
            init_tts,
            True,
            True,
        )
        if not is_realtime_conversation(config):
            selected = config.get("selected_module", {})
            required_modules = [("LLM", "llm")]
            if init_asr:
                required_modules.extend((("VAD", "vad"), ("ASR", "asr")))
            if init_tts:
                required_modules.append(("TTS", "tts"))
            for module_type, name in required_modules:
                if selected.get(module_type) and modules.get(name) is None:
                    raise RuntimeError(f"{module_type}热更新初始化失败")
        return modules

    async def _deactivate_pipeline(self):
        conn = self.conn
        old_tts = getattr(conn, "tts", None)
        old_asr = getattr(conn, "asr", None)
        old_voiceprint = getattr(conn, "voiceprint_provider", None)
        old_tools = getattr(conn, "func_handler", None)
        old_vad = getattr(conn, "vad", None)
        threads = [
            getattr(conn, "asr_priority_thread", None),
            getattr(old_tts, "tts_priority_thread", None),
            getattr(old_tts, "audio_play_priority_thread", None),
        ]

        stop_event = getattr(conn, "stop_event", None)
        if stop_event is not None:
            stop_event.set()

        try:
            if old_tools is not None:
                await old_tools.cleanup()
            conn.func_handler = None
            if hasattr(conn, "mcp_endpoint_client"):
                conn.mcp_endpoint_client = None

            conversation_router = getattr(conn, "conversation_router", None)
            if conversation_router is not None:
                await conversation_router.close()

            if old_tts is not None:
                await old_tts.close()
            if old_asr is not None:
                await old_asr.close()
            if old_voiceprint is not None:
                await old_voiceprint.close()
            conn.voiceprint_provider = None
            if old_vad is not None and hasattr(old_vad, "release_conn_resources"):
                await asyncio.to_thread(old_vad.release_conn_resources, conn)
        except Exception:
            await conn.close()
            raise

        alive_threads = await asyncio.to_thread(self._join_threads, threads)
        if alive_threads:
            conn.logger.bind(tag=__name__).error(
                "运行配置热更新停止旧工作线程超时，关闭连接避免重复消费者: threads={}",
                alive_threads,
            )
            await conn.close()
            raise RuntimeError("旧音频工作线程未能在超时内退出")
        if getattr(conn, "closing", False) or getattr(conn, "closed", False):
            raise RuntimeError("连接关闭期间取消运行配置热更新")
        conn.stop_event = threading.Event()
        self._drain_queue(getattr(conn, "asr_audio_queue", None))
        conn.reset_audio_states()
        conn.tts_ready.clear()
        conn.conversation_ready.clear()
        conn.audio_input_ready.clear()
        conn._modules_ready.clear()

    async def _activate_pipeline(self, config, modules, speech_route=None):
        conn = self.conn
        conn.config = copy.deepcopy(config)
        conn.prompt_manager = PromptManager(conn.config, conn.logger)
        conn.load_function_plugin = False
        conn.configured_intent_type = "nointent"
        conn.intent_type = "nointent"
        realtime_conversation = is_realtime_conversation(conn.config)
        device_asr = bool(
            speech_route is not None and speech_route.uses_device_asr
        )
        device_tts = bool(
            speech_route is not None and speech_route.uses_device_tts
        )

        conn.vad = None if realtime_conversation or device_asr else modules.get("vad")
        conn.asr = None if realtime_conversation or device_asr else modules.get("asr")
        conn.llm = None if realtime_conversation else modules.get("llm")
        conn.tts = None if realtime_conversation else (
            DeviceTTSProvider(conn.config, delete_audio_file=True)
            if device_tts
            else modules.get("tts") or DefaultTTS(conn.config, delete_audio_file=True)
        )
        conn.memory = modules.get("memory")
        conn.intent = modules.get("intent")
        conn.conversation_router = ConversationRouter(conn)

        if not realtime_conversation:
            conn.tts_session_options.reapply(
                conn.tts,
                provider_identity=conn.tts_session_options.provider_identity(conn),
            )
            await conn.tts.open_audio_channels(conn)
            conn.tts_ready.set()
            if device_asr:
                conn.audio_input_ready.set()
            elif conn.asr is not None:
                await conn.asr.open_audio_channels(conn)
                if conn.vad is not None:
                    conn.audio_input_ready.set()

        await asyncio.to_thread(conn._initialize_memory)
        await asyncio.to_thread(conn._initialize_intent)
        if not realtime_conversation and not device_asr:
            await asyncio.to_thread(conn._initialize_voiceprint)

        apply_connection_runtime_state(conn)
        if not conn.config.get("voiceprint"):
            conn.dialogue.clear_voiceprint_metadata()
        conn.prompt_manager.config = conn.config
        conn._init_prompt_enhancement()
        conn.dialogue.clear_temporary_messages()
        conn._inject_tool_call_fewshot()
        conn._init_voiceprint_enrollment_from_config()
        max_turns = int(conn.config.get("dialogue", {}).get("max_history_turns", 0))
        conn.dialogue.max_history_turns = max_turns
        conn.selected_module_str = build_module_string(
            conn.config.get("selected_module", {})
        )
        conn.logger = create_connection_logger(conn.selected_module_str)
        if speech_route is not None:
            await conn.speech_runtime.apply_route(speech_route)

        if realtime_conversation:
            await conn.conversation_router.initialize()
            conn.audio_input_ready.set()
            conn.tts_ready.set()
            conn.conversation_ready.set()
        elif conn.llm is not None and conn.tts_ready.is_set():
            conn.conversation_ready.set()
        if (
            conn.tts_ready.is_set()
            and conn.conversation_ready.is_set()
            and conn.audio_input_ready.is_set()
        ):
            conn._modules_ready.set()

    def _join_threads(self, threads):
        deadline = time.monotonic() + self.WORKER_JOIN_TIMEOUT_SECONDS
        alive = []
        for thread in threads:
            if thread is None or thread is threading.current_thread():
                continue
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                if thread.is_alive():
                    alive.append(thread.name)
                continue
            thread.join(timeout=remaining)
            if thread.is_alive():
                alive.append(thread.name)
        return alive

    @staticmethod
    async def _close_unactivated_modules(modules):
        closes = []
        for name in ("tts", "asr"):
            close = getattr(modules.get(name), "close", None)
            if close is not None:
                closes.append(close())
        if closes:
            await asyncio.gather(*closes, return_exceptions=True)

    @staticmethod
    def _drain_queue(target):
        if target is None:
            return
        while True:
            try:
                target.get_nowait()
            except queue.Empty:
                return
