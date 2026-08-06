import json
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
from core.handle.textMessageHandlerRegistry import TextMessageHandlerRegistry

TAG = __name__


class TextMessageProcessor:
    """消息处理器主类"""

    def __init__(self, registry: TextMessageHandlerRegistry):
        self.registry = registry

    async def process_message(self, conn: "ConnectionHandler", message: str) -> None:
        """处理消息的主入口"""
        try:
            # 解析JSON消息
            msg_json = json.loads(message)

            # 处理JSON消息
            if isinstance(msg_json, dict):
                message_type = msg_json.get("type")

                # 记录日志
                log_message = message
                if message_type == "listen" and "context" in msg_json:
                    redacted = dict(msg_json)
                    context = redacted.get("context")
                    context_type = (
                        "string" if isinstance(context, str) else type(context).__name__
                    )
                    redacted["context"] = f"<redacted:{context_type}>"
                    if redacted.get("source") == "device_asr" and "text" in redacted:
                        text = redacted.get("text")
                        text_length = len(text) if isinstance(text, str) else 0
                        redacted["text"] = f"<redacted:{text_length} chars>"
                    log_message = json.dumps(redacted, ensure_ascii=False)
                elif (
                    message_type == "listen"
                    and msg_json.get("source") == "device_asr"
                    and "text" in msg_json
                ):
                    redacted = dict(msg_json)
                    text = redacted.get("text")
                    text_length = len(text) if isinstance(text, str) else 0
                    redacted["text"] = f"<redacted:{text_length} chars>"
                    log_message = json.dumps(redacted, ensure_ascii=False)
                elif message_type == "external_tts" and "text" in msg_json:
                    redacted = dict(msg_json)
                    text = redacted.get("text")
                    text_length = len(text) if isinstance(text, str) else 0
                    redacted["text"] = f"<redacted:{text_length} chars>"
                    log_message = json.dumps(redacted, ensure_ascii=False)
                conn.logger.bind(tag=TAG).info(
                    f"收到{message_type}消息：{log_message}"
                )

                # 获取并执行处理器
                handler = self.registry.get_handler(message_type)
                if handler:
                    await handler.handle(conn, msg_json)
                else:
                    conn.logger.bind(tag=TAG).error(f"收到未知类型消息：{message}")
            # 处理纯数字消息
            elif isinstance(msg_json, int):
                conn.logger.bind(tag=TAG).info(f"收到数字消息：{message}")
                await conn.websocket.send(message)

        except json.JSONDecodeError:
            # 非JSON消息直接转发
            conn.logger.bind(tag=TAG).error(f"解析到错误的消息：{message}")
            await conn.websocket.send(message)
