from typing import TYPE_CHECKING, Any, Dict

from core.handle.textMessageHandler import TextMessageHandler
from core.handle.textMessageType import TextMessageType

if TYPE_CHECKING:
    from core.connection import ConnectionHandler


class TTSAckTextMessageHandler(TextMessageHandler):
    """Resolve device-side playback acknowledgements for device TTS."""

    @property
    def message_type(self) -> TextMessageType:
        return TextMessageType.TTS_ACK

    async def handle(self, conn: "ConnectionHandler", msg_json: Dict[str, Any]) -> None:
        runtime = getattr(conn, "speech_runtime", None)
        if runtime is None or not runtime.handle_tts_ack(msg_json):
            conn.logger.bind(tag=__name__).warning(
                "忽略无效或过期设备TTS ACK: sentenceId={}, event={}",
                msg_json.get("sentence_id") or msg_json.get("sentenceId"),
                msg_json.get("event"),
            )
