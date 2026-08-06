"""Deadline-aware cleanup coordination for a connection session."""

import asyncio
import threading
import time


class CloseCoordinator:
    """Run connection cleanup without blocking the server event loop."""

    def __init__(self, connection, timeout_seconds, log_tag=__name__):
        self.connection = connection
        self.timeout_seconds = timeout_seconds
        self.log_tag = log_tag

    def new_deadline(self):
        return time.monotonic() + self.timeout_seconds

    @staticmethod
    def remaining_time(deadline):
        return max(0.0, deadline - time.monotonic())

    @staticmethod
    def join_threads_until(threads, deadline):
        for thread in threads:
            remaining = max(0.0, deadline - time.monotonic())
            if remaining <= 0:
                break
            thread.join(timeout=remaining)

    def run_async_in_thread(self, coro, thread_name):
        conn = self.connection

        def _run():
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            try:
                loop.run_until_complete(coro)
            except Exception as exc:
                conn.logger.bind(msg_id=conn.session_id).warning(
                    f"{thread_name} failed: {exc}"
                )
            finally:
                loop.close()

        thread = threading.Thread(target=_run, daemon=False, name=thread_name)
        thread.start()
        return thread

    async def wait_for_threads(self, threads, deadline):
        if not threads:
            return
        join_task = asyncio.create_task(
            asyncio.to_thread(self.join_threads_until, threads, deadline)
        )
        remaining = self.remaining_time(deadline)
        if remaining <= 0:
            join_task.cancel()
            return
        done, _ = await asyncio.wait({join_task}, timeout=remaining)
        if join_task not in done:
            join_task.cancel()

    async def run_blocking_cleanup(self, callback, deadline, label):
        conn = self.connection
        remaining = self.remaining_time(deadline)
        if remaining <= 0:
            return False
        task = asyncio.create_task(asyncio.to_thread(callback))
        done, _ = await asyncio.wait({task}, timeout=remaining)
        if task not in done:
            task.cancel()
            conn.logger.bind(tag=self.log_tag).warning(
                f"{label}超过关闭总期限，继续清理"
            )
            return False
        try:
            task.result()
        except Exception as exc:
            conn.logger.bind(tag=self.log_tag).error(f"{label}时出错: {exc}")
            return False
        return True

    async def run_async_cleanup(self, callback, deadline, label):
        conn = self.connection
        remaining = self.remaining_time(deadline)
        if remaining <= 0:
            return False
        try:
            task = asyncio.create_task(callback())
        except Exception as exc:
            conn.logger.bind(tag=self.log_tag).error(f"{label}时出错: {exc}")
            return False
        done, _ = await asyncio.wait({task}, timeout=remaining)
        if task not in done:
            task.cancel()
            conn.logger.bind(tag=self.log_tag).warning(
                f"{label}超过关闭总期限，继续清理"
            )
            return False
        try:
            task.result()
        except asyncio.CancelledError:
            return False
        except Exception as exc:
            conn.logger.bind(tag=self.log_tag).error(f"{label}时出错: {exc}")
            return False
        return True

    async def close(self, ws=None, deadline=None):
        conn = self.connection
        conn._begin_closing()
        deadline = deadline or self.new_deadline()
        close_lock = getattr(conn, "_close_lock", None)
        if close_lock is None:
            close_lock = asyncio.Lock()
            conn._close_lock = close_lock

        remaining = self.remaining_time(deadline)
        if remaining <= 0:
            return
        try:
            await asyncio.wait_for(close_lock.acquire(), timeout=remaining)
        except asyncio.TimeoutError:
            return

        try:
            if conn.closed:
                return

            await conn._wait_for_background_initialize(deadline)
            components_future = getattr(conn, "_components_initialize_future", None)
            if components_future is not None and not components_future.done():
                components_future.cancel()

            conversation_router = getattr(conn, "conversation_router", None)
            if conversation_router is not None:
                await self.run_async_cleanup(
                    conversation_router.close, deadline, "关闭Realtime会话"
                )

            if (
                hasattr(conn, "vad")
                and conn.vad
                and hasattr(conn.vad, "release_conn_resources")
            ):
                await self.run_blocking_cleanup(
                    lambda: conn.vad.release_conn_resources(conn),
                    deadline,
                    "清理VAD连接资源",
                )

            if hasattr(conn, "audio_buffer"):
                conn.audio_buffer.clear()

            if hasattr(conn, "func_handler") and conn.func_handler:
                await self.run_async_cleanup(
                    conn.func_handler.cleanup, deadline, "清理工具处理器"
                )

            if hasattr(conn, "report_manager"):
                report_timeout = self.remaining_time(deadline)
                await self.run_blocking_cleanup(
                    lambda: conn.report_manager.stop(conn, timeout=report_timeout),
                    deadline,
                    "停止上报线程",
                )

            reply_lifecycle = getattr(conn, "reply_lifecycle", None)
            if reply_lifecycle is not None:
                reply_lifecycle.interrupt(reason="connection_closed")
            clear_deferred = getattr(conn, "_clear_deferred_listen_detects", None)
            if callable(clear_deferred):
                clear_deferred("connection_closed")
            conn.clear_queues()

            if hasattr(conn, "iot_descriptors"):
                conn.iot_descriptors.clear()

            websocket = ws or conn.websocket
            if websocket is not None:
                await self.run_async_cleanup(
                    websocket.close, deadline, "关闭WebSocket连接"
                )

            if conn.tts:
                await self.run_async_cleanup(conn.tts.close, deadline, "关闭TTS")
            if conn.asr:
                await self.run_async_cleanup(conn.asr.close, deadline, "关闭ASR")
            if conn.voiceprint_provider:
                await self.run_async_cleanup(
                    conn.voiceprint_provider.close, deadline, "关闭声纹识别"
                )

            if conn.executor:
                try:
                    conn.executor.shutdown(wait=False, cancel_futures=True)
                except Exception as exc:
                    conn.logger.bind(tag=self.log_tag).error(f"关闭线程池时出错: {exc}")
                conn.executor = None
            conn.logger.bind(tag=self.log_tag).info("连接资源已释放")
        except Exception as exc:
            conn.logger.bind(tag=self.log_tag).error(f"关闭连接时出错: {exc}")
        finally:
            conn.closed = True
            if conn.stop_event:
                conn.stop_event.set()
            close_lock.release()
