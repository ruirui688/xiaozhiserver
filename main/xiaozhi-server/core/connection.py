import os
import sys
import copy
import json
import re
import uuid
import time
import queue
import asyncio
import threading
import traceback
import subprocess
import websockets

from core.utils.util import (
    extract_json_from_string,
    check_vad_update,
    check_asr_update,
    filter_sensitive_info,
)
from typing import Dict, Any
from collections import deque
from core.utils.modules_initialize import (
    initialize_modules,
    initialize_tts,
    initialize_asr,
)
from core.handle.reportHandle import report, enqueue_tool_report
from core.providers.tts.default import DefaultTTS
from concurrent.futures import ThreadPoolExecutor, TimeoutError
from core.utils.dialogue import Message, Dialogue
from core.providers.asr.dto.dto import InterfaceType
from core.handle.textHandle import handleTextMessage
from core.providers.tools.unified_tool_handler import UnifiedToolHandler
from core.providers.tools.tool_protocol_guard import looks_like_unhandled_tool_protocol
from plugins_func.loadplugins import auto_import_modules
from plugins_func.register import Action, ActionResponse
from core.auth import AuthenticationError
from config.config_loader import get_private_config_from_api
from core.providers.tts.dto.dto import ContentType, TTSMessageDTO, SentenceType
from config.logger import setup_logging, build_module_string, create_connection_logger
from config.manage_api_client import (
    DeviceNotFoundException,
    DeviceBindException,
    generate_and_save_chat_title,
    get_managed_memory_query_timeout,
    query_managed_memory,
)
from core.utils.model_config import has_runtime_config, normalize_runtime_config
from core.utils.prompt_manager import PromptManager
from core.utils.voiceprint_provider import VoiceprintProvider
from core.utils.util import get_system_error_response
from core.handle.audioInitializer import (
    initialize_tts as _ext_init_tts,
    initialize_asr as _ext_init_asr,
    initialize_voiceprint as _ext_init_voiceprint,
)
from core.handle.reportThreadManager import ReportQueue, ReportThreadManager
from core.utils import textUtils
from core.utils.correct_words import parse_correct_words
from core.game import GameSessionManager
from core.semantic.semantic_center import SemanticCenter
from core.device_runtime_registry import register_connection, unregister_connection
from core.observability import SessionTelemetry
from core.session import CloseCoordinator, SessionLifecycle
from core.utils.voiceprint_enrollment import VoiceprintEnrollmentCollector
from core.utils.turn_metrics import TurnMetrics
from core.meeting import (
    MEETING_PLUGIN_NAME,
    MeetingMinutesRuntime,
    build_meeting_runtime_context,
)


TAG = __name__
CLOSE_TIMEOUT_SECONDS = 10.0

auto_import_modules("plugins_func.functions")


def _sanitize_log_text(value, limit=500):
    text = str(value)
    text = re.sub(
        r"(?i)(authorization|api[_-]?key|access[_-]?token|secret[_-]?key|token|secret)"
        r'(\s*[:=]\s*)(".*?"|\'.*?\'|[^\s,;}]+)',
        r"\1\2***",
        text,
    )
    return text[:limit]


def _sanitize_headers_for_log(headers):
    """Return a copy of request headers with credential-bearing values redacted."""
    if not headers:
        return {}

    sanitized = {}
    for key, value in headers.items():
        normalized_key = str(key).strip().lower().replace("_", "-")
        is_sensitive = (
            normalized_key
            in {
                "authorization",
                "proxy-authorization",
                "cookie",
                "set-cookie",
            }
            or "token" in normalized_key
            or "api-key" in normalized_key
            or "apikey" in normalized_key
            or "secret" in normalized_key
        )
        sanitized[key] = "***" if is_sensitive else value
    return sanitized


def _format_headers_for_log(headers, limit=500):
    return str(_sanitize_headers_for_log(headers))[:limit]


class TTSException(RuntimeError):
    pass

# direct_answer 虚拟工具定义
# 不是真实工具，是路由机制：将"调不调工具"的二选一变为"调哪个"的多选，防止小模型误触发真实工具
DIRECT_ANSWER_TOOL = {
    "type": "function",
    "function": {
        "name": "direct_answer",
        "description": "当用户的请求不匹配其他任何工具时，可用此选项直接回复。将回复内容写在response参数里。",
        "parameters": {
            "type": "object",
            "properties": {
                "response": {
                    "type": "string",
                    "description": "你回复用户的完整内容",
                },
            },
            "required": ["response"],
        },
    },
}


