from typing import Any, Dict

from core.external_tts import external_tts_coordinator
from core.handle.textMessageHandler import TextMessageHandler
from core.handle.textMessageType import TextMessageType


class ExternalTTSTextMessageHandler(TextMessageHandler):
    """Handle exact-text TTS requests from the authenticated device socket."""

    @property
    def message_type(self) -> TextMessageType:
        return TextMessageType.EXTERNAL_TTS

    async def handle(self, conn, msg_json: Dict[str, Any]) -> None:
        await external_tts_coordinator.handle_message(conn, msg_json)
