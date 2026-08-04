"""Volcengine Doubao end-to-end realtime speech transport and device session."""

import asyncio
import gzip
import inspect
import json
import time
import uuid
import zlib
from typing import Any, Awaitable, Callable, Dict, Optional
from urllib.parse import urlsplit, urlunsplit

import opuslib_next
import websockets

from core.handle.reportHandle import enqueue_asr_report, enqueue_tts_report
from core.handle.sendAudioHandle import sendAudio, send_stt_display, send_tts_message
from core.utils.dialogue import Message
from core.utils.opus_encoder_utils import OpusEncoderUtils
from core.utils.prompt_budget import compact_memory_context


DOUBAO_REALTIME_HOST = "openspeech.bytedance.com"
DOUBAO_REALTIME_PATH = "/api/v3/realtime/dialogue"
DOUBAO_RESOURCE_ID = "volc.speech.dialog"
DOUBAO_APP_KEY = "PlgvMymc7f3tQnJ6"
SUPPORTED_ADAPTERS = {"doubao_realtime", "doubao-realtime"}
SUPPORTED_MODELS = {"1.2.1.1", "2.2.0.0"}
DEFAULT_MAX_EVENT_BYTES = 10 * 1024 * 1024
MAX_EVENT_BYTES_LIMIT = 16 * 1024 * 1024

CLIENT_FULL_REQUEST = 0x1
CLIENT_AUDIO_REQUEST = 0x2
SERVER_FULL_RESPONSE = 0x9
SERVER_AUDIO_RESPONSE = 0xB
SERVER_ERROR_RESPONSE = 0xF
FLAG_WITH_EVENT = 0x4
SERIALIZATION_RAW = 0x0
SERIALIZATION_JSON = 0x1
COMPRESSION_GZIP = 0x1

START_CONNECTION = 1
FINISH_CONNECTION = 2
START_SESSION = 100
FINISH_SESSION = 102
TASK_REQUEST = 200
END_ASR = 400
CHAT_TEXT_QUERY = 501
CLIENT_INTERRUPT = 515

CONNECTION_STARTED = 50
CONNECTION_FAILED = 51
CONNECTION_FINISHED = 52
SESSION_STARTED = 150
SESSION_FINISHED = 152
SESSION_FAILED = 153
TTS_SENTENCE_START = 350
TTS_RESPONSE = 352
TTS_ENDED = 359
ASR_INFO = 450
ASR_RESPONSE = 451
ASR_ENDED = 459
CHAT_RESPONSE = 550
CHAT_TEXT_QUERY_CONFIRMED = 553
CHAT_ENDED = 559
DIALOG_COMMON_ERROR = 599

CONNECTION_EVENTS = {
    START_CONNECTION,
    FINISH_CONNECTION,
    CONNECTION_STARTED,
    CONNECTION_FAILED,
    CONNECTION_FINISHED,
}
ERROR_EVENTS = {CONNECTION_FAILED, SESSION_FAILED, DIALOG_COMMON_ERROR}
EVENT_NAMES = {
    CONNECTION_STARTED: "connection.started",
    CONNECTION_FAILED: "connection.failed",
    CONNECTION_FINISHED: "connection.finished",
    SESSION_STARTED: "session.started",
    SESSION_FINISHED: "session.finished",
    SESSION_FAILED: "session.failed",
    TTS_SENTENCE_START: "tts.sentence_start",
    TTS_RESPONSE: "tts.audio",
    TTS_ENDED: "tts.ended",
    ASR_INFO: "asr.info",
    ASR_RESPONSE: "asr.response",
    ASR_ENDED: "asr.ended",
    CHAT_RESPONSE: "chat.response",
    CHAT_TEXT_QUERY_CONFIRMED: "chat.query_confirmed",
    CHAT_ENDED: "chat.ended",
    DIALOG_COMMON_ERROR: "dialog.error",
}


