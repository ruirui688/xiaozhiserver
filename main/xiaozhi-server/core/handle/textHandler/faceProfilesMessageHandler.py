from typing import Any, Dict

from core.device_face_profiles import handle_face_profile_message
from core.handle.textMessageHandler import TextMessageHandler
from core.handle.textMessageType import TextMessageType


class FaceProfilesTextMessageHandler(TextMessageHandler):
    """处理 v2 人脸库本地缓存版本协商。"""

    @property
    def message_type(self) -> TextMessageType:
        return TextMessageType.FACE_PROFILES

    async def handle(self, conn, msg_json: Dict[str, Any]) -> None:
        await handle_face_profile_message(conn, msg_json)
