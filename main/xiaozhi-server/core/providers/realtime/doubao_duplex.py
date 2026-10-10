"""Device session for Doubao 3.0; reuse device media/reporting, not wire protocol."""

import asyncio
import base64
import json
from collections import deque

import opuslib_next

from core.handle.abortHandle import handleAbortMessage
from core.handle.reportHandle import enqueue_asr_report
from core.handle.sendAudioHandle import send_stt_display
from core.observability.session_telemetry import latency_span, mark_latency
from core.providers.realtime.base import RealtimeCapabilities, tool_output_text
from core.providers.realtime.doubao_realtime import DoubaoRealtimeSession
from core.providers.realtime.doubao_duplex_transport import DoubaoDuplexTransport
from core.utils.dialogue import Message

SUPPORTED_ADAPTERS = {"doubao_duplex", "doubao-duplex"}
CAPABILITIES = RealtimeCapabilities(
    native_function_calling=True, natural_barge_in=True, context_reset=True,
    session_voice=True, custom_voice_ids=True,
)


class DoubaoDuplexSession(DoubaoRealtimeSession):
    def __init__(self, conn, config, transport=None, transport_factory=None):
        super().__init__(conn, config, transport=transport, transport_factory=(
            transport_factory or (lambda: DoubaoDuplexTransport(self.config))))
        self._generation = 0
        self._tool_tasks = set()
        self._completed_calls = deque(maxlen=256)
        self._retired_responses = deque(maxlen=128)
        self._response_id = None
        self._timeout_task = None
        self._tool_followup_pending = False
        self._tools_returned = False
        self._finish_task = None
        self._reconnect_task = None
        self._closing = False

    @property
    def capabilities(self):
        return CAPABILITIES

    def _session_prompt(self):
        from core.utils.current_time import render_runtime_time
        from core.utils.prompt_budget import compact_memory_context
        prompt = render_runtime_time(self.conn.prompt or self.conn.config.get("prompt", ""))
        summary = compact_memory_context(self.conn.config.get("summaryMemory"))
        if summary:
            prompt += f"\n<realtime_summary_memory>\n{summary}\n</realtime_summary_memory>"
        return prompt

    async def initialize(self):
        try:
            await self.transport.connect(self.handle_event)
            await self.refresh_tools()
        except BaseException:
            await self.transport.close()
            raise

    async def refresh_tools(self):
        handler = getattr(self.conn, "func_handler", None)
        tools = handler.get_functions() if handler else []
        await self.transport.configure(self._session_prompt(), tools)

    async def _reconnect_for_session_controls(self):
        async with self._turn_lock:
            if self._closing or self._closed or self._response_active or self._reply_started:
                return False
            self._reconnect_task = asyncio.current_task()
            try:
                self._generation += 1
                await self._cancel_finish_response()
                await self._reset_output()
                await self._cancel_tools()
                self._completed_calls.clear()
                self._retired_responses.clear()
                self._response_id = None
                # The base lifecycle gates events while reconnecting, so session
                # ACKs must bypass the turn lock in handle_event below.
                return await super()._reconnect_for_session_controls()
            finally:
                self._reconnect_task = None

    async def handle_audio(self, packet):
        if self._context_resetting or self._closing or self._closed:
            return True
        try:
            pcm = self.decoder.decode(packet, 960)
        except opuslib_next.OpusError:
            return True
        # Bound recording retained for reporting during long silent sessions (30 seconds).
        self.input_audio.append(packet)
        if len(self.input_audio) > 500:
            del self.input_audio[:-500]
        await self.transport.send_audio(pcm)
        return True

    async def handle_listen(self, message):
        if self._context_resetting or self._closing or self._closed:
            return True
        if message.get("state") == "stop":
            await self.transport.mute(commit=True)
        # Unmute on actual audio, not listen.start (microphone may never send a frame).
        return True

    async def handle_text(self, text):
        # Firmware also sends listen.detect wake words. Do not disconnect the
        # microphone session simply because a text-only event isn't supported.
        await self.conn.websocket.send(json.dumps({
            "type": "error", "code": "unsupported_text_input",
            "message": "豆包3.0当前仅支持语音输入，请直接说话。",
        }, ensure_ascii=False))
        return True

    async def _cancel_tools(self):
        tasks = list(self._tool_tasks)
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self._tool_tasks.clear()

    async def _cancel_finish_response(self):
        task, self._finish_task = self._finish_task, None
        if task is not None and task is not asyncio.current_task():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def _reset_output(self):
        # Realtime has no traditional TTS provider; clear_queues() alone does
        # not reset its audio sender. Join cancellation before reusing the queue.
        controller = getattr(self.conn, "audio_rate_controller", None)
        if controller is not None:
            task = controller.pending_send_task
            controller.reset()
            if task is not None and task is not asyncio.current_task():
                await asyncio.gather(task, return_exceptions=True)

    def _start_finish_response(self):
        if self._finish_task is not None:
            return
        generation = self._generation
        sentence_id = getattr(self.conn, "sentence_id", None)
        mark_latency(self.conn, "realtime.audio.upstream_completed",
                     sentence_id=sentence_id, generation=generation, source="doubao_duplex")

        async def finish():
            try:
                if self._closed or generation != self._generation:
                    return
                with latency_span(self.conn, "realtime.reply.finish",
                                  sentence_id=sentence_id, generation=generation,
                                  source="doubao_duplex"):
                    await self._finish_response()
            except asyncio.CancelledError:
                raise
            except Exception as error:
                self.conn.logger.bind(tag=__name__).warning(
                    "豆包3.0音频收尾失败: errorType={}", type(error).__name__)
                async with self._turn_lock:
                    if generation == self._generation and not self._closed:
                        await self._interrupt(notify_upstream=False)
                        self.conn.audio_input_ready.clear()
                        self.conn.conversation_ready.clear()
                        await handleAbortMessage(self.conn, preserve_user_audio=True)
            finally:
                if self._finish_task is asyncio.current_task():
                    self._finish_task = None

        self._finish_task = asyncio.create_task(finish(), name="doubao-duplex-response-finish")

    async def _interrupt(self, notify_upstream=True):
        self._generation += 1
        self._cancel_timeout()
        await self._cancel_finish_response()
        await self._reset_output()
        await self._cancel_tools()
        if self._response_id:
            self._retired_responses.append(self._response_id)
        self._response_id = None
        self.response_text = ""
        self._tool_followup_pending = False
        self._tools_returned = False
        self.response_audio = []
        self._subtitle_text_sent = ""
        self._response_active = False
        self._reply_started = False
        self.encoder.reset_state()
        self.response_done.set()
        if notify_upstream:
            await self.transport.send({"type": "response.cancel"})

    async def handle_abort(self):
        async with self._turn_lock:
            if not self._closing and not self._closed:
                await self._interrupt()
        return True

    async def handle_event(self, event):
        # Session ACKs during a reconnect cannot wait on the lock held by the
        # reconnect itself. The transport resolves its ACK waiter independently.
        if self._closing or self._closed or self._context_resetting:
            return
        async with self._turn_lock:
            await self._handle_event(event)

    async def _handle_event(self, event):
        if self._closing or self._closed or self._context_resetting:
            return
        kind = event.get("type", "")
        if kind in {"error", "transport.closed", "session.closed"}:
            await self._interrupt(notify_upstream=False)
            self.conn.audio_input_ready.clear()
            self.conn.conversation_ready.clear()
            await handleAbortMessage(self.conn, preserve_user_audio=True)
            return
        if kind == "conversation.item.input_audio_transcription.started":
            if self._response_active or self._reply_started or self._tool_tasks:
                await self._interrupt()
                await handleAbortMessage(self.conn, preserve_user_audio=True)
            self.current_transcript = ""
        elif kind == "conversation.item.input_audio_transcription.delta":
            self.current_transcript += str(event.get("delta") or "")
        elif kind == "conversation.item.input_audio_transcription.completed":
            # Some upstream turns omit started. A new input must still cancel
            # the old drain before it can replace shared response state.
            if self._finish_task is not None:
                await self._interrupt()
                await handleAbortMessage(self.conn, preserve_user_audio=True)
            text = str(event.get("transcript") or event.get("text") or self.current_transcript).strip()
            if text:
                self.conn.dialogue.put(Message(role="user", content=text))
                await send_stt_display(self.conn, text)
                enqueue_asr_report(self.conn, text, list(self.input_audio))
            self.input_audio = []
            self.current_transcript = ""
            self._response_active = True
            self.response_done.clear()
            self._cancel_timeout()
            self._timeout_task = asyncio.create_task(self._response_timeout())
            with self.conn._abort_lock:
                self.conn.client_abort = False
        elif kind == "conversation.item.input_audio_transcription.failed":
            self.current_transcript = ""
            self.input_audio = []
        elif kind.startswith("response."):
            if self._finish_task is not None:
                return
            response_id = event.get("response_id")
            if response_id and response_id in self._retired_responses:
                return
            if kind == "response.canceled":
                # Explicit cancel already converged locally; a late ACK must not stop a new turn.
                return
            if not self._response_active:
                return
            if response_id:
                if self._response_id and response_id != self._response_id:
                    return
                self._response_id = response_id
            if kind == "response.output_text.delta":
                self.response_text += str(event.get("delta") or "")
                await self._send_subtitle_preview_if_ready()
            elif kind == "response.output_text.done":
                self.response_text = str(event.get("text") or self.response_text)
            elif kind == "response.output_audio.delta":
                if self._tools_returned:
                    self._tool_followup_pending = False
                    self._tools_returned = False
                pcm = base64.b64decode(event.get("delta") or "", validate=True)
                if len(pcm) % 2:
                    raise ValueError("豆包3.0返回无效PCM16音频")
                await self._handle_audio(pcm)
                if len(self.response_audio) > 5000:
                    raise RuntimeError("豆包3.0单轮回复音频超过预算")
            elif kind == "response.function_call_arguments.done":
                if len(self._tool_tasks) >= 4:
                    raise RuntimeError("豆包3.0工具并发超过限制")
                items = event.get("items")
                if isinstance(items, list) and items and all(
                    isinstance(item, dict) and item.get("call_id") in self._completed_calls for item in items
                ):
                    return
                self._tool_followup_pending = True
                self._tools_returned = False
                task = asyncio.create_task(self._execute_tools(items, self._generation))
                self._tool_tasks.add(task)
                task.add_done_callback(self._tool_tasks.discard)
            elif kind == "response.output_audio.done":
                if self._tool_followup_pending:
                    return
                self._cancel_timeout()
                if self._response_id:
                    self._retired_responses.append(self._response_id)
                self._response_id = None
                self._start_finish_response()
            # response.done carries usage; audio.done is the audio lifecycle terminal event.
            if len(self.response_text) > 65536:
                raise RuntimeError("豆包3.0单轮回复文本超过预算")

    async def _execute_tools(self, items, generation):
        try:
            if not isinstance(items, list) or not 1 <= len(items) <= 16:
                raise ValueError("无效工具调用列表")
            outputs = []
            handler = getattr(self.conn, "func_handler", None)
            for item in items:
                call_id = item.get("call_id")
                if not isinstance(call_id, str) or not call_id or len(call_id) > 256:
                    raise ValueError("无效工具调用ID")
                if call_id in self._completed_calls:
                    continue
                self._completed_calls.append(call_id)
                if not handler or not handler.has_tool(item.get("name")):
                    output = "工具未启用或不可用"
                else:
                    result = await asyncio.wait_for(handler.handle_llm_function_call(
                        self.conn, {"name": item.get("name"), "arguments": item.get("arguments", "{}")}
                    ), 30)
                    output = tool_output_text(result)
                outputs.append({"type": "message", "role": "tool", "call_id": call_id,
                                "content": [{"type": "input_text", "text": output}]})
            if outputs and generation == self._generation and not self._closed:
                self._response_id = None
                self._tools_returned = True
                await self.transport.send({"type": "conversation.item.create", "items": outputs})
        except asyncio.CancelledError:
            raise
        except Exception:
            # Do not leave a tool round hanging, and do not leak tool exception bodies.
            self._tool_tasks.discard(asyncio.current_task())
            async with self._turn_lock:
                if generation == self._generation and not self._closed:
                    self.conn.audio_input_ready.clear()
                    self.conn.conversation_ready.clear()
                    self.conn.logger.bind(tag=__name__).warning("豆包3.0工具回合失败，请重新建立会话")
                    await self._interrupt()
                    await handleAbortMessage(self.conn, preserve_user_audio=True)

    def _cancel_timeout(self):
        if self._timeout_task and self._timeout_task is not asyncio.current_task():
            self._timeout_task.cancel()
        self._timeout_task = None

    async def _response_timeout(self):
        try:
            await asyncio.sleep(min(300, max(1, float(self.config.get("response_timeout_seconds", 120)))))
            async with self._turn_lock:
                await self._interrupt()
                await handleAbortMessage(self.conn, preserve_user_audio=True)
                self.conn.audio_input_ready.clear()
                self.conn.conversation_ready.clear()
        except asyncio.CancelledError:
            raise
        except Exception:
            self.conn.audio_input_ready.clear()
            self.conn.conversation_ready.clear()

    async def close(self):
        if self._closed:
            return
        self._closing = True
        self.conn.audio_input_ready.clear()
        self.conn.conversation_ready.clear()
        # Reconnect owns the turn lock while waiting for network ACKs. Cancel it
        # before acquiring that lock, including an explicitly requested reset.
        reconnect_tasks = {
            task for task in (self._session_maintenance_task, self._reconnect_task)
            if task is not None and task is not asyncio.current_task() and not task.done()
        }
        for task in reconnect_tasks:
            task.cancel()
        if reconnect_tasks:
            await asyncio.gather(*reconnect_tasks, return_exceptions=True)
        async with self._turn_lock:
            if self._closed:
                return
            self._generation += 1
            self._cancel_timeout()
            await self._cancel_finish_response()
            await self._reset_output()
            await self._cancel_tools()
            await super().close()
