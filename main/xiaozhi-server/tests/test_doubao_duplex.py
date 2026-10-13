import asyncio
import base64
import json
import threading
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
from config import settings
settings.config_file_valid = True

from core.providers.realtime.doubao_duplex_transport import (
    DoubaoDuplexTransport, OFFICIAL_URL, OfficialConnect, session_config, validate_url,
)
from core.providers.realtime.doubao_duplex import DoubaoDuplexSession
from core.providers.realtime.registry import realtime_adapter_capabilities, create_realtime_session
from core.benchmark.doubao_duplex import run_duplex_samples


@pytest.mark.parametrize('url', [
    'ws://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue',
    'wss://openspeech.bytedance.com.evil.test/api/v3/duplex/realtime/dialogue',
    'wss://user:secret@openspeech.bytedance.com/api/v3/duplex/realtime/dialogue',
    OFFICIAL_URL + '?key=secret', OFFICIAL_URL + '#fragment',
    'wss://openspeech.bytedance.com/api/v3/realtime/dialogue',
    'wss://127.0.0.1/api/v3/duplex/realtime/dialogue',
])
def test_credentials_never_sent_to_other_endpoints(url):
    factory = AsyncMock()
    async def scenario():
        transport = DoubaoDuplexTransport({'base_url': url, 'api_key': 'test-key'}, websocket_factory=factory)
        with pytest.raises(ValueError):
            await transport.connect()
        factory.assert_not_called()
    asyncio.run(scenario())


def test_configuration_is_json_duplex_not_legacy_binary_or_openai():
    assert validate_url(OFFICIAL_URL) == OFFICIAL_URL
    config = session_config({}, '角色设定', [{'type': 'function', 'function': {
        'name': 'allowed', 'description': 'description', 'parameters': {'type': 'object'},
    }}])
    assert config['model'] == '1.2.6.1'
    assert config['audio']['output']['format'] == {'type': 'pcm_s16le', 'rate': 24000}
    assert config['tools'][0]['name'] == 'allowed'
    assert 'function' not in config['tools'][0]
    with pytest.raises(ValueError):
        session_config({'model_name': '1.2.1.1'}, '', [])
    with pytest.raises(ValueError):
        session_config({'output_sample_rate': 16000}, '', [])
    assert isinstance(OfficialConnect.process_redirect(None, RuntimeError('redirect')), ValueError)


class Socket:
    def __init__(self):
        self.queue = asyncio.Queue()
        self.sent = []
        self.closed = False
    def __aiter__(self):
        return self
    async def __anext__(self):
        value = await self.queue.get()
        if value is None:
            raise StopAsyncIteration
        return json.dumps(value)
    async def send(self, raw):
        assert isinstance(raw, str)
        event = json.loads(raw)
        self.sent.append(event)
        responses = {'session.create': 'session.created', 'session.update': 'session.updated',
                     'session.close': 'session.closed'}
        if event['type'] in responses:
            await self.queue.put({'type': responses[event['type']], 'session': {'id': 'dialog-1'}})
    async def close(self):
        self.closed = True
        await self.queue.put(None)


def test_transport_auth_ack_pacing_mute_and_graceful_close():
    async def scenario():
        socket = Socket()
        async def connect(url, additional_headers=None, **kwargs):
            assert url == OFFICIAL_URL
            assert additional_headers == {'X-Api-Key': 'test-key'}
            return socket
        transport = DoubaoDuplexTransport({'base_url': OFFICIAL_URL, 'api_key': 'test-key'}, websocket_factory=connect)
        await transport.connect()
        await transport.configure('角色', [])
        await transport.send_audio(b'\x01\x00' * 960)
        await transport.mute(commit=True)
        await transport.configure('新角色', [])
        await transport.close()
        types = [e['type'] for e in socket.sent]
        assert types[:3] == ['session.create', 'input_audio_mute.commit', 'input_audio_unmute.commit']
        chunks = [base64.b64decode(e['audio']) for e in socket.sent if e['type'] == 'input_audio_buffer.append']
        assert list(map(len, chunks)) == [640, 640, 640]
        assert b''.join(chunks) == b'\x01\x00' * 960
        assert types[-2:] == ['session.update', 'session.close']
        assert socket.closed and transport.reader_task.done() and transport.idle_task.done()
        assert 'test-key' not in json.dumps(socket.sent)
    asyncio.run(scenario())


