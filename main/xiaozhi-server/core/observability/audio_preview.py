"""Authenticated, non-blocking browser preview of device downlink audio."""

import asyncio
import json
import time
from dataclasses import dataclass
from typing import Any

import websockets


AUDIO_PREVIEW_PATH = "/xiaozhi/audio-preview"
AUTH_TIMEOUT_SECONDS = 5
TICKET_MAX_AGE_SECONDS = 60
MAX_AUTH_MESSAGE_BYTES = 4096
PREVIEW_QUEUE_SIZE = 64
MAX_TRACKED_STREAMS = 1024
PREVIEW_CLIENT_PREFIX = "web-audio-preview-"


@dataclass
class _PreviewSubscriber:
    websocket: Any
    client_id: str
    queue: asyncio.Queue
    discontinuity_pending: bool = False


class AudioPreviewHub:
    """Fan out exact Opus packets without awaiting the preview connection."""

    def __init__(self, auth, logger):
        self.auth = auth
        self.logger = logger
        self._subscribers = {}
        self._active_streams = {}
        self._lock = asyncio.Lock()

    async def handle_connection(self, websocket):
        subscriber = None
        device_id = None
        registered = False
        try:
            auth_message = await asyncio.wait_for(
                websocket.recv(), timeout=AUTH_TIMEOUT_SECONDS
            )
            credentials = self._parse_auth_message(auth_message)
            device_id = credentials["deviceId"]
            client_id = credentials["clientId"]
            token = credentials["token"]
            if not self._verify_ticket(token, client_id, device_id):
                await self._send_error(websocket, "authentication_failed")
                await self._close(websocket, code=4401, reason="authentication_failed")
                return

            subscriber = _PreviewSubscriber(
                websocket=websocket,
                client_id=client_id,
                queue=asyncio.Queue(maxsize=PREVIEW_QUEUE_SIZE),
            )
            async with self._lock:
                current = self._subscribers.get(device_id)
                if current is not None:
                    await self._send_json(websocket, {"type": "listener_busy"})
                    await self._close(websocket, code=4409, reason="listener_busy")
                    return
                self._subscribers[device_id] = subscriber
                registered = True

            await self._send_json(
                websocket,
                {
                    "type": "ready",
                    "deviceId": device_id,
                    "format": "opus",
                    "channels": 1,
                },
            )
            active_stream = self._active_streams.get(device_id)
            if active_stream is not None:
                await self._send_json(websocket, active_stream)
            self.logger.bind(tag=__name__).info(
                f"设备真实音频监听已连接: device-id={device_id}"
            )
            await self._serve_subscriber(subscriber)
        except (asyncio.TimeoutError, ValueError, TypeError, json.JSONDecodeError):
            await self._send_error(websocket, "invalid_auth_message")
            await self._close(websocket, code=4400, reason="invalid_auth_message")
        except websockets.exceptions.ConnectionClosed:
            pass
        except Exception as exc:
            self.logger.bind(tag=__name__).warning(
                f"设备真实音频监听异常: device-id={device_id or '-'}, type={type(exc).__name__}"
            )
        finally:
            if registered and subscriber is not None and device_id is not None:
                async with self._lock:
                    if self._subscribers.get(device_id) is subscriber:
                        self._subscribers.pop(device_id, None)
                self.logger.bind(tag=__name__).info(
                    f"设备真实音频监听已断开: device-id={device_id}"
                )

    async def _serve_subscriber(self, subscriber):
        sender = asyncio.create_task(self._sender(subscriber))
        receiver = asyncio.create_task(self._receiver(subscriber.websocket))
        done, pending = await asyncio.wait(
            {sender, receiver}, return_when=asyncio.FIRST_COMPLETED
        )
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)
        for task in done:
            task.result()

    async def _sender(self, subscriber):
        while True:
            payload = await subscriber.queue.get()
            await subscriber.websocket.send(payload)

    @staticmethod
    async def _receiver(websocket):
        async for _message in websocket:
            # Authentication is the only client application message.
            continue

    @staticmethod
    def _parse_auth_message(message):
        if not isinstance(message, str):
            raise ValueError("auth message must be text")
        if len(message.encode("utf-8")) > MAX_AUTH_MESSAGE_BYTES:
            raise ValueError("auth message too large")
        payload = json.loads(message)
        if not isinstance(payload, dict) or payload.get("type") != "auth":
            raise ValueError("invalid auth message")
        credentials = {
            "deviceId": str(payload.get("deviceId") or "").strip(),
            "clientId": str(payload.get("clientId") or "").strip(),
            "token": str(payload.get("token") or "").strip(),
        }
        if not all(credentials.values()):
            raise ValueError("missing credentials")
        return credentials

    def _verify_ticket(self, token, client_id, device_id):
        if not client_id.startswith(PREVIEW_CLIENT_PREFIX):
            return False
        try:
            _signature, timestamp_text = token.split(".", 1)
            timestamp = int(timestamp_text)
        except (TypeError, ValueError):
            return False
        age = int(time.time()) - timestamp
        if age < -30 or age > TICKET_MAX_AGE_SECONDS:
            return False
        return self.auth.verify_token(token, client_id=client_id, username=device_id)

    def publish_stream_start(self, device_id, sentence_id, sample_rate, frame_duration):
        payload = {
            "type": "stream_start",
            "sentenceId": sentence_id,
            "sampleRate": int(sample_rate),
            "channels": 1,
            "frameDurationMs": int(frame_duration),
        }
        self._active_streams[device_id] = payload
        while len(self._active_streams) > MAX_TRACKED_STREAMS:
            oldest_device_id = next(iter(self._active_streams))
            self._active_streams.pop(oldest_device_id, None)
        self._publish_json(device_id, payload)

    def publish_audio(self, device_id, opus_packet):
        if not isinstance(opus_packet, bytes) or not opus_packet:
            return
        subscriber = self._subscribers.get(device_id)
        if subscriber is None:
            return
        self._enqueue(subscriber, opus_packet)

    def publish_stream_stop(self, device_id, sentence_id, reason=None):
        payload = {"type": "stream_stop", "sentenceId": sentence_id}
        if reason:
            payload["reason"] = reason
        self._publish_json(device_id, payload)
        self._active_streams.pop(device_id, None)

    def _publish_json(self, device_id, payload):
        subscriber = self._subscribers.get(device_id)
        if subscriber is None:
            return
        self._enqueue(subscriber, json.dumps(payload, ensure_ascii=False))

    def _enqueue(self, subscriber, payload):
        if subscriber.queue.full():
            while not subscriber.queue.empty():
                try:
                    subscriber.queue.get_nowait()
                except asyncio.QueueEmpty:
                    break
            subscriber.discontinuity_pending = True
        if subscriber.discontinuity_pending:
            try:
                subscriber.queue.put_nowait(
                    json.dumps({"type": "discontinuity"}, ensure_ascii=False)
                )
                subscriber.discontinuity_pending = False
            except asyncio.QueueFull:
                return
        try:
            subscriber.queue.put_nowait(payload)
        except asyncio.QueueFull:
            # Preview loss is acceptable; device audio delivery must never wait.
            subscriber.discontinuity_pending = True

    @staticmethod
    async def _send_json(websocket, payload):
        await websocket.send(json.dumps(payload, ensure_ascii=False))

    @classmethod
    async def _send_error(cls, websocket, code):
        try:
            await cls._send_json(websocket, {"type": "error", "code": code})
        except Exception:
            pass

    @staticmethod
    async def _close(websocket, code, reason):
        try:
            await websocket.close(code=code, reason=reason)
        except Exception:
            pass
