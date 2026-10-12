"""Async DashScope Qwen Omni Realtime transport and device session."""

import asyncio
import base64
import json
import inspect
import re
import time
import uuid
from typing import Any, Awaitable, Callable, Dict, Optional
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import opuslib_next
import websockets

from core.handle.abortHandle import handleAbortMessage
from core.handle.reportHandle import enqueue_asr_report, enqueue_tts_report
from core.handle.sendAudioHandle import sendAudio, send_stt_display, send_tts_message
from core.providers.realtime.base import (
    RealtimeCapabilities,
    event,
    realtime_tools,
    tool_output_text,
)
from core.utils.dialogue import Message
from core.utils.opus_encoder_utils import OpusEncoderUtils
from core.utils.prompt_data import render_realtime_user_request
from core.session.realtime_tools import (
    RealtimeToolPolicy, await_realtime_controls, cancel_realtime_updates,
    configure_realtime_session,
)


LEGACY_REALTIME_HOST = "dashscope.aliyuncs.com"
WORKSPACE_REALTIME_HOST = re.compile(
    r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\."
    r"(?:cn-beijing|ap-southeast-1)\.maas\.aliyuncs\.com$"
)
SUPPORTED_ADAPTERS = {"qwen_omni_realtime", "qwen-omni-realtime"}
CAPABILITIES = RealtimeCapabilities(
    native_function_calling=True,
    natural_barge_in=True,
    context_reset=True,
    session_voice=True,
)
VOICE_CATALOG = (
    {"id": "Cherry", "name": "Cherry"},
    {"id": "Serena", "name": "Serena"},
    {"id": "Ethan", "name": "Ethan"},
    {"id": "Chelsie", "name": "Chelsie"},
)
MANUAL_TURN_PCM = b"\x00\x00" * 3200
MAX_REALTIME_IMAGE_BASE64_BYTES = 256 * 1024


def validate_realtime_url(value: str) -> str:
    url = str(value or "").strip()
    if not url:
        raise ValueError("Realtime base_url 不能为空，请填写百炼业务空间官方地址")
    parsed = urlsplit(url)
    hostname = str(parsed.hostname or "").lower()
    if parsed.scheme != "wss" or not (
        hostname == LEGACY_REALTIME_HOST
        or WORKSPACE_REALTIME_HOST.fullmatch(hostname)
    ):
        raise ValueError(
            "Realtime base_url 仅允许阿里云百炼官方 DashScope 或业务空间域名"
        )
    if parsed.username or parsed.password or parsed.port not in (None, 443):
        raise ValueError("Realtime base_url 不允许凭据或非标准端口")
    if parsed.path.rstrip("/") != "/api-ws/v1/realtime":
        raise ValueError("Realtime base_url 路径必须为 /api-ws/v1/realtime")
    if parsed.query or parsed.fragment:
        raise ValueError("Realtime base_url 不允许预置查询参数或片段")
    return urlunsplit(("wss", hostname, parsed.path, "", ""))


def build_realtime_url(base_url: str, model: str) -> str:
    parsed = urlsplit(validate_realtime_url(base_url))
    query = dict(parse_qsl(parsed.query, keep_blank_values=True))
    query["model"] = str(model or "").strip()
    if not query["model"]:
        raise ValueError("Realtime model_name 不能为空")
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path, urlencode(query), ""))


def build_session_update(config: Dict[str, Any], prompt: str, tools=None) -> Dict[str, Any]:
    input_rate = int(config.get("input_sample_rate", 16000))
    output_rate = int(config.get("output_sample_rate", 24000))
    if (input_rate, output_rate) != (16000, 24000):
        raise ValueError("Qwen Realtime 音频契约固定为输入16kHz、输出24kHz PCM16")
    turn_detection_type = str(config.get("turn_detection_type") or "server_vad")
    turn_detection = None
    if turn_detection_type != "manual":
        turn_detection = {
            "type": turn_detection_type,
            "threshold": float(config.get("turn_detection_threshold", 0.2)),
            "prefix_padding_ms": int(config.get("prefix_padding_ms", 300)),
            "silence_duration_ms": int(config.get("silence_duration_ms", 800)),
        }
    transcription = None
    if _as_bool(config.get("enable_input_audio_transcription", True)):
        transcription = {}
        transcription_model = str(
            config.get("input_audio_transcription_model") or ""
        ).strip()
        if transcription_model:
            transcription["model"] = transcription_model
    session = {
        "modalities": ["text", "audio"],
        "voice": str(config.get("voice") or "Ethan"),
        "input_audio_format": "pcm",
        "output_audio_format": "pcm",
        "input_audio_transcription": transcription,
        "turn_detection": turn_detection,
        "instructions": str(prompt or ""),
        "tools": realtime_tools(tools or []),
    }
    return event("session.update", session=session)


