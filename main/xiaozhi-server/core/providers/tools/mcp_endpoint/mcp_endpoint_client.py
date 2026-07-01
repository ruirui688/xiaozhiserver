"""MCP接入点客户端定义"""

from ..base import BaseMCPClient


class MCPEndpointClient(BaseMCPClient):
    """MCP接入点客户端，用于管理MCP接入点状态和工具"""

    def __init__(self, conn=None):
        super().__init__()
        self.conn = conn
        self.websocket = None  # WebSocket连接
        self.endpoint_url = None  # 接入点URL，用于重连
        self.permanent_failure = False  # 永久故障标志

    def set_websocket(self, websocket):
        """设置WebSocket连接"""
        self.websocket = websocket

    async def send_message(self, message: str):
        """发送消息到MCP接入点"""
        if self.websocket:
            await self.websocket.send(message)
        else:
            raise RuntimeError("WebSocket连接未建立")

    async def close(self):
        """关闭WebSocket连接"""
        if self.websocket:
            await self.websocket.close()
            self.websocket = None
