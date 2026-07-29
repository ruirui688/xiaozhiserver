import asyncio
from aiohttp import web
from config.logger import setup_logging
from core.utils.util import is_placeholder_value
from core.api.ota_handler import OTAHandler
from core.api.vision_handler import VisionHandler
from core.api.device_mcp_handler import DeviceMcpHandler
from core.api.benchmark_handler import BenchmarkHandler
from core.api.voiceprint_enrollment_handler import VoiceprintEnrollmentHandler
from core.api.device_notification_handler import DeviceNotificationHandler
from core.api.chat_history_runtime_handler import ChatHistoryRuntimeHandler

TAG = __name__


class SimpleHttpServer:
    def __init__(self, config: dict):
        self.config = config
        self.logger = setup_logging()
        self.ota_handler = OTAHandler(config)
        self.vision_handler = VisionHandler(config)
        self.device_mcp_handler = DeviceMcpHandler(config)
        self.benchmark_handler = BenchmarkHandler(config)
        self.voiceprint_enrollment_handler = VoiceprintEnrollmentHandler(config)
        self.device_notification_handler = DeviceNotificationHandler(config)
        self.chat_history_runtime_handler = ChatHistoryRuntimeHandler(config)

    def _get_websocket_url(self, local_ip: str, port: int) -> str:
        """获取websocket地址

        Args:
            local_ip: 本地IP地址
            port: 端口号

        Returns:
            str: websocket地址
        """
        server_config = self.config["server"]
        websocket_config = server_config.get("websocket")

        if websocket_config and not is_placeholder_value(websocket_config):
            return websocket_config
        else:
            return f"ws://{local_ip}:{port}/xiaozhi/v1/"

    async def start(self):
        try:
            server_config = self.config["server"]
            read_config_from_api = self.config.get("read_config_from_api", False)
            host = server_config.get("ip", "0.0.0.0")
            port = int(server_config.get("http_port", 8003))

            if port:
                app = web.Application()

                if not read_config_from_api:
                    # 如果没有开启智控台，只是单模块运行，就需要再添加简单OTA接口，用于下发websocket接口
                    app.add_routes(
                        [
                            web.get("/xiaozhi/ota/", self.ota_handler.handle_get),
                            web.post("/xiaozhi/ota/", self.ota_handler.handle_post),
                            web.options(
                                "/xiaozhi/ota/", self.ota_handler.handle_options
                            ),
                            # 下载接口，仅提供 data/bin/*.bin 下载
                            web.get(
                                "/xiaozhi/ota/download/{filename}",
                                self.ota_handler.handle_download,
                            ),
                            web.options(
                                "/xiaozhi/ota/download/{filename}",
                                self.ota_handler.handle_options,
                            ),
                        ]
                    )
                # 添加路由
                app.add_routes(
                    [
                        web.get("/mcp/vision/explain", self.vision_handler.handle_get),
                        web.post(
                            "/mcp/vision/explain", self.vision_handler.handle_post
                        ),
                        web.options(
                            "/mcp/vision/explain", self.vision_handler.handle_options
                        ),
                        web.get(
                            "/mcp/vision/images/{filename}",
                            self.vision_handler.handle_image,
                        ),
                        web.options(
                            "/mcp/vision/images/{filename}",
                            self.vision_handler.handle_options,
                        ),
                        web.get(
                            "/device/mcp/tools", self.device_mcp_handler.handle_tools
                        ),
                        web.options(
                            "/device/mcp/tools", self.device_mcp_handler.handle_options
                        ),
                        web.get(
                            "/device/mcp/status", self.device_mcp_handler.handle_status
                        ),
                        web.options(
                            "/device/mcp/status", self.device_mcp_handler.handle_options
                        ),
                        web.post(
                            "/device/mcp/call", self.device_mcp_handler.handle_call
                        ),
                        web.options(
                            "/device/mcp/call", self.device_mcp_handler.handle_options
                        ),
                        web.post(
                            "/device/voiceprint/enrollment/start",
                            self.voiceprint_enrollment_handler.handle_start,
                        ),
                        web.options(
                            "/device/voiceprint/enrollment/start",
                            self.voiceprint_enrollment_handler.handle_options,
                        ),
                        web.post(
                            "/device/voiceprint/enrollment/stop",
                            self.voiceprint_enrollment_handler.handle_stop,
                        ),
                        web.options(
                            "/device/voiceprint/enrollment/stop",
                            self.voiceprint_enrollment_handler.handle_options,
                        ),
                        web.get(
                            "/device/voiceprint/enrollment/status",
                            self.voiceprint_enrollment_handler.handle_status,
                        ),
                        web.options(
                            "/device/voiceprint/enrollment/status",
                            self.voiceprint_enrollment_handler.handle_options,
                        ),
                        web.post(
                            "/device/voiceprint/refresh",
                            self.voiceprint_enrollment_handler.handle_refresh,
                        ),
                        web.options(
                            "/device/voiceprint/refresh",
                            self.voiceprint_enrollment_handler.handle_options,
                        ),
                        web.post(
                            "/device/notifications/broadcast",
                            self.device_notification_handler.handle_broadcast,
                        ),
                        web.options(
                            "/device/notifications/broadcast",
                            self.device_notification_handler.handle_options,
                        ),
                        web.post(
                            "/device/chat-history/refresh",
                            self.chat_history_runtime_handler.handle_refresh,
                        ),
                        web.options(
                            "/device/chat-history/refresh",
                            self.chat_history_runtime_handler.handle_options,
                        ),
                        web.post(
                            "/benchmark/models", self.benchmark_handler.handle_models
                        ),
                        web.options(
                            "/benchmark/models", self.benchmark_handler.handle_options
                        ),
                        web.get(
                            "/benchmark/health", self.benchmark_handler.handle_health
                        ),
                        web.options(
                            "/benchmark/health", self.benchmark_handler.handle_options
                        ),
                    ]
                )

                # 运行服务
                runner = web.AppRunner(app)
                await runner.setup()
                site = web.TCPSite(runner, host, port)
                await site.start()

                # 保持服务运行
                while True:
                    await asyncio.sleep(3600)  # 每隔 1 小时检查一次
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"HTTP服务器启动失败: {e}")
            import traceback

            self.logger.bind(tag=TAG).error(f"错误堆栈: {traceback.format_exc()}")
            raise