def build_audio_append(pcm: bytes) -> Dict[str, Any]:
    return event("input_audio_buffer.append", audio=base64.b64encode(pcm).decode("ascii"))


def build_image_append(image: bytes) -> Dict[str, Any]:
    if not image.startswith(b"\xff\xd8\xff"):
        raise ValueError("Qwen Realtime 图片仅支持 JPG/JPEG 格式")
    encoded = base64.b64encode(image)
    if len(encoded) > MAX_REALTIME_IMAGE_BASE64_BYTES:
        raise ValueError("Qwen Realtime 图片 Base64 编码后不能超过 256KB")
    return event("input_image_buffer.append", image=encoded.decode("ascii"))


def build_instruction_prompt(base_prompt: str, request: str) -> str:
    return (
        f"{str(base_prompt or '').strip()}\n\n"
        f"{render_realtime_user_request(request)}\n"
        "请在上述边界内完成 current_user_request 中的本轮请求。"
    ).strip()


def build_manual_turn_events(image: Optional[bytes] = None):
    payloads = [
        event("input_audio_buffer.clear"),
        build_audio_append(MANUAL_TURN_PCM),
    ]
    if image is not None:
        payloads.append(build_image_append(image))
    payloads.extend(
        [
            event("input_audio_buffer.commit"),
            event("response.create"),
        ]
    )
    return payloads


def _as_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


class QwenOmniRealtimeTransport:
    """Credential-safe asyncio transport for the DashScope realtime protocol."""

    def __init__(self, config: Dict[str, Any], logger=None, websocket_factory=None):
        self.config = dict(config or {})
        self.logger = logger
        self.websocket_factory = websocket_factory or websockets.connect
        self.websocket = None
        self.reader_task = None
        self.event_handler: Optional[Callable[[Dict[str, Any]], Awaitable[None]]] = None
        self._waiters: Dict[str, list] = {}
        self._configure_lock = asyncio.Lock()
        self._closed = False

    async def connect(self, event_handler=None) -> None:
        api_key = str(self.config.get("api_key") or "").strip()
        if not api_key:
            raise ValueError("Realtime api_key 不能为空")
        url = build_realtime_url(
            self.config.get("base_url") or self.config.get("endpoint_url"),
            self.config.get("model_name") or self.config.get("model"),
        )
        self.event_handler = event_handler
        timeout = float(self.config.get("connect_timeout_seconds", 10))
        try:
            connect_parameters = inspect.signature(self.websocket_factory).parameters
        except (TypeError, ValueError):
            connect_parameters = {}
        header_argument = (
            "additional_headers"
            if "additional_headers" in connect_parameters
            else "extra_headers"
        )
        connect_kwargs = {
            header_argument: {
                "Authorization": f"Bearer {api_key}",
                "X-DashScope-OmniRealtime": "true",
            },
            "max_size": int(self.config.get("max_event_bytes", 10 * 1024 * 1024)),
            "open_timeout": timeout,
        }
        self.websocket = await asyncio.wait_for(
            self.websocket_factory(url, **connect_kwargs),
            timeout=timeout + 1,
        )
        created_waiter = self._new_waiter("session.created")
        self.reader_task = asyncio.create_task(self._reader(), name="qwen-realtime-reader")
        await self._await_waiter(
            "session.created", created_waiter, self._response_timeout()
        )

    async def configure(self, prompt: str, tools=None, config_override=None) -> None:
        async with self._configure_lock:
            updated_waiter = self._new_waiter("session.updated")
            session_config = dict(self.config)
            session_config.update(config_override or {})
            await self.send(build_session_update(session_config, prompt, tools))
            await self._await_waiter(
                "session.updated", updated_waiter, self._response_timeout()
            )

    async def send(self, payload: Dict[str, Any]) -> None:
        if self.websocket is None:
            raise RuntimeError("Realtime WebSocket 尚未连接")
        outgoing = dict(payload)
        outgoing.setdefault("event_id", f"event_{uuid.uuid4().hex}")
        await self.websocket.send(json.dumps(outgoing, ensure_ascii=False))

    async def wait_for(self, event_type: str, timeout: Optional[float] = None) -> Dict[str, Any]:
        future = self._new_waiter(event_type)
        return await self._await_waiter(
            event_type, future, timeout or self._response_timeout()
        )

    def _new_waiter(self, event_type: str):
        future = asyncio.get_running_loop().create_future()
        self._waiters.setdefault(event_type, []).append(future)
        return future

    def _response_timeout(self) -> float:
        return float(self.config.get("response_timeout_seconds", 120))

    async def _await_waiter(self, event_type: str, future, timeout: float):
        try:
            return await asyncio.wait_for(future, timeout=timeout)
        finally:
            waiters = self._waiters.get(event_type, [])
            if future in waiters:
                waiters.remove(future)

    async def _reader(self) -> None:
        close_error = None
        try:
            async for raw in self.websocket:
                if not isinstance(raw, str):
                    continue
                payload = json.loads(raw)
                event_type = payload.get("type")
                waiters = list(self._waiters.pop(event_type, []))
                for waiter in waiters:
                    if not waiter.done():
                        waiter.set_result(payload)
                if self.event_handler is not None:
                    await self.event_handler(payload)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            close_error = exc
        else:
            close_error = ConnectionError("Realtime WebSocket 已由上游关闭")
        if self._closed:
            return
        pending_waiters = list(self._waiters.values())
        self._waiters.clear()
        for waiters in pending_waiters:
            for waiter in waiters:
                if not waiter.done():
                    waiter.set_exception(close_error)
        if self.event_handler is not None:
            await self.event_handler(
                {
                    "type": "transport.closed",
                    "error": {"code": type(close_error).__name__},
                }
            )
        if self.logger is not None:
            self.logger.warning(
                "Qwen Realtime 上游连接异常: errorType={}",
                type(close_error).__name__,
            )

    async def close(self) -> None:
        self._closed = True
        close_error = ConnectionError("Realtime WebSocket 已关闭")
        for waiters in self._waiters.values():
            for waiter in waiters:
                if not waiter.done():
                    waiter.set_exception(close_error)
        self._waiters.clear()
        if self.websocket is not None:
            await self.websocket.close()
        if self.reader_task is not None and not self.reader_task.done():
            self.reader_task.cancel()
            await asyncio.gather(self.reader_task, return_exceptions=True)


