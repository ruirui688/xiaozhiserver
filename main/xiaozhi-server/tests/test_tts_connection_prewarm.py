import asyncio
import threading
from unittest.mock import AsyncMock, Mock

import pytest

from config import settings
settings.config_file_valid = True

from core.providers.tts import huoshan_double_stream
from test_huoshan_double_stream_tts_config import _fault_provider


def provider_for_loop():
    provider = _fault_provider()
    provider.conn.loop = asyncio.get_running_loop()
    provider.conn.stop_event = threading.Event()
    provider.conn.client_abort = False
    provider.conn.logger = Mock()
    return provider


def test_prewarm_overlaps_llm_wait_without_starting_synthesis(monkeypatch):
    async def run():
        provider = provider_for_loop()
        connected = asyncio.Event()
        llm_text = asyncio.Event()
        socket = Mock(close=AsyncMock())

        async def connect():
            if provider.ws is None:
                provider.ws = socket
                connected.set()
            return provider.ws

        monkeypatch.setattr(provider, "_open_connection", connect)
        monkeypatch.setattr(provider, "start_session", AsyncMock())
        assert provider._prepare_pending_session("sentence", None)
        provider._connection_prewarm.start()
        await asyncio.wait_for(connected.wait(), 1)
        assert not llm_text.is_set()
        assert provider.activate_session is False
        assert provider.tts_audio_queue.empty()
        provider.start_session.assert_not_awaited()
        llm_text.set()
        assert await provider._ensure_connection() is socket
        await provider.close()

    asyncio.run(run())


def test_concurrent_prewarm_and_text_open_only_one_socket(monkeypatch):
    async def run():
        provider = provider_for_loop()
        entered, release = asyncio.Event(), asyncio.Event()
        sockets = []

        async def connect():
            if provider.ws is None:
                entered.set()
                await release.wait()
                provider.ws = Mock(close=AsyncMock())
                sockets.append(provider.ws)
            return provider.ws

        monkeypatch.setattr(provider, "_open_connection", connect)
        provider._connection_prewarm.start()
        provider._connection_prewarm.start()
        await asyncio.wait_for(entered.wait(), 1)
        actual_text = asyncio.create_task(provider._ensure_connection())
        await asyncio.sleep(0)
        assert not actual_text.done()
        release.set()
        await asyncio.wait_for(actual_text, 1)
        assert len(sockets) == 1
        await provider.close()

    asyncio.run(run())


@pytest.mark.parametrize("error", [TimeoutError("test"), RuntimeError("test")])
def test_failed_prewarm_leaves_normal_text_retry_available(monkeypatch, error):
    async def run():
        provider = provider_for_loop()
        connect = AsyncMock(side_effect=[error, "retry-socket"])
        monkeypatch.setattr(provider, "_open_connection", connect)
        provider._connection_prewarm.start()
        await asyncio.sleep(0)
        await provider._connection_prewarm.task
        assert not provider.activate_session
        assert await provider._ensure_connection() == "retry-socket"
        assert connect.await_count == 2
        await provider.close()

    asyncio.run(run())


def test_close_cancels_handshake_and_closes_provisional_socket(monkeypatch):
    async def run():
        provider = provider_for_loop()
        waiting = asyncio.Event()
        socket = Mock(close=AsyncMock())

        async def handshake(*args, **kwargs):
            waiting.set()
            await asyncio.Event().wait()

        monkeypatch.setattr(huoshan_double_stream.websockets, "connect", AsyncMock(return_value=socket))
        monkeypatch.setattr(provider, "start_connection", AsyncMock())
        monkeypatch.setattr(provider, "_wait_for_event", handshake)
        provider._connection_prewarm.start()
        await asyncio.wait_for(waiting.wait(), 1)
        task = provider._connection_prewarm.task
        await asyncio.wait_for(provider.close(), 1)
        assert task.cancelled()
        assert provider.ws is None
        socket.close.assert_awaited_once()
        assert not provider._connection_lock.locked()

    asyncio.run(run())


def test_close_invalidates_scheduled_but_not_started_prewarm(monkeypatch):
    async def run():
        provider = provider_for_loop()
        connect = AsyncMock()
        monkeypatch.setattr(provider, "_open_connection", connect)
        provider._connection_prewarm.start()
        await provider.close()
        await asyncio.sleep(0)
        connect.assert_not_awaited()
        assert provider._connection_prewarm.task is None

    asyncio.run(run())


def test_disabled_reuse_does_not_open_a_throwaway_connection(monkeypatch):
    async def run():
        provider = provider_for_loop()
        provider.enable_ws_reuse = False
        connect = AsyncMock()
        monkeypatch.setattr(provider, "_open_connection", connect)
        provider._connection_prewarm.start()
        await asyncio.sleep(0)
        connect.assert_not_awaited()
        await provider.close()

    asyncio.run(run())


def test_closed_prewarmed_socket_is_reconnected_before_text(monkeypatch):
    async def run():
        provider = provider_for_loop()
        old = Mock(state=huoshan_double_stream.State.CLOSED, close=AsyncMock())
        new = Mock(state=huoshan_double_stream.State.OPEN, close=AsyncMock())
        provider.ws = old
        monkeypatch.setattr(huoshan_double_stream.websockets, "connect", AsyncMock(return_value=new))
        monkeypatch.setattr(provider, "start_connection", AsyncMock())
        monkeypatch.setattr(provider, "_wait_for_event", AsyncMock())
        assert await provider._ensure_connection() is new
        old.close.assert_awaited_once()
        assert not provider.activate_session
        await provider.close()

    asyncio.run(run())


@pytest.mark.parametrize("state", ["closing", "closed", "client_abort", "stopped"])
def test_prewarm_respects_connection_state(monkeypatch, state):
    async def run():
        provider = provider_for_loop()
        connect = AsyncMock()
        monkeypatch.setattr(provider, "_open_connection", connect)
        if state == "stopped":
            provider.conn.stop_event.set()
        else:
            setattr(provider.conn, state, True)
        provider._connection_prewarm.start()
        await asyncio.sleep(0)
        connect.assert_not_awaited()
        await provider.close()

    asyncio.run(run())
