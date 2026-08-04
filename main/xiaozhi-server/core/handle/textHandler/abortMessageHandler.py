from typing import Dict, Any

from core.handle.abortHandle import handleAbortMessage
from core.handle.textMessageHandler import TextMessageHandler
from core.handle.textMessageType import TextMessageType


class AbortTextMessageHandler(TextMessageHandler):
    """Abort消息处理器"""

    @property
    def message_type(self) -> TextMessageType:
        return TextMessageType.ABORT

    async def handle(self, conn, msg_json: Dict[str, Any]) -> None:
        conversation_router = getattr(conn, "conversation_router", None)
        if conversation_router is not None and conversation_router.is_realtime:
            try:
                await conversation_router.handle_abort()
            except Exception as error:
                conn.logger.bind(tag=__name__).warning(
                    "Realtime上游取消失败，继续收敛本地打断状态: errorType={}",
                    type(error).__name__,
                )
            await handleAbortMessage(conn)
            return
        await handleAbortMessage(conn)