class QwenOmniRealtimeSession:
    """Bind one realtime transport to one authenticated device connection."""

    def __init__(
        self, conn, config: Dict[str, Any], transport=None, transport_factory=None
    ):
        self.conn = conn
        self.config = dict(config or {})
        bound_logger = conn.logger.bind(tag=__name__)
        self._transport_factory = transport_factory
        if transport is None:
            self._transport_factory = self._transport_factory or (
                lambda: QwenOmniRealtimeTransport(self.config, bound_logger)
            )
            transport = self._transport_factory()
        self.transport = transport
        self.decoder = opuslib_next.Decoder(16000, 1)
        self.encoder = OpusEncoderUtils(24000, 1, 60)
        self.response_text = ""
        self.response_audio = []
        self.input_audio = []
        self.function_calls: Dict[str, Dict[str, str]] = {}
        self.completed_function_calls = set()
        self._tool_followup_pending = False
        self.response_done = asyncio.Event()
        self.last_response_text = ""
        self._final_subtitle_text = ""
        self._reply_started = False
        self._response_active = False
        self._turn_lock = asyncio.Lock()
        self._instruction_turn_active = False
        self._instruction_request = None
        self._restore_task = None
        self._cancel_generation = 0
        self._barge_in_active = False
        self._context_reset_pending = False
        self._context_resetting = False
        self._pending_voice = None
        self._session_maintenance_task = None
        self._tool_policy = RealtimeToolPolicy(conn)
        self._tool_task = None
        self._tool_batch_pending = False
        self._tool_policy_restore = False
        self._closed = False

    @property
    def capabilities(self):
        return CAPABILITIES

    def voice_catalog(self):
        return [dict(item) for item in VOICE_CATALOG]

    def current_voice(self):
        return str(self.config.get("voice") or "Ethan")

    def request_voice_change(self, voice: str) -> bool:
        voice_id = str(voice or "").strip()
        allowed = {item["id"] for item in VOICE_CATALOG}
        if not CAPABILITIES.session_voice or voice_id not in allowed:
            return False
        self.config["voice"] = voice_id
        self._pending_voice = voice_id
        if not self._response_active and not self._instruction_turn_active:
            self._schedule_session_maintenance_threadsafe()
        return True

    def request_context_reset(self) -> bool:
        if not CAPABILITIES.context_reset or self._transport_factory is None:
            return False
        self._context_reset_pending = True
        if not self._response_active and not self._instruction_turn_active:
            self._schedule_session_maintenance_threadsafe()
        return True

    async def reset_context_now(self) -> bool:
        if (
            not CAPABILITIES.context_reset
            or self._transport_factory is None
            or self._response_active
            or self._instruction_turn_active
        ):
            return False
        self._context_reset_pending = True
        await self._reconnect_for_context_reset()
        return bool(
            not self._context_resetting
            and not self._closed
            and self.conn.conversation_ready.is_set()
        )

    def _schedule_session_maintenance_threadsafe(self) -> None:
        loop = getattr(self.conn, "loop", None)
        if loop is not None and loop.is_running():
            loop.call_soon_threadsafe(self._schedule_session_maintenance)

    def _schedule_session_maintenance(self) -> None:
        task = getattr(self, "_session_maintenance_task", None)
        if task is not None and not task.done():
            return
        if not getattr(self, "_context_reset_pending", False) and getattr(
            self, "_pending_voice", None
        ) is None and not getattr(self, "_tool_policy_restore", False):
            return
        self._session_maintenance_task = asyncio.create_task(
            self._apply_pending_session_controls(),
            name="qwen-realtime-session-controls",
        )

    async def _apply_pending_session_controls(self) -> None:
        try:
            if self._context_reset_pending:
                await self._reconnect_for_context_reset()
            elif self._pending_voice is not None:
                voice = self._pending_voice
                tools = self._tool_policy.tools()
                await configure_realtime_session(
                    self, self._session_prompt(), tools, {"voice": voice}
                )
                transport_config = getattr(self.transport, "config", None)
                if isinstance(transport_config, dict):
                    transport_config["voice"] = voice
                self._pending_voice = None
            while getattr(self, "_tool_policy_restore", False):
                self._tool_policy_restore = False
                await self.refresh_tools()
        except Exception as error:
            self.conn.logger.bind(tag=__name__).warning(
                "应用Qwen Realtime会话控制失败: errorType={}",
                type(error).__name__,
            )
            self.conn.audio_input_ready.clear()
            self.conn.conversation_ready.clear()

    async def _reconnect_for_context_reset(self) -> None:
        self._context_resetting = True
        self._context_reset_pending = False
        self.conn.audio_input_ready.clear()
        self.conn.conversation_ready.clear()
        old_transport = self.transport
        await cancel_realtime_updates(self)
        await old_transport.close()
        self.transport = self._transport_factory()
        self.completed_function_calls.clear()
        self.function_calls.clear()
        self.input_audio = []
        self.encoder.reset_state()
        await self.transport.connect(self.handle_event)
        await self.refresh_tools()
        self._pending_voice = None
        self._context_resetting = False
        if not self._closed:
            self.conn.audio_input_ready.set()
            self.conn.conversation_ready.set()

    async def initialize(self) -> None:
        await self.transport.connect(self.handle_event)
        await self.refresh_tools()

    def _session_prompt(self) -> str:
        from core.utils.current_time import render_runtime_time
        prompt = render_runtime_time(self.conn.prompt or self.conn.config.get("prompt", ""))
        from core.session.realtime_memory import append_realtime_memory
        prompt = append_realtime_memory(self.conn, prompt)
        policy = getattr(self, "_tool_policy", None)
        return policy.prompt(prompt, policy.tools()) if policy else prompt

    async def refresh_tools(self) -> None:
        policy = getattr(self, "_tool_policy", None)
        tools = policy.tools() if policy else (self.conn.func_handler.get_functions() if self.conn.func_handler else [])
        statistics_getter = getattr(self.conn.func_handler, "get_tool_statistics", None)
        statistics = statistics_getter() if callable(statistics_getter) else {}
        self.conn.logger.bind(tag=__name__).info(
            "同步Qwen Realtime工具描述: total={}, sources={}",
            len(tools),
            statistics,
        )
        prompt = self._session_prompt()
        override = None
        if getattr(self, "_instruction_turn_active", False):
            prompt = build_instruction_prompt(prompt, self._instruction_request or "")
            override = {"turn_detection_type": "manual"}
        await configure_realtime_session(self, prompt, tools, override)

    async def handle_audio(self, opus_packet: bytes) -> bool:
        if getattr(self, "_context_resetting", False):
            return True
        if getattr(self, "_instruction_turn_active", False):
            self.conn.logger.bind(tag=__name__).debug(
                "忽略Realtime手动文字/图片回合期间的并发设备音频"
            )
            return True
        await await_realtime_controls(self)
        if getattr(self, "_closed", False):
            return True
        try:
            pcm = self.decoder.decode(opus_packet, 960)
        except opuslib_next.OpusError:
            self.conn.logger.bind(tag=__name__).warning(
                "丢弃无效Realtime Opus音频帧: sizeBytes={}", len(opus_packet or b"")
            )
            return True
        self.input_audio.append(opus_packet)
        await self.transport.send(build_audio_append(pcm))
        return True

    async def handle_listen(self, message: Dict[str, Any]) -> bool:
        if getattr(self, "_context_resetting", False):
            return True
        if self._instruction_turn_active:
            return True
        await await_realtime_controls(self)
        if self._closed:
            return True
        state = message.get("state")
        if state == "start":
            self.input_audio = []
            await self.transport.send(event("input_audio_buffer.clear"))
        elif state == "stop" and str(self.config.get("turn_detection_type") or "server_vad") == "manual":
            await self.transport.send(event("input_audio_buffer.commit"))
            await self.transport.send(event("response.create", response={}))
        return True

    async def handle_text(self, text: str) -> bool:
        clean_text = str(text or "").strip()
        if not clean_text:
            return True
        render_realtime_user_request(clean_text)
        if getattr(self, "_context_resetting", False):
            raise RuntimeError("Realtime 正在重建上下文，请稍后再试")
        async with self._turn_lock:
            if self._response_active or self._instruction_turn_active:
                raise RuntimeError("Realtime 正在处理上一轮响应")
            await await_realtime_controls(self)
            self._response_active = True
            self._instruction_turn_active = True
        self.response_done.clear()
        self.conn.dialogue.put(Message(role="user", content=clean_text))
        enqueue_asr_report(self.conn, clean_text, [])
        await send_stt_display(self.conn, clean_text)
        try:
            await self._configure_instruction_turn(clean_text)
            for payload in build_manual_turn_events():
                await self.transport.send(payload)
        except Exception:
            self._response_active = False
            self._instruction_turn_active = False
            raise
        self._restore_task = asyncio.create_task(
            self._restore_after_response(), name="qwen-realtime-restore-session"
        )
        return True

    async def handle_abort(self) -> bool:
        self._cancel_generation += 1
        await self._cancel_tool_response()
        self._reset_tool_policy()
        upstream_error = None
        for payload in (
            event("response.cancel"),
            event("input_audio_buffer.clear"),
        ):
            try:
                await self.transport.send(payload)
            except Exception as error:
                upstream_error = upstream_error or error
        self.response_text = ""
        self.response_audio = []
        self.input_audio = []
        self._final_subtitle_text = ""
        self._reply_started = False
        self._response_active = False
        self._barge_in_active = False
        encoder = getattr(self, "encoder", None)
        if encoder is not None:
            encoder.reset_state()
        self.response_done.set()
        if upstream_error is not None:
            raise upstream_error
        return True

    async def handle_image(self, question: str, image: bytes, timeout=None) -> str:
        render_realtime_user_request(question or "请描述这张图片")
        async with self._turn_lock:
            if self._response_active or self._instruction_turn_active:
                raise RuntimeError("Realtime 正在响应，不能并发提交图片")
            await await_realtime_controls(self)
            self._response_active = True
            self._instruction_turn_active = True
        self.response_done.clear()
        self.last_response_text = ""
        try:
            await self._configure_instruction_turn(question or "请描述这张图片")
            for payload in build_manual_turn_events(image):
                await self.transport.send(payload)
            await asyncio.wait_for(
                self.response_done.wait(),
                timeout=timeout
                or float(self.config.get("response_timeout_seconds", 120)),
            )
            return self.last_response_text
        except Exception:
            self._response_active = False
            raise
        finally:
            await self._restore_normal_session()

    async def _configure_instruction_turn(self, request: str) -> None:
        self._instruction_request = request
        await self.refresh_tools()

    async def _restore_after_response(self) -> None:
        await self.response_done.wait()
        await self._restore_normal_session()

    async def _restore_normal_session(self) -> None:
        try:
            if not self._closed:
                self._instruction_turn_active = False
                self._instruction_request = None
                self._tool_policy.reset()
                self._tool_policy_restore = False
                if self._context_reset_pending:
                    await self._reconnect_for_context_reset()
                elif self._pending_voice is not None:
                    await self._apply_pending_session_controls()
                else:
                    await self.refresh_tools()
        except Exception as error:
            self.conn.logger.bind(tag=__name__).warning(
                "恢复Realtime常规会话配置失败: errorType={}", type(error).__name__
            )
            self.conn.audio_input_ready.clear()
            self.conn.conversation_ready.clear()
        finally:
            self._instruction_turn_active = False

    async def handle_event(self, payload: Dict[str, Any]) -> None:
        event_type = payload.get("type")
        if event_type == "response.created":
            self._response_active = True
            self._barge_in_active = False
            if self._tool_followup_pending:
                self._tool_followup_pending = False
            with self.conn._abort_lock:
                self.conn.client_abort = False
        elif event_type == "input_audio_buffer.speech_started":
            await self._handle_speech_started_barge_in()
        elif event_type in {
            "conversation.item.input_audio_transcription.completed",
            "input_audio_transcription.completed",
        }:
            transcript = str(payload.get("transcript") or "").strip()
            if transcript:
                self.conn.dialogue.put(Message(role="user", content=transcript))
                await send_stt_display(self.conn, transcript)
                enqueue_asr_report(self.conn, transcript, self.input_audio)
                self.input_audio = []
        elif event_type in {"response.audio_transcript.delta", "response.text.delta"}:
            if not self._response_active:
                return
            self.response_text += str(payload.get("delta") or "")
            await self._send_subtitle_preview_if_ready()
        elif event_type in {"response.audio_transcript.done", "response.text.done"}:
            if not self._response_active:
                return
            completed_text = str(
                payload.get("transcript") or payload.get("text") or ""
            ).strip()
            if completed_text:
                self.response_text = completed_text
                if (
                    self._reply_started
                    and self._final_subtitle_text != completed_text
                ):
                    await send_tts_message(
                        self.conn,
                        "sentence_start",
                        completed_text,
                        protocol_sentence_id=self.conn.sentence_id,
                    )
                    self._final_subtitle_text = completed_text
        elif event_type == "response.audio.delta":
            if not self._response_active:
                self.conn.logger.bind(tag=__name__).debug(
                    "忽略已收敛Realtime响应的迟到音频事件"
                )
                return
            await self._handle_audio_delta(payload.get("delta"))
        elif event_type == "response.function_call_arguments.delta":
            call_id = str(payload.get("call_id") or payload.get("item_id") or "")
            call = self.function_calls.setdefault(call_id, {"name": "", "arguments": ""})
            call["name"] = str(payload.get("name") or call["name"])
            call["arguments"] += str(payload.get("delta") or "")
        elif event_type in {
            "response.function_call_arguments.done",
            "response.output_item.done",
        }:
            await self._handle_function_call(payload)
        elif event_type == "response.done":
            if (
                not self._response_active
                and not self._tool_followup_pending
                and not self.function_calls
            ):
                return
            response = payload.get("response") or {}
            output = response.get("output") if isinstance(response, dict) else []
            contains_function_call = any(
                isinstance(item, dict) and item.get("type") == "function_call"
                for item in (output or [])
            )
            if contains_function_call or self.function_calls:
                if self.function_calls:
                    self._tool_followup_pending = True
                    self._tool_batch_pending = True
                    if self._tool_task is None or self._tool_task.done():
                        # Session updates await an ACK read by the transport.
                        # Keep its event reader free while applying tool policy.
                        self._tool_task = asyncio.create_task(self._continue_tool_response())
                else:
                    self.conn.logger.bind(tag=__name__).error(
                        "Qwen Realtime 工具响应缺少完整调用参数"
                    )
                    await self._finish_response(reason="realtime_error")
                return
            if not self._tool_followup_pending:
                await self._finish_response()
        elif event_type == "error":
            self._cancel_generation += 1
            await self._cancel_tool_response()
            error = payload.get("error") or {}
            code = error.get("code") if isinstance(error, dict) else "UPSTREAM_ERROR"
            self.conn.logger.bind(tag=__name__).error("Qwen Realtime 返回错误: code={}", code)
            await self._finish_response(reason="realtime_error")
        elif event_type == "transport.closed":
            self._cancel_generation += 1
            await self._cancel_tool_response()
            self.conn.audio_input_ready.clear()
            self.conn.conversation_ready.clear()
            try:
                if self._response_active:
                    await self._finish_response(reason="realtime_error")
            finally:
                self._response_active = False
                self.response_done.set()

    async def _handle_audio_delta(self, delta: Any) -> None:
        if not delta:
            return
        if not self._reply_started:
            self.conn.sentence_id = uuid.uuid4().hex
            await send_tts_message(
                self.conn, "start", protocol_sentence_id=self.conn.sentence_id
            )
            self._reply_started = True
        await self._send_subtitle_preview_if_ready()
        pcm = base64.b64decode(delta)
        packets = []
        self.encoder.encode_pcm_to_opus_stream(pcm, False, packets.append)
        for packet in packets:
            self.response_audio.append(packet)
            await sendAudio(self.conn, packet)

    async def _send_subtitle_preview_if_ready(self) -> None:
        preview = self.response_text.strip()
        if not self._reply_started or not preview or self._final_subtitle_text:
            return
        await send_tts_message(
            self.conn,
            "sentence_start",
            preview,
            protocol_sentence_id=self.conn.sentence_id,
        )
        self._final_subtitle_text = preview

    async def _handle_speech_started_barge_in(self) -> None:
        if getattr(self, "_barge_in_active", False) or not (
            self._response_active
            or self._reply_started
            or self._tool_followup_pending
            or self.function_calls
        ):
            return
        self._barge_in_active = True
        self._cancel_generation += 1
        await self._cancel_tool_response()
        try:
            await self.transport.send(event("response.cancel"))
        except Exception as error:
            self.conn.logger.bind(tag=__name__).warning(
                "Qwen Realtime自然打断上游取消失败: errorType={}",
                type(error).__name__,
            )
        self.response_text = ""
        self.response_audio = []
        self._final_subtitle_text = ""
        self._response_active = False
        self.encoder.reset_state()
        await handleAbortMessage(self.conn, preserve_user_audio=True)
        self._reply_started = False
        self.response_done.set()

        self._reset_tool_policy()

    async def _cancel_tool_response(self):
        self._tool_batch_pending = False
        task = getattr(self, "_tool_task", None)
        if task is not None and task is not asyncio.current_task() and not task.done():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        self.function_calls.clear()
        self._tool_followup_pending = False

    def _reset_tool_policy(self):
        policy = getattr(self, "_tool_policy", None)
        if policy is not None:
            policy.reset()
            self._tool_policy_restore = True
            if not getattr(self, "_instruction_turn_active", False):
                self._schedule_session_maintenance()

    async def _handle_function_call(self, payload: Dict[str, Any]) -> None:
        item = payload.get("item") if isinstance(payload.get("item"), dict) else {}
        if item.get("type") not in {None, "function_call"}:
            return
        call_id = str(payload.get("call_id") or item.get("call_id") or payload.get("item_id") or "")
        if call_id in self.completed_function_calls:
            return
        call = self.function_calls.pop(call_id, {})
        name = str(payload.get("name") or item.get("name") or call.get("name") or "")
        arguments = payload.get("arguments") or item.get("arguments") or call.get("arguments") or "{}"
        if not call_id or not name or self.conn.func_handler is None:
            return
        logger = getattr(self.conn, "logger", None)
        if logger is not None:
            logger.bind(tag=__name__).info(
                "收到Qwen Realtime工具调用: name={}, callId={}", name, call_id
            )
        self.function_calls[call_id] = {"name": name, "arguments": arguments}

    async def _execute_function_calls(self) -> bool:
        pending = list(self.function_calls.items())
        self.function_calls.clear()
        cancel_generation = getattr(self, "_cancel_generation", 0)
        outputs = []
        for call_id, call in pending:
            if call_id in self.completed_function_calls:
                continue
            self.completed_function_calls.add(call_id)
            if not self._tool_policy.permits(call.get("name")):
                from plugins_func.register import Action, ActionResponse
                result = ActionResponse(action=Action.NOTFOUND, response="工具未启用或本轮不允许调用")
            else:
                result = await self.conn.func_handler.handle_llm_function_call(self.conn, call)
            if cancel_generation != getattr(self, "_cancel_generation", 0):
                return False
            self._tool_policy.accept([(result, call)], count_round=False)
            outputs.append(
                event(
                    "conversation.item.create",
                    item={
                        "type": "function_call_output",
                        "call_id": call_id,
                        "output": tool_output_text(result),
                    },
                )
            )
        if outputs:
            self._tool_policy.accept([])
            await self.refresh_tools()
            if cancel_generation != self._cancel_generation or self._closed:
                return False
            for output in outputs:
                await self.transport.send(output)
        return cancel_generation == getattr(self, "_cancel_generation", 0)

    async def _continue_tool_response(self):
        try:
            while self._tool_batch_pending and not self._closed:
                self._tool_batch_pending = False
                if not await self._execute_function_calls() or self._closed:
                    return
                await self.transport.send(event("response.create"))
        except asyncio.CancelledError:
            raise
        except Exception as error:
            self.conn.logger.bind(tag=__name__).warning(
                "Realtime工具续轮失败: errorType={}", type(error).__name__)
            self.conn.audio_input_ready.clear()
            self.conn.conversation_ready.clear()
            await self._finish_response(reason="realtime_error")

    async def _finish_response(self, reason=None) -> None:
        packets = []
        self.encoder.encode_pcm_to_opus_stream(b"", True, packets.append)
        for packet in packets:
            self.response_audio.append(packet)
            await sendAudio(self.conn, packet)
        text = self.response_text.strip()
        if text:
            self.conn.dialogue.put(Message(role="assistant", content=text))
            enqueue_tts_report(self.conn, text, list(self.response_audio))
        self.last_response_text = text
        if self._reply_started:
            if text and getattr(self, "_final_subtitle_text", "") != text:
                await send_tts_message(
                    self.conn,
                    "sentence_start",
                    text,
                    protocol_sentence_id=self.conn.sentence_id,
                )
            await send_tts_message(
                self.conn,
                "stop",
                protocol_sentence_id=self.conn.sentence_id,
                reason=reason,
                drain_audio=reason != "abort",
                allow_pending_stop=True,
            )
        self.response_text = ""
        self.response_audio = []
        self.input_audio = []
        self._final_subtitle_text = ""
        self._reply_started = False
        self._response_active = False
        self.response_done.set()
        if not getattr(self, "_instruction_turn_active", False):
            if getattr(self, "_tool_policy", None) is not None and self._tool_policy.rounds:
                self._reset_tool_policy()
            self._schedule_session_maintenance()

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        tool_task = getattr(self, "_tool_task", None)
        if tool_task is not None and not tool_task.done():
            tool_task.cancel()
            await asyncio.gather(tool_task, return_exceptions=True)
        if self._restore_task is not None and not self._restore_task.done():
            self._restore_task.cancel()
            await asyncio.gather(self._restore_task, return_exceptions=True)
        maintenance_task = getattr(self, "_session_maintenance_task", None)
        if maintenance_task is not None and not maintenance_task.done():
            maintenance_task.cancel()
            await asyncio.gather(maintenance_task, return_exceptions=True)
        await cancel_realtime_updates(self)
        await self.transport.close()
        self.encoder.close()