def make_session():
    logger = Mock()
    logger.bind.return_value = logger
    conn = SimpleNamespace(logger=logger, prompt='角色', config={},
        func_handler=Mock(), dialogue=Mock(), websocket=SimpleNamespace(send=AsyncMock()),
        _abort_lock=threading.Lock(), client_abort=False,
        audio_input_ready=asyncio.Event(), conversation_ready=asyncio.Event(), loop=asyncio.get_running_loop())
    conn.func_handler.get_functions.return_value = []
    transport = SimpleNamespace(connect=AsyncMock(), configure=AsyncMock(), send=AsyncMock(),
        close=AsyncMock(), send_audio=AsyncMock(), mute=AsyncMock())
    session = DoubaoDuplexSession(conn, {}, transport=transport)
    return session, transport, conn


def test_tools_gated_batched_deduplicated_and_cancelled(monkeypatch):
    async def scenario():
        session, transport, conn = make_session()
        conn.func_handler.has_tool.side_effect = lambda name: name == 'allowed'
        conn.func_handler.handle_llm_function_call = AsyncMock(return_value=SimpleNamespace(result='ok', response=None, action=None))
        items = [{'call_id': 'one', 'name': 'allowed', 'arguments': '{}'},
                 {'call_id': 'two', 'name': 'forbidden', 'arguments': '{}'}]
        await session._execute_tools(items, 0)
        conn.func_handler.handle_llm_function_call.assert_awaited_once()
        assert (
            conn.func_handler.handle_llm_function_call.await_args.args[1]["id"]
            == "one"
        )
        reply = transport.send.call_args.args[0]
        assert reply['type'] == 'conversation.item.create'
        assert [i['call_id'] for i in reply['items']] == ['one', 'two']
        assert reply['items'][1]['content'][0]['text'] == '工具未启用或不可用'
        await session._execute_tools(items, 0)
        assert transport.send.await_count == 1
        gate = asyncio.Event()
        async def delayed(*args):
            await gate.wait()
        conn.func_handler.handle_llm_function_call.side_effect = delayed
        task = asyncio.create_task(session._execute_tools([{'call_id': 'three', 'name': 'allowed'}], 0))
        session._tool_tasks.add(task)
        await asyncio.sleep(0)
        await session.handle_abort()
        assert task.cancelled()
        assert transport.send.call_args.args[0]['type'] == 'response.cancel'
        await session.close()
    asyncio.run(scenario())


def test_audio_lifecycle_barge_in_and_late_response(monkeypatch):
    monkeypatch.setattr('core.providers.realtime.doubao_duplex.handleAbortMessage', AsyncMock())
    monkeypatch.setattr('core.providers.realtime.doubao_duplex.send_stt_display', AsyncMock())
    monkeypatch.setattr('core.providers.realtime.doubao_duplex.enqueue_asr_report', Mock())
    async def scenario():
        session, transport, conn = make_session()
        session._handle_audio = AsyncMock()
        session._finish_response = AsyncMock()
        await session.handle_event({'type': 'conversation.item.input_audio_transcription.completed', 'transcript': '你好'})
        await session.handle_event({'type': 'response.output_audio.delta', 'response_id': 'old', 'delta': 'AAA='})
        session.input_audio = [b'new-user-audio']
        await session.handle_event({'type': 'conversation.item.input_audio_transcription.started'})
        assert session.input_audio == [b'new-user-audio']
        await session.handle_event({'type': 'conversation.item.input_audio_transcription.completed', 'transcript': '第二句'})
        await session.handle_event({'type': 'response.output_audio.delta', 'response_id': 'old', 'delta': 'AAA='})
        assert session._handle_audio.await_count == 1
        await session.handle_event({'type': 'response.output_audio.delta', 'response_id': 'new', 'delta': 'AAA='})
        await session.handle_event({'type': 'response.output_audio.done', 'response_id': 'new'})
        await session._finish_task
        session._finish_response.assert_awaited_once()
        assert await session.handle_text('text')
        assert json.loads(conn.websocket.send.call_args.args[0])['code'] == 'unsupported_text_input'
        with pytest.raises(RuntimeError, match='不支持图片'):
            await session.handle_image('what', b'image')
        await session.close()
    asyncio.run(scenario())


