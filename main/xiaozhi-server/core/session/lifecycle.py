"""State transitions shared by connection initialization and shutdown."""

import asyncio


class SessionLifecycle:
    """Publish shutdown state and guard component initialization races."""

    def __init__(self, connection, log_tag=__name__):
        self.connection = connection
        self.log_tag = log_tag

    def begin_closing(self):
        conn = self.connection
        lifecycle_lock = getattr(conn, "_lifecycle_lock", None)
        if lifecycle_lock is not None:
            with lifecycle_lock:
                first_close = not conn.closing
                conn.closing = True
        else:
            first_close = not getattr(conn, "closing", False)
            conn.closing = True

        stop_event = getattr(conn, "stop_event", None)
        voice_status = getattr(conn, "voice_status", None)
        if voice_status is not None:
            voice_status.close()
        snapshot = getattr(conn, "managed_memory_snapshot", None)
        if snapshot is not None:
            snapshot.invalidate()
        memory_session = getattr(conn, "managed_memory_session", None)
        if memory_session is not None:
            memory_session.cancel_scheduled_flush()
        reloader = getattr(conn, "runtime_config_reloader", None)
        cancel_snapshot_refresh = getattr(reloader, "cancel_snapshot_refresh", None)
        if cancel_snapshot_refresh is not None:
            cancel_snapshot_refresh()
        readiness = getattr(conn, "runtime_readiness", None)
        if readiness is not None:
            readiness.clear_wake_ready()
        if stop_event is not None:
            stop_event.set()

        abort_lock = getattr(conn, "_abort_lock", None)
        if abort_lock is not None:
            with abort_lock:
                conn.client_abort = True
                if first_close:
                    conn.abort_generation += 1
        else:
            conn.client_abort = True
            if first_close:
                conn.abort_generation = getattr(conn, "abort_generation", 0) + 1

        if first_close:
            rate_controller = getattr(conn, "audio_rate_controller", None)
            if rate_controller is not None:
                try:
                    rate_controller.stop_sending()
                    rate_controller.reset()
                except Exception as exc:
                    conn.logger.bind(tag=self.log_tag).warning(
                        f"停止音频流控器失败，继续关闭连接: {exc}"
                    )

        background_task = getattr(conn, "_background_initialize_task", None)
        if background_task is not None and not background_task.done():
            background_task.cancel()
        return first_close

    def assign_initialized_component(self, name, value):
        if value is None:
            return False

        conn = self.connection
        lifecycle_lock = getattr(conn, "_lifecycle_lock", None)
        if lifecycle_lock is None:
            if getattr(conn, "closing", False):
                return False
            setattr(conn, name, value)
            return True

        with lifecycle_lock:
            if conn.closing:
                return False
            setattr(conn, name, value)
            return True

    async def wait_for_background_initialize(self, deadline, remaining_time):
        conn = self.connection
        task = getattr(conn, "_background_initialize_task", None)
        if task is None or task.done() or task is asyncio.current_task():
            return

        task.cancel()
        remaining = remaining_time(deadline)
        if remaining <= 0:
            return

        done, _ = await asyncio.wait({task}, timeout=remaining)
        if task not in done:
            return
        try:
            task.result()
        except asyncio.CancelledError:
            pass
        except Exception as exc:
            conn.logger.bind(tag=self.log_tag).warning(
                f"后台初始化结束时出现异常: {exc}"
            )
