"""Seeduplex 3.0 JSON protocol (not the legacy binary Doubao protocol)."""

import asyncio
import base64
import inspect
import json
import time
import uuid
from urllib.parse import urlsplit

import websockets

from core.providers.realtime.doubao_realtime import _bounded_int, _max_event_bytes

OFFICIAL_URL = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue"
MODEL = "1.2.6.1"
DEFAULT_VOICE = "zh_female_vv_jupiter_bigtts"


class OfficialConnect(websockets.connect):
    def process_redirect(self, exc):
        # Never forward X-Api-Key through an upstream HTTP redirect.
        return ValueError("豆包3.0不允许WebSocket重定向")


def validate_url(value):
    parsed = urlsplit(str(value or ""))
    if (parsed.scheme != "wss" or parsed.hostname != "openspeech.bytedance.com"
            or parsed.path != "/api/v3/duplex/realtime/dialogue"
            or parsed.port not in (None, 443) or parsed.username is not None
            or parsed.password is not None or parsed.query or parsed.fragment):
        raise ValueError("豆包3.0仅允许官方全双工WSS地址")
    return OFFICIAL_URL


def session_config(config, prompt, tools):
    if str(config.get("model_name") or MODEL) != MODEL:
        raise ValueError("豆包3.0全双工模型版本必须为1.2.6.1")
    if (int(config.get("input_sample_rate", 16000)),
            int(config.get("output_sample_rate", 24000))) != (16000, 24000):
        raise ValueError("豆包3.0固定输入16kHz、输出24kHz单声道PCM16")
    functions = []
    for tool in tools or []:
        function = tool.get("function") if isinstance(tool, dict) else None
        if isinstance(function, dict) and function.get("name"):
            functions.append({"type": "function", **function})
    return {
        "model": MODEL, "instructions": prompt, "tools": functions,
        "audio": {
            "input": {"format": {"type": "pcm", "rate": 16000}},
            "output": {
                "format": {"type": "pcm_s16le", "rate": 24000},
                "voice": str(config.get("voice") or DEFAULT_VOICE),
                "speed": _bounded_int(config.get("speech_rate"), 0, -50, 100),
                "loudness": _bounded_int(config.get("loudness_rate"), 0, -50, 100),
            },
        },
    }


