import asyncio
import logging
from urllib.parse import parse_qs, urlparse

import websockets
from config.logger import setup_logging


class SuppressInvalidHandshakeFilter(logging.Filter):
    """过滤掉无效握手错误日志（如HTTPS访问WS端口）"""

    def filter(self, record):
        msg = record.getMessage()
        suppress_keywords = [
            "opening handshake failed",
            "did not receive a valid HTTP request",
            "connection closed while reading HTTP request",
            "line without CRLF",
        ]
        return not any(keyword in msg for keyword in suppress_keywords)


def _setup_websockets_logger():
    """配置 websockets 相关的所有 logger，过滤无效握手错误"""
    filter_instance = SuppressInvalidHandshakeFilter()
    for logger_name in ["websockets", "websockets.server", "websockets.client"]:
        logger = logging.getLogger(logger_name)
        logger.addFilter(filter_instance)


_setup_websockets_logger()


from core.connection import ConnectionHandler
from config.config_loader import get_config_from_api_async
from core.auth import AuthManager, AuthenticationError, is_mobile_device
from core.observability import AUDIO_PREVIEW_PATH, AudioPreviewHub
from core.utils.modules_initialize import initialize_modules
from core.utils.util import check_vad_update, check_asr_update

TAG = __name__
DEFAULT_MAX_DEVICE_CONNECTIONS = 64


def _max_device_connections(server_config: dict) -> int:
    try:
        configured = int(
            server_config.get(
                "max_device_connections", DEFAULT_MAX_DEVICE_CONNECTIONS
            )
        )
    except (TypeError, ValueError):
        configured = DEFAULT_MAX_DEVICE_CONNECTIONS
    return max(1, configured)