def test_context_reset_and_registry_preserve_tool_capability():
    async def scenario():
        session, _, conn = make_session()
        new_transport = SimpleNamespace(connect=AsyncMock(), configure=AsyncMock(), close=AsyncMock())
        session._transport_factory = lambda: new_transport
        assert await session.reset_context_now()
        assert session.transport is new_transport
        assert new_transport.configure.await_count == 2
        assert conn.conversation_ready.is_set()
        assert realtime_adapter_capabilities({'type': 'doubao_duplex'}).native_function_calling
        routed = create_realtime_session(conn, {'type': 'doubao_duplex'})
        assert isinstance(routed, DoubaoDuplexSession)
        await routed.close()
        await session.close()
    asyncio.run(scenario())


@pytest.mark.parametrize('reply_audio,terminal,success', [(True, True, True), (False, True, False), (True, False, False)])
def test_real_audio_probe_requires_audio_and_terminal(reply_audio, terminal, success):
    class ProbeTransport:
        def __init__(self, config):
            pass
        async def connect(self, handler):
            self.handler = handler
        async def configure(self, prompt, tools):
            pass
        async def send_audio(self, pcm):
            assert len(pcm) > 640
            await self.handler({'type': 'conversation.item.input_audio_transcription.completed', 'transcript': '你好'})
            if reply_audio:
                await self.handler({'type': 'response.output_audio.delta', 'delta': base64.b64encode(b'\0' * 960).decode()})
            if terminal:
                await self.handler({'type': 'response.output_audio.done'})
            else:
                await self.handler({'type': 'transport.closed'})
        async def mute(self, commit=False):
            assert commit
        async def close(self):
            pass
    result = asyncio.run(run_duplex_samples({}, 1, ProbeTransport))
    assert result[0]['success'] is success
    assert result[0]['measured'] is success


def test_upstream_errors_fail_waiters_without_echoing_secrets():
    class ErrorSocket(Socket):
        async def send(self, raw):
            if json.loads(raw)['type'] == 'session.create':
                await self.queue.put({'type': 'error', 'message': 'X-Api-Key: private-secret'})
    async def scenario():
        socket = ErrorSocket()
        factory = AsyncMock(return_value=socket)
        transport = DoubaoDuplexTransport({'base_url': OFFICIAL_URL, 'api_key': 'test-key'}, websocket_factory=factory)
        seen = []
        async def handler(event):
            seen.append(event)
        await transport.connect(handler)
        with pytest.raises(RuntimeError) as error:
            await transport.configure('prompt', [])
        assert 'private-secret' not in str(error.value)
        assert 'private-secret' not in json.dumps(seen)
        assert not transport.waiters
        await transport.close()
    asyncio.run(scenario())


def test_audio_stop_mutes_but_does_not_clear_new_input():
    async def scenario():
        session, transport, _ = make_session()
        session.input_audio = [b'new-input']
        await session.handle_listen({'state': 'stop'})
        transport.mute.assert_awaited_once_with(commit=True)
        await session.handle_listen({'state': 'start'})
        assert session.input_audio == [b'new-input']
        await session.close()
    asyncio.run(scenario())


def test_text_benchmark_does_not_silently_substitute_audio(monkeypatch):
    from core.benchmark.runner import _run_model
    async def scenario():
        result = await _run_model({'model_type': 'LLM', 'provider_type': 'doubao_duplex',
            'config': {'conversation_mode': 'realtime'}}, 1)
        assert result['benchmark_level'] == 'UNSUPPORTED'
        assert result['success_count'] == 0
    asyncio.run(scenario())
