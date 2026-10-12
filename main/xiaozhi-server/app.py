import os
import sys
import uuid
import signal
import asyncio
from aioconsole import ainput
from config.settings import load_config
from config.logger import setup_logging
from core.utils.util import get_local_ip, validate_mcp_endpoint, is_placeholder_value
from core.http_server import SimpleHttpServer
from core.websocket_server import WebSocketServer
from core.utils.util import check_ffmpeg_installed
from core.utils.gc_manager import get_gc_manager
from core.metering.service import MeteringService

TAG = __name__
logger = setup_logging()


async def wait_for_exit() -> None:
    """
    阻塞直到收到 Ctrl‑C / SIGTERM。
    - Unix: 使用 add_signal_handler
    - Windows: 依赖 KeyboardInterrupt
    """
    loop = asyncio.get_running_loop()
    stop_event = asyncio.Event()

    if sys.platform != "win32":  # Unix / macOS
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, stop_event.set)
        await stop_event.wait()
    else:
        # Windows：await一个永远pending的fut，
        # 让 KeyboardInterrupt 冒泡到 asyncio.run，以此消除遗留普通线程导致进程退出阻塞的问题
        try:
            await asyncio.Future()
        except KeyboardInterrupt:  # Ctrl‑C
            pass


async def monitor_stdin():
    """监控标准输入，消费回车键"""
    while True:
        await ainput()  # 异步等待输入，消费回车


async def main():
    check_ffmpeg_installed()
    config = load_config()
    metering = MeteringService(config)
    await metering.start()

    # Validate configured providers at startup
    from core.utils.provider_validator import validate_all_providers
    provider_warnings = validate_all_providers(config)
    for warning in provider_warnings:
        logger.bind(tag="ProviderValidator").warning(warning)

    # auth_key优先级：配置文件server.auth_key > manager-api.secret > data/.auth_key文件 > 自动生成
    # auth_key用于jwt认证，比如视觉分析接口的jwt认证、ota接口的token生成与websocket认证
    # 持久化到 data/.auth_key 文件，确保重启后设备token仍然有效
    auth_key_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", ".auth_key")
    auth_key = config["server"].get("auth_key", "")

    # 验证auth_key，无效则尝试使用manager-api.secret
    if not auth_key or len(auth_key) == 0 or is_placeholder_value(auth_key):
        auth_key = config.get("manager-api", {}).get("secret", "")
        # 验证secret，无效则尝试从持久文件读取
        if not auth_key or len(auth_key) == 0 or is_placeholder_value(auth_key):
            if os.path.exists(auth_key_file):
                with open(auth_key_file, "r") as f:
                    auth_key = f.read().strip()
            # 文件中也无效，生成随机密钥
            if not auth_key or len(auth_key) == 0 or is_placeholder_value(auth_key):
                auth_key = str(uuid.uuid4().hex)

    config["server"]["auth_key"] = auth_key

    # 持久化auth_key到文件，确保重启后一致
    os.makedirs(os.path.dirname(auth_key_file), exist_ok=True)
    with open(auth_key_file, "w") as f:
        f.write(auth_key)

    # 后台/nohup 启动时 stdin 不可交互，跳过 stdin 监听，避免服务立即退出
    stdin_task = None
    if sys.stdin.isatty():
        stdin_task = asyncio.create_task(monitor_stdin())

    # 启动全局GC管理器（5分钟清理一次）
    gc_manager = get_gc_manager(interval_seconds=300)
    await gc_manager.start()

    # 启动 WebSocket 服务器
    ws_server = WebSocketServer(config)
    ws_task = asyncio.create_task(ws_server.start())
    # 启动 Simple http 服务器
    ota_server = SimpleHttpServer(config)
    ota_task = asyncio.create_task(ota_server.start())

    read_config_from_api = config.get("read_config_from_api", False)
    port = int(config["server"].get("http_port", 8003))
    if not read_config_from_api:
        logger.bind(tag=TAG).info(
            "OTA接口是\t\thttp://{}:{}/xiaozhi/ota/",
            get_local_ip(),
            port,
        )
    logger.bind(tag=TAG).info(
        "视觉分析接口是\thttp://{}:{}/mcp/vision/explain",
        get_local_ip(),
        port,
    )
    mcp_endpoint = config.get("mcp_endpoint", None)
    if mcp_endpoint is not None and not is_placeholder_value(mcp_endpoint):
        # 校验MCP接入点格式
        if validate_mcp_endpoint(mcp_endpoint):
            logger.bind(tag=TAG).info("mcp接入点是\t{}", mcp_endpoint)
            # 将mcp计入点地址转成调用点
            mcp_endpoint = mcp_endpoint.replace("/mcp/", "/call/")
            config["mcp_endpoint"] = mcp_endpoint
        else:
            logger.bind(tag=TAG).error("mcp接入点不符合规范")
            config["mcp_endpoint"] = "你的接入点 websocket地址"

    # 获取WebSocket配置，使用安全的默认值
    websocket_port = 8000
    server_config = config.get("server", {})
    if isinstance(server_config, dict):
        websocket_port = int(server_config.get("port", 8000))

    logger.bind(tag=TAG).info(
        "Websocket地址是\tws://{}:{}/xiaozhi/v1/",
        get_local_ip(),
        websocket_port,
    )

    logger.bind(tag=TAG).info(
        "=======上面的地址是websocket协议地址，请勿用浏览器访问======="
    )
    logger.bind(tag=TAG).info("如需测试WebSocket，请使用真实ESP32设备或WebSocket客户端")
    logger.bind(tag=TAG).info(
        "=============================================================\n"
    )

    try:
        await wait_for_exit()  # 阻塞直到收到退出信号
    except asyncio.CancelledError:
        print("任务被取消，清理资源中...")
    finally:
        await metering.stop()
        # 停止全局GC管理器
        await gc_manager.stop()

        # 取消所有任务（关键修复点）
        if stdin_task:
            stdin_task.cancel()
        ws_task.cancel()
        if ota_task:
            ota_task.cancel()

        # 等待任务终止（必须加超时）
        tasks = [task for task in [stdin_task, ws_task, ota_task] if task]
        await asyncio.wait(
            tasks,
            timeout=3.0,
            return_when=asyncio.ALL_COMPLETED,
        )
        print("服务器已关闭，程序退出。")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("手动中断，程序终止。")