class WebSocketServer:
    def __init__(self, config: dict):
        self.config = config
        self.logger = setup_logging()
        self.config_lock = asyncio.Lock()
        selected_module = self.config.get("selected_module", {})
        modules = initialize_modules(
            self.logger,
            self.config,
            "VAD" in selected_module,
            "ASR" in selected_module,
            "LLM" in selected_module,
            False,
            "Memory" in selected_module,
            "Intent" in selected_module,
        )
        self._vad = modules["vad"] if "vad" in modules else None
        self._asr = modules["asr"] if "asr" in modules else None
        self._llm = modules["llm"] if "llm" in modules else None
        self._intent = modules["intent"] if "intent" in modules else None
        self._memory = modules["memory"] if "memory" in modules else None

        # Track active connection handlers for cache invalidation
        self.active_handlers = set()
        self.active_mobile_devices = set()
        self.max_device_connections = _max_device_connections(
            self.config.get("server", {})
        )

        auth_config = self.config["server"].get("auth", {})
        self.auth_enable = auth_config.get("enabled", True)
        # 设备白名单
        self.allowed_devices = set(auth_config.get("allowed_devices", []))
        secret_key = self.config["server"]["auth_key"]
        expire_seconds = auth_config.get("expire_seconds", None)
        self.auth = AuthManager(secret_key=secret_key, expire_seconds=expire_seconds)
        self.audio_preview_hub = AudioPreviewHub(self.auth, self.logger)

    async def start(self):
        server_config = self.config["server"]
        host = server_config.get("ip", "0.0.0.0")
        port = int(server_config.get("port", 8000))

        async with websockets.serve(
            self._handle_connection, host, port, process_request=self._http_response
        ):
            await asyncio.Future()

    async def _handle_connection(self, websocket: websockets.ServerConnection):
        request_path = urlparse(websocket.request.path or "").path.rstrip("/")
        if request_path == AUDIO_PREVIEW_PATH:
            await self.audio_preview_hub.handle_connection(websocket)
            return

        headers = dict(websocket.request.headers)
        if headers.get("device-id", None) is None:
            # 尝试从 URL 的查询参数中获取 device-id
            # 从 WebSocket 请求中获取路径
            request_path = websocket.request.path
            if not request_path:
                self.logger.bind(tag=TAG).error("无法获取请求路径")
                await websocket.close()
                return
            parsed_url = urlparse(request_path)
            query_params = parse_qs(parsed_url.query)

            def _sanitize_header_value(value: str) -> str:
                """Strip control characters before any identity/security decisions."""
                return value.replace("\r", "").replace("\n", "").replace("\x00", "")

            if any(is_mobile_device(_sanitize_header_value(value)) for value in query_params.get("device-id", [])):
                # Mobile tickets belong in native HTTP headers, never URLs
                # which are routinely persisted by proxies and access logs.
                await websocket.send("认证失败")
                await websocket.close()
                return

            if "device-id" not in query_params:
                await websocket.send("端口正常，如需测试连接，请使用ESP32设备或WebSocket客户端")
                await websocket.close()
                return
            else:
                websocket.request.headers["device-id"] = _sanitize_header_value(query_params["device-id"][0])
            if "client-id" in query_params:
                websocket.request.headers["client-id"] = _sanitize_header_value(query_params["client-id"][0])
            if "authorization" in query_params:
                websocket.request.headers["authorization"] = _sanitize_header_value(
                    query_params["authorization"][0]
                )

        """处理新连接，每次创建独立的ConnectionHandler"""
        # 先认证，后建立连接
        try:
            await self._handle_auth(websocket)
        except AuthenticationError as e:
            auth_headers = dict(websocket.request.headers)
            self.logger.bind(tag=TAG).warning(
                "WebSocket认证失败: device-id={}, client-id={}, reason={}".format(
                    auth_headers.get("device-id", ""),
                    auth_headers.get("client-id", ""),
                    str(e),
                )
            )
            await websocket.send("认证失败")
            await websocket.close()
            return
        if len(self.active_handlers) >= self.max_device_connections:
            self.logger.bind(tag=TAG).warning(
                "WebSocket连接数已达上限: active={}, limit={}",
                len(self.active_handlers),
                self.max_device_connections,
            )
            await websocket.close(code=1013, reason="server capacity reached")
            return
        mobile_device_id = websocket.request.headers.get("device-id", "")
        mobile_device_id = mobile_device_id if is_mobile_device(mobile_device_id) else None
        if mobile_device_id is not None and mobile_device_id in self.active_mobile_devices:
            await websocket.close(code=1013, reason="mobile session already active")
            return
        # 创建ConnectionHandler时传入当前server实例
        handler = ConnectionHandler(
            self.config,
            self._vad,
            self._asr,
            self._llm,
            self._memory,
            self._intent,
            self,  # 传入server实例
        )
        self.active_handlers.add(handler)
        if mobile_device_id is not None:
            # No await between checking capacity and reserving the identity.
            # Keep the reservation through cleanup, including initialisation.
            self.active_mobile_devices.add(mobile_device_id)
        try:
            if mobile_device_id is not None:
                # Limit paid mobile debugging sessions; the App also closes on leaving the foreground.
                await asyncio.wait_for(handler.handle_connection(websocket), timeout=1800)
            else:
                await handler.handle_connection(websocket)
        except asyncio.TimeoutError:
            self.logger.bind(tag=TAG).info(
                "WebSocket会话超时: mobile={}", mobile_device_id is not None
            )
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"处理连接时出错: {e}")
        finally:
            self.active_handlers.discard(handler)
            if mobile_device_id is not None:
                self.active_mobile_devices.discard(mobile_device_id)
            # 强制关闭连接（如果还没有关闭的话）
            try:
                # 安全地检查WebSocket状态并关闭
                if hasattr(websocket, "closed") and not websocket.closed:
                    await websocket.close()
                elif hasattr(websocket, "state") and websocket.state.name != "CLOSED":
                    await websocket.close()
                else:
                    # 如果没有closed属性，直接尝试关闭
                    await websocket.close()
            except Exception as close_error:
                self.logger.bind(tag=TAG).error(
                    f"服务器端强制关闭连接时出错: {close_error}"
                )

    async def _http_response(self, websocket, request_headers):
        # 检查是否为 WebSocket 升级请求
        if request_headers.headers.get("connection", "").lower() == "upgrade":
            # 如果是 WebSocket 请求，返回 None 允许握手继续
            return None
        else:
            # 如果是普通 HTTP 请求，返回 "server is running"
            return websocket.respond(200, "Server is running\n")

    async def update_config(self) -> bool:
        """更新服务器配置并重新初始化组件

        Returns:
            bool: 更新是否成功
        """
        try:
            async with self.config_lock:
                # 重新获取配置（使用异步版本）
                new_config = await get_config_from_api_async(self.config)
                if new_config is None:
                    self.logger.bind(tag=TAG).error("获取新配置失败")
                    return False
                self.logger.bind(tag=TAG).info(f"获取新配置成功")
                # 检查 VAD 和 ASR 类型是否需要更新
                update_vad = check_vad_update(self.config, new_config)
                update_asr = check_asr_update(self.config, new_config)
                self.logger.bind(tag=TAG).info(
                    f"检查VAD和ASR类型是否需要更新: {update_vad} {update_asr}"
                )
                # 更新配置
                self.config = new_config
                self.max_device_connections = _max_device_connections(
                    new_config.get("server", {})
                )
                selected_module = new_config.get("selected_module", {})
                # 重新初始化组件
                modules = initialize_modules(
                    self.logger,
                    new_config,
                    update_vad,
                    update_asr,
                    "LLM" in selected_module,
                    False,
                    "Memory" in selected_module,
                    "Intent" in selected_module,
                )

                # 更新组件实例
                if "vad" in modules:
                    self._vad = modules["vad"]
                if "asr" in modules:
                    self._asr = modules["asr"]
                if "llm" in modules:
                    self._llm = modules["llm"]
                if "intent" in modules:
                    self._intent = modules["intent"]
                if "memory" in modules:
                    self._memory = modules["memory"]
                # Invalidate all device prompt caches so next request rebuilds with new config
                for handler in self.active_handlers:
                    if hasattr(handler, 'prompt_manager') and handler.prompt_manager:
                        handler.prompt_manager.invalidate_device_cache()
                self.logger.bind(tag=TAG).info(f"更新配置任务执行完毕")
                return True
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"更新服务器配置失败: {str(e)}")
            return False

    async def _handle_auth(self, websocket: websockets.ServerConnection):
        # 先认证，后建立连接
        is_mobile = is_mobile_device(websocket.request.headers.get("device-id", ""))
        if is_mobile:
            query = parse_qs(urlparse(websocket.request.path or "").query)
            if any(key.lower() in {"authorization", "token", "access_token"} for key in query):
                raise AuthenticationError("Mobile credentials require HTTP headers")
        if self.auth_enable or is_mobile:
            headers = dict(websocket.request.headers)
            device_id = headers.get("device-id", None)
            client_id = headers.get("client-id", None)
            if self.allowed_devices and device_id in self.allowed_devices and not is_mobile:
                # 如果属于白名单内的设备，不校验token，直接放行
                return
            else:
                # 否则校验token
                token = headers.get("authorization", "")
                if token.startswith("Bearer "):
                    token = token[7:]  # 移除'Bearer '前缀
                else:
                    raise AuthenticationError("Missing or invalid Authorization header")
                self.logger.bind(tag=TAG).info(
                    f"WebSocket认证校验: device-id={device_id}, client-id={client_id}, tokenPresent={bool(token)}"
                )
                # 进行认证
                auth_success = self.auth.verify_token(
                    token, client_id=client_id, username=device_id
                )
                if not auth_success:
                    raise AuthenticationError("Invalid token")
