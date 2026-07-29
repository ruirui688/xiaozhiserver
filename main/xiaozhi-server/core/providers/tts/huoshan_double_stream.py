import os
import uuid
import json
import math
import queue
import time
import asyncio
import threading
import traceback
import websockets
import concurrent.futures

from collections import OrderedDict
from collections.abc import Mapping
from typing import Callable, Any
from config.logger import setup_logging
from core.utils.util import check_model_key
from core.providers.tts.base import TTSProviderBase
from core.utils.tts import convert_percentage_to_range
from core.providers.tts.dto.dto import SentenceType, ContentType, InterfaceType


TAG = __name__
logger = setup_logging()

DOUBAO_SEED_TTS_2_RESOURCE_ID = "seed-tts-2.0"
DOUBAO_BIGTTS_RESOURCE_ID = "volc.service_type.10029"
SUPPORTED_DOUBAO_TTS_RESOURCES = {
    DOUBAO_SEED_TTS_2_RESOURCE_ID: "豆包语音合成模型 2.0（火山服务 10035）",
    DOUBAO_BIGTTS_RESOURCE_ID: "语音合成大模型（火山服务 10029）",
}
HUOSHAN_DOUBLE_STREAM_WS_URL = "wss://openspeech.bytedance.com/api/v3/tts/bidirection"

PROTOCOL_VERSION = 0b0001
DEFAULT_HEADER_SIZE = 0b0001

# Message Type:
FULL_CLIENT_REQUEST = 0b0001
AUDIO_ONLY_RESPONSE = 0b1011
FULL_SERVER_RESPONSE = 0b1001
ERROR_INFORMATION = 0b1111

# Message Type Specific Flags
MsgTypeFlagNoSeq = 0b0000  # Non-terminal packet with no sequence
MsgTypeFlagPositiveSeq = 0b1  # Non-terminal packet with sequence > 0
MsgTypeFlagLastNoSeq = 0b10  # last packet with no sequence
MsgTypeFlagNegativeSeq = 0b11  # Payload contains event number (int32)
MsgTypeFlagWithEvent = 0b100
# Message Serialization
NO_SERIALIZATION = 0b0000
JSON = 0b0001
# Message Compression
COMPRESSION_NO = 0b0000
COMPRESSION_GZIP = 0b0001

EVENT_NONE = 0
EVENT_Start_Connection = 1

EVENT_FinishConnection = 2

EVENT_ConnectionStarted = 50  # 成功建连

EVENT_ConnectionFailed = 51  # 建连失败（可能是无法通过权限认证）

EVENT_ConnectionFinished = 52  # 连接结束

# 上行Session事件
EVENT_StartSession = 100
EVENT_CancelSession = 101
EVENT_FinishSession = 102
# 下行Session事件
EVENT_SessionStarted = 150
EVENT_SessionCanceled = 151
EVENT_SessionFinished = 152

EVENT_SessionFailed = 153
EVENT_UsageResponse = 154

# 上行通用事件
EVENT_TaskRequest = 200

# 下行TTS事件
EVENT_TTSSentenceStart = 350

EVENT_TTSSentenceEnd = 351

EVENT_TTSResponse = 352


class Header:
    def __init__(
        self,
        protocol_version=PROTOCOL_VERSION,
        header_size=DEFAULT_HEADER_SIZE,
        message_type: int = 0,
        message_type_specific_flags: int = 0,
        serial_method: int = NO_SERIALIZATION,
        compression_type: int = COMPRESSION_NO,
        reserved_data=0,
    ):
        self.header_size = header_size
        self.protocol_version = protocol_version
        self.message_type = message_type
        self.message_type_specific_flags = message_type_specific_flags
        self.serial_method = serial_method
        self.compression_type = compression_type
        self.reserved_data = reserved_data

    def as_bytes(self) -> bytes:
        return bytes(
            [
                (self.protocol_version << 4) | self.header_size,
                (self.message_type << 4) | self.message_type_specific_flags,
                (self.serial_method << 4) | self.compression_type,
                self.reserved_data,
            ]
        )


class Optional:
    def __init__(
        self, event: int = EVENT_NONE, sessionId: str = None, sequence: int = None
    ):
        self.event = event
        self.sessionId = sessionId
        self.errorCode: int = 0
        self.connectionId: str | None = None
        self.response_meta_json: str | None = None
        self.sequence = sequence

    # 转成 byte 序列
    def as_bytes(self) -> bytes:
        option_bytes = bytearray()
        if self.event != EVENT_NONE:
            option_bytes.extend(self.event.to_bytes(4, "big", signed=True))
        if self.sessionId is not None:
            session_id_bytes = str.encode(self.sessionId)
            size = len(session_id_bytes).to_bytes(4, "big", signed=True)
            option_bytes.extend(size)
            option_bytes.extend(session_id_bytes)
        if self.sequence is not None:
            option_bytes.extend(self.sequence.to_bytes(4, "big", signed=True))
        return option_bytes


class Response:
    def __init__(self, header: Header, optional: Optional):
        self.optional = optional
        self.header = header
        self.payload: bytes | None = None

    def __str__(self):
        return super().__str__()


