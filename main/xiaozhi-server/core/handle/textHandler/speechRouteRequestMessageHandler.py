import json
import re
import time
from collections import deque
from typing import Any, Dict

from config.manage_api_client import update_device_speech_input_policy
from core.handle.textMessageHandler import TextMessageHandler
from core.handle.textMessageType import TextMessageType


_REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9_.:-]{1,128}$")
_INPUT_POLICIES = {"server", "device", "auto"}


class SpeechRouteRequestTextMessageHandler(TextMessageHandler):
    """Persist a route request for the device owning this authenticated socket."""

    RATE_LIMIT = 4
    RATE_WINDOW_SECONDS = 5.0

    @property
    def message_type(self) -> TextMessageType:
        return TextMessageType.SPEECH_ROUTE_REQUEST

    async def handle(self, conn, msg_json: Dict[str, Any]) -> None:
        request_id = str(msg_json.get("request_id") or "").strip()
        input_policy = str(msg_json.get("inputPolicy") or "").strip().lower()
        device_id = str(getattr(conn, "device_id", None) or "").strip()

        if request_id and not _REQUEST_ID_PATTERN.fullmatch(request_id):
            await self._send_ack(conn, request_id, input_policy, "invalid_request_id")
            return
        if input_policy not in _INPUT_POLICIES:
            await self._send_ack(conn, request_id, input_policy, "invalid_input_policy")
            return
        if not device_id or not getattr(conn, "read_config_from_api", False):
            await self._send_ack(conn, request_id, input_policy, "route_management_unavailable")
            return
        if not self._admit_request(conn):
            await self._send_ack(conn, request_id, input_policy, "rate_limited")
            return

        try:
            await update_device_speech_input_policy(device_id, input_policy)
        except Exception as error:
            conn.logger.bind(tag=__name__).warning(
                "设备语音输入路由保存失败: deviceId={}, errorType={}",
                device_id,
                type(error).__name__,
            )
            await self._send_ack(conn, request_id, input_policy, "persistence_failed")
            return

        await self._send_ack(conn, request_id, input_policy, "", accepted=True)

    @classmethod
    def _admit_request(cls, conn) -> bool:
        now = time.monotonic()
        timestamps = getattr(conn, "_speech_route_request_times", None)
        if timestamps is None:
            timestamps = deque()
            conn._speech_route_request_times = timestamps
        cutoff = now - cls.RATE_WINDOW_SECONDS
        while timestamps and timestamps[0] <= cutoff:
            timestamps.popleft()
        if len(timestamps) >= cls.RATE_LIMIT:
            return False
        timestamps.append(now)
        return True

    @staticmethod
    async def _send_ack(
        conn,
        request_id: str,
        input_policy: str,
        reason: str,
        *,
        accepted: bool = False,
    ) -> None:
        payload = {
            "type": "speech_route_request_ack",
            "session_id": conn.session_id,
            "request_id": request_id,
            "inputPolicy": input_policy,
            "accepted": accepted,
            "pending": accepted,
        }
        if reason:
            payload["reason"] = reason
        await conn.websocket.send(json.dumps(payload, ensure_ascii=False))
