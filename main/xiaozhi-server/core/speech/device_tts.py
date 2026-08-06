"""Compatibility TTS façade that sends text to a capable device."""

from __future__ import annotations

import asyncio
import json
import queue
import threading

from core.handle.sendAudioHandle import send_tts_message
from core.providers.tts.base import TTSProviderBase
from core.providers.tts.dto.dto import ContentType, SentenceType


class DeviceTTSProvider(TTSProviderBase):
    """Consume the existing TTS text queue without invoking a server provider."""

    is_device_tts = True

    def __init__(self, config=None, delete_audio_file=True):
        super().__init__({"output_dir": "tmp/"}, delete_audio_file)
        routing = config.get("speech_routing") if isinstance(config, dict) else None
        raw_timeout = (
            routing.get("device_tts_ack_timeout_seconds")
            if isinstance(routing, dict)
            else 30.0
        )
        try:
            self.ack_timeout_seconds = min(120.0, max(1.0, float(raw_timeout)))
        except (TypeError, ValueError):
            self.ack_timeout_seconds = 30.0
        self._closed = threading.Event()
        self._sequence_by_sentence = {}
        self._aggregate_by_sentence = {}

    async def open_audio_channels(self, conn):
        self.conn = conn
        self.tts_priority_thread = threading.Thread(
            target=self._device_text_worker,
            name=f"device-tts-{getattr(conn, 'session_id', '')[:8]}",
            daemon=True,
        )
        self.tts_priority_thread.start()
        self.audio_play_priority_thread = None

    async def close(self):
        self._closed.set()
        runtime = getattr(self.conn, "speech_runtime", None)
        if runtime is not None:
            runtime.abort_device_tts()
        worker = getattr(self, "tts_priority_thread", None)
        if worker is not None and worker is not threading.current_thread():
            await asyncio.to_thread(worker.join, 2.0)

    async def text_to_speak(self, text, output_file):
        return None

    def to_tts(self, text):
        return None

    def _device_text_worker(self):
        while not self._closed.is_set():
            try:
                message = self.tts_text_queue.get(timeout=0.1)
            except queue.Empty:
                continue
            conn = self.conn
            if conn is None or getattr(conn, "closing", False):
                continue
            future = asyncio.run_coroutine_threadsafe(
                self._handle_message(message), conn.loop
            )
            try:
                future.result(timeout=self.ack_timeout_seconds + 5.0)
            except Exception as error:
                conn.logger.bind(tag=__name__).warning(
                    "设备TTS消息处理失败: sentenceId={}, errorType={}",
                    getattr(message, "sentence_id", None),
                    type(error).__name__,
                )

    async def _handle_message(self, message):
        conn = self.conn
        sentence_id = str(message.sentence_id or "")
        generation = message.generation
        lifecycle = getattr(conn, "reply_lifecycle", None)
        if generation is None and lifecycle is not None:
            snapshot = lifecycle.snapshot()
            if snapshot.is_active and snapshot.sentence_id in {None, sentence_id}:
                generation = snapshot.generation

        if message.sentence_type is SentenceType.FIRST:
            self.current_sentence_id = sentence_id
            self.tts_audio_first_sentence = True
            self._sequence_by_sentence[sentence_id] = 0
            self._aggregate_by_sentence[sentence_id] = []
            if lifecycle is not None:
                lifecycle.bind_sentence(generation, sentence_id)
                lifecycle.mark_synthesizing(generation)
            if (
                getattr(conn, "_tts_protocol_sentence_id", None) is None
                and getattr(conn, "_tts_protocol_start_sent", False)
                and not getattr(conn, "_tts_protocol_stop_sent", False)
            ):
                conn._tts_protocol_sentence_id = sentence_id
            await send_tts_message(
                conn,
                "start",
                protocol_sentence_id=sentence_id,
                protocol_generation=generation,
            )
            runtime = conn.speech_runtime
            runtime.create_tts_ack_waiter(sentence_id, generation)
            return

        if message.sentence_type is SentenceType.MIDDLE:
            if message.content_type is ContentType.FILE:
                await self._finish(sentence_id, generation, "failed", "device_tts_file_unsupported")
                return
            if message.content_type is not ContentType.TEXT:
                return
            text = str(message.content_detail or "")
            if not text:
                return
            sequence = self._sequence_by_sentence.get(sentence_id, 0) + 1
            self._sequence_by_sentence[sentence_id] = sequence
            self._aggregate_by_sentence.setdefault(sentence_id, []).append(text)
            payload = {
                "type": "tts",
                "state": "text",
                "session_id": conn.session_id,
                "sentence_id": sentence_id,
                "generation": generation,
                "sequence": sequence,
                "text": text,
            }
            await conn.websocket.send(json.dumps(payload, ensure_ascii=False))
            if lifecycle is not None:
                lifecycle.mark_streaming(generation)
            external_tts = getattr(conn, "external_tts_coordinator", None)
            if external_tts is not None:
                external_tts.mark_streaming(conn, sentence_id)
            return

        if message.sentence_type is SentenceType.LAST:
            await conn.websocket.send(
                json.dumps(
                    {
                        "type": "tts",
                        "state": "text_end",
                        "session_id": conn.session_id,
                        "sentence_id": sentence_id,
                        "generation": generation,
                        "sequence": self._sequence_by_sentence.get(sentence_id, 0),
                    },
                    ensure_ascii=False,
                )
            )
            waiter = conn.speech_runtime.create_tts_ack_waiter(
                sentence_id, generation
            )
            try:
                event = await asyncio.wait_for(
                    asyncio.shield(waiter), timeout=self.ack_timeout_seconds
                )
            except asyncio.TimeoutError:
                event = "timeout"
            await self._finish(sentence_id, generation, event)

    async def _finish(self, sentence_id, generation, event, reason=None):
        conn = self.conn
        conn.speech_runtime.remove_tts_ack_waiter(sentence_id, generation)
        full_text = self.get_report_text(sentence_id) or "".join(
            self._aggregate_by_sentence.get(sentence_id, [])
        )
        if event == "played":
            turn_metrics = getattr(conn, "turn_metrics", None)
            metrics = (
                turn_metrics.build_assistant_metrics(
                    full_text, sentence_id=sentence_id
                )
                if full_text and turn_metrics is not None
                else None
            )
            if full_text:
                self._enqueue_final_report(
                    sentence_id, full_text, [], metrics=metrics
                )
            await send_tts_message(
                conn,
                "stop",
                protocol_sentence_id=sentence_id,
                protocol_generation=generation,
                drain_audio=False,
            )
            external_tts = getattr(conn, "external_tts_coordinator", None)
            if external_tts is not None:
                external_tts.mark_completed(conn, sentence_id)
        else:
            terminal_reason = reason or {
                "aborted": "abort",
                "failed": "device_tts_failed",
                "timeout": "device_tts_ack_timeout",
            }.get(event, "device_tts_failed")
            await send_tts_message(
                conn,
                "stop",
                protocol_sentence_id=sentence_id,
                protocol_generation=generation,
                drain_audio=False,
                reason=terminal_reason,
                allow_pending_stop=True,
            )
            external_tts = getattr(conn, "external_tts_coordinator", None)
            if external_tts is not None:
                if event == "aborted":
                    external_tts.mark_interrupted(conn, sentence_id)
                else:
                    external_tts.mark_failed(conn, sentence_id, terminal_reason)
        self._sequence_by_sentence.pop(sentence_id, None)
        self._aggregate_by_sentence.pop(sentence_id, None)
        self.clear_tts_text(sentence_id)
        self._clear_report_state(sentence_id)
        self.clear_exact_text(sentence_id)