class QwenRealtimeTextProbe:
    """Reusable benchmark session preserving provider-side multi-turn context."""

    def __init__(self, config: Dict[str, Any], transport=None):
        self.config = dict(config or {})
        self.transport = transport or QwenOmniRealtimeTransport(self.config)
        self.connect_ms = None
        self.started = 0.0
        self.first_text_ms = None
        self.first_audio_ms = None
        self.output = ""
        self.audio_bytes = 0
        self.error = None
        self.done = asyncio.Event()
        self._turn_lock = asyncio.Lock()

    async def connect(self) -> int:
        connect_started = time.perf_counter()
        await self.transport.connect(self._on_event)
        await self.transport.configure(
            str(self.config.get("prompt") or ""),
            [],
            {"turn_detection_type": "manual"},
        )
        self.connect_ms = int((time.perf_counter() - connect_started) * 1000)
        return self.connect_ms

    async def turn(self, text: str) -> Dict[str, Any]:
        async with self._turn_lock:
            self.started = time.perf_counter()
            self.first_text_ms = None
            self.first_audio_ms = None
            self.output = ""
            self.audio_bytes = 0
            self.error = None
            self.done.clear()
            await self.transport.configure(
                build_instruction_prompt(self.config.get("prompt"), text),
                [],
                {"turn_detection_type": "manual"},
            )
            for payload in build_manual_turn_events():
                await self.transport.send(payload)
            await asyncio.wait_for(
                self.done.wait(),
                timeout=float(self.config.get("response_timeout_seconds", 120)),
            )
            if self.error is not None:
                raise self.error
            return {
                "output": self.output,
                "connect_ms": self.connect_ms or 0,
                "ttft_ms": self.first_text_ms,
                "first_audio_ms": self.first_audio_ms,
                "audio_bytes": self.audio_bytes,
                "latency_ms": int((time.perf_counter() - self.started) * 1000),
            }

    async def _on_event(self, payload):
        event_type = payload.get("type")
        elapsed = int((time.perf_counter() - self.started) * 1000)
        if event_type in {"response.text.delta", "response.audio_transcript.delta"}:
            if self.first_text_ms is None:
                self.first_text_ms = elapsed
            self.output += str(payload.get("delta") or "")
        elif event_type == "response.audio.delta":
            if self.first_audio_ms is None:
                self.first_audio_ms = elapsed
            delta = payload.get("delta") or ""
            if delta:
                self.audio_bytes += len(base64.b64decode(delta))
        elif event_type == "response.done":
            self.done.set()
        elif event_type == "error":
            error = payload.get("error") or {}
            self.error = RuntimeError(
                str(error.get("message") or error.get("code") or "Realtime 调用失败")
            )
            self.done.set()

    async def close(self) -> None:
        await self.transport.close()


async def run_qwen_realtime_text_probe(config: Dict[str, Any], text: str) -> Dict[str, Any]:
    """One-turn compatibility wrapper around the reusable benchmark session."""
    probe = QwenRealtimeTextProbe(config)
    try:
        await probe.connect()
        return await probe.turn(text)
    finally:
        await probe.close()