def validate_realtime_url(value: str) -> str:
    url = str(value or "").strip()
    if not url:
        raise ValueError("豆包 Realtime base_url 不能为空")
    parsed = urlsplit(url)
    hostname = str(parsed.hostname or "").lower()
    if parsed.scheme != "wss" or hostname != DOUBAO_REALTIME_HOST:
        raise ValueError("豆包 Realtime base_url 仅允许火山引擎官方域名")
    if parsed.username or parsed.password or parsed.port not in (None, 443):
        raise ValueError("豆包 Realtime base_url 不允许凭据或非标准端口")
    if parsed.path.rstrip("/") != DOUBAO_REALTIME_PATH:
        raise ValueError(f"豆包 Realtime base_url 路径必须为 {DOUBAO_REALTIME_PATH}")
    if parsed.query or parsed.fragment:
        raise ValueError("豆包 Realtime base_url 不允许预置查询参数或片段")
    return urlunsplit(("wss", hostname, parsed.path, "", ""))


def _as_bool(value: Any, default=False) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


def _bounded_int(value: Any, default: int, minimum: int, maximum: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        number = default
    return max(minimum, min(number, maximum))


def _max_event_bytes(value: Any) -> int:
    return _bounded_int(value, DEFAULT_MAX_EVENT_BYTES, 1024, MAX_EVENT_BYTES_LIMIT)


def _gzip_decompress_limited(payload: bytes, limit: int) -> bytes:
    try:
        decompressor = zlib.decompressobj(16 + zlib.MAX_WBITS)
        decoded = decompressor.decompress(payload, limit + 1)
        if len(decoded) > limit or decompressor.unconsumed_tail:
            raise ValueError("豆包 Realtime 解压后 payload 超过限制")
        decoded += decompressor.flush()
        if len(decoded) > limit:
            raise ValueError("豆包 Realtime 解压后 payload 超过限制")
        if not decompressor.eof or decompressor.unused_data:
            raise ValueError("豆包 Realtime gzip payload 不完整或包含尾随数据")
        return decoded
    except zlib.error as error:
        raise ValueError("豆包 Realtime gzip payload 无效") from error


def build_start_session(config: Dict[str, Any], prompt: str) -> Dict[str, Any]:
    input_rate = int(config.get("input_sample_rate", 16000))
    output_rate = int(config.get("output_sample_rate", 24000))
    if (input_rate, output_rate) != (16000, 24000):
        raise ValueError("豆包端到端语音固定为输入16kHz、输出24kHz")
    model = str(config.get("model_name") or config.get("model") or "1.2.1.1").strip()
    if model not in SUPPORTED_MODELS:
        raise ValueError("豆包端到端模型版本仅支持 1.2.1.1 或 2.2.0.0")
    input_mode = str(config.get("input_mode") or "audio").strip().lower()
    if input_mode not in {"audio", "push_to_talk", "text"}:
        raise ValueError("豆包端到端 input_mode 仅支持 audio、push_to_talk 或 text")

    role_prompt = str(prompt or "").strip()
    dialog = {
        "bot_name": str(config.get("bot_name") or "小智")[:20],
        "dialog_id": "",
        "extra": {
            "strict_audit": _as_bool(config.get("strict_audit"), True),
            "input_mod": input_mode,
            "model": model,
        },
    }
    if input_mode in {"text", "audio_file"}:
        dialog["extra"]["recv_timeout"] = _bounded_int(
            config.get("response_timeout_seconds"), 120, 10, 120
        )
    if model == "2.2.0.0":
        dialog["character_manifest"] = role_prompt
    else:
        dialog["system_role"] = role_prompt
        dialog["speaking_style"] = str(
            config.get("speaking_style") or "回复简洁自然，适合实时语音对话。"
        )

    return {
        "asr": {
            "audio_info": {"format": "pcm", "sample_rate": input_rate, "channel": 1},
            "extra": {
                "end_smooth_window_ms": _bounded_int(
                    config.get("end_smooth_window_ms"), 1500, 500, 50000
                )
            },
        },
        "tts": {
            "speaker": str(config.get("voice") or "zh_female_vv_jupiter_bigtts"),
            "audio_config": {
                "channel": 1,
                "format": "pcm_s16le",
                "sample_rate": output_rate,
                "speech_rate": _bounded_int(config.get("speech_rate"), 0, -50, 100),
                "loudness_rate": _bounded_int(config.get("loudness_rate"), 0, -50, 100),
            },
        },
        "dialog": dialog,
    }


def build_client_frame(
    event_id: int,
    payload: Any = None,
    *,
    session_id: Optional[str] = None,
    message_type: int = CLIENT_FULL_REQUEST,
    serialization: int = SERIALIZATION_JSON,
    compression: int = COMPRESSION_GZIP,
) -> bytes:
    if serialization == SERIALIZATION_JSON:
        raw_payload = json.dumps(
            {} if payload is None else payload, ensure_ascii=False, separators=(",", ":")
        ).encode("utf-8")
    else:
        raw_payload = bytes(payload or b"")
    if compression == COMPRESSION_GZIP:
        raw_payload = gzip.compress(raw_payload)

    frame = bytearray(
        (0x11, (message_type << 4) | FLAG_WITH_EVENT,
         (serialization << 4) | compression, 0x00)
    )
    frame.extend(int(event_id).to_bytes(4, "big"))
    if event_id not in CONNECTION_EVENTS:
        encoded_session_id = str(session_id or "").encode("utf-8")
        if not encoded_session_id:
            raise ValueError("豆包 Realtime Session 事件缺少 session_id")
        frame.extend(len(encoded_session_id).to_bytes(4, "big"))
        frame.extend(encoded_session_id)
    frame.extend(len(raw_payload).to_bytes(4, "big"))
    frame.extend(raw_payload)
    return bytes(frame)


def parse_server_frame(raw: bytes, max_event_bytes=DEFAULT_MAX_EVENT_BYTES) -> Dict[str, Any]:
    if not isinstance(raw, (bytes, bytearray)) or len(raw) < 8:
        raise ValueError("豆包 Realtime 返回了无效二进制帧")
    header_size = (raw[0] & 0x0F) * 4
    if raw[0] >> 4 != 1 or header_size < 4 or len(raw) < header_size + 4:
        raise ValueError("豆包 Realtime 二进制帧头无效")
    message_type = raw[1] >> 4
    flags = raw[1] & 0x0F
    serialization = raw[2] >> 4
    compression = raw[2] & 0x0F
    offset = header_size
    result: Dict[str, Any] = {
        "message_type": message_type,
        "event": None,
        "session_id": "",
        "payload": None,
    }

    if message_type == SERVER_ERROR_RESPONSE:
        if len(raw) < offset + 8:
            raise ValueError("豆包 Realtime 错误帧长度无效")
        result["code"] = int.from_bytes(raw[offset:offset + 4], "big")
        offset += 4
    elif flags & 0x3:
        if len(raw) < offset + 4:
            raise ValueError("豆包 Realtime sequence 字段长度无效")
        result["sequence"] = int.from_bytes(raw[offset:offset + 4], "big", signed=True)
        offset += 4

    if message_type != SERVER_ERROR_RESPONSE and flags & FLAG_WITH_EVENT:
        if len(raw) < offset + 4:
            raise ValueError("豆包 Realtime event 字段长度无效")
        result["event"] = int.from_bytes(raw[offset:offset + 4], "big")
        offset += 4
        if len(raw) < offset + 4:
            raise ValueError("豆包 Realtime 事件标识长度字段缺失")
        identifier_size = int.from_bytes(raw[offset:offset + 4], "big")
        offset += 4
        if identifier_size > 4096 or len(raw) < offset + identifier_size:
            raise ValueError("豆包 Realtime 事件标识长度无效")
        identifier = bytes(raw[offset:offset + identifier_size]).decode(
            "utf-8", errors="replace"
        )
        if result["event"] in CONNECTION_EVENTS:
            result["connect_id"] = identifier
        else:
            result["session_id"] = identifier
        offset += identifier_size

    if len(raw) < offset + 4:
        raise ValueError("豆包 Realtime payload 长度字段缺失")
    payload_size = int.from_bytes(raw[offset:offset + 4], "big")
    offset += 4
    limit = _bounded_int(max_event_bytes, DEFAULT_MAX_EVENT_BYTES, 1, MAX_EVENT_BYTES_LIMIT)
    if payload_size > limit or len(raw) != offset + payload_size:
        raise ValueError("豆包 Realtime payload 超过限制或长度不完整")
    payload_bytes = bytes(raw[offset:offset + payload_size])
    if compression == COMPRESSION_GZIP and payload_bytes:
        payload_bytes = _gzip_decompress_limited(payload_bytes, limit)
    if serialization == SERIALIZATION_JSON and payload_bytes:
        result["payload"] = json.loads(payload_bytes.decode("utf-8"))
    elif serialization == SERIALIZATION_RAW:
        result["payload"] = payload_bytes
    else:
        result["payload"] = payload_bytes.decode("utf-8", errors="replace")
    result["type"] = (
        "server.error"
        if message_type == SERVER_ERROR_RESPONSE
        else EVENT_NAMES.get(result.get("event"), "event.unknown")
    )
    return result


class DoubaoRealtimeTransport:
    """Credential-safe transport for Volcengine's binary realtime protocol."""

    def __init__(self, config: Dict[str, Any], logger=None, websocket_factory=None):
        self.config = dict(config or {})
        self.logger = logger
        self.websocket_factory = websocket_factory or websockets.connect
        self.websocket = None
        self.reader_task = None
        self.event_handler: Optional[Callable[[Dict[str, Any]], Awaitable[None]]] = None
        self.session_id = uuid.uuid4().hex
        self._waiters: Dict[int, list] = {}
        self._send_lock = asyncio.Lock()
        self._closed = False
        self._session_started = False

    async def connect(self, event_handler=None) -> None:
        app_id = str(self.config.get("app_id") or self.config.get("appid") or "").strip()
        access_key = str(
            self.config.get("api_key") or self.config.get("access_token") or ""
        ).strip()
        if not app_id:
            raise ValueError("豆包 Realtime app_id 不能为空")
        if not access_key:
            raise ValueError("豆包 Realtime api_key 不能为空")
        url = validate_realtime_url(
            self.config.get("base_url") or self.config.get("endpoint_url")
        )
        self.event_handler = event_handler
        timeout = _bounded_int(
            self.config.get("connect_timeout_seconds"), 10, 1, 60
        )
        headers = {
            "X-Api-App-ID": app_id,
            "X-Api-Access-Key": access_key,
            "X-Api-Resource-Id": DOUBAO_RESOURCE_ID,
            "X-Api-App-Key": DOUBAO_APP_KEY,
            "X-Api-Connect-Id": str(uuid.uuid4()),
        }
        try:
            parameters = inspect.signature(self.websocket_factory).parameters
        except (TypeError, ValueError):
            parameters = {}
        header_argument = (
            "additional_headers" if "additional_headers" in parameters else "extra_headers"
        )
        kwargs = {
            header_argument: headers,
            "max_size": _max_event_bytes(self.config.get("max_event_bytes")),
            "open_timeout": timeout,
            "ping_interval": None,
        }
        self.websocket = await asyncio.wait_for(
            self.websocket_factory(url, **kwargs), timeout=timeout + 1
        )
        waiter = self._new_waiter(CONNECTION_STARTED)
        self.reader_task = asyncio.create_task(
            self._reader(), name="doubao-realtime-reader"
        )
        await self.send_event(START_CONNECTION, {})
        await self._await_waiter(CONNECTION_STARTED, waiter, self._response_timeout())

    async def configure(self, prompt: str, tools=None, config_override=None) -> None:
        if self._session_started:
            return
        session_config = dict(self.config)
        session_config.update(config_override or {})
        waiter = self._new_waiter(SESSION_STARTED)
        await self.send_event(
            START_SESSION,
            build_start_session(session_config, prompt),
            session_id=self.session_id,
        )
        await self._await_waiter(SESSION_STARTED, waiter, self._response_timeout())
        self._session_started = True

    async def send_event(
        self,
        event_id: int,
        payload: Any = None,
        *,
        session_id: Optional[str] = None,
        audio=False,
    ) -> None:
        if self.websocket is None:
            raise RuntimeError("豆包 Realtime WebSocket 尚未连接")
        frame = build_client_frame(
            event_id,
            payload,
            session_id=session_id or self.session_id,
            message_type=CLIENT_AUDIO_REQUEST if audio else CLIENT_FULL_REQUEST,
            serialization=SERIALIZATION_RAW if audio else SERIALIZATION_JSON,
        )
        async with self._send_lock:
            await self.websocket.send(frame)

    def _new_waiter(self, event_id: int):
        future = asyncio.get_running_loop().create_future()
        self._waiters.setdefault(event_id, []).append(future)
        return future

    async def _await_waiter(self, event_id: int, future, timeout: float):
        try:
            return await asyncio.wait_for(future, timeout=timeout)
        finally:
            waiters = self._waiters.get(event_id, [])
            if future in waiters:
                waiters.remove(future)

    def _response_timeout(self) -> float:
        return float(
            _bounded_int(self.config.get("response_timeout_seconds"), 120, 1, 300)
        )

    async def _reader(self) -> None:
        close_error = None
        try:
            async for raw in self.websocket:
                payload = parse_server_frame(
                    raw, _max_event_bytes(self.config.get("max_event_bytes"))
                )
                event_id = payload.get("event")
                if event_id in ERROR_EVENTS or payload.get("type") == "server.error":
                    error_payload = payload.get("payload") or {}
                    error = RuntimeError(
                        str(
                            (error_payload.get("message") if isinstance(error_payload, dict) else "")
                            or (error_payload.get("error") if isinstance(error_payload, dict) else "")
                            or payload.get("code")
                            or f"豆包 Realtime 事件失败: {event_id}"
                        )
                    )
                    pending = list(self._waiters.values())
                    self._waiters.clear()
                    for waiters in pending:
                        for waiter in waiters:
                            if not waiter.done():
                                waiter.set_exception(error)
                for waiter in list(self._waiters.pop(event_id, [])):
                    if not waiter.done():
                        waiter.set_result(payload)
                if self.event_handler is not None:
                    await self.event_handler(payload)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            close_error = error
        else:
            close_error = ConnectionError("豆包 Realtime WebSocket 已由上游关闭")
        if self._closed:
            return
        pending = list(self._waiters.values())
        self._waiters.clear()
        for waiters in pending:
            for waiter in waiters:
                if not waiter.done():
                    waiter.set_exception(close_error)
        if self.event_handler is not None:
            await self.event_handler(
                {"type": "transport.closed", "error": {"code": type(close_error).__name__}}
            )
        if self.logger is not None:
            self.logger.warning(
                "豆包 Realtime 上游连接异常: errorType={}", type(close_error).__name__
            )

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        close_error = ConnectionError("豆包 Realtime WebSocket 已关闭")
        for waiters in self._waiters.values():
            for waiter in waiters:
                if not waiter.done():
                    waiter.set_exception(close_error)
        self._waiters.clear()
        if self.websocket is not None:
            try:
                if self._session_started:
                    await self.send_event(FINISH_SESSION, {}, session_id=self.session_id)
                await self.send_event(FINISH_CONNECTION, {})
            except Exception:
                pass
            await self.websocket.close()
        if self.reader_task is not None and not self.reader_task.done():
            self.reader_task.cancel()
            await asyncio.gather(self.reader_task, return_exceptions=True)


class DoubaoRealtimeSession:
    """Bind one Doubao realtime session to one authenticated device connection."""

    def __init__(self, conn, config: Dict[str, Any], transport=None):
        self.conn = conn
        self.config = dict(config or {})
        self.transport = transport or DoubaoRealtimeTransport(
            self.config, conn.logger.bind(tag=__name__)
        )
        self.decoder = opuslib_next.Decoder(16000, 1)
        self.encoder = OpusEncoderUtils(24000, 1, 60)
        self.input_audio = []
        self.response_audio = []
        self.response_text = ""
        self.current_transcript = ""
        self.last_response_text = ""
        self.response_done = asyncio.Event()
        self._reply_started = False
        self._response_active = False
        self._turn_lock = asyncio.Lock()
        self._closed = False
        self._warned_tools = False

    async def initialize(self) -> None:
        await self.transport.connect(self.handle_event)
        await self.transport.configure(self._session_prompt(), [])
        await self.refresh_tools()

    def _session_prompt(self) -> str:
        prompt = self.conn.prompt or self.conn.config.get("prompt", "")
        prompt_manager = getattr(self.conn, "prompt_manager", None)
        if prompt_manager is not None:
            prompt = prompt_manager.replace_available_tools_prompt(prompt, [])
        summary = compact_memory_context(self.conn.config.get("summaryMemory"))
        if summary:
            prompt = f"{prompt}\n\n<realtime_summary_memory>\n{summary}\n</realtime_summary_memory>"
        return prompt

    async def refresh_tools(self) -> None:
        tools = self.conn.func_handler.get_functions() if self.conn.func_handler else []
        if tools and not self._warned_tools:
            self._warned_tools = True
            self.conn.logger.bind(tag=__name__).warning(
                "豆包端到端实时语音协议当前不支持本地 Function Calling 工具回注，已保持服务端工具门控但不向上游暴露工具"
            )

    async def handle_audio(self, opus_packet: bytes) -> bool:
        try:
            pcm = self.decoder.decode(opus_packet, 960)
        except opuslib_next.OpusError:
            self.conn.logger.bind(tag=__name__).warning(
                "丢弃无效豆包 Realtime Opus音频帧: sizeBytes={}", len(opus_packet or b"")
            )
            return True
        self.input_audio.append(opus_packet)
        await self.transport.send_event(TASK_REQUEST, pcm, audio=True)
        return True

    async def handle_listen(self, message: Dict[str, Any]) -> bool:
        state = message.get("state")
        if state == "start":
            self.input_audio = []
            self.current_transcript = ""
        elif (
            state == "stop"
            and str(self.config.get("input_mode") or "audio") == "push_to_talk"
        ):
            await self.transport.send_event(END_ASR, {})
        return True

    async def handle_text(self, text: str) -> bool:
        clean_text = str(text or "").strip()
        if not clean_text:
            return True
        async with self._turn_lock:
            if self._response_active:
                raise RuntimeError("豆包 Realtime 正在处理上一轮响应")
            self._response_active = True
        self.response_done.clear()
        self.conn.dialogue.put(Message(role="user", content=clean_text))
        enqueue_asr_report(self.conn, clean_text, [])
        await send_stt_display(self.conn, clean_text)
        try:
            await self.transport.send_event(CHAT_TEXT_QUERY, {"content": clean_text})
        except Exception:
            self._response_active = False
            raise
        return True

    async def handle_abort(self) -> bool:
        upstream_error = None
        try:
            await self.transport.send_event(CLIENT_INTERRUPT, {})
        except Exception as error:
            upstream_error = error
        await self._finish_response(reason="abort")
        if upstream_error is not None:
            raise upstream_error
        return True

    async def handle_image(self, question: str, image: bytes, timeout=None) -> str:
        raise RuntimeError("豆包端到端实时语音当前不支持图片输入，请切换支持视觉的 Realtime 模型")

    async def handle_event(self, event: Dict[str, Any]) -> None:
        event_id = event.get("event")
        payload = event.get("payload") or {}
        if event_id == ASR_INFO:
            if self._reply_started or self._response_active:
                await self._finish_response(reason="abort")
            self.current_transcript = ""
        elif event_id == ASR_RESPONSE:
            results = payload.get("results") if isinstance(payload, dict) else []
            for item in results or []:
                if isinstance(item, dict) and not item.get("is_interim"):
                    text = str(item.get("text") or "").strip()
                    if text:
                        self.current_transcript = text
        elif event_id == ASR_ENDED:
            transcript = self.current_transcript.strip()
            if transcript:
                self.conn.dialogue.put(Message(role="user", content=transcript))
                await send_stt_display(self.conn, transcript)
                enqueue_asr_report(self.conn, transcript, self.input_audio)
            self.input_audio = []
            self.current_transcript = ""
            self._response_active = True
            self.response_done.clear()
            with self.conn._abort_lock:
                self.conn.client_abort = False
        elif event_id == CHAT_RESPONSE:
            if self._response_active and isinstance(payload, dict):
                self.response_text += str(payload.get("content") or "")
        elif event_id == TTS_SENTENCE_START:
            if self._response_active and not self.response_text and isinstance(payload, dict):
                self.response_text = str(payload.get("text") or "")
        elif event_id == TTS_RESPONSE:
            if self._response_active:
                await self._handle_audio(payload)
        elif event_id == TTS_ENDED:
            if self._response_active or self._reply_started:
                await self._finish_response()
        elif event_id in ERROR_EVENTS or event.get("type") == "server.error":
            code = payload.get("status_code") if isinstance(payload, dict) else "UPSTREAM_ERROR"
            self.conn.logger.bind(tag=__name__).error(
                "豆包 Realtime 返回错误: code={}", code
            )
            await self._finish_response(reason="realtime_error")
        elif event.get("type") == "transport.closed":
            self.conn.audio_input_ready.clear()
            self.conn.conversation_ready.clear()
            await self._finish_response(reason="realtime_error")

    async def _handle_audio(self, pcm: Any) -> None:
        if not isinstance(pcm, (bytes, bytearray)) or not pcm:
            return
        if not self._reply_started:
            self.conn.sentence_id = uuid.uuid4().hex
            await send_tts_message(
                self.conn, "start", protocol_sentence_id=self.conn.sentence_id
            )
            self._reply_started = True
        packets = []
        self.encoder.encode_pcm_to_opus_stream(bytes(pcm), False, packets.append)
        for packet in packets:
            self.response_audio.append(packet)
            await sendAudio(self.conn, packet)

    async def _finish_response(self, reason=None) -> None:
        if self._reply_started:
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
        self._reply_started = False
        self._response_active = False
        self.response_done.set()

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        await self.transport.close()
        self.encoder.close()


class DoubaoRealtimeTextProbe:
    """Real text turn probe for manager-api model testing and benchmarks."""

    def __init__(self, config: Dict[str, Any], transport=None):
        self.config = dict(config or {})
        self.transport = transport or DoubaoRealtimeTransport(self.config)
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
        started = time.perf_counter()
        await self.transport.connect(self._on_event)
        await self.transport.configure(
            str(self.config.get("prompt") or ""), [], {"input_mode": "text"}
        )
        self.connect_ms = int((time.perf_counter() - started) * 1000)
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
            await self.transport.send_event(
                CHAT_TEXT_QUERY, {"content": str(text or "")}
            )
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

    async def _on_event(self, event: Dict[str, Any]) -> None:
        event_id = event.get("event")
        payload = event.get("payload") or {}
        elapsed = int((time.perf_counter() - self.started) * 1000)
        if event_id == CHAT_RESPONSE:
            if self.first_text_ms is None:
                self.first_text_ms = elapsed
            if isinstance(payload, dict):
                self.output += str(payload.get("content") or "")
        elif event_id == TTS_RESPONSE:
            if self.first_audio_ms is None:
                self.first_audio_ms = elapsed
            if isinstance(payload, (bytes, bytearray)):
                self.audio_bytes += len(payload)
        elif event_id == TTS_ENDED:
            self.done.set()
        elif event_id in ERROR_EVENTS or event.get("type") == "server.error":
            message = payload.get("message") if isinstance(payload, dict) else None
            self.error = RuntimeError(str(message or "豆包 Realtime 调用失败"))
            self.done.set()
        elif event.get("type") == "transport.closed":
            self.error = RuntimeError("豆包 Realtime 连接已关闭")
            self.done.set()

    async def close(self) -> None:
        await self.transport.close()