class TTSProvider(TTSProviderBase):
    def __init__(self, config, delete_audio_file):
        super().__init__(config, delete_audio_file)
        self.ws = None
        self.interface_type = InterfaceType.DUAL_STREAM
        self._monitor_task = None  # 监听任务引用
        self._session_error = None  # 会话失败时的错误信息
        self._session_started_event = None
        self._session_finished_event = None
        self._active_session_id = None
        self._pending_session_id = None
        self._session_start_attempted_id = None
        self._upstream_session_id = None
        self._session_audio_state_lock = threading.Lock()
        self._session_audio_states = OrderedDict()
        self.api_key = config.get("api_key")
        self.resource_id = self._normalize_resource_id(config.get("resource_id"))
        self.report_on_last = True
        self.activate_session = False
        if config.get("private_voice"):
            self.voice = config.get("private_voice")
        else:
            self.voice = config.get("speaker")
        if not self.voice:
            self.voice = self._default_voice_for_resource()

        # 默认 audio_params 配置
        default_audio_params = {
            "speech_rate": 0,
            "loudness_rate": 0
        }

        # 默认 additions 配置
        default_additions = {
            "aigc_metadata": {},
            "cache_config": {},
            "post_process": {
                "pitch": 0
            }
        }

        # 合并用户配置。智控台可能把高级 JSON 字段以字符串下发，先统一解析成字典。
        self.audio_params = {
            **default_audio_params,
            **self._mapping_config(config, "audio_params"),
        }
        self.additions = {
            **default_additions,
            **self._mapping_config(config, "additions"),
        }
        self.additions["post_process"] = self._mapping_value(
            self.additions.get("post_process"), "additions.post_process"
        )
        self._normalize_audio_params()
        self.audio_format = "pcm"
        self.audio_file_type = self.audio_format
        self._merge_flat_audio_params(config)
        self._normalize_audio_params()

        # 应用百分比调整（如果存在），否则使用公有化配置
        if "ttsVolume" in config:
            self.audio_params["loudness_rate"] = int(convert_percentage_to_range(
                config["ttsVolume"], min_val=-50, max_val=100, base_val=0
            ))

        if "ttsRate" in config:
            self.audio_params["speech_rate"] = int(convert_percentage_to_range(
                config["ttsRate"], min_val=-50, max_val=100, base_val=0
            ))

        if "ttsPitch" in config:
            self.additions["post_process"]["pitch"] = int(convert_percentage_to_range(
                config["ttsPitch"], min_val=-12, max_val=12, base_val=0
            ))

        self.ws_url = HUOSHAN_DOUBLE_STREAM_WS_URL
        self.enable_ws_reuse = True
        self.tts_text = ""
        legacy_session_timeout = config.get("session_timeout_seconds")
        if legacy_session_timeout is not None:
            legacy_session_timeout = self._positive_timeout(
                legacy_session_timeout,
                "session_timeout_seconds",
            )
        self.connection_timeout_seconds = self._positive_timeout(
            config.get("connection_timeout_seconds", 10),
            "connection_timeout_seconds",
        )
        self.session_start_timeout_seconds = self._positive_timeout(
            config.get(
                "session_start_timeout_seconds",
                legacy_session_timeout if legacy_session_timeout is not None else 10,
            ),
            "session_start_timeout_seconds",
        )
        self.session_finish_timeout_seconds = self._positive_timeout(
            config.get(
                "session_finish_timeout_seconds",
                max(legacy_session_timeout or 0, 30),
            ),
            "session_finish_timeout_seconds",
        )
        # 保留旧属性供外部诊断代码读取；新逻辑分别使用启动和结束预算。
        self.session_timeout_seconds = self.session_start_timeout_seconds
        self.response_timeout_seconds = self._positive_timeout(
            config.get("response_timeout_seconds", 15),
            "response_timeout_seconds",
        )
        self.send_timeout_seconds = self._positive_timeout(
            config.get("send_timeout_seconds", 5),
            "send_timeout_seconds",
        )
        try:
            self.stream_prebuffer_ms = float(
                config.get("stream_prebuffer_ms", 0)
            )
        except (TypeError, ValueError) as exc:
            raise ValueError(
                "stream_prebuffer_ms 必须是大于或等于 0 的有限数字"
            ) from exc
        if (
            not math.isfinite(self.stream_prebuffer_ms)
            or self.stream_prebuffer_ms < 0
        ):
            raise ValueError("stream_prebuffer_ms 必须是大于或等于 0 的有限数字")
        self.stream_prebuffer_frames = math.ceil(self.stream_prebuffer_ms / 60)

        model_key_msg = check_model_key("TTS", self.api_key)
        if model_key_msg:
            logger.bind(tag=TAG).error(model_key_msg)
        if not self.api_key:
            raise ValueError("豆包 TTS 需要配置 api_key")

    def _normalize_resource_id(self, value):
        resource_id = str(value or "").strip() or DOUBAO_SEED_TTS_2_RESOURCE_ID
        if resource_id not in SUPPORTED_DOUBAO_TTS_RESOURCES:
            supported = ", ".join(SUPPORTED_DOUBAO_TTS_RESOURCES)
            raise ValueError(f"不支持的豆包 TTS resource_id: {resource_id}，仅支持 {supported}")
        return resource_id

    def _default_voice_for_resource(self):
        if self.resource_id == DOUBAO_BIGTTS_RESOURCE_ID:
            return "zh_female_wanwanxiaohe_moon_bigtts"
        return "zh_female_sophie_uranus_bigtts"

    def _build_ws_headers(self):
        ws_header = {
            "X-Api-Resource-Id": self.resource_id,
            "X-Api-Connect-Id": str(uuid.uuid4()),
            "X-Control-Require-Usage-Tokens-Return": "*",
        }
        ws_header["X-Api-Key"] = self.api_key
        return ws_header

    def _mapping_config(self, config, key):
        return self._mapping_value(config.get(key), key)

    def _mapping_value(self, value, field_name):
        if value in (None, ""):
            return {}
        if isinstance(value, Mapping):
            return dict(value)
        if isinstance(value, str):
            text = value.strip()
            if not text or text.lower() in ("null", "undefined"):
                return {}
            try:
                parsed = json.loads(text)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{field_name} 必须是 JSON 对象字符串：{exc.msg}") from exc
            if parsed is None:
                return {}
            if isinstance(parsed, Mapping):
                return dict(parsed)
        raise ValueError(f"{field_name} 必须是 JSON 对象，当前类型为 {type(value).__name__}")

    def _normalize_audio_params(self):
        for key in ("speech_rate", "loudness_rate", "sample_rate", "emotion_scale"):
            if key in self.audio_params:
                self.audio_params[key] = self._numeric_value(self.audio_params[key], key)

        post_process = self.additions.get("post_process")
        if isinstance(post_process, Mapping) and "pitch" in post_process:
            post_process["pitch"] = self._numeric_value(post_process["pitch"], "pitch")

    def _numeric_value(self, value, field_name):
        if value in (None, ""):
            return value
        if isinstance(value, bool):
            raise ValueError(f"{field_name} 必须是数字，当前类型为 boolean")
        if isinstance(value, (int, float)):
            return value
        if isinstance(value, str):
            text = value.strip()
            if not text:
                return value
            try:
                number = float(text)
            except ValueError as exc:
                raise ValueError(f"{field_name} 必须是数字，当前值为 {value!r}") from exc
            return int(number) if number.is_integer() else number
        raise ValueError(f"{field_name} 必须是数字，当前类型为 {type(value).__name__}")

    def _merge_flat_audio_params(self, config):
        for key in (
            "speech_rate",
            "loudness_rate",
            "sample_rate",
            "emotion",
            "emotion_scale",
            "language",
        ):
            value = config.get(key)
            if value not in (None, ""):
                if key in ("emotion", "language"):
                    self.audio_params[key] = value
                else:
                    self.audio_params[key] = self._numeric_value(value, key)

        pitch = config.get("pitch")
        if pitch not in (None, ""):
            post_process = self._mapping_value(
                self.additions.get("post_process"), "additions.post_process"
            )
            self.additions["post_process"] = post_process
            post_process["pitch"] = self._numeric_value(pitch, "pitch")

    async def open_audio_channels(self, conn):
        try:
            await super().open_audio_channels(conn)
            # 更新 audio_params 中的采样率为实际的 conn.sample_rate
            self.audio_params["sample_rate"] = conn.sample_rate
        except Exception as e:
            logger.bind(tag=TAG).error(f"Failed to open audio channels: {str(e)}")
            self.ws = None
            raise

    async def _ensure_connection(self):
        """建立新的WebSocket连接，并启动监听任务（仅第一次）"""
        try:
            if self.ws:
                if self.enable_ws_reuse:
                    logger.bind(tag=TAG).debug(f"使用已有链接...")
                    return self.ws
                else:
                    try:
                        await self.finish_connection()
                    except Exception:
                        pass
            logger.bind(tag=TAG).debug("开始建立新连接...")
            ws_header = self._build_ws_headers()
            self.ws = await asyncio.wait_for(
                websockets.connect(
                    self.ws_url,
                    additional_headers=ws_header,
                    max_size=1000000000,
                ),
                timeout=self.connection_timeout_seconds,
            )
            logger.bind(tag=TAG).debug("WebSocket连接建立成功")
            await self.start_connection()
            await self._wait_for_event(
                self.ws,
                EVENT_ConnectionStarted,
                timeout=self.connection_timeout_seconds,
            )
            logger.bind(tag=TAG).debug("TTS连接启动成功")
            
            return self.ws
        except Exception as e:
            logger.bind(tag=TAG).error(f"建立连接失败: {str(e)}")
            if self.ws:
                try:
                    await self.ws.close()
                except Exception:
                    pass
            self.ws = None
            raise

    def _reset_session_waiters(self):
        self._session_error = None
        self._session_started_event = asyncio.Event()
        self._session_finished_event = asyncio.Event()

    def _wake_session_waiters(self):
        for event in (self._session_started_event, self._session_finished_event):
            if event is not None:
                event.set()

    async def _wait_for_session_event(self, event, stage, timeout_seconds):
        try:
            await asyncio.wait_for(
                event.wait(),
                timeout=timeout_seconds,
            )
        except asyncio.TimeoutError as exc:
            raise TimeoutError(
                f"{stage}超时（{timeout_seconds:.1f}s）"
            ) from exc
        if self._session_error:
            raise RuntimeError(self._session_error)

    def _wait_threadsafe_future(self, future, stage, timeout_seconds=None):
        wait_timeout = self._positive_timeout(
            timeout_seconds if timeout_seconds is not None else self.tts_timeout,
            f"{stage}线程等待超时",
        )
        try:
            return future.result(timeout=wait_timeout)
        except (concurrent.futures.TimeoutError, TimeoutError) as exc:
            future.cancel()
            close_future = None
            if self.conn and self.conn.loop and not self.conn.loop.is_closed():
                close_future = asyncio.run_coroutine_threadsafe(
                    self.close(), self.conn.loop
                )
                try:
                    close_future.result(timeout=min(wait_timeout, self.tts_timeout))
                except Exception:
                    close_future.cancel()
            raise TimeoutError(
                f"{stage}超时（{wait_timeout:.1f}s），已取消并清理TTS连接"
            ) from exc

    @staticmethod
    def _new_session_audio_state(generation=None):
        return {
            "first_enqueued": False,
            "last_enqueued": False,
            "content_requested": False,
            "generation": generation,
            "first_text": None,
            "prebuffer_frames": [],
            "prebuffer_released": False,
            "terminal_error": None,
        }

    def _prepare_session_audio_state(self, session_id, generation=None):
        with self._session_audio_state_lock:
            if session_id not in self._session_audio_states:
                self._session_audio_states[session_id] = (
                    self._new_session_audio_state(generation)
                )
            elif generation is not None:
                self._session_audio_states[session_id]["generation"] = generation
            self._session_audio_states.move_to_end(session_id)
            while len(self._session_audio_states) > 32:
                self._session_audio_states.popitem(last=False)

    def _session_audio_state(self, session_id):
        with self._session_audio_state_lock:
            state = self._session_audio_states.get(session_id)
            return dict(state) if state is not None else None

    def _mark_session_content_requested(
        self, session_id, *, text=None, generation=None
    ):
        with self._session_audio_state_lock:
            state = self._session_audio_states.setdefault(
                session_id,
                self._new_session_audio_state(generation),
            )
            state["content_requested"] = True
            if generation is not None:
                state["generation"] = generation
            if text and not state["first_text"]:
                state["first_text"] = text
            self._session_audio_states.move_to_end(session_id)

        if text:
            self.store_tts_preview(session_id, text)

    def _prepare_pending_session(self, session_id, generation=None):
        if self._pending_session_id == session_id:
            self._prepare_session_audio_state(session_id, generation)
            return True

        state = self._session_audio_state(session_id)
        if state is not None and (
            state["first_enqueued"] or state["last_enqueued"]
        ):
            logger.bind(tag=TAG).warning(
                f"忽略已发布音频边界的重复FIRST | session_id={session_id}"
            )
            return False

        self.reset_stream_state()
        self.current_sentence_id = session_id
        self._pending_session_id = session_id
        self._session_start_attempted_id = None
        self.before_stop_play_files.clear()
        self._prepare_session_audio_state(session_id, generation)
        return True

    def _has_speakable_text(self, text):
        if not text:
            return False
        filtered_text = self._clean_synthesis_text(text)
        return bool(filtered_text and filtered_text.strip())

    def _start_pending_session_once(self, session_id):
        if self._pending_session_id != session_id:
            return False
        if self._upstream_session_id == session_id:
            return True
        if self._session_start_attempted_id == session_id:
            return False

        self._session_start_attempted_id = session_id
        logger.bind(tag=TAG).debug("首段有效内容到达，开始启动TTS会话...")
        tts_session_started_at = time.monotonic()
        future = asyncio.run_coroutine_threadsafe(
            self.start_session(session_id),
            loop=self.conn.loop,
        )
        self._wait_threadsafe_future(
            future,
            "启动TTS会话",
            self.connection_timeout_seconds
            + self.session_start_timeout_seconds
            + 2,
        )
        self._upstream_session_id = session_id
        logger.bind(tag=TAG).info(
            f"TTS会话启动成功 | elapsed={time.monotonic() - tts_session_started_at:.3f}s | "
            f"enable_ws_reuse={self.enable_ws_reuse}"
        )
        return True

    def _clear_pending_session(self, session_id):
        if self._pending_session_id == session_id:
            self._pending_session_id = None
            self._session_start_attempted_id = None
        if self._upstream_session_id == session_id:
            self._upstream_session_id = None

    def _cancel_current_session_for_abort(self):
        """Cancel the upstream session at most once for one client abort."""
        active_session_id = self._upstream_session_id
        pending_session_id = self._pending_session_id
        self._upstream_session_id = None
        self._pending_session_id = None
        self._session_start_attempted_id = None
        self.before_stop_play_files.clear()
        self._reset_opus_audio_stream()

        if active_session_id and self.enable_ws_reuse:
            asyncio.run_coroutine_threadsafe(
                self.cancel_session(active_session_id),
                loop=self.conn.loop,
            )
        elif active_session_id:
            asyncio.run_coroutine_threadsafe(
                self.finish_connection(),
                loop=self.conn.loop,
            )
        elif pending_session_id:
            logger.bind(tag=TAG).debug(
                "TTS会话尚未懒启动，打断时无需取消上游会话 | "
                f"session_id={pending_session_id}"
            )

    def _enqueue_session_first_once(self, session_id, text=None):
        if not session_id or not self._has_speakable_text(text):
            return False
        with self._session_audio_state_lock:
            state = self._session_audio_states.setdefault(
                session_id,
                self._new_session_audio_state(),
            )
            if state["first_enqueued"] or state["last_enqueued"]:
                return False
            self.tts_audio_queue.put(
                (
                    SentenceType.FIRST,
                    [],
                    text,
                    session_id,
                    False,
                    state["generation"],
                    None,
                )
            )
            state["first_enqueued"] = True
            state["prebuffer_released"] = True
            self._session_audio_states.move_to_end(session_id)
            return True

    def _handle_session_opus(self, session_id, opus_data):
        """Publish Opus immediately; optional buffering remains configurable."""
        if not session_id or not opus_data:
            return False
        with self._session_audio_state_lock:
            state = self._session_audio_states.get(session_id)
            if state is None or state["last_enqueued"]:
                return False
            self._record_turn_metric(
                "record_tts_audio_frame", len(opus_data), session_id
            )
            if state["prebuffer_released"]:
                self.tts_audio_queue.put(
                    (
                        SentenceType.MIDDLE,
                        opus_data,
                        None,
                        session_id,
                        False,
                        state["generation"],
                        None,
                    )
                )
                return True

            state["prebuffer_frames"].append(opus_data)
            if len(state["prebuffer_frames"]) < self.stream_prebuffer_frames:
                return True
            return self._release_session_prebuffer_locked(session_id, state)

    def _release_session_prebuffer(self, session_id, *, force=False):
        with self._session_audio_state_lock:
            state = self._session_audio_states.get(session_id)
            if state is None or state["last_enqueued"]:
                return False
            if state["prebuffer_released"]:
                return state["first_enqueued"]
            if not force and (
                len(state["prebuffer_frames"]) < self.stream_prebuffer_frames
            ):
                return False
            return self._release_session_prebuffer_locked(session_id, state)

    def _release_session_prebuffer_locked(self, session_id, state):
        text = state["first_text"] or self.get_tts_text(session_id)
        if not self._has_speakable_text(text) or not state["prebuffer_frames"]:
            return False
        generation = state["generation"]
        self.tts_audio_queue.put(
            (
                SentenceType.FIRST,
                [],
                text,
                session_id,
                False,
                generation,
                None,
            )
        )
        for frame in state["prebuffer_frames"]:
            self.tts_audio_queue.put(
                (
                    SentenceType.MIDDLE,
                    frame,
                    None,
                    session_id,
                    False,
                    generation,
                    None,
                )
            )
        state["prebuffer_frames"].clear()
        state["first_enqueued"] = True
        state["prebuffer_released"] = True
        self._session_audio_states.move_to_end(session_id)
        return True

    def _finalize_session_audio_once(self, session_id, *, normal, reason):
        """Publish the provider audio boundary exactly once for one TTS turn."""
        if not session_id:
            return False
        conn = self.conn
        if conn is not None:
            stop_event = getattr(conn, "stop_event", None)
            if stop_event is not None and stop_event.is_set():
                return False
            if getattr(conn, "client_abort", False):
                return False

        if normal:
            self._flush_opus_audio_stream(session_id)
            self._release_session_prebuffer(session_id, force=True)
        else:
            self._reset_opus_audio_stream()

        with self._session_audio_state_lock:
            state = self._session_audio_states.setdefault(
                session_id,
                self._new_session_audio_state(),
            )
            if state["last_enqueued"]:
                return False
            terminal_error = None if normal else f"tts_error:{reason}"
            state["terminal_error"] = terminal_error
            if not normal:
                state["prebuffer_frames"].clear()

            # FILE 音频已经完整编码，可以在 TTS 异常收尾时继续播放；如果
            # 本轮没有厂商 TTS 音频，则用第一个文件建立设备 speaking 边界。
            if self.before_stop_play_files and not state["first_enqueued"]:
                first_text = self.before_stop_play_files[0][1]
                if self._has_speakable_text(first_text):
                    self.tts_audio_queue.put(
                        (
                            SentenceType.FIRST,
                            [],
                            first_text,
                            session_id,
                            False,
                            state["generation"],
                            None,
                        )
                    )
                    state["first_enqueued"] = True

            if normal and not state["first_enqueued"]:
                return False

            for audio_datas, text in self.before_stop_play_files:
                self.tts_audio_queue.put(
                    (
                        SentenceType.MIDDLE,
                        audio_datas,
                        text,
                        session_id,
                        False,
                        state["generation"],
                        None,
                    )
                )
            self.before_stop_play_files.clear()
            self.tts_audio_queue.put(
                (
                    SentenceType.LAST,
                    [],
                    None,
                    session_id,
                    False,
                    state["generation"],
                    terminal_error,
                )
            )
            state["last_enqueued"] = True
            self._session_audio_states.move_to_end(session_id)

        log_method = (
            logger.bind(tag=TAG).info
            if normal
            else logger.bind(tag=TAG).warning
        )
        log_method(
            f"TTS会话音频边界已收尾 | session_id={session_id} | reason={reason}"
        )
        return True
    
    async def finish_connection(self):
        """发送 FinishConnection 事件，等待服务端返回 EVENT_ConnectionFinished"""
        try:
            if self.ws:
                logger.bind(tag=TAG).debug("开始关闭连接...")
                header = Header(
                    message_type=FULL_CLIENT_REQUEST,
                    message_type_specific_flags=MsgTypeFlagWithEvent,
                    serial_method=JSON,
                ).as_bytes()
                optional = Optional(event=EVENT_FinishConnection).as_bytes()
                payload = str.encode("{}")
                await self.send_event(self.ws, header, optional, payload)
        except Exception:
            pass

    def tts_text_priority_thread(self):
        """火山引擎双流式TTS的文本处理线程"""
        while not self.conn.stop_event.is_set():
            try:
                message = self.tts_text_queue.get(timeout=1)

                if self.conn.client_abort:
                    try:
                        logger.bind(tag=TAG).info("收到打断信息，终止TTS文本处理线程")
                        self._cancel_current_session_for_abort()
                        continue
                    except Exception as e:
                        logger.bind(tag=TAG).error(f"取消TTS会话失败: {str(e)}")
                        continue

                # 过滤旧消息：检查sentence_id是否匹配
                if message.sentence_id != self.conn.sentence_id:
                    continue

                logger.bind(tag=TAG).debug(
                    f"收到TTS任务｜{message.sentence_type.name} ｜ {message.content_type.name} | 会话ID: {message.sentence_id}"
                )

                if message.sentence_type == SentenceType.FIRST:
                    if not self._prepare_pending_session(
                        message.sentence_id, message.generation
                    ):
                        continue

                elif ContentType.TEXT == message.content_type:
                    if self._has_speakable_text(message.content_detail):
                        try:
                            self._mark_session_content_requested(
                                message.sentence_id,
                                text=message.content_detail,
                                generation=message.generation,
                            )
                            if not self._start_pending_session_once(
                                message.sentence_id
                            ):
                                continue
                            if self.conn.client_abort:
                                self._cancel_current_session_for_abort()
                                continue
                            self._record_turn_metric(
                                "record_tts_start", message.sentence_id
                            )
                            log_text = self._text_for_log(
                                message.content_detail, message.sentence_id
                            )
                            logger.bind(tag=TAG).debug(
                                f"开始发送TTS文本: {log_text}"
                            )
                            tts_text_started_at = time.monotonic()
                            future = asyncio.run_coroutine_threadsafe(
                                self.text_to_speak(
                                    message.content_detail,
                                    message.sentence_id,
                                ),
                                loop=self.conn.loop,
                            )
                            self._wait_threadsafe_future(future, "发送TTS文本")
                            if self.conn.client_abort:
                                self._cancel_current_session_for_abort()
                                continue
                            logger.bind(tag=TAG).info(
                                f"TTS文本发送完成 | elapsed={time.monotonic() - tts_text_started_at:.3f}s | "
                                f"chars={len(message.content_detail)}"
                            )
                        except Exception as e:
                            logger.bind(tag=TAG).error(f"发送TTS文本失败: {str(e)}")
                            self._finalize_session_audio_once(
                                message.sentence_id,
                                normal=False,
                                reason=f"text_send_error:{type(e).__name__}",
                            )
                            self._clear_pending_session(message.sentence_id)
                            continue

                elif ContentType.FILE == message.content_type:
                    logger.bind(tag=TAG).info(
                        f"添加音频文件到待播放列表: {message.content_file}"
                    )
                    if message.content_file and os.path.exists(message.content_file):
                        try:
                            self._start_pending_session_once(message.sentence_id)
                            if self.conn.client_abort:
                                self._cancel_current_session_for_abort()
                                continue
                        except Exception as e:
                            # 本地文件播放不依赖厂商音频；即使上游启动失败，
                            # 仍保留文件并在 LAST 时发布完整设备音频边界。
                            logger.bind(tag=TAG).error(
                                f"为音频文件启动TTS会话失败: {str(e)}"
                            )
                        # 先处理文件音频数据
                        self._process_audio_file_stream(message.content_file, callback=lambda audio_data: self.handle_audio_file(audio_data, message.content_detail))
                if message.sentence_type == SentenceType.LAST:
                    self._prepare_session_audio_state(
                        message.sentence_id, message.generation
                    )
                    if self._upstream_session_id != message.sentence_id:
                        if self.before_stop_play_files:
                            self._finalize_session_audio_once(
                                message.sentence_id,
                                normal=True,
                                reason="local_file_without_upstream_session",
                            )
                        else:
                            logger.bind(tag=TAG).debug(
                                "空TTS轮次结束，未创建上游会话 | "
                                f"session_id={message.sentence_id}"
                            )
                        self._clear_pending_session(message.sentence_id)
                        continue
                    try:
                        logger.bind(tag=TAG).debug("开始结束TTS会话...")
                        tts_finish_started_at = time.monotonic()
                        future = asyncio.run_coroutine_threadsafe(
                            self.finish_session(message.sentence_id),
                            loop=self.conn.loop,
                        )
                        self._wait_threadsafe_future(
                            future,
                            "结束TTS会话",
                            self.session_finish_timeout_seconds + 2,
                        )
                        logger.bind(tag=TAG).info(
                            f"TTS会话结束成功 | elapsed={time.monotonic() - tts_finish_started_at:.3f}s"
                        )
                    except Exception as e:
                        logger.bind(tag=TAG).error(f"结束TTS会话失败: {str(e)}")
                        self._finalize_session_audio_once(
                            message.sentence_id,
                            normal=False,
                            reason=f"finish_error:{type(e).__name__}",
                        )
                    finally:
                        self._clear_pending_session(message.sentence_id)

            except queue.Empty:
                continue
            except Exception as e:
                logger.bind(tag=TAG).error(
                    f"处理TTS文本失败: {str(e)}, 类型: {type(e).__name__}, 堆栈: {traceback.format_exc()}"
                )
                continue

    async def text_to_speak(self, text, session_id):
        """发送文本到TTS服务"""
        try:
            # 建立新连接
            if self.ws is None:
                logger.bind(tag=TAG).warning(f"WebSocket连接不存在，终止发送文本")
                return

            filtered_text = self._clean_synthesis_text(text)

            if filtered_text:
                # 使用滑动窗口匹配处理跨分片的替换词
                confirmed_texts, self._pending_prefix = self._match_stream_text(filtered_text)

                # 发送每个确定的文本片段
                for txt in confirmed_texts:
                    if txt and self.ws:
                        await self.send_text(
                            self.voice,
                            txt,
                            session_id or self._active_session_id,
                        )

            return
        except Exception as e:
            logger.bind(tag=TAG).error(f"发送TTS文本失败: {str(e)}")
            if self.ws:
                try:
                    await self.ws.close()
                except Exception:
                    pass
                self.ws = None
            raise

    async def start_session(self, session_id):
        logger.bind(tag=TAG).debug(f"开始会话～～{session_id}")
        try:       
            # 上个会话处于激活状态时关闭上个连接新建链接
            if self.activate_session:
                await self.close()
            
            # 确保连接建立
            await self._ensure_connection()

            self._prepare_session_audio_state(session_id)
            self._active_session_id = session_id
            self._reset_session_waiters()
            self.activate_session = True
            if self._monitor_task is None or self._monitor_task.done():
                self._monitor_task = asyncio.create_task(
                    self._start_monitor_tts_response()
                )

            header = Header(
                message_type=FULL_CLIENT_REQUEST,
                message_type_specific_flags=MsgTypeFlagWithEvent,
                serial_method=JSON,
            ).as_bytes()
            optional = Optional(
                event=EVENT_StartSession, sessionId=session_id
            ).as_bytes()
            payload = self.get_payload_bytes(
                event=EVENT_StartSession, speaker=self.voice
            )
            await self.send_event(self.ws, header, optional, payload)
            logger.bind(tag=TAG).debug("会话启动请求已发送")
            await self._wait_for_session_event(
                self._session_started_event,
                "等待TTS会话启动响应",
                self.session_start_timeout_seconds,
            )
            logger.bind(tag=TAG).debug("TTS会话启动成功")
        except Exception as e:
            logger.bind(tag=TAG).error(f"启动会话失败: {str(e)}")
            # 确保清理资源
            await self.close()
            raise

    async def finish_session(self, session_id):
        logger.bind(tag=TAG).debug(f"关闭会话～～{session_id}")
        try:
            if self.ws:
                header = Header(
                    message_type=FULL_CLIENT_REQUEST,
                    message_type_specific_flags=MsgTypeFlagWithEvent,
                    serial_method=JSON,
                ).as_bytes()
                optional = Optional(
                    event=EVENT_FinishSession, sessionId=session_id
                ).as_bytes()
                payload = str.encode("{}")
                await self.send_event(self.ws, header, optional, payload)
                logger.bind(tag=TAG).debug("会话结束请求已发送")
                await self._wait_for_session_event(
                    self._session_finished_event,
                    "等待TTS会话结束响应",
                    self.session_finish_timeout_seconds,
                )

        except Exception as e:
            logger.bind(tag=TAG).error(f"关闭会话失败: {str(e)}")
            # 确保清理资源
            await self.close()
            raise

    async def cancel_session(self,session_id):
        logger.bind(tag=TAG).debug(f"取消会话，释放服务端资源～～{session_id}")
        try:
            if self.ws:
                header = Header(
                    message_type=FULL_CLIENT_REQUEST,
                    message_type_specific_flags=MsgTypeFlagWithEvent,
                    serial_method=JSON,
                ).as_bytes()
                optional = Optional(
                    event=EVENT_CancelSession, sessionId=session_id
                ).as_bytes()
                payload = str.encode("{}")
                await self.send_event(self.ws, header, optional, payload)
                logger.bind(tag=TAG).debug("会话取消请求已发送")
        except Exception as e:
            logger.bind(tag=TAG).error(f"取消会话失败: {str(e)}")
            # 确保清理资源
            await self.close()
            raise

    async def close(self):
        """资源清理方法"""
        await super().close()
        self.activate_session = False
        self._active_session_id = None
        self._upstream_session_id = None
        # 取消监听任务
        if self._monitor_task:
            try:
                self._monitor_task.cancel()
                await self._monitor_task
            except asyncio.CancelledError:
                pass
            except Exception as e:
                logger.bind(tag=TAG).warning(f"关闭时取消监听任务错误: {e}")
            self._monitor_task = None

        if self.ws:
            try:
                await self.ws.close()
            except Exception:
                pass
            self.ws = None
        self._wake_session_waiters()

    async def _start_monitor_tts_response(self):
        """监听TTS响应 - 长期运行"""
        try:
            while not self.conn.stop_event.is_set():
                try:
                    # 确保 `recv()` 运行在同一个 event loop
                    if self.activate_session:
                        msg = await asyncio.wait_for(
                            self.ws.recv(),
                            timeout=self.response_timeout_seconds,
                        )
                    else:
                        msg = await self.ws.recv()
                    res = self.parser_response(msg)
                    self.print_response(res, "send_text res:")

                    # 优先处理连接级别事件
                    if res.optional.event == EVENT_ConnectionFinished:
                        logger.bind(tag=TAG).debug(f"链接关闭成功～～")
                        break

                    active_session_id = self._active_session_id
                    response_session_id = res.optional.sessionId
                    if (
                        response_session_id
                        and response_session_id != active_session_id
                    ):
                        logger.bind(tag=TAG).debug(
                            "忽略非当前TTS会话响应 | "
                            f"event={res.optional.event} | "
                            f"response_session_id={response_session_id} | "
                            f"active_session_id={active_session_id}"
                        )
                        continue

                    if res.optional.event == EVENT_SessionStarted:
                        if self._session_started_event is not None:
                            self._session_started_event.set()
                        continue

                    if res.optional.event == EVENT_SessionCanceled:
                        logger.bind(tag=TAG).debug(f"释放服务端资源成功～～")
                        self._reset_opus_audio_stream()
                        self.activate_session = False
                        self._active_session_id = None
                        if self._session_finished_event is not None:
                            self._session_finished_event.set()
                    elif (
                        res.optional.event == EVENT_TTSResponse
                        and res.header.message_type == AUDIO_ONLY_RESPONSE
                    ):
                        session_id = response_session_id or active_session_id
                        tts_text = self.get_tts_text(session_id)
                        if tts_text:
                            log_text = self._text_for_log(tts_text, session_id)
                            logger.bind(tag=TAG).info(
                                f"TTS会话继续生成音频： {log_text}"
                            )
                        self.wav_to_opus_data_audio_raw_stream(
                            res.payload,
                            callback=lambda frame: self._handle_session_opus(
                                session_id, frame
                            ),
                        )
                    elif res.optional.event == EVENT_SessionFailed:
                        error_msg = ""
                        try:
                            if res.optional.response_meta_json:
                                error_meta = json.loads(res.optional.response_meta_json)
                                error_msg = error_meta.get("message", res.optional.response_meta_json)
                        except Exception:
                            error_msg = str(res.optional.response_meta_json or "")
                        self._session_error = (
                            f"TTS 会话启动失败（resource_id={self.resource_id}, "
                            f"speaker={self.voice}）：{error_msg or '未知错误'}"
                        )
                        logger.bind(tag=TAG).error(self._session_error)
                        self._mark_external_tts_failed(
                            response_session_id or active_session_id
                        )
                        self.activate_session = False
                        self._active_session_id = None
                        self._finalize_session_audio_once(
                            response_session_id or active_session_id,
                            normal=False,
                            reason="session_failed",
                        )
                        self._wake_session_waiters()
                    elif res.optional.event == EVENT_SessionFinished:
                        logger.bind(tag=TAG).debug(f"会话结束～～")
                        self.activate_session = False
                        session_id = response_session_id or active_session_id
                        self._record_turn_metric(
                            "record_tts_generation_complete", session_id
                        )
                        self._active_session_id = None
                        state = self._session_audio_state(session_id)
                        content_requested = bool(
                            state is not None and state["content_requested"]
                        )
                        finalized = self._finalize_session_audio_once(
                            session_id,
                            normal=True,
                            reason="session_finished",
                        )
                        if content_requested and not finalized:
                            self._session_error = (
                                "TTS会话已结束但未返回任何音频"
                            )
                            logger.bind(tag=TAG).error(
                                f"{self._session_error} | session_id={session_id}"
                            )
                            self._finalize_session_audio_once(
                                session_id,
                                normal=False,
                                reason="zero_audio",
                            )
                        if self._session_finished_event is not None:
                            self._session_finished_event.set()
                        # 非复用模式下，会话结束后发送 FinishConnection
                        if not self.enable_ws_reuse:
                            await self.finish_connection()
                except websockets.ConnectionClosed:
                    logger.bind(tag=TAG).warning("WebSocket连接已关闭")
                    self._session_error = "TTS WebSocket连接已关闭"
                    self._finalize_session_audio_once(
                        self._active_session_id,
                        normal=False,
                        reason="websocket_closed",
                    )
                    self._active_session_id = None
                    self._wake_session_waiters()
                    break
                except asyncio.TimeoutError:
                    logger.bind(tag=TAG).error(
                        "等待TTS下行响应超时: "
                        f"timeout={self.response_timeout_seconds:.1f}s"
                    )
                    self._session_error = (
                        "等待TTS下行响应超时"
                        f"（{self.response_timeout_seconds:.1f}s）"
                    )
                    self._finalize_session_audio_once(
                        self._active_session_id,
                        normal=False,
                        reason="response_timeout",
                    )
                    self._active_session_id = None
                    self._wake_session_waiters()
                    break
                except Exception as e:
                    logger.bind(tag=TAG).error(
                        f"Error in _start_monitor_tts_response: {e}"
                    )
                    self._session_error = (
                        f"TTS下行响应异常: {type(e).__name__}"
                    )
                    self._finalize_session_audio_once(
                        self._active_session_id,
                        normal=False,
                        reason=f"response_error:{type(e).__name__}",
                    )
                    self._active_session_id = None
                    self._wake_session_waiters()
                    traceback.print_exc()
                    break
            # 连接异常时关闭WebSocket
            if self.ws:
                try:
                    await self.ws.close()
                except Exception:
                    pass
                self.ws = None
        # 监听任务退出时清理引用
        finally:
            if self.activate_session:
                self._finalize_session_audio_once(
                    self._active_session_id,
                    normal=False,
                    reason="monitor_stopped",
                )
            self.activate_session = False
            self._active_session_id = None
            self._wake_session_waiters()
            self._monitor_task = None

    def _flush_opus_audio_stream(self, session_id=None):
        """Flush the final partial PCM frame before publishing the session LAST marker."""
        if not getattr(self, "opus_encoder", None):
            return
        resolved_session_id = session_id or self._active_session_id
        try:
            self.wav_to_opus_data_audio_raw_stream(
                b"",
                is_end=True,
                callback=lambda frame: self._handle_session_opus(
                    resolved_session_id, frame
                ),
            )
        except Exception as exc:
            logger.bind(tag=TAG).warning(f"冲刷TTS Opus尾帧失败: {exc}")

    def _reset_opus_audio_stream(self):
        """Discard audio buffered by an aborted/failed session."""
        encoder = getattr(self, "opus_encoder", None)
        if not encoder or not hasattr(encoder, "reset_state"):
            return
        try:
            encoder.reset_state()
        except Exception as exc:
            logger.bind(tag=TAG).warning(f"重置TTS Opus编码器失败: {exc}")

    async def send_event(
        self,
        ws: websockets.WebSocketClientProtocol,
        header: bytes,
        optional: bytes | None = None,
        payload: bytes = None,
    ):
        try:
            full_client_request = bytearray(header)
            if optional is not None:
                full_client_request.extend(optional)
            if payload is not None:
                payload_size = len(payload).to_bytes(4, "big", signed=True)
                full_client_request.extend(payload_size)
                full_client_request.extend(payload)
            await asyncio.wait_for(
                ws.send(full_client_request),
                timeout=self.send_timeout_seconds,
            )
        except asyncio.TimeoutError as exc:
            logger.bind(tag=TAG).error(
                "发送TTS WebSocket事件超时: "
                f"timeout={self.send_timeout_seconds:.1f}s"
            )
            raise TimeoutError(
                f"发送TTS WebSocket事件超时（{self.send_timeout_seconds:.1f}s）"
            ) from exc
        except websockets.ConnectionClosed:
            logger.bind(tag=TAG).error(f"ConnectionClosed")
            raise

    async def send_text(self, speaker: str, text: str, session_id):
        header = Header(
            message_type=FULL_CLIENT_REQUEST,
            message_type_specific_flags=MsgTypeFlagWithEvent,
            serial_method=JSON,
        ).as_bytes()
        optional = Optional(event=EVENT_TaskRequest, sessionId=session_id).as_bytes()
        payload = self.get_payload_bytes(
            event=EVENT_TaskRequest, text=text, speaker=speaker
        )
        return await self.send_event(self.ws, header, optional, payload)

    async def _receive_response(self, ws: websockets.WebSocketClientProtocol, timeout=None) -> Response:
        if timeout:
            msg = await asyncio.wait_for(ws.recv(), timeout=timeout)
        else:
            msg = await ws.recv()
        return self.parser_response(msg)

    async def _wait_for_event(self, ws: websockets.WebSocketClientProtocol, event: int, timeout=None) -> Response:
        while True:
            res = await self._receive_response(ws, timeout=timeout)
            self.print_response(res, f"wait_event {event}:")
            if res.header.message_type == ERROR_INFORMATION:
                detail = res.payload.decode("utf-8", "ignore") if res.payload else ""
                raise RuntimeError(f"TTS WebSocket 错误: code={res.optional.errorCode}, detail={detail}")
            if res.optional.event in (EVENT_ConnectionFailed, EVENT_SessionFailed):
                detail = ""
                if res.payload:
                    detail = res.payload.decode("utf-8", "ignore")
                elif res.optional.response_meta_json:
                    detail = res.optional.response_meta_json
                raise RuntimeError(f"TTS 事件失败: event={res.optional.event}, detail={detail}")
            if res.optional.event == event:
                return res

    # 读取 res 数组某段 字符串内容
    def read_res_content(self, res: bytes, offset: int):
        content_size = int.from_bytes(res[offset : offset + 4], "big", signed=True)
        offset += 4
        content = res[offset : offset + content_size].decode('utf-8')
        offset += content_size
        return content, offset

    # 读取 payload
    def read_res_payload(self, res: bytes, offset: int):
        payload_size = int.from_bytes(res[offset : offset + 4], "big", signed=True)
        offset += 4
        payload = res[offset : offset + payload_size]
        offset += payload_size
        return payload, offset

    def parser_response(self, res) -> Response:
        if isinstance(res, str):
            raise RuntimeError(res)
        response = Response(Header(), Optional())
        # 解析结果
        # header
        header = response.header
        num = 0b00001111
        header.protocol_version = res[0] >> 4 & num
        header.header_size = res[0] & 0x0F
        header.message_type = (res[1] >> 4) & num
        header.message_type_specific_flags = res[1] & 0x0F
        header.serialization_method = (res[2] >> 4) & num
        header.message_compression = res[2] & 0x0F
        header.reserved = res[3]
        #
        offset = header.header_size * 4
        optional = response.optional
        if header.message_type in (FULL_SERVER_RESPONSE, AUDIO_ONLY_RESPONSE):
            # read event
            if header.message_type_specific_flags == MsgTypeFlagWithEvent:
                optional.event = int.from_bytes(res[offset:offset + 4], "big", signed=True)
                offset += 4
                if optional.event == EVENT_NONE:
                    return response
                elif optional.event == EVENT_ConnectionStarted:
                    optional.connectionId, offset = self.read_res_content(res, offset)
                    response.payload, offset = self.read_res_payload(res, offset)
                elif (
                    optional.event == EVENT_ConnectionFailed
                    or optional.event == EVENT_ConnectionFinished
                ):
                    optional.connectionId, offset = self.read_res_content(res, offset)
                    response.payload, offset = self.read_res_payload(res, offset)
                    optional.response_meta_json = response.payload.decode("utf-8", "ignore") if response.payload else None
                elif optional.event in (
                    EVENT_SessionStarted,
                    EVENT_SessionFailed,
                    EVENT_SessionFinished,
                ):
                    optional.sessionId, offset = self.read_res_content(res, offset)
                    response.payload, offset = self.read_res_payload(res, offset)
                    optional.response_meta_json = response.payload.decode("utf-8", "ignore") if response.payload else None
                else:
                    optional.sessionId, offset = self.read_res_content(res, offset)
                    response.payload, offset = self.read_res_payload(res, offset)

        elif header.message_type == ERROR_INFORMATION:
            optional.errorCode = int.from_bytes(
                res[offset : offset + 4], "big", signed=True
            )
            offset += 4
            response.payload, offset = self.read_res_payload(res, offset)
        return response

    async def start_connection(self):
        header = Header(
            message_type=FULL_CLIENT_REQUEST,
            message_type_specific_flags=MsgTypeFlagWithEvent,
            serial_method=JSON,
        ).as_bytes()
        optional = Optional(event=EVENT_Start_Connection).as_bytes()
        payload = str.encode("{}")
        return await self.send_event(self.ws, header, optional, payload)

    def print_response(self, res, tag_msg: str):
        logger.bind(tag=TAG).debug(f"===>{tag_msg} header:{res.header.__dict__}")
        logger.bind(tag=TAG).debug(f"===>{tag_msg} optional:{res.optional.__dict__}")

    def get_payload_bytes(
        self,
        uid="1234",
        event=EVENT_NONE,
        text="",
        speaker="",
        audio_format=None,
    ):
        audio_format = audio_format or self.audio_format
        # 构建 req_params
        req_params = {
            "text": text,
            "speaker": speaker,
            "audio_params": {**self.audio_params, "format": audio_format},
            "additions": json.dumps(self.additions)
        }
        
        return str.encode(
            json.dumps(
                {
                    "user": {"uid": uid},
                    "event": event,
                    "namespace": "BidirectionalTTS",
                    "req_params": req_params
                }
            )
        )

    def audio_to_opus_data_stream(
        self, audio_file_path, callback: Callable[[Any], Any] = None
    ):
        """重写父类方法：使用独立的临时编码器处理音频文件，避免与TTS流式编码器并发冲突。
        双流式TTS中，monitor任务在event loop线程接收TTS音频并使用self.opus_encoder编码，
        同时tts_text_priority_thread处理音乐文件也使用self.opus_encoder，
        共享的encoder.buffer非线程安全，并发访问会导致SILK resampler断言失败。
        """
        from core.utils.util import audio_to_data_stream
        return audio_to_data_stream(
            audio_file_path, is_opus=True, callback=callback,
            sample_rate=self.conn.sample_rate, opus_encoder=None
        )

    def wav_to_opus_data_audio_raw_stream(self, raw_data_var, is_end=False, callback: Callable[[Any], Any]=None):
        return self.opus_encoder.encode_pcm_to_opus_stream(raw_data_var, is_end, callback=callback)

    def to_tts(self, text: str) -> list:
        """非流式生成音频数据，用于生成音频及测试场景
        Args:
            text: 要转换的文本
        Returns:
            list: 音频数据列表
        """
        try:
            # 创建事件循环
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)

            # 生成会话ID
            session_id = uuid.uuid4().__str__().replace("-", "")

            # 存储音频数据
            audio_data = []

            async def _generate_audio():
                # 创建新的WebSocket连接
                ws_header = self._build_ws_headers()
                ws = await asyncio.wait_for(
                    websockets.connect(
                        self.ws_url,
                        additional_headers=ws_header,
                        max_size=1000000000,
                    ),
                    timeout=self.connection_timeout_seconds,
                )

                try:
                    # 启动连接
                    self.ws = ws
                    await self.start_connection()
                    await self._wait_for_event(
                        ws,
                        EVENT_ConnectionStarted,
                        timeout=self.connection_timeout_seconds,
                    )

                    # 启动会话
                    header = Header(
                        message_type=FULL_CLIENT_REQUEST,
                        message_type_specific_flags=MsgTypeFlagWithEvent,
                        serial_method=JSON,
                    ).as_bytes()
                    optional = Optional(
                        event=EVENT_StartSession, sessionId=session_id
                    ).as_bytes()
                    payload = self.get_payload_bytes(
                        event=EVENT_StartSession, speaker=self.voice
                    )
                    await self.send_event(ws, header, optional, payload)
                    await self._wait_for_event(
                        ws,
                        EVENT_SessionStarted,
                        timeout=self.session_start_timeout_seconds,
                    )

                    # 发送文本
                    header = Header(
                        message_type=FULL_CLIENT_REQUEST,
                        message_type_specific_flags=MsgTypeFlagWithEvent,
                        serial_method=JSON,
                    ).as_bytes()
                    optional = Optional(
                        event=EVENT_TaskRequest, sessionId=session_id
                    ).as_bytes()
                    payload = self.get_payload_bytes(
                        event=EVENT_TaskRequest, text=text, speaker=self.voice
                    )
                    await self.send_event(ws, header, optional, payload)

                    # 发送结束会话请求
                    header = Header(
                        message_type=FULL_CLIENT_REQUEST,
                        message_type_specific_flags=MsgTypeFlagWithEvent,
                        serial_method=JSON,
                    ).as_bytes()
                    optional = Optional(
                        event=EVENT_FinishSession, sessionId=session_id
                    ).as_bytes()
                    payload = str.encode("{}")
                    await self.send_event(ws, header, optional, payload)

                    # 接收音频数据
                    while True:
                        res = await self._receive_response(
                            ws,
                            timeout=self.response_timeout_seconds,
                        )

                        if (
                            res.optional.event == EVENT_TTSResponse
                            and res.header.message_type == AUDIO_ONLY_RESPONSE
                        ):
                            self.wav_to_opus_data_audio_raw_stream(res.payload, callback=lambda opus_frame: audio_data.append(opus_frame))
                        elif res.optional.event == EVENT_UsageResponse:
                            continue
                        elif res.optional.event == EVENT_SessionFailed:
                            detail = res.payload.decode("utf-8", "ignore") if res.payload else ""
                            raise RuntimeError(f"TTS 会话失败: {detail}")
                        elif res.optional.event == EVENT_SessionFinished:
                            break

                finally:
                    # 清理资源
                    try:
                        await self.finish_connection()
                        await self._wait_for_event(ws, EVENT_ConnectionFinished, timeout=5)
                    except Exception:
                        pass
                    try:
                        await ws.close()
                    except Exception:
                        pass
                    self.ws = None

            # 运行异步任务
            loop.run_until_complete(_generate_audio())
            loop.close()

            return audio_data

        except Exception as e:
            logger.bind(tag=TAG).error(f"生成音频数据失败: {str(e)}")
            return []
