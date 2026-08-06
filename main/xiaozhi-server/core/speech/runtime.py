"""Connection-scoped speech negotiation, device ASR guard, and TTS ACKs."""

from __future__ import annotations

import asyncio
import json
import math
import re
import time
from collections import OrderedDict, deque
from dataclasses import dataclass

from core.speech.routing import SpeechRoute, SpeechRouteError, resolve_speech_route


TURN_ID_PATTERN = re.compile(r"^[A-Za-z0-9_.:-]{1,128}$")


@dataclass(frozen=True)
class DeviceASRDecision:
    accepted: bool
    duplicate: bool = False
    reason: str = ""
    text: str = ""
    turn_id: str = ""


class SpeechRuntime:
    HELLO_TIMEOUT_SECONDS = 1.5
    DEVICE_ASR_MAX_TEXT_CHARS = 2000
    DEVICE_ASR_RATE_LIMIT = 8
    DEVICE_ASR_RATE_WINDOW_SECONDS = 5.0
    DEVICE_ASR_TURN_TTL_SECONDS = 600.0
    DEVICE_ASR_TURN_CACHE_SIZE = 256

    def __init__(self, conn):
        self.conn = conn
        self.route: SpeechRoute | None = None
        self.features = {}
        self.capabilities = {}
        self._hello_event = asyncio.Event()
        self._hello_received = False
        self._device_asr_times = deque()
        self._device_asr_turns = OrderedDict()
        self._tts_ack_waiters = {}
        self._route_publish_lock = asyncio.Lock()

    def record_hello(self, features) -> None:
        if self.route is not None:
            return
        self.features = dict(features) if isinstance(features, dict) else {}
        self._hello_received = True
        self._hello_event.set()

    async def resolve(self, config, *, realtime=False) -> SpeechRoute:
        if self.route is not None:
            return self.route
        routing = config.get("speech_routing") if isinstance(config, dict) else None
        timeout = (
            routing.get("hello_timeout_seconds")
            if isinstance(routing, dict)
            else self.HELLO_TIMEOUT_SECONDS
        )
        try:
            timeout = float(timeout or self.HELLO_TIMEOUT_SECONDS)
        except (TypeError, ValueError):
            timeout = self.HELLO_TIMEOUT_SECONDS
        if not math.isfinite(timeout):
            timeout = self.HELLO_TIMEOUT_SECONDS
        timeout = min(5.0, max(0.1, timeout))
        if not self._hello_received:
            try:
                await asyncio.wait_for(self._hello_event.wait(), timeout=timeout)
            except asyncio.TimeoutError:
                pass
        try:
            self.route = resolve_speech_route(
                config,
                self.features,
                realtime=realtime,
                hello_timed_out=not self._hello_received,
            )
        except SpeechRouteError as error:
            await self.publish_route_error(error)
            raise
        await self.publish_route()
        return self.route

    def resolve_candidate(self, config, *, realtime=False) -> SpeechRoute:
        return resolve_speech_route(
            config,
            self.features,
            realtime=realtime,
            hello_timed_out=not self._hello_received,
        )

    async def apply_route(self, route: SpeechRoute) -> None:
        self.route = route
        await self.publish_route()

    async def publish_route(self) -> None:
        if self.route is None or self.conn.websocket is None:
            return
        async with self._route_publish_lock:
            await self.conn.websocket.send(
                json.dumps(
                    self.route.as_message(self.conn.session_id),
                    ensure_ascii=False,
                )
            )

    async def publish_route_error(self, error) -> None:
        if self.conn.websocket is None:
            return
        payload = {
            "type": "speech_route",
            "session_id": self.conn.session_id,
            "version": 1,
            "status": "error",
            "reason": "incompatible_route",
            "detail": str(error)[:160],
        }
        try:
            await self.conn.websocket.send(
                json.dumps(payload, ensure_ascii=False)
            )
        except Exception:
            return

    def validate_device_asr(self, message) -> DeviceASRDecision:
        route = self.route
        if route is None or not route.uses_device_asr:
            return DeviceASRDecision(False, reason="device_asr_not_selected")
        if message.get("source") != "device_asr" or message.get("final") is not True:
            return DeviceASRDecision(False, reason="device_asr_final_required")
        text = message.get("text")
        turn_id = str(message.get("turn_id") or "").strip()
        if not isinstance(text, str):
            return DeviceASRDecision(False, reason="invalid_text")
        text = text.strip()
        if not text or len(text) > self.DEVICE_ASR_MAX_TEXT_CHARS:
            return DeviceASRDecision(False, reason="invalid_text_length")
        if not TURN_ID_PATTERN.fullmatch(turn_id):
            return DeviceASRDecision(False, reason="invalid_turn_id")

        now = time.monotonic()
        self._expire_device_asr_turns(now)
        if turn_id in self._device_asr_turns:
            return DeviceASRDecision(
                False, duplicate=True, reason="duplicate", turn_id=turn_id
            )
        cutoff = now - self.DEVICE_ASR_RATE_WINDOW_SECONDS
        while self._device_asr_times and self._device_asr_times[0] <= cutoff:
            self._device_asr_times.popleft()
        if len(self._device_asr_times) >= self.DEVICE_ASR_RATE_LIMIT:
            return DeviceASRDecision(False, reason="rate_limited", turn_id=turn_id)
        self._device_asr_times.append(now)
        self._device_asr_turns[turn_id] = now + self.DEVICE_ASR_TURN_TTL_SECONDS
        while len(self._device_asr_turns) > self.DEVICE_ASR_TURN_CACHE_SIZE:
            self._device_asr_turns.popitem(last=False)
        return DeviceASRDecision(True, text=text, turn_id=turn_id)

    def _expire_device_asr_turns(self, now) -> None:
        expired = [
            turn_id
            for turn_id, expires_at in self._device_asr_turns.items()
            if expires_at <= now
        ]
        for turn_id in expired:
            self._device_asr_turns.pop(turn_id, None)

    async def send_device_asr_ack(self, decision: DeviceASRDecision) -> None:
        payload = {
            "type": "speech_asr_ack",
            "session_id": self.conn.session_id,
            "turn_id": decision.turn_id,
            "accepted": decision.accepted,
            "duplicate": decision.duplicate,
        }
        if decision.reason:
            payload["reason"] = decision.reason
        await self.conn.websocket.send(json.dumps(payload, ensure_ascii=False))

    def create_tts_ack_waiter(self, sentence_id: str, generation: int | None):
        key = (str(sentence_id), generation)
        waiter = self._tts_ack_waiters.get(key)
        if waiter is None:
            waiter = self.conn.loop.create_future()
            self._tts_ack_waiters[key] = waiter
        return waiter

    def handle_tts_ack(self, message) -> bool:
        sentence_id = str(
            message.get("sentence_id") or message.get("sentenceId") or ""
        ).strip()
        if not sentence_id:
            return False
        raw_generation = message.get("generation")
        try:
            generation = int(raw_generation) if raw_generation is not None else None
        except (TypeError, ValueError):
            return False
        event = str(message.get("event") or "").strip().lower()
        if event not in {"played", "failed", "aborted"}:
            return False
        key = (sentence_id, generation)
        waiter = self._tts_ack_waiters.get(key)
        if waiter is None and generation is None:
            matches = [
                item
                for item in self._tts_ack_waiters
                if item[0] == sentence_id
            ]
            if len(matches) == 1:
                key = matches[0]
                waiter = self._tts_ack_waiters.get(key)
        if waiter is None or waiter.done():
            return False
        waiter.set_result(event)
        return True

    def remove_tts_ack_waiter(self, sentence_id, generation) -> None:
        self._tts_ack_waiters.pop((str(sentence_id), generation), None)

    def abort_device_tts(self, sentence_id=None) -> None:
        for key, waiter in list(self._tts_ack_waiters.items()):
            if sentence_id and key[0] != str(sentence_id):
                continue
            if not waiter.done():
                waiter.set_result("aborted")

    def close(self) -> None:
        self.abort_device_tts()
        self._tts_ack_waiters.clear()