class DoubaoDuplexTransport:
    def __init__(self, config, logger=None, websocket_factory=None):
        self.config = dict(config)
        self.websocket_factory = websocket_factory or OfficialConnect
        self.websocket = None
        self.reader_task = None
        self.idle_task = None
        self.handler = None
        self.session_id = ""
        self.started = False
        self.closed = False
        self.muted = True
        self.last_audio = 0.0
        self.next_audio = 0.0
        self.waiters = {}
        self.send_lock = asyncio.Lock()
        self.audio_lock = asyncio.Lock()
        self.configure_lock = asyncio.Lock()

    async def connect(self, event_handler=None):
        url = validate_url(self.config.get("base_url"))
        key = str(self.config.get("api_key") or "").strip()
        if not key or "***" in key:
            raise ValueError("豆包3.0需要有效API Key")
        session_config(self.config, "", [])
        timeout = _bounded_int(self.config.get("connect_timeout_seconds"), 10, 1, 60)
        try:
            params = inspect.signature(self.websocket_factory).parameters
        except (TypeError, ValueError):
            params = {}
        header = "additional_headers" if "additional_headers" in params else "extra_headers"
        self.handler = event_handler
        try:
            self.websocket = await asyncio.wait_for(self.websocket_factory(
                url, **{header: {"X-Api-Key": key}}, open_timeout=timeout,
                max_size=_max_event_bytes(self.config.get("max_event_bytes")),
                ping_interval=None, close_timeout=3,
            ), timeout + 1)
        except Exception:
            # Never propagate upstream headers, URLs, or credential-bearing errors.
            raise ConnectionError("豆包3.0连接失败，请核对服务开通、API Key和网络") from None
        self.reader_task = asyncio.create_task(self._reader())

    async def send(self, event):
        if self.websocket is None or self.closed:
            raise ConnectionError("豆包3.0连接已关闭")
        payload = {"event_id": uuid.uuid4().hex, **event}
        async with self.send_lock:
            await self.websocket.send(json.dumps(payload, ensure_ascii=False))

    async def request(self, event, ack, timeout=10):
        future = asyncio.get_running_loop().create_future()
        if ack in self.waiters:
            raise RuntimeError("豆包3.0并发会话控制请求")
        self.waiters[ack] = future
        try:
            await self.send(event)
            return await asyncio.wait_for(future, timeout)
        finally:
            self.waiters.pop(ack, None)
            if not future.done():
                future.cancel()

    async def configure(self, prompt, tools=None, config_override=None):
        async with self.configure_lock:
            config = {**self.config, **(config_override or {})}
            session = session_config(config, prompt, tools)
            if self.session_id:
                session["id"] = self.session_id
            action = "update" if self.started else "create"
            response = await self.request({
                "type": f"session.{action}", "session": session,
                "extension": {"dialog": {"extra": {"strict_audit": True}}},
            }, "session.updated" if self.started else "session.created",
                _bounded_int(config.get("connect_timeout_seconds"), 10, 1, 60))
            self.session_id = response.get("session", {}).get("id") or self.session_id
            if not self.started:
                self.started = True
                await self.send({"type": "input_audio_mute.commit"})
                self.idle_task = asyncio.create_task(self._idle_watchdog())

    async def send_audio(self, pcm):
        if not pcm or len(pcm) % 2:
            raise ValueError("豆包3.0输入必须为PCM16")
        async with self.audio_lock:
            if self.muted:
                await self.send({"type": "input_audio_unmute.commit"})
                self.muted = False
                self.next_audio = time.monotonic()
            for offset in range(0, len(pcm), 640):
                chunk = pcm[offset:offset + 640]
                await asyncio.sleep(max(0, self.next_audio - time.monotonic()))
                await self.send({"type": "input_audio_buffer.append",
                                 "audio": base64.b64encode(chunk).decode("ascii")})
                self.last_audio = time.monotonic()
                self.next_audio = self.last_audio + len(chunk) / 32000

    async def mute(self, commit=False):
        async with self.audio_lock:
            if not self.muted:
                if commit:
                    await self.send({"type": "input_audio_buffer.commit"})
                await self.send({"type": "input_audio_mute.commit"})
                self.muted = True

    async def _idle_watchdog(self):
        try:
            while not self.closed:
                await asyncio.sleep(0.2)
                if not self.muted and time.monotonic() - self.last_audio > 0.4:
                    await self.mute()
        except asyncio.CancelledError:
            raise
        except Exception:
            if self.handler and not self.closed:
                await self.handler({"type": "transport.closed"})

    async def _reader(self):
        try:
            async for raw in self.websocket:
                if not isinstance(raw, str):
                    raise ValueError("豆包3.0要求JSON文本帧")
                if len(raw.encode("utf-8")) > _max_event_bytes(self.config.get("max_event_bytes")):
                    raise ValueError("豆包3.0事件超过大小限制")
                event = json.loads(raw)
                if not isinstance(event, dict):
                    raise ValueError("豆包3.0事件必须为对象")
                kind = event.get("type")
                if kind == "error":
                    # Upstream error messages may echo input or credentials.
                    event = {"type": "error"}
                    for waiter in self.waiters.values():
                        if not waiter.done():
                            waiter.set_exception(RuntimeError("豆包3.0上游返回错误"))
                waiter = self.waiters.get(kind)
                if waiter is not None and not waiter.done():
                    waiter.set_result(event)
                if self.handler:
                    await self.handler(event)
                if kind == "error":
                    break
        except asyncio.CancelledError:
            raise
        except Exception:
            pass
        finally:
            for waiter in self.waiters.values():
                if not waiter.done():
                    waiter.set_exception(ConnectionError("豆包3.0上游连接中断"))
            if not self.closed and self.handler:
                await self.handler({"type": "transport.closed"})
            if not self.closed and self.websocket:
                await self.websocket.close()

    async def close(self):
        if self.closed:
            return
        for task in (self.idle_task,):
            if task:
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
        if self.started and self.reader_task and not self.reader_task.done():
            try:
                await self.request({"type": "session.close"}, "session.closed", 3)
            except Exception:
                pass
        self.closed = True
        if self.websocket:
            await self.websocket.close()
        if self.reader_task:
            self.reader_task.cancel()
            await asyncio.gather(self.reader_task, return_exceptions=True)
