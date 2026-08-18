from typing import Any, Dict

from core.handle.textMessageHandler import TextMessageHandler
from core.handle.textMessageType import TextMessageType
from core.reception import handle_reception_event


class ReceptionTextMessageHandler(TextMessageHandler):
    @property
    def message_type(self) -> TextMessageType:
        return TextMessageType.RECEPTION

    async def handle(self, conn, msg_json: Dict[str, Any]) -> None:
        await handle_reception_event(conn, msg_json)
