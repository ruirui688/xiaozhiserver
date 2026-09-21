"""Prepare only the TTS transport while the LLM works; never start synthesis."""

import asyncio

from core.observability.session_telemetry import latency_span


class ConnectionPrewarm:
    def __init__(self, provider):
        self.provider = provider
        self.task = None
        self.epoch = 0

    def start(self):
        """Called by the text worker; all task ownership stays on its event loop."""
        if not self.provider.enable_ws_reuse:
            return
        conn = self.provider.conn
        epoch = self.epoch

        def schedule():
            if (epoch != self.epoch or self.provider.ws is not None
                    or any(getattr(conn, flag, False) for flag in
                           ("closing", "closed", "client_abort"))
                    or conn.stop_event.is_set()):
                return
            if self.task is None or self.task.done():
                self.task = asyncio.create_task(self._run())

        conn.loop.call_soon_threadsafe(schedule)

    async def _run(self):
        conn = self.provider.conn
        try:
            with latency_span(conn, "tts.connection_prewarm"):
                await self.provider._ensure_connection()
        except asyncio.CancelledError:
            raise
        except Exception as error:
            # Opportunistic: a failed warmup must not fail or synthesize a reply.
            conn.logger.bind(tag=__name__).warning(
                "TTS连接预热失败，正文到达时按原路径重试: errorType={}", type(error).__name__
            )

    async def close(self):
        self.epoch += 1
        task, self.task = self.task, None
        if task is not None and task is not asyncio.current_task():
            if not task.done():
                task.cancel()
            await asyncio.gather(task, return_exceptions=True)
