from typing import Any, Dict

from core.device_face_profiles import handle_face_recognition_event
from core.handle.textMessageHandler import TextMessageHandler
from core.handle.textMessageType import TextMessageType


class FaceRecognitionTextMessageHandler(TextMessageHandler):
    """处理已认证、已绑定设备的本地人脸识别事件。"""

    @property
    def message_type(self) -> TextMessageType:
        return TextMessageType.FACE_RECOGNITION

    async def handle(self, conn, msg_json: Dict[str, Any]) -> None:
        await handle_face_recognition_event(conn, msg_json)