class ConnectionHandler:
    def __init__(
            self,
            config: Dict[str, Any],
            _vad,
            _asr,
            _llm,
            _memory,
            _intent,
            server=None,
    ):
        self.common_config = config
        self.config = copy.deepcopy(config)
        self.session_id = str(uuid.uuid4())
        self.logger = setup_logging()
        self.session_telemetry = SessionTelemetry(self.session_id, self.logger, TAG)
        self.turn_metrics = TurnMetrics()
        self.server = server  # 保存server实例的引用

        self.need_bind = False  # 是否需要绑定设备
        self.initialization_failed = False  # 动态配置初始化是否失败
        self.bind_completed_event = asyncio.Event()
        self.bind_code = None  # 绑定设备的验证码
        self.last_bind_prompt_time = 0  # 上次播放绑定提示的时间戳(秒)
        self.bind_prompt_interval = 60  # 绑定提示播放间隔(秒)

        self.read_config_from_api = self.config.get("read_config_from_api", False)

        self.websocket: websockets.ServerConnection | None = None
        self.headers = None
        self.device_id = None
        self.client_ip = None
        self.correct_word_replacements = {}
        self.last_tool_context = None
        self.prompt = None
        self.welcome_msg = None
        self.max_output_size = 0
        self.chat_history_conf = 0
        self.audio_format = "opus"
        self.sample_rate = 24000  # 服务端输出音频采样率

        # 客户端状态相关
        self.client_abort = False
        self.abort_generation = 0
        self.client_is_speaking = False
        self.client_listen_mode = "auto"

        # 线程任务相关
        self.loop = None  # 在 handle_connection 中获取运行中的事件循环
        self.stop_event = threading.Event()
        self.executor = ThreadPoolExecutor(max_workers=5)
        self.closing = False
        self.closed = False
        self._close_lock = asyncio.Lock()
        self._lifecycle_lock = threading.Lock()
        self._background_initialize_task = None
        self._components_initialize_future = None
        self._session_lifecycle = SessionLifecycle(self, log_tag=TAG)
        self._close_coordinator = CloseCoordinator(
            self, CLOSE_TIMEOUT_SECONDS, log_tag=TAG
        )

        # client_abort 跨线程读写锁（event loop / TTS text / TTS audio 线程）
        self._abort_lock = threading.Lock()
        # 会议激活后每句都要等待主LLM工具结果，必须按ASR顺序串行处理。
        self._meeting_turn_lock = asyncio.Lock()

        # 添加上报线程池
        self.report_queue = ReportQueue()
        self.report_thread = None
        # 未来可以通过修改此处，调节asr的上报和tts的上报，目前默认都开启
        self.report_asr_enable = self.read_config_from_api
        self.report_tts_enable = self.read_config_from_api
        self.report_manager = ReportThreadManager()

        # 依赖的组件
        self.vad = None
        self.asr = None
        self.tts = None
        self._asr = _asr
        self._vad = _vad
        self.llm = _llm
        self.memory = _memory
        self.intent = _intent

        # 为每个连接单独管理声纹识别
        self.voiceprint_provider = None
        self.voiceprint_enrollment = VoiceprintEnrollmentCollector()

        # 模块初始化完成事件，_route_message 在处理音频前检查此事件
        self._modules_ready = threading.Event()

        # vad相关变量
        self.client_audio_buffer = bytearray()
        self.client_have_voice = False
        self.client_voice_window = deque(maxlen=5)
        self.first_activity_time = 0.0  # 记录首次活动的时间（毫秒）
        self.last_activity_time = 0.0  # 统一的活动时间戳（毫秒）
        self.vad_last_voice_time = 0.0  # 记录用户最后一次说话的时间（毫秒）
        self.client_voice_stop = False
        self.last_is_voice = False

        # asr相关变量
        # 因为实际部署时可能会用到公共的本地ASR，不能把变量暴露给公共ASR
        # 所以涉及到ASR的变量，需要在这里定义，属于connection的私有变量
        self.asr_audio = []
        self.asr_audio_queue = queue.Queue()
        self.current_speaker = None  # 存储当前说话人
        self.current_speaker_id = None  # 当前说话人的稳定声纹记录ID

        # llm相关变量
        self.dialogue = Dialogue()

        # tts相关变量
        self.sentence_id = None
        # 处理TTS响应没有文本返回
        self.tts_MessageText = ""

        # websocket音频缓冲区（替换之前的延迟初始化hasattr检查）
        self.audio_timestamp_buffer = {}
        self.last_processed_timestamp = 0
        self.max_timestamp_buffer_size = 20

        # iot相关变量
        self.iot_descriptors = {}
        self.func_handler = None

        self.cmd_exit = self.config["exit_commands"]

        # 是否在聊天结束后关闭连接
        self.close_after_chat = False
        self.load_function_plugin = False
        self.configured_intent_type = "nointent"
        self.intent_type = "nointent"

        # {"mcp":true} 表示启用MCP功能
        self.features = None

        # 标记连接是否来自MQTT
        self.conn_from_mqtt_gateway = False

        # 初始化提示词管理器
        self.prompt_manager = PromptManager(self.config, self.logger)
        self.game_session_manager = GameSessionManager()
        self.semantic_center = SemanticCenter()
        self.meeting_minutes = MeetingMinutesRuntime()

        # 初始化通话状态
        self.calling = False

    async def handle_connection(self, ws: websockets.ServerConnection):
        try:
            # 获取运行中的事件循环（必须在异步上下文中）
            self.loop = asyncio.get_running_loop()

            # 获取并验证headers
            self.headers = dict(ws.request.headers)
            real_ip = self.headers.get("x-real-ip") or self.headers.get(
                "x-forwarded-for"
            )
            if real_ip:
                self.client_ip = real_ip.split(",")[0].strip()
            else:
                self.client_ip = ws.remote_address[0]
            self.logger.bind(tag=TAG).info(
                f"{self.client_ip} conn - Headers: {_format_headers_for_log(self.headers)}"
            )

            self.device_id = self.headers.get("device-id", None)
            self.session_telemetry.set_device_id(self.device_id)
            register_connection(self)

            # 认证通过,继续处理
            self.websocket = ws

            # 检查是否来自MQTT连接
            request_path = ws.request.path
            self.conn_from_mqtt_gateway = request_path.endswith("?from=mqtt_gateway")
            if self.conn_from_mqtt_gateway:
                self.logger.bind(tag=TAG).info("连接来自:MQTT网关")

            # 初始化活动时间戳
            self.first_activity_time = time.time() * 1000
            self.last_activity_time = time.time() * 1000

            self.welcome_msg = self._build_welcome_msg()

            # 从配置中读取采样率
            self.sample_rate = self.welcome_msg["audio_params"]["sample_rate"]
            self.logger.bind(tag=TAG).info(f"配置输出音频采样率为: {self.sample_rate}")

            # 在后台初始化配置和组件（完全不阻塞主循环）
            self._background_initialize_task = asyncio.create_task(
                self._background_initialize(),
                name=f"connection-init-{self.session_id}",
            )

            try:
                async for message in self.websocket:
                    await self._route_message(message)
                self.session_telemetry.set_close_reason("client_stream_ended")
            except websockets.exceptions.ConnectionClosed:
                self.session_telemetry.set_close_reason("client_disconnect")
                self.logger.bind(tag=TAG).info("客户端断开连接")

        except AuthenticationError as e:
            self.session_telemetry.set_close_reason("authentication_failed")
            self.logger.bind(tag=TAG).error(f"Authentication failed: {str(e)}")
            return
        except Exception as e:
            self.session_telemetry.set_close_reason("connection_error")
            stack_trace = traceback.format_exc()
            self.logger.bind(tag=TAG).error(f"Connection error: {str(e)}-{stack_trace}")
            return
        finally:
            unregister_connection(self)
            try:
                await self._save_and_close(ws)
            except Exception as final_error:
                self.logger.bind(tag=TAG).error(f"最终清理时出错: {final_error}")
                # 确保即使保存记忆失败，也要关闭连接
                try:
                    await self.close(ws)
                except Exception as close_error:
                    self.logger.bind(tag=TAG).error(
                        f"强制关闭连接时出错: {close_error}"
                    )
            if self.closed:
                self.session_telemetry.emit_close_summary()

    def _build_welcome_msg(self):
        welcome_msg = copy.deepcopy(self.config["xiaozhi"])
        welcome_msg["session_id"] = self.session_id
        audio_params = welcome_msg.get("audio_params") or {}
        audio_params["sample_rate"] = int(audio_params.get("sample_rate", self.sample_rate))
        audio_params["format"] = audio_params.get("format", self.audio_format)
        audio_params["channels"] = int(audio_params.get("channels", 1))
        audio_params["frame_duration"] = int(audio_params.get("frame_duration", 60))
        welcome_msg["audio_params"] = audio_params
        return welcome_msg

    def _run_async_in_daemon_thread(self, coro, thread_name):
        return self._get_close_coordinator().run_async_in_thread(coro, thread_name)

    def _get_session_lifecycle(self):
        lifecycle = getattr(self, "_session_lifecycle", None)
        if lifecycle is None:
            lifecycle = SessionLifecycle(self, log_tag=TAG)
            self._session_lifecycle = lifecycle
        return lifecycle

    def _get_close_coordinator(self):
        coordinator = getattr(self, "_close_coordinator", None)
        if coordinator is None:
            coordinator = CloseCoordinator(self, CLOSE_TIMEOUT_SECONDS, log_tag=TAG)
            self._close_coordinator = coordinator
        return coordinator

    def _begin_closing(self):
        """Publish shutdown intent before any slow cleanup starts."""
        return self._get_session_lifecycle().begin_closing()

    def _assign_initialized_component(self, name, value):
        return self._get_session_lifecycle().assign_initialized_component(name, value)

    @staticmethod
    def _remaining_close_time(deadline):
        return CloseCoordinator.remaining_time(deadline)

    @staticmethod
    def _join_threads_until(threads, deadline):
        CloseCoordinator.join_threads_until(threads, deadline)

    async def _wait_for_background_initialize(self, deadline):
        await self._get_session_lifecycle().wait_for_background_initialize(
            deadline, self._remaining_close_time
        )

    async def _save_and_close(self, ws):
        """保存记忆并关闭连接"""
        coordinator = self._get_close_coordinator()
        deadline = coordinator.new_deadline()
        self._begin_closing()
        threads = []
        join_task = None
        try:
            # 守护线程1：独立生成标题（不依赖记忆模型）
            if self.session_id:
                threads.append(
                    self._run_async_in_daemon_thread(
                        generate_and_save_chat_title(self.session_id),
                        "title-generator",
                    )
                )

            # 守护线程2：走老流程记忆保存（仅记忆，不含标题）
            if self.memory:
                threads.append(
                    self._run_async_in_daemon_thread(
                        self.memory.save_memory(
                            self.dialogue.dialogue, self.session_id
                        ),
                        "memory-save",
                    )
                )

        except Exception as e:
            self.logger.bind(tag=TAG).error(f"保存记忆失败: {e}")
        finally:
            # 记忆线程等待与连接清理并行执行，共享同一个关闭总期限。
            if threads:
                join_task = asyncio.create_task(
                    coordinator.wait_for_threads(threads, deadline)
                )
            try:
                await self.close(ws, _deadline=deadline)
            except Exception as close_error:
                self.logger.bind(tag=TAG).error(
                    f"保存记忆后关闭连接失败: {close_error}"
                )
            finally:
                if join_task is not None:
                    remaining = self._remaining_close_time(deadline)
                    if remaining > 0:
                        done, _ = await asyncio.wait(
                            {join_task}, timeout=remaining
                        )
                        if join_task not in done:
                            join_task.cancel()
                    else:
                        join_task.cancel()

    async def _discard_message_with_bind_prompt(self):
        """丢弃消息并检查是否需要播放绑定提示"""
        current_time = time.time()
        # 检查是否需要播放绑定提示
        if current_time - self.last_bind_prompt_time >= self.bind_prompt_interval:
            self.last_bind_prompt_time = current_time
            # 复用现有的绑定提示逻辑
            from core.handle.receiveAudioHandle import check_bind_device

            asyncio.create_task(check_bind_device(self))

    async def _route_message(self, message):
        """消息路由"""
        self.session_telemetry.metrics.record_message(message)
        self.last_activity_time = time.time() * 1000

        if (
            isinstance(message, str)
            and not self.bind_completed_event.is_set()
            and self._should_handle_before_bind_check(message)
        ):
            await handleTextMessage(self, message)
            return

        # 检查是否已经获取到真实的绑定状态
        if not self.bind_completed_event.is_set():
            # 还没有获取到真实状态，等待直到获取到真实状态或超时
            try:
                await asyncio.wait_for(self.bind_completed_event.wait(), timeout=1)
            except asyncio.TimeoutError:
                # 超时仍未获取到真实状态，丢弃消息
                await self._discard_message_with_bind_prompt()
                return

        # 已经获取到真实状态，检查是否需要绑定
        if self.need_bind:
            # 需要绑定，丢弃消息
            await self._discard_message_with_bind_prompt()
            return

        if self.initialization_failed:
            return

        # 不需要绑定，继续处理消息

        if isinstance(message, str):
            await handleTextMessage(self, message)
        elif isinstance(message, bytes):
            # 等待模块初始化完成后再处理音频
            if not self._modules_ready.is_set():
                return
            if self.vad is None or self.asr is None:
                return

            # 处理来自MQTT网关的音频包
            if self.conn_from_mqtt_gateway and len(message) >= 16:
                handled = await self._process_mqtt_audio_message(message)
                if handled:
                    return

            # 不需要头部处理或没有头部时，直接处理原始消息
            self.asr_audio_queue.put(message)

    async def _process_mqtt_audio_message(self, message):
        """
        处理来自MQTT网关的音频消息，解析16字节头部并提取音频数据

        Args:
            message: 包含头部的音频消息

        Returns:
            bool: 是否成功处理了消息
        """
        try:
            # 提取头部信息
            timestamp = int.from_bytes(message[8:12], "big")
            audio_length = int.from_bytes(message[12:16], "big")

            # 提取音频数据
            if audio_length > 0 and len(message) >= 16 + audio_length:
                # 有指定长度，提取精确的音频数据
                audio_data = message[16 : 16 + audio_length]
                # 基于时间戳进行排序处理
                self._process_websocket_audio(audio_data, timestamp)
                return True
            elif len(message) > 16:
                # 没有指定长度或长度无效，去掉头部后处理剩余数据
                audio_data = message[16:]
                self.asr_audio_queue.put(audio_data)
                return True
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"解析WebSocket音频包失败: {e}")

        # 处理失败，返回False表示需要继续处理
        return False

    def _should_handle_before_bind_check(self, message: str) -> bool:
        """连接协议消息不能被绑定状态初始化阻塞。"""
        try:
            msg_json = json.loads(message)
        except json.JSONDecodeError:
            return False

        if not isinstance(msg_json, dict):
            return False

        return msg_json.get("type") in {"hello", "mcp", "ping"}

    def _process_websocket_audio(self, audio_data, timestamp):
        """处理WebSocket格式的音频包"""
        # 如果时间戳是递增的，直接处理
        if timestamp >= self.last_processed_timestamp:
            self.asr_audio_queue.put(audio_data)
            self.last_processed_timestamp = timestamp

            # 处理缓冲区中的后续包
            processed_any = True
            while processed_any:
                processed_any = False
                for ts in sorted(self.audio_timestamp_buffer.keys()):
                    if ts > self.last_processed_timestamp:
                        buffered_audio = self.audio_timestamp_buffer.pop(ts)
                        self.asr_audio_queue.put(buffered_audio)
                        self.last_processed_timestamp = ts
                        processed_any = True
                        break
        else:
            # 乱序包，暂存
            if len(self.audio_timestamp_buffer) < self.max_timestamp_buffer_size:
                self.audio_timestamp_buffer[timestamp] = audio_data
            else:
                self.asr_audio_queue.put(audio_data)

    async def handle_restart(self, message):
        """处理服务器重启请求"""
        try:

            self.logger.bind(tag=TAG).info("收到服务器重启指令，准备执行...")

            # 发送确认响应
            await self.websocket.send(
                json.dumps(
                    {
                        "type": "server",
                        "status": "success",
                        "message": "服务器重启中...",
                        "content": {"action": "restart"},
                    }
                )
            )

            # 异步执行重启操作
            def restart_server():
                """实际执行重启的方法"""
                self.logger.bind(tag=TAG).warning("Server restarting - unsaved dialogue state will be regenerated on reconnect")
                # TODO: 实现优雅关闭，保存对话状态到持久化存储，新进程启动时恢复
                time.sleep(1)
                self.logger.bind(tag=TAG).info("执行服务器重启...")
                subprocess.Popen(
                    [sys.executable, "app.py"],
                    stdin=sys.stdin,
                    stdout=sys.stdout,
                    stderr=sys.stderr,
                    start_new_session=True,
                )
                os._exit(0)

            # 使用线程执行重启避免阻塞事件循环
            threading.Thread(target=restart_server, daemon=True).start()

        except Exception as e:
            self.logger.bind(tag=TAG).error(f"重启失败: {str(e)}")
            await self.websocket.send(
                json.dumps(
                    {
                        "type": "server",
                        "status": "error",
                        "message": f"Restart failed: {str(e)}",
                        "content": {"action": "restart"},
                    }
                )
            )

    def _initialize_components(self):
        try:
            if self.closing:
                return
            if self.tts is None:
                initialized_tts = self._initialize_tts()
                if not self._assign_initialized_component("tts", initialized_tts):
                    return
            if self.closing:
                return
            # 打开语音合成通道
            tts_future = asyncio.run_coroutine_threadsafe(
                self.tts.open_audio_channels(self), self.loop
            )
            def _on_tts_open_done(f):
                if f.exception():
                    self.logger.bind(tag=TAG).warning(f"TTS channel open failed: {f.exception()}")
                    self.client_is_speaking = False
            tts_future.add_done_callback(_on_tts_open_done)
            if self.need_bind:
                self.bind_completed_event.set()
                return
            self.selected_module_str = build_module_string(
                self.config.get("selected_module", {})
            )
            self.logger = create_connection_logger(self.selected_module_str)

            """初始化组件"""
            if self.config.get("prompt") is not None:
                user_prompt = self.config["prompt"]
                # 使用快速提示词进行初始化
                prompt = self.prompt_manager.get_quick_prompt(user_prompt)
                self.change_system_prompt(prompt)
                self.logger.bind(tag=TAG).info(
                    f"快速初始化组件: prompt成功 {prompt[:50]}..."
                )

            """设置对话历史截断"""
            max_turns = int(self.config.get("dialogue", {}).get("max_history_turns", 0))
            if max_turns > 0:
                self.dialogue.max_history_turns = max_turns
                self.logger.bind(tag=TAG).info(f"对话历史截断已启用: max_history_turns={max_turns}")

            """初始化本地组件"""
            if self.closing:
                return
            if self.vad is None and self._vad is not None:
                if not self._assign_initialized_component("vad", self._vad):
                    return
            if self.asr is None:
                initialized_asr = self._initialize_asr()
                if initialized_asr is not None and not self._assign_initialized_component(
                    "asr", initialized_asr
                ):
                    return

            # 初始化声纹识别
            if self.closing:
                return
            self._initialize_voiceprint()
            if self.closing:
                self.voiceprint_provider = None
                return
            # 打开语音识别通道
            if self.asr is not None:
                asr_future = asyncio.run_coroutine_threadsafe(
                    self.asr.open_audio_channels(self), self.loop
                )
                asr_future.add_done_callback(lambda f: self.logger.bind(tag=TAG).warning(f"ASR channel open failed: {f.exception()}") if f.exception() else None)
            else:
                self.logger.bind(tag=TAG).warning("ASR未配置，语音识别暂不可用")

            """加载记忆"""
            self._initialize_memory()
            """加载意图识别"""
            self._initialize_intent()
            """初始化上报线程"""
            if self.closing:
                return
            self._init_report_threads()
            self._init_voiceprint_enrollment_from_config()
            """更新系统提示词"""
            self._init_prompt_enhancement()
            """注入工具调用few-shot示例（仅function_call模式）"""
            self._inject_tool_call_fewshot()

        except Exception as e:
            self.logger.bind(tag=TAG).error(f"实例化组件失败: {e}")

        if not self.closing:
            self._modules_ready.set()

    def _init_prompt_enhancement(self):

        # 更新上下文信息
        self.prompt_manager.update_context_info(self, self.client_ip)
        enhanced_prompt = self.prompt_manager.build_enhanced_prompt(
            self.config["prompt"],
            self.device_id,
            self.client_ip,
            emoji_enabled=(self.features or {}).get("emoji", True),
            available_tools=(
                self.func_handler.get_functions()
                if self.intent_type == "function_call"
                and getattr(self, "func_handler", None) is not None
                else []
            ),
        )
        if enhanced_prompt:
            self.change_system_prompt(enhanced_prompt)
            self.logger.bind(tag=TAG).debug("系统提示词已增强更新")

    def _inject_tool_call_fewshot(self):
        """注入工具调用 few-shot 示例到对话历史。
        结构：正样本（工具调用示例）放在动态 system 之前，可命中前缀缓存；
        负样本（直接回答示例）放在动态 system 之后、紧挨真实用户消息，
        确保模型在处理用户消息前最后看到的是"不调工具"的行为模式。
        """
        if self.intent_type != "function_call":
            return
        if not hasattr(self, "func_handler") or self.func_handler is None:
            return

        tools = self.func_handler.get_functions()
        if not tools:
            return

        tool_names = {t.get("function", {}).get("name") for t in tools}

        # === few-shot 示例（is_temporary）===
        # 展示 direct_answer 携带 response 参数的用法，一次调用完成回复

        # 示例1：direct_answer（回复内容写在 response 参数里，无需递归）
        da_tc_id = "fewshot_da_001"
        self.dialogue.put(Message(role="user", content="给我讲个故事吧", is_temporary=True))
        self.dialogue.put(Message(
            role="assistant",
            tool_calls=[{
                "id": da_tc_id,
                "function": {"arguments": '{"response": "你想听什么类型的呀？童话、冒险还是搞笑的？选一个我给你开讲。"}', "name": "direct_answer"},
                "type": "function", "index": 0,
            }],
            is_temporary=True,
        ))
        self.dialogue.put(Message(
            role="tool", tool_call_id=da_tc_id,
            content="已直接回复", is_temporary=True,
        ))

        # 示例2：真实工具调用（handle_exit_intent）
        if "handle_exit_intent" in tool_names:
            tc_id = "fewshot_exit_001"
            self.dialogue.put(Message(role="user", content="拜拜", is_temporary=True))
            self.dialogue.put(Message(
                role="assistant",
                tool_calls=[{
                    "id": tc_id,
                    "function": {"arguments": '{"say_goodbye": "再见，下次再聊~"}', "name": "handle_exit_intent"},
                    "type": "function", "index": 0,
                }],
                is_temporary=True,
            ))
            self.dialogue.put(Message(
                role="tool", tool_call_id=tc_id,
                content="退出意图已处理", is_temporary=True,
            ))
            self.dialogue.put(Message(
                role="assistant", content="再见，下次再聊~", is_temporary=True,
            ))

        self.logger.bind(tag=TAG).debug("已注入工具调用 few-shot 示例")

    def _init_report_threads(self):
        self.report_manager.start(self)

    def _init_voiceprint_enrollment_from_config(self):
        enrollment_config = self.config.get("voiceprint_enrollment")
        if not isinstance(enrollment_config, dict):
            return
        if not enrollment_config.get("enabled"):
            return
        status = self.start_voiceprint_enrollment(enrollment_config)
        self.logger.bind(tag=TAG).info(
            "配置触发声纹采集模式: requestId={}, targetSamples={}".format(
                status.get("requestId"), status.get("targetSamples")
            )
        )

    def _initialize_tts(self):
        return _ext_init_tts(self)

    def _initialize_asr(self):
        return _ext_init_asr(self)

    def _initialize_voiceprint(self):
        return _ext_init_voiceprint(self)

    async def _background_initialize(self):
        """在后台初始化配置和组件（完全不阻塞主循环）"""
        try:
            if self.closing:
                return
            # 异步获取差异化配置
            should_initialize = await self._initialize_private_config_async()
            if not should_initialize or self.closing:
                return
            # 在线程池中初始化组件
            executor = self.executor
            if executor is None:
                return
            self._components_initialize_future = executor.submit(
                self._initialize_components
            )
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"后台初始化失败: {e}")

    async def _initialize_private_config_async(self):
        """从接口异步获取差异化配置（异步版本，不阻塞主循环）"""
        if self.closing:
            return False
        if not self.read_config_from_api:
            self.need_bind = False
            self.bind_completed_event.set()
            return True
        try:
            begin_time = time.time()
            private_config = await get_private_config_from_api(
                self.config,
                self.headers.get("device-id"),
                self.headers.get("client-id", self.headers.get("device-id")),
            )
            if self.closing:
                return False
            private_config["delete_audio"] = bool(self.config.get("delete_audio", True))
            normalize_runtime_config(private_config)
            self.logger.bind(tag=TAG).info(
                f"{time.time() - begin_time} 秒，异步获取差异化配置成功: {json.dumps(filter_sensitive_info(private_config), ensure_ascii=False)}"
            )
            self.need_bind = False
            self.bind_completed_event.set()
        except DeviceNotFoundException as e:
            if self.closing:
                return False
            self.need_bind = True
            self.bind_completed_event.set()
            private_config = {}
        except DeviceBindException as e:
            if self.closing:
                return False
            self.need_bind = True
            self.bind_code = e.bind_code
            self.bind_completed_event.set()
            private_config = {}
        except Exception as e:
            if self.closing:
                return False
            self.logger.bind(tag=TAG).error(f"异步获取差异化配置失败: {e}")
            self.initialization_failed = True
            self.bind_completed_event.set()
            return False

        if self.closing:
            return False
        init_vad = check_vad_update(self.common_config, private_config)
        init_asr = check_asr_update(self.common_config, private_config)
        init_llm, init_tts, init_memory, init_intent = (
            self._apply_private_runtime_config(private_config)
        )

        # 使用 run_in_executor 在线程池中执行 initialize_modules，避免阻塞主循环
        try:
            modules = await self.loop.run_in_executor(
                None,  # 使用默认线程池
                initialize_modules,
                self.logger,
                private_config,
                init_vad,
                init_asr,
                init_llm,
                init_tts,
                init_memory,
                init_intent,
            )
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"初始化组件失败: {e}")
            modules = {}
        if self.closing:
            return False
        for name in ("tts", "vad", "asr", "llm", "intent", "memory"):
            value = modules.get(name)
            if value is not None and not self._assign_initialized_component(name, value):
                return False
        return True

    def _apply_private_runtime_config(self, private_config):
        normalize_runtime_config(private_config)
        self.config.setdefault("selected_module", {})

        private_model_config = private_config.get("model_config")
        if isinstance(private_model_config, dict):
            self.config.setdefault("model_config", {})
            self.config["model_config"].update(copy.deepcopy(private_model_config))

        selected_module = private_config.get("selected_module", {})
        init_flags = {
            "LLM": False,
            "TTS": False,
            "Memory": False,
            "Intent": False,
        }
        for module_type in (
            "VAD",
            "ASR",
            "TTS",
            "LLM",
            "VLLM",
            "Memory",
            "Intent",
            "SLM",
            "RAG",
        ):
            selected_name = selected_module.get(module_type)
            if not selected_name or not has_runtime_config(
                private_config, module_type, selected_name
            ):
                continue
            self.config["selected_module"][module_type] = selected_name
            if module_type in init_flags:
                init_flags[module_type] = True

        normalize_runtime_config(self.config)

        for key in (
            "agent_id",
            "agent_name",
            "prompt",
            "voiceprint",
            "voiceprint_enrollment",
            "summaryMemory",
            "mcp_endpoint",
            "context_providers",
        ):
            if private_config.get(key) is not None:
                self.config[key] = copy.deepcopy(private_config[key])

        if private_config.get("device_max_output_size") is not None:
            self.max_output_size = int(private_config["device_max_output_size"])
        if private_config.get("chat_history_conf") is not None:
            self.chat_history_conf = int(private_config["chat_history_conf"])

        self._apply_private_plugins(private_config)
        if private_config.get("correct_words") is not None:
            self._apply_correct_words(private_config["correct_words"])

        return (
            init_flags["LLM"],
            init_flags["TTS"],
            init_flags["Memory"],
            init_flags["Intent"],
        )

    def _apply_private_plugins(self, private_config):
        plugin_from_server = private_config.get("plugins")
        if plugin_from_server is None:
            return

        selected_intent = self.config.get("selected_module", {}).get("Intent")
        if not selected_intent or selected_intent == "Intent_nointent":
            return

        normalized_plugins = {}
        for plugin, config_value in plugin_from_server.items():
            if isinstance(config_value, str):
                normalized_plugins[plugin] = json.loads(config_value)
            else:
                normalized_plugins[plugin] = copy.deepcopy(config_value)
        self.config["plugins"] = normalized_plugins

        intent_config = self.config.get("Intent", {}).get(selected_intent)
        if isinstance(intent_config, dict):
            intent_config["functions"] = normalized_plugins.keys()

    def _apply_correct_words(self, correct_words):
        self.config["correct_words"] = copy.deepcopy(correct_words)
        self.correct_word_replacements = parse_correct_words(correct_words)
        select_tts_module = self.config.get("selected_module", {}).get("TTS")
        tts_config = self.config.get("TTS", {})
        if select_tts_module in tts_config:
            tts_config[select_tts_module]["correct_words"] = copy.deepcopy(
                correct_words
            )
        tts_model_config = (
            self.config.get("model_config", {})
            .get(select_tts_module, {})
            .get("typed_config")
        )
        if isinstance(tts_model_config, dict):
            tts_model_config["correct_words"] = copy.deepcopy(correct_words)

    def _initialize_memory(self):
        if self.memory is None:
            return
        selected_memory = self.config.get("selected_module", {}).get("Memory")
        if not selected_memory or selected_memory not in self.config.get("Memory", {}):
            return
        """初始化记忆模块"""
        self.memory.init_memory(
            role_id=self.device_id,
            llm=self.llm,
            summary_memory=self.config.get("summaryMemory", None),
            save_to_file=not self.read_config_from_api,
        )

        # 获取记忆总结配置
        memory_config = self.config["Memory"]
        memory_type = self.config["Memory"][selected_memory]["type"]
        # 如果使用 nomen 或 mem_report_only，直接返回
        if memory_type == "nomem" or memory_type == "mem_report_only":
            return
        # 使用 mem_local_short 模式
        elif memory_type == "mem_local_short":
            memory_llm_name = memory_config[selected_memory]["llm"]
            if memory_llm_name and memory_llm_name in self.config["LLM"]:
                # 如果配置了专用LLM，则创建独立的LLM实例
                from core.utils import llm as llm_utils

                memory_llm_config = self.config["LLM"][memory_llm_name]
                memory_llm_type = memory_llm_config.get("type", memory_llm_name)
                memory_llm = llm_utils.create_instance(
                    memory_llm_type, memory_llm_config
                )
                self.logger.bind(tag=TAG).info(
                    f"为记忆总结创建了专用LLM: {memory_llm_name}, 类型: {memory_llm_type}"
                )
                self.memory.set_llm(memory_llm)
            else:
                # 否则使用主LLM
                self.memory.set_llm(self.llm)
                self.logger.bind(tag=TAG).info("使用主LLM作为意图识别模型")

    def _initialize_intent(self):
        if self.intent is None:
            return
        selected_intent = self.config.get("selected_module", {}).get("Intent")
        if not selected_intent or selected_intent not in self.config.get("Intent", {}):
            return
        self.configured_intent_type = self.config["Intent"][selected_intent]["type"]
        self.intent_type = self._resolve_runtime_intent_type(self.configured_intent_type)
        if self.intent_type == "intent_llm":
            selected_config = self.config["Intent"].get(selected_intent, {})
            selected_config["functions"] = self._without_meeting_function(
                selected_config.get("functions", [])
            )
        if self.configured_intent_type == "function_call" and self.intent_type == "intent_llm":
            self._switch_to_intent_llm_provider()
        if self.intent_type == "function_call" or self.intent_type == "intent_llm":
            self.load_function_plugin = True
        """初始化意图识别模块"""
        # 获取意图识别配置
        intent_config = self.config["Intent"]

        # 如果使用 nointent，直接返回
        if self.intent_type == "nointent":
            return
        # 使用 intent_llm 模式
        elif self.intent_type == "intent_llm":
            intent_llm_name = intent_config.get(selected_intent, {}).get("llm")

            if intent_llm_name and intent_llm_name in self.config["LLM"]:
                # 如果配置了专用LLM，则创建独立的LLM实例
                from core.utils import llm as llm_utils

                intent_llm_config = self.config["LLM"][intent_llm_name]
                intent_llm_type = intent_llm_config.get("type", intent_llm_name)
                intent_llm = llm_utils.create_instance(
                    intent_llm_type, intent_llm_config
                )
                self.logger.bind(tag=TAG).info(
                    f"为意图识别创建了专用LLM: {intent_llm_name}, 类型: {intent_llm_type}"
                )
                self.intent.set_llm(intent_llm)
            else:
                # 否则使用主LLM
                self.intent.set_llm(self.llm)
                self.logger.bind(tag=TAG).info("使用主LLM作为意图识别模型")

        """加载统一工具处理器"""
        self.func_handler = UnifiedToolHandler(self)

        # 异步初始化工具处理器
        if hasattr(self, "loop") and self.loop:
            handler_future = asyncio.run_coroutine_threadsafe(self.func_handler._initialize(), self.loop)
            handler_future.add_done_callback(lambda f: self.logger.bind(tag=TAG).warning(f"Tool handler init failed: {f.exception()}") if f.exception() else None)

    def _query_memory_context(self, query):
        if not query:
            return None

        memory_parts = []
        memory_type = None

        # NOTE: When read_config_from_api=True and memory_type != "nomem",
        # there are TWO potential memory sources:
        #   1. Local provider memory (self.memory.query_memory)
        #   2. Managed memory from manager-api (query_managed_memory)
        # If managed memory returns results, we skip the local query to
        # avoid dual-source conflicts (the managed memory is authoritative).
        managed_result = None
        if self.read_config_from_api:
            selected_memory = self.config.get("selected_module", {}).get("Memory")
            memory_type = (
                self.config.get("Memory", {})
                .get(selected_memory, {})
                .get("type")
            )
            if selected_memory and memory_type != "nomem":
                agent_id = self.config.get("agent_id") if self.config else None
                current_speaker_id = getattr(self, "current_speaker_id", None)
                current_speaker_name = getattr(self, "current_speaker", None)
                query_started_at = time.monotonic()
                future = asyncio.run_coroutine_threadsafe(
                    query_managed_memory(
                        self.device_id,
                        query,
                        agent_id=agent_id,
                        speaker_id=current_speaker_id,
                        speaker_name=(current_speaker_name if current_speaker_id else None),
                    ),
                    self.loop,
                )
                try:
                    managed_result = future.result(
                        timeout=get_managed_memory_query_timeout()
                    )
                    elapsed_ms = int((time.monotonic() - query_started_at) * 1000)
                    if managed_result:
                        memory_parts.append(managed_result)
                        status = "success_with_context"
                        context_chars = len(managed_result)
                    elif managed_result == "":
                        status = "success_empty"
                        context_chars = 0
                    else:
                        status = "failed"
                        context_chars = 0
                    log_method = (
                        self.logger.bind(tag=TAG).info
                        if status.startswith("success_")
                        else self.logger.bind(tag=TAG).warning
                    )
                    log_method(
                        f"managed_memory_query status={status} "
                        f"elapsed_ms={elapsed_ms} context_chars={context_chars}"
                    )
                except TimeoutError:
                    future.cancel()
                    elapsed_ms = int((time.monotonic() - query_started_at) * 1000)
                    self.logger.bind(tag=TAG).warning(
                        "managed_memory_query status=timeout "
                        f"elapsed_ms={elapsed_ms} context_chars=0"
                    )
                except Exception:
                    elapsed_ms = int((time.monotonic() - query_started_at) * 1000)
                    self.logger.bind(tag=TAG).warning(
                        "managed_memory_query status=failed "
                        f"elapsed_ms={elapsed_ms} context_chars=0"
                    )

        # Only query local provider memory if managed memory returned nothing.
        # This prevents the LLM from receiving duplicate/conflicting memory from
        # two sources at once.
        if not managed_result and self.memory is not None and memory_type != "managed_memory":
            future = asyncio.run_coroutine_threadsafe(
                self.memory.query_memory(query), self.loop
            )
            try:
                memory_str = future.result(timeout=2)
                if memory_str:
                    memory_parts.append(memory_str)
            except TimeoutError:
                future.cancel()
                self.logger.bind(tag=TAG).warning("本地记忆查询超时，已跳过本轮记忆注入")
            except Exception as e:
                self.logger.bind(tag=TAG).warning(f"本地记忆查询失败，已跳过: {e}")

        return "\n\n".join(memory_parts) if memory_parts else None

    def _resolve_runtime_intent_type(self, configured_intent_type):
        if configured_intent_type != "function_call":
            return configured_intent_type
        if self._llm_supports_native_function_call():
            return "function_call"
        self.logger.bind(tag=TAG).warning(
            "当前主LLM不支持原生结构化 function_call，已自动降级为 intent_llm，避免工具协议文本进入语音和聊天记录"
        )
        return "intent_llm"

    def _llm_supports_native_function_call(self):
        return bool(getattr(self.llm, "supports_native_function_call", False))

    def _switch_to_intent_llm_provider(self):
        intent_config = self.config.get("Intent", {})
        from core.utils import intent as intent_utils

        intent_llm_config = intent_config.setdefault(
            "intent_llm",
            {"type": "intent_llm", "llm": "", "functions": []},
        )
        self.intent = intent_utils.create_instance("intent_llm", intent_llm_config)

        selected_intent = self.config.get("selected_module", {}).get("Intent")
        selected_functions = (
            intent_config.get(selected_intent, {}).get("functions")
            if selected_intent
            else None
        )
        if selected_functions is not None:
            intent_llm_config["functions"] = self._without_meeting_function(
                selected_functions
            )

    @staticmethod
    def _without_meeting_function(functions):
        if isinstance(functions, dict):
            return {
                name: value
                for name, value in functions.items()
                if name != MEETING_PLUGIN_NAME
            }
        if isinstance(functions, str):
            return ";".join(
                name
                for name in functions.split(";")
                if name and name != MEETING_PLUGIN_NAME
            )
        try:
            return [name for name in functions if name != MEETING_PLUGIN_NAME]
        except TypeError:
            return []

    def change_system_prompt(self, prompt):
        self.prompt = prompt
        # 更新系统prompt至上下文
        self.dialogue.update_system_message(self.prompt)

    def _refresh_available_tools_prompt(self, available_tools):
        """Keep the system prompt aligned with the current tools schema."""
        updated_prompt = self.prompt_manager.replace_available_tools_prompt(
            self.prompt, available_tools
        )
        if updated_prompt != self.prompt:
            self.change_system_prompt(updated_prompt)
            tool_names = self.prompt_manager.get_available_tool_names(available_tools)
            self.logger.bind(tag=TAG).debug(
                f"系统提示词工具索引已同步: {tool_names}"
            )

    def chat(self, query, depth=0, allow_tools=True):
        if depth == 0:
            self.turn_metrics.ensure_text_turn()
        chat_started_at = time.monotonic()
        current_abort_generation = self.abort_generation
        meeting = getattr(self, "meeting_minutes", None)
        meeting_tool_turn = bool(
            depth == 0 and meeting is not None and meeting.is_active()
        )
        # 保存当前任务的sentence_id到局部变量，避免被新任务覆盖
        current_sentence_id = None
        freeform_game_prompt = None

        if query is not None:
            self.logger.bind(tag=TAG).info(f"大模型收到用户消息: {query}")

        # 为最顶层时新建会话ID和发送FIRST请求
        if depth == 0:
            current_sentence_id = str(uuid.uuid4().hex)
            self.sentence_id = current_sentence_id  # 更新共享属性
            self.dialogue.put(Message(role="user", content=query))
            freeform_game_prompt = self.game_session_manager.build_freeform_game_prompt(
                query,
                self._get_enabled_plugin_configs(),
            )
            if freeform_game_prompt:
                self.logger.bind(tag=TAG).info("已注入纯语言游戏提示词")
            if not meeting_tool_turn:
                self.tts.tts_text_queue.put(
                    TTSMessageDTO(
                        sentence_id=current_sentence_id,
                        sentence_type=SentenceType.FIRST,
                        content_type=ContentType.ACTION,
                    )
                )
        else:
            # 递归调用时，使用当前的sentence_id
            current_sentence_id = self.sentence_id

        # 设置最大递归深度，避免无限循环，可根据实际需求调整
        MAX_DEPTH = 5
        force_final_answer = False  # 标记是否强制最终回答

        if depth >= MAX_DEPTH:
            self.logger.bind(tag=TAG).debug(
                f"已达到最大工具调用深度 {MAX_DEPTH}，将强制基于现有信息回答"
            )
            force_final_answer = True
            # 添加系统指令，要求 LLM 基于现有信息回答
            self.dialogue.put(
                Message(
                    role="user",
                    content="[系统提示] 已达到最大工具调用次数限制，请你基于目前已经获取的所有信息，直接给出最终答案。不要再尝试调用任何工具。",
                )
            )

        # Define intent functions
        functions = None
        # 达到最大深度时，禁用工具调用，强制 LLM 直接回答
        if (
            meeting_tool_turn
            and hasattr(self, "func_handler")
            and self._llm_supports_native_function_call()
        ):
            functions = [
                item
                for item in self.func_handler.get_functions()
                if item.get("function", {}).get("name") == MEETING_PLUGIN_NAME
            ]
        elif (
                self.intent_type == "function_call"
                and hasattr(self, "func_handler")
                and not force_final_answer
                and allow_tools
        ):
            functions = list(self.func_handler.get_functions())
            # 仅在第一层调用时注入 direct_answer 虚拟工具
            # 递归调用（depth>0）不注入，避免模型在生成文本回复时再次调 direct_answer 导致循环
            if functions is not None and depth == 0:
                functions.append(DIRECT_ANSWER_TOOL)

        if meeting_tool_turn and not functions:
            appended = self._record_pending_meeting_turn("meeting_tool_unavailable")
            self.logger.bind(tag=TAG).warning(
                "会议已激活但主LLM不支持可用的会议function tool，"
                f"按安全规则继续记录: utteranceCount={appended}"
            )
            return True

        # Mirror the schema actually attached to this main-LLM request. Intent-LLM
        # mode owns a separate tool prompt, and recursive final-answer turns may
        # intentionally disable tools even though the session registry is populated.
        prompt_tools = functions or []
        self._refresh_available_tools_prompt(prompt_tools)

        response_message = []

        try:
            # 使用带记忆的对话
            memory_str = self._query_memory_context(query)

            # 当前轮说话人每次都作为 system 事实提供，避免模型把历史中提到的人误判为当前用户。
            # 是否自然称呼用户由基础提示词约束，不能通过隐藏身份事实来实现。
            speaker_for_system = None
            current_speaker = (self.current_speaker or "").strip()
            if current_speaker and current_speaker != "未知说话人":
                speaker_for_system = current_speaker

            llm_dialogue = self.dialogue.get_llm_dialogue_with_memory(
                memory_str,
                self.config.get("voiceprint", {}),
                speaker_for_system,
                self.current_speaker_id,
            )
            llm_dialogue = self._with_freeform_game_prompt(
                llm_dialogue, freeform_game_prompt
            )
            llm_dialogue = self._with_music_library_context(llm_dialogue)
            llm_dialogue = self._with_meeting_runtime_context(llm_dialogue)

            llm_started_at = time.monotonic()
            self.session_telemetry.metrics.record_llm_started()
            tools_enabled = functions is not None and (
                self.intent_type == "function_call" or meeting_tool_turn
            )
            if tools_enabled:
                # 使用支持functions的streaming接口
                function_options = {}
                if meeting_tool_turn and getattr(
                    self.llm, "supports_tool_choice", False
                ):
                    function_options["tool_choice"] = "required"
                llm_responses = self.llm.response_with_functions(
                    self.session_id,
                    llm_dialogue,
                    functions=functions,
                    **function_options,
                )
            else:
                llm_responses = self.llm.response(
                    self.session_id,
                    llm_dialogue,
                )
            self.turn_metrics.record_llm_started(
                tools_enabled,
                depth,
                dialogue=llm_dialogue,
                functions=functions,
            )
            self.logger.bind(tag=TAG).info(
                f"LLM请求已发起 | depth={depth} | tools_enabled={tools_enabled} | "
                f"elapsed={time.monotonic() - llm_started_at:.3f}s"
            )
        except Exception as e:
            if "llm_started_at" in locals():
                self.session_telemetry.metrics.record_llm_complete(
                    time.monotonic() - llm_started_at
                )
            self.logger.bind(tag=TAG).error(f"LLM 处理出错 {query}: {e}")
            if meeting_tool_turn:
                self._record_pending_meeting_turn("meeting_llm_request_error")
                return True
            return None

        # 处理流式响应
        tool_call_flag = False
        # 支持多个并行工具调用 - 使用列表存储
        tool_calls_list = []  # 格式: [{"id": "", "name": "", "arguments": ""}]
        content_arguments = ""
        unhandled_tool_protocol = False
        emotion_flag = not meeting_tool_turn
        defer_plain_content_for_tools = (
            self.intent_type == "function_call" and functions is not None
        )
        first_llm_chunk_at = None
        try:
            for response in llm_responses:
                if first_llm_chunk_at is None:
                    first_llm_chunk_at = time.monotonic()
                    self.session_telemetry.metrics.record_llm_first_chunk(
                        first_llm_chunk_at - llm_started_at
                    )
                    self.turn_metrics.record_llm_first_chunk(depth=depth)
                    self.logger.bind(tag=TAG).info(
                        f"LLM首个响应分片 | depth={depth} | "
                        f"elapsed={first_llm_chunk_at - llm_started_at:.3f}s"
                    )
                if (
                        self.client_abort
                        or current_abort_generation != self.abort_generation
                ):
                    break
                if self.intent_type == "function_call" and functions is not None:
                    content, tools_call = response
                    if "content" in response:
                        content = response["content"]
                        tools_call = None
                    if content is not None and len(content) > 0:
                        content_arguments += content

                    if not tool_call_flag and content_arguments.startswith("<tool_call>"):
                        # print("content_arguments", content_arguments)
                        tool_call_flag = True
                    elif (
                        not tool_call_flag
                        and looks_like_unhandled_tool_protocol(content_arguments)
                    ):
                        tool_call_flag = True
                        unhandled_tool_protocol = True
                        content = None
                    elif (
                        not tool_call_flag
                        and content_arguments.lstrip().startswith("<")
                        and len(content_arguments.strip()) < 80
                    ):
                        content = None
                    elif (
                        not tool_call_flag
                        and content_arguments.lstrip().startswith("<")
                    ):
                        content = content_arguments
                        content_arguments = ""

                    if tools_call is not None and len(tools_call) > 0:
                        tool_call_flag = True
                        self._merge_tool_calls(tool_calls_list, tools_call)

                    # 流式提取 direct_answer 的 response 参数，实时送 TTS
                    for tc in tool_calls_list:
                        if (
                            not meeting_tool_turn
                            and tc["name"] == "direct_answer"
                            and tc.get("arguments")
                        ):
                            da_text = self._extract_direct_answer_response(tc["arguments"])
                            sent_len = tc.get("_da_sent", 0)
                            if da_text and len(da_text) > sent_len:
                                new_part = da_text[sent_len:]
                                if new_part:
                                    tc["_da_sent"] = len(da_text)
                                    self.tts.tts_text_queue.put(
                                        TTSMessageDTO(
                                            sentence_id=current_sentence_id,
                                            sentence_type=SentenceType.MIDDLE,
                                            content_type=ContentType.TEXT,
                                            content_detail=new_part,
                                        )
                                    )
                else:
                    content = response

                # 在llm回复中获取情绪表情，一轮对话只在开头获取一次
                if emotion_flag and content is not None and content.strip():
                    if (self.features or {}).get("emoji", True):
                        emotion_future = asyncio.run_coroutine_threadsafe(
                            textUtils.get_emotion(self, content),
                            self.loop,
                        )
                        emotion_future.add_done_callback(lambda f: self.logger.bind(tag=TAG).warning(f"Emotion detection failed: {f.exception()}") if f.exception() else None)
                    emotion_flag = False

                if content is not None and len(content) > 0:
                    if not tool_call_flag:
                        response_message.append(content)
                        if not defer_plain_content_for_tools:
                            self.tts.tts_text_queue.put(
                                TTSMessageDTO(
                                    sentence_id=current_sentence_id,
                                    sentence_type=SentenceType.MIDDLE,
                                    content_type=ContentType.TEXT,
                                    content_detail=content,
                                )
                            )
        except Exception as e:
            self.session_telemetry.metrics.record_llm_complete(
                time.monotonic() - llm_started_at
            )
            self.logger.bind(tag=TAG).error(f"LLM stream processing error: {e}")
            if meeting_tool_turn:
                self._record_pending_meeting_turn("meeting_llm_stream_error")
                return True
            self.tts.tts_text_queue.put(
                TTSMessageDTO(
                    sentence_id=current_sentence_id,
                    sentence_type=SentenceType.MIDDLE,
                    content_type=ContentType.TEXT,
                    content_detail=get_system_error_response(self.config),
                )
            )
            if depth == 0:
                self.tts.tts_text_queue.put(
                    TTSMessageDTO(
                        sentence_id=current_sentence_id,
                        sentence_type=SentenceType.LAST,
                        content_type=ContentType.ACTION,
                    )
            )
            return
        self.session_telemetry.metrics.record_llm_complete(
            time.monotonic() - llm_started_at
        )
        llm_content_text = "".join(response_message) or content_arguments
        usage = getattr(self.llm, "last_usage", None)
        self.turn_metrics.record_llm_complete(
            llm_content_text,
            usage,
            tool_call_flag,
            len(tool_calls_list),
            depth=depth,
        )
        self.logger.bind(tag=TAG).info(
            f"LLM响应流完成 | depth={depth} | elapsed={time.monotonic() - llm_started_at:.3f}s | "
            f"tool_call={tool_call_flag} | tool_count={len(tool_calls_list)} | "
            f"content_chars={sum(len(part) for part in response_message)}"
        )

        if self.client_abort or current_abort_generation != self.abort_generation:
            if len(response_message) > 0:
                interrupted_text = "".join(response_message)
                if interrupted_text:
                    self.logger.bind(tag=TAG).debug(
                        f"LLM流式响应被打断，丢弃部分文本(不写入对话历史): "
                        f"{interrupted_text[:80]}..."
                    )
            if meeting_tool_turn:
                self._record_pending_meeting_turn("meeting_turn_aborted")
            return
        # 处理function call
        if tool_call_flag:
            bHasError = False
            # 处理基于文本的工具调用格式
            if unhandled_tool_protocol:
                bHasError = True
                response_message.clear()
                if not meeting_tool_turn:
                    self.tts.tts_text_queue.put(
                        TTSMessageDTO(
                            sentence_id=current_sentence_id,
                            sentence_type=SentenceType.MIDDLE,
                            content_type=ContentType.TEXT,
                            content_detail="这个工具调用没有按正确格式返回，我先不执行。",
                        )
                    )
                self.logger.bind(tag=TAG).error(
                    f"unsupported tool protocol from LLM: {_sanitize_log_text(content_arguments)}"
                )
            elif len(tool_calls_list) == 0 and content_arguments:
                a = extract_json_from_string(content_arguments)
                if a is not None:
                    try:
                        content_arguments_json = json.loads(a)
                        tool_calls_list.append(
                            {
                                "id": str(uuid.uuid4().hex),
                                "name": content_arguments_json["name"],
                                "arguments": json.dumps(
                                    content_arguments_json["arguments"],
                                    ensure_ascii=False,
                                ),
                            }
                        )
                    except Exception as e:
                        bHasError = True
                else:
                    bHasError = True
                if bHasError:
                    response_message.clear()
                    if not meeting_tool_turn:
                        self.tts.tts_text_queue.put(
                            TTSMessageDTO(
                                sentence_id=current_sentence_id,
                                sentence_type=SentenceType.MIDDLE,
                                content_type=ContentType.TEXT,
                                content_detail="这个工具调用没有按正确格式返回，我先不执行。",
                            )
                        )
                    self.logger.bind(tag=TAG).error(
                        f"function call error: {_sanitize_log_text(content_arguments)}"
                    )

            if not bHasError and len(tool_calls_list) > 0:
                # 处理 direct_answer 虚拟工具
                direct_answer_calls = [tc for tc in tool_calls_list if tc["name"] == "direct_answer"]
                real_tool_calls = [tc for tc in tool_calls_list if tc["name"] != "direct_answer"]

                if meeting_tool_turn and direct_answer_calls:
                    self.logger.bind(tag=TAG).warning(
                        "会议激活期间主LLM返回了未提供的 direct_answer，已丢弃并继续记录"
                    )
                    direct_answer_calls = []
                    tool_calls_list = real_tool_calls

                if direct_answer_calls:
                    self.logger.bind(tag=TAG).debug(
                        f"模型选择 direct_answer，流式已播报，写入对话历史"
                    )
                    for tc in direct_answer_calls:
                        da_response = self._extract_direct_answer_response(tc.get("arguments", "{}"))
                        if da_response:
                            # 刷新流式缓冲区中未发送的部分
                            sent_len = tc.get("_da_sent", 0)
                            remaining = da_response[sent_len:]
                            if remaining:
                                self.tts.tts_text_queue.put(
                                    TTSMessageDTO(
                                        sentence_id=current_sentence_id,
                                        sentence_type=SentenceType.MIDDLE,
                                        content_type=ContentType.TEXT,
                                        content_detail=remaining,
                                    )
                                )
                            # 写入对话历史
                            self.tts.store_tts_text(current_sentence_id, da_response)
                            self.dialogue.put(Message(role="assistant", content=da_response))

                    if not real_tool_calls:
                        if depth == 0:
                            self.tts.tts_text_queue.put(
                                TTSMessageDTO(
                                    sentence_id=current_sentence_id,
                                    sentence_type=SentenceType.LAST,
                                    content_type=ContentType.ACTION,
                                )
                            )
                        return

                    tool_calls_list = real_tool_calls

            if not bHasError and len(tool_calls_list) > 0:
                self.logger.bind(tag=TAG).debug(
                    f"检测到 {len(tool_calls_list)} 个工具调用"
                )

                # LLM 流式阶段已播报过的文本
                streamed_text = ""
                if len(response_message) > 0:
                    if defer_plain_content_for_tools:
                        discarded_text = "".join(response_message)
                        self.logger.bind(tag=TAG).debug(
                            f"丢弃工具调用前的过渡文本: {discarded_text}"
                        )
                    else:
                        streamed_text = "".join(response_message)
                        self.tts.store_tts_text(current_sentence_id, streamed_text)
                        self.dialogue.put(Message(role="assistant", content=streamed_text))
                response_message.clear()

                # 收集所有工具调用的 Future
                futures_with_data = []
                for tool_call_data in tool_calls_list:
                    self.logger.bind(tag=TAG).debug(
                        f"function_name={tool_call_data['name']}, function_id={tool_call_data['id']}, function_arguments={tool_call_data['arguments']}"
                    )

                    # 使用公共方法上报工具调用
                    tool_input = json.loads(tool_call_data.get("arguments") or "{}")
                    enqueue_tool_report(self, tool_call_data['name'], tool_input)

                    future = asyncio.run_coroutine_threadsafe(
                        self.func_handler.handle_llm_function_call(
                            self, tool_call_data
                        ),
                        self.loop,
                    )
                    futures_with_data.append((future, tool_call_data, tool_input, time.monotonic()))

                # 工具调用超时时间，可配置，默认30秒
                tool_call_timeout = int(self.config.get("tool_call_timeout", 30))
                # 等待协程结束（实际等待时长为最慢的那个）
                tool_results = []

                for future, tool_call_data, tool_input, tool_started_at in futures_with_data:
                    try:
                        result = future.result(timeout=tool_call_timeout)
                        action_name = getattr(result.action, "name", result.action)
                        self.logger.bind(tag=TAG).info(
                            f"工具调用完成 | name={tool_call_data.get('name')} | "
                            f"action={action_name} | elapsed={time.monotonic() - tool_started_at:.3f}s"
                        )
                        self.turn_metrics.record_tool_call(
                            tool_call_data.get("name"),
                            (time.monotonic() - tool_started_at) * 1000,
                            action=action_name,
                            success=True,
                            depth=depth,
                        )
                        tool_results.append((result, tool_call_data))
                        # 使用公共方法上报工具调用结果
                        report_result = result.result if result.result else result.response
                        enqueue_tool_report(self, tool_call_data['name'], tool_input, str(report_result) if report_result else None, report_tool_call=False)

                    except Exception as e:
                        self.turn_metrics.record_tool_call(
                            tool_call_data.get("name"),
                            (time.monotonic() - tool_started_at) * 1000,
                            action="ERROR",
                            success=False,
                            depth=depth,
                        )
                        self.logger.bind(tag=TAG).error(
                            f"工具调用超时或异常: {tool_call_data['name']}, 错误: {e}"
                        )
                        # 超时时返回错误响应，避免整个流程卡死
                        tool_results.append((
                            ActionResponse(action=Action.ERROR, result="网络遇到点问题，请稍后再试下。"),
                            tool_call_data
                        ))
                        # 上报工具调用错误
                        enqueue_tool_report(self, tool_call_data['name'], tool_input, str(e), report_tool_call=False)

                # 统一处理工具调用结果
                if tool_results:
                    self._handle_function_result(
                        tool_results,
                        depth=depth,
                        streamed_text=streamed_text,
                        sentence_id=current_sentence_id,
                        abort_generation=current_abort_generation,
                        suppress_tts=meeting_tool_turn,
                    )

        if meeting_tool_turn:
            pending_meeting_turn = bool(
                meeting is not None and meeting.has_pending_turn()
            )
            if pending_meeting_turn and (response_message or content_arguments):
                self.logger.bind(tag=TAG).warning(
                    "会议激活期间主LLM未返回有效会议工具调用，丢弃普通文本并继续记录"
                )
            self._record_pending_meeting_turn("meeting_tool_not_called")
            return True

        # 存储对话内容
        if len(response_message) > 0:
            text_buff = "".join(response_message)
            if defer_plain_content_for_tools:
                self.tts.tts_text_queue.put(
                    TTSMessageDTO(
                        sentence_id=current_sentence_id,
                        sentence_type=SentenceType.MIDDLE,
                        content_type=ContentType.TEXT,
                        content_detail=text_buff,
                    )
                )
            self.tts.store_tts_text(current_sentence_id, text_buff)
            self.dialogue.put(Message(role="assistant", content=text_buff))

        if depth == 0:
            self.tts.tts_text_queue.put(
                TTSMessageDTO(
                    sentence_id=current_sentence_id,
                    sentence_type=SentenceType.LAST,
                    content_type=ContentType.ACTION,
                )
            )
            self.logger.bind(tag=TAG).info(
                f"对话处理结束 | elapsed={time.monotonic() - chat_started_at:.3f}s | "
                f"sentence_id={current_sentence_id}"
            )
            # 使用lambda延迟计算，只有在DEBUG级别时才执行get_llm_dialogue()
            self.logger.bind(tag=TAG).debug(
                lambda: json.dumps(
                    self.dialogue.get_llm_dialogue(), indent=4, ensure_ascii=False
                )
            )

        return True

    @staticmethod
    def _with_freeform_game_prompt(llm_dialogue, prompt):
        if not prompt:
            return llm_dialogue
        messages = list(llm_dialogue)
        insert_at = len(messages)
        for index in range(len(messages) - 1, -1, -1):
            if messages[index].get("role") == "user":
                insert_at = index
                break
        messages.insert(insert_at, {"role": "system", "content": prompt})
        return messages

    def _with_music_library_context(self, llm_dialogue):
        if not getattr(self, "func_handler", None):
            return llm_dialogue
        if not self.func_handler.has_tool("play_music"):
            return llm_dialogue
        try:
            from plugins_func.functions.play_music import list_music_prompt_entries

            music_entries = list_music_prompt_entries(self, limit=30)
        except Exception as e:
            self.logger.bind(tag=TAG).debug(f"构建音乐曲库上下文失败: {e}")
            return llm_dialogue
        if not music_entries:
            return llm_dialogue

        context = (
            "<music_library>\n"
            "用户询问你会放什么歌、有什么歌、曲库里有什么时，直接根据这里的本地曲库回答，不要调用 play_music。\n"
            "用户明确要求播放、唱、放某首歌或随机播放音乐时，才调用 play_music。\n"
            f"本地曲库：{'；'.join(music_entries)}\n"
            "</music_library>"
        )
        messages = list(llm_dialogue)
        insert_at = len(messages)
        for index in range(len(messages) - 1, -1, -1):
            if messages[index].get("role") == "user":
                insert_at = index
                break
        messages.insert(insert_at, {"role": "system", "content": context})
        return messages

    def _with_meeting_runtime_context(self, llm_dialogue):
        context = build_meeting_runtime_context(self)
        messages = list(llm_dialogue)
        insert_at = len(messages)
        for index in range(len(messages) - 1, -1, -1):
            if messages[index].get("role") == "user":
                insert_at = index
                break
        messages.insert(insert_at, {"role": "system", "content": context})
        return messages

    def _record_pending_meeting_turn(self, reason):
        meeting = getattr(self, "meeting_minutes", None)
        if meeting is None or not meeting.has_pending_turn():
            return 0
        appended = meeting.record_pending_turn()
        self.logger.bind(tag=TAG).info(
            f"会议主LLM工具安全回退继续记录: reason={reason}, "
            f"utteranceCount={appended}"
        )
        return appended

    def _get_enabled_plugin_configs(self):
        plugins = self.config.get("plugins", {})
        if not isinstance(plugins, dict):
            return {}

        intent_name = self.config.get("selected_module", {}).get("Intent")
        intent_config = self.config.get("Intent", {}).get(intent_name, {})
        function_names = intent_config.get("functions", [])
        if isinstance(function_names, dict):
            enabled_names = set(function_names.keys())
        elif isinstance(function_names, str):
            enabled_names = {name for name in function_names.split(";") if name}
        else:
            try:
                enabled_names = set(function_names)
            except TypeError:
                enabled_names = set()

        return {
            name: plugin_config
            for name, plugin_config in plugins.items()
            if name in enabled_names
        }

    def _build_tool_call_messages(self, tool_results_with_data):
        """Build assistant tool_calls message and tool response messages in dialogue.

        Args:
            tool_results_with_data: List of (result, tool_call_data) tuples

        Returns:
            List of all_tool_calls dicts that were inserted
        """
        all_tool_calls = [
            {
                "id": tool_call_data["id"],
                "function": {
                    "arguments": (
                        "{}"
                        if tool_call_data["arguments"] == ""
                        else tool_call_data["arguments"]
                    ),
                    "name": tool_call_data["name"],
                },
                "type": "function",
                "index": idx,
            }
            for idx, (_, tool_call_data) in enumerate(tool_results_with_data)
        ]
        self.dialogue.put(Message(role="assistant", tool_calls=all_tool_calls))

        for result, tool_call_data in tool_results_with_data:
            text = result.result or ""
            self.dialogue.put(
                Message(
                    role="tool",
                    tool_call_id=(
                        str(uuid.uuid4())
                        if tool_call_data["id"] is None
                        else tool_call_data["id"]
                    ),
                    content=text,
                )
            )

        return all_tool_calls

    def _handle_function_result(
        self,
        tool_results,
        depth,
        streamed_text="",
        sentence_id=None,
        abort_generation=None,
        suppress_tts=False,
    ):
        if abort_generation is None:
            abort_generation = self.abort_generation
        if sentence_id is None:
            sentence_id = self.sentence_id

        def is_aborted():
            return self.client_abort or abort_generation != self.abort_generation

        need_llm_tools = []
        record_tools = []

        for result, tool_call_data in tool_results:
            if is_aborted():
                return
            if result.action in [
                Action.RESPONSE,
                Action.NOTFOUND,
                Action.ERROR,
            ]:
                text = result.response if result.response else result.result
                if suppress_tts:
                    self.dialogue.put(Message(role="assistant", content=text))
                elif streamed_text and text in streamed_text:
                    self.logger.bind(tag=TAG).debug(
                        f"Skipping duplicate TTS for tool {tool_call_data['name']}, already streamed"
                    )
                else:
                    self.tts.tts_one_sentence(
                        self,
                        ContentType.TEXT,
                        content_detail=text,
                        sentence_id=sentence_id,
                    )
                    self.tts.store_tts_text(sentence_id, text)
                if not suppress_tts:
                    self.dialogue.put(Message(role="assistant", content=text))
            elif result.action == Action.REQLLM:
                need_llm_tools.append((result, tool_call_data))
            elif result.action == Action.RECORD:
                record_tools.append((result, tool_call_data))
            else:
                pass

        # Action.RECORD：写入完整工具调用链（assistant(tool_calls) → tool(result) → assistant(response)）
        # 模型从历史中学到工具调用模式，不额外调用LLM
        if record_tools:
            if is_aborted():
                return
            self._build_tool_call_messages(record_tools)

            # 用固定文本作为最终回复，补全标准三段式，保证下一条消息是 user 而非接 tool
            response_parts = []
            for result, _ in record_tools:
                resp = result.response or result.result
                if resp:
                    response_parts.append(resp)
            if response_parts:
                response_text = "，".join(response_parts)
                self.dialogue.put(Message(role="assistant", content=response_text))

        if need_llm_tools:
            if is_aborted():
                return
            self._build_tool_call_messages(need_llm_tools)

            if not is_aborted():
                self.chat(None, depth=depth + 1, allow_tools=True)

    def _report_worker(self):
        self.report_manager._report_worker(self)

    def _process_report(self, type, text, audio_data, report_time):
        self.report_manager._process_report(self, type, text, audio_data, report_time)

    def clearSpeakStatus(self):
        self.client_is_speaking = False
        self._tts_started_at = 0.0
        self.logger.bind(tag=TAG).debug(f"清除服务端讲话状态")

    async def _run_blocking_cleanup(self, callback, deadline, label):
        return await self._get_close_coordinator().run_blocking_cleanup(
            callback, deadline, label
        )

    async def _run_async_cleanup(self, callback, deadline, label):
        return await self._get_close_coordinator().run_async_cleanup(
            callback, deadline, label
        )

    async def close(self, ws=None, _deadline=None):
        """资源清理方法"""
        session_telemetry = getattr(self, "session_telemetry", None)
        if session_telemetry is not None:
            session_telemetry.set_close_reason("server_close")
        await self._get_close_coordinator().close(ws, deadline=_deadline)
        if self.closed and session_telemetry is not None:
            session_telemetry.emit_close_summary()

    def clear_queues(self):
        """清空所有任务队列"""
        if self.tts:
            self.logger.bind(tag=TAG).debug(
                f"开始清理: TTS队列大小={self.tts.tts_text_queue.qsize()}, 音频队列大小={self.tts.tts_audio_queue.qsize()}"
            )

            # 使用非阻塞方式清空队列
            for q in [
                self.tts.tts_text_queue,
                self.tts.tts_audio_queue,
            ]:
                if not q:
                    continue
                while True:
                    try:
                        q.get_nowait()
                    except queue.Empty:
                        break

            # 重置音频流控器（取消后台任务并清空队列）
            if hasattr(self, "audio_rate_controller") and self.audio_rate_controller:
                self.audio_rate_controller.reset()
                self.logger.bind(tag=TAG).debug("已重置音频流控器")

        # 排空 ASR 音频输入队列，防止旧音频包在打断后继续触发识别
        import queue as q_mod
        while True:
            try:
                self.asr_audio_queue.get_nowait()
            except q_mod.Empty:
                break

        if self.tts:
            self.logger.bind(tag=TAG).debug(
                f"清理结束: TTS队列大小={self.tts.tts_text_queue.qsize()}, 音频队列大小={self.tts.tts_audio_queue.qsize()}"
            )

    def reset_audio_states(self, preserve_voiceprint=False):
        """
        重置所有音频相关状态(VAD + ASR)
        """
        if not preserve_voiceprint and self.voiceprint_provider is not None:
            self.voiceprint_provider.reset_utterance()

        # Reset VAD states
        self.client_audio_buffer.clear()
        self.client_have_voice = False
        self.client_voice_stop = False
        self.client_voice_window.clear()
        self.last_is_voice = False
        self.vad_last_voice_time = 0.0

        # Clear ASR buffers
        self.asr_audio.clear()

        self.logger.bind(tag=TAG).debug("All audio states reset.")

    def start_voiceprint_enrollment(self, options=None):
        self.reset_audio_states(preserve_voiceprint=True)
        status = self.voiceprint_enrollment.start(options or {})
        self.logger.bind(tag=TAG).info(
            "声纹采集模式已开启: requestId={}, targetSamples={}, uploadMode={}".format(
                status.get("requestId"),
                status.get("targetSamples"),
                getattr(self.voiceprint_enrollment, "upload_mode", "chat_history"),
            )
        )
        self.notify_voiceprint_enrollment_event(
            "started",
            status,
            "声纹注册开始，请朗读屏幕上的文字，读完后保持安静。",
        )
        return status

    def stop_voiceprint_enrollment(self, reason="stopped"):
        status = self.voiceprint_enrollment.stop(reason)
        self.logger.bind(tag=TAG).info(
            "声纹采集模式已停止: requestId={}, reason={}".format(
                status.get("requestId"), reason
            )
        )
        prompt = "声纹注册已取消。" if reason != "completed" else None
        self.notify_voiceprint_enrollment_event("stopped", status, prompt)
        return status

    async def finish_voiceprint_enrollment(self, reason="manual_stop"):
        enrollment = self.voiceprint_enrollment
        if enrollment.status.active and self.asr_audio:
            audio_snapshot = self.asr_audio.copy()
            self.reset_audio_states(preserve_voiceprint=True)
            consumed = await enrollment.collect_utterance(
                self,
                audio_snapshot,
                self.audio_format,
            )
            if consumed and self.voiceprint_provider is not None:
                self.voiceprint_provider.reset_utterance()

        if enrollment.status.active:
            return self.stop_voiceprint_enrollment(reason)
        return enrollment.get_status()

    def get_voiceprint_enrollment_status(self):
        return self.voiceprint_enrollment.get_status()

    def notify_voiceprint_enrollment_event(self, event, status=None, prompt=None):
        status = status or self.get_voiceprint_enrollment_status()
        payload = {
            "type": "voiceprint_enrollment",
            "event": event,
            "session_id": self.session_id,
            "requestId": status.get("requestId"),
            "state": status.get("state"),
            "active": status.get("active"),
            "sampleCount": status.get("sampleCount", 0),
            "targetSamples": status.get("targetSamples", 0),
            "reason": status.get("reason", ""),
        }
        if prompt:
            payload["prompt"] = prompt
        self._schedule_voiceprint_enrollment_event(payload)
        if prompt:
            self._enqueue_voiceprint_enrollment_prompt(prompt)

    def _schedule_voiceprint_enrollment_event(self, payload):
        if not self.websocket or not self.loop:
            return
        try:
            asyncio.run_coroutine_threadsafe(
                self.websocket.send(json.dumps(payload, ensure_ascii=False)),
                self.loop,
            )
        except Exception as error:
            self.logger.bind(tag=TAG).warning(
                "声纹采集状态事件发送失败: errorType={}".format(type(error).__name__)
            )

    def _enqueue_voiceprint_enrollment_prompt(self, text):
        if not text or not self.tts:
            return
        try:
            with self._abort_lock:
                self.client_abort = False
            sentence_id = str(uuid.uuid4().hex)
            self.sentence_id = sentence_id
            self.tts.store_tts_text(sentence_id, text)
            self.tts.tts_text_queue.put(
                TTSMessageDTO(
                    sentence_id=sentence_id,
                    sentence_type=SentenceType.FIRST,
                    content_type=ContentType.ACTION,
                )
            )
            self.tts.tts_one_sentence(
                self,
                ContentType.TEXT,
                content_detail=text,
                sentence_id=sentence_id,
            )
            self.tts.tts_text_queue.put(
                TTSMessageDTO(
                    sentence_id=sentence_id,
                    sentence_type=SentenceType.LAST,
                    content_type=ContentType.ACTION,
                )
            )
        except Exception as error:
            self.logger.bind(tag=TAG).warning(
                "声纹采集提示语入队失败: errorType={}".format(type(error).__name__)
            )

    def chat_and_close(self, text):
        """Chat with the user and then close the connection"""
        try:
            # Use the existing chat method
            self.chat(text)

            # After chat is complete, close the connection
            self.close_after_chat = True
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"Chat and close error: {str(e)}")

    @staticmethod
    def _extract_direct_answer_response(arguments_str):
        """从 direct_answer 的参数中提取 response 值。
        优先使用 json.loads 标准解析，流式阶段 fallback 到字符串提取。
        """
        if not arguments_str:
            return ""
        # 优先尝试标准 JSON 解析（适用于完整且格式正确的 JSON）
        try:
            data = json.loads(arguments_str)
            if isinstance(data, dict) and "response" in data:
                return data["response"]
        except (json.JSONDecodeError, TypeError):
            pass
        # Fallback：流式阶段 JSON 可能不完整，使用字符串提取
        marker = '"response": "'
        idx = arguments_str.find(marker)
        if idx < 0:
            marker = '"response":"'
            idx = arguments_str.find(marker)
        if idx < 0:
            return ""
        start = idx + len(marker)
        raw = arguments_str[start:]
        # 去掉末尾的 JSON 闭合符号（如果已完整）
        if raw.endswith('"}'):
            raw = raw[:-2]
        elif raw.endswith('"'):
            raw = raw[:-1]
        # 处理 JSON 转义
        raw = raw.replace('\\"', '"').replace('\\n', '\n').replace('\\\\', '\\')
        return raw

    def _merge_tool_calls(self, tool_calls_list, tools_call):
        """合并工具调用列表

        Args:
            tool_calls_list: 已收集的工具调用列表
            tools_call: 新的工具调用
        """
        for tool_call in tools_call:
            tool_index = getattr(tool_call, "index", None)
            if tool_index is None:
                if tool_call.function.name:
                    # 有 function_name，说明是新的工具调用
                    tool_index = len(tool_calls_list)
                else:
                    tool_index = len(tool_calls_list) - 1 if tool_calls_list else 0

            # 确保列表有足够的位置
            if tool_index >= len(tool_calls_list):
                tool_calls_list.append({"id": "", "name": "", "arguments": ""})

            # 更新工具调用信息
            if tool_call.id:
                tool_calls_list[tool_index]["id"] = tool_call.id
            if tool_call.function.name:
                tool_calls_list[tool_index]["name"] = tool_call.function.name
            if tool_call.function.arguments:
                tool_calls_list[tool_index]["arguments"] += tool_call.function.arguments
