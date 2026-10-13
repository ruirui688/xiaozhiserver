import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock
import pytest
import config.settings
config.settings.config_file_valid = True
from core.auth import AuthManager, AuthenticationError
from core.websocket_server import WebSocketServer

DEVICE = "mobile-0123456789abcdef01234567"
CLIENT = "mobile-voice-12345678-1234-4234-8234-123456789abc"

@pytest.mark.parametrize("age,accepted", [(0,True),(120,True),(121,False),(-5,True),(-6,False)])
def test_mobile_ticket_age_is_bounded(monkeypatch, age, accepted):
    auth=AuthManager("unit-test-only")
    monkeypatch.setattr("core.auth.time.time",lambda:1000)
    token=auth.generate_token(CLIENT,DEVICE)
    monkeypatch.setattr("core.auth.time.time",lambda:1000+age)
    assert auth.verify_token(token,CLIENT,DEVICE) is accepted

def test_mobile_identity_and_client_are_signed():
    auth=AuthManager("unit-test-only")
    token=auth.generate_token(CLIENT,DEVICE)
    assert not auth.verify_token(token,CLIENT,DEVICE+"other")
    assert not auth.verify_token(token,CLIENT+"other",DEVICE)
    assert not auth.verify_token(auth.generate_token("physical-client",DEVICE),"physical-client",DEVICE)

def test_existing_physical_ticket_lifetime_is_preserved(monkeypatch):
    auth=AuthManager("unit-test-only")
    monkeypatch.setattr("core.auth.time.time",lambda:1000)
    token=auth.generate_token("esp32-client","aa:bb:cc")
    monkeypatch.setattr("core.auth.time.time",lambda:1000+86400)
    assert auth.verify_token(token,"esp32-client","aa:bb:cc")

@pytest.mark.parametrize("auth_enabled,whitelisted",[(False,False),(False,True),(True,True)])
def test_mobile_cannot_bypass_auth_when_disabled_or_whitelisted(auth_enabled,whitelisted):
    server=object.__new__(WebSocketServer)
    server.auth_enable=auth_enabled
    server.allowed_devices=[DEVICE] if whitelisted else []
    server.auth=AuthManager("unit-test-only")
    server.logger=Mock()
    ws=SimpleNamespace(request=SimpleNamespace(path="/xiaozhi/v1/",headers={"device-id":DEVICE,"client-id":CLIENT}))
    with pytest.raises(AuthenticationError):
        asyncio.run(server._handle_auth(ws))
    ws.request.headers["authorization"]="Bearer "+server.auth.generate_token(CLIENT,DEVICE)
    asyncio.run(server._handle_auth(ws))
    ws.request.headers["authorization"]="Bearer invalid"
    with pytest.raises(AuthenticationError):
        asyncio.run(server._handle_auth(ws))

def test_session_deadline_cancels_handler_and_releases_connection(monkeypatch):
    from unittest.mock import AsyncMock
    server=object.__new__(WebSocketServer)
    server.active_handlers=set()
    server.active_mobile_devices=set()
    server.max_device_connections=2
    server.logger=Mock()
    server.config={}
    server._vad=server._asr=server._llm=server._memory=server._intent=None
    server.audio_preview_hub=SimpleNamespace()
    server._handle_auth=AsyncMock()
    closed=[]
    class Handler:
        def __init__(self,*args): pass
        async def handle_connection(self,ws):
            try:
                await asyncio.Event().wait()
            finally:
                closed.append("handler")
    monkeypatch.setattr("core.websocket_server.ConnectionHandler",Handler)
    real_wait=asyncio.wait_for
    async def short_deadline(awaitable,timeout):
        assert timeout==1800
        return await real_wait(awaitable,timeout=0.01)
    monkeypatch.setattr("core.websocket_server.asyncio.wait_for",short_deadline)
    ws=SimpleNamespace(request=SimpleNamespace(path="/xiaozhi/v1/",headers={"device-id":DEVICE}),close=AsyncMock())
    asyncio.run(server._handle_connection(ws))
    assert closed==["handler"] and not server.active_handlers
    assert not server.active_mobile_devices
    ws.close.assert_awaited_once()


def _server():
    server = object.__new__(WebSocketServer)
    server.active_handlers = set()
    server.active_mobile_devices = set()
    server.max_device_connections = 4
    server.logger = Mock()
    server.config = {}
    server._vad = server._asr = server._llm = server._memory = server._intent = None
    server.auth_enable = False
    server.allowed_devices = set()
    server.auth = AuthManager("unit-test-only")
    return server


def _websocket(server, *, device=DEVICE, path="/xiaozhi/v1/"):
    return SimpleNamespace(
        request=SimpleNamespace(path=path, headers={
            "device-id": device,
            "client-id": CLIENT,
            "authorization": "Bearer " + server.auth.generate_token(CLIENT, device),
        }),
        close=AsyncMock(), send=AsyncMock(),
    )


@pytest.mark.parametrize("device", [DEVICE.upper(), " " + DEVICE, DEVICE + " ", "mobile-1"])
def test_reserved_mobile_variants_cannot_bypass_disabled_auth_or_whitelist(device):
    server = _server()
    server.allowed_devices = {device}
    ws = _websocket(server, device=device)
    with pytest.raises(AuthenticationError):
        asyncio.run(server._handle_auth(ws))


@pytest.mark.parametrize("client", ["mobile-voice-", CLIENT.upper(), CLIENT + "x"])
def test_noncanonical_mobile_client_is_rejected_even_with_valid_signature(client):
    auth = AuthManager("unit-test-only")
    assert not auth.verify_token(auth.generate_token(client, DEVICE), client, DEVICE)


@pytest.mark.parametrize("device", [DEVICE, DEVICE.upper(), DEVICE.replace("mobile", "mob%00ile")])
def test_mobile_query_identity_is_rejected_before_handler_allocation(monkeypatch, device):
    server = _server()
    constructor = Mock()
    monkeypatch.setattr("core.websocket_server.ConnectionHandler", constructor)
    ws = _websocket(server, path="/xiaozhi/v1/?device-id=" + device)
    ws.request.headers = {}
    asyncio.run(server._handle_connection(ws))
    constructor.assert_not_called()
    ws.send.assert_awaited_once_with("认证失败")
    ws.close.assert_awaited_once()


@pytest.mark.parametrize("parameter", ["authorization", "token", "ACCESS_TOKEN"])
def test_mobile_query_credentials_rejected_and_not_logged(parameter):
    server = _server()
    ws = _websocket(server, path="/xiaozhi/v1/?" + parameter + "=secret-canary")
    asyncio.run(server._handle_connection(ws))
    ws.send.assert_awaited_once_with("认证失败")
    assert "secret-canary" not in str(server.logger.mock_calls)
    assert not server.active_handlers


def test_legacy_device_auth_failure_does_not_log_query_credentials():
    server = _server()
    server.auth_enable = True
    ws = _websocket(server, device="aa:bb:cc", path="/xiaozhi/v1/?authorization=secret-canary")
    ws.request.headers["authorization"] = "Bearer invalid"
    asyncio.run(server._handle_connection(ws))
    assert "secret-canary" not in str(server.logger.mock_calls)


def test_parallel_mobile_connections_reserve_identity_before_handler_starts(monkeypatch):
    async def exercise():
        server = _server()
        started = asyncio.Event()
        release = asyncio.Event()
        constructed = []

        class Handler:
            def __init__(self, *args):
                constructed.append(self)

            async def handle_connection(self, websocket):
                started.set()
                await release.wait()

        monkeypatch.setattr("core.websocket_server.ConnectionHandler", Handler)
        first, second = _websocket(server), _websocket(server)
        # Schedule both before the first ConnectionHandler coroutine executes.
        first_task = asyncio.create_task(server._handle_connection(first))
        second_task = asyncio.create_task(server._handle_connection(second))
        await started.wait()
        await second_task
        second.close.assert_awaited_once_with(code=1013, reason="mobile session already active")
        assert len(constructed) == 1
        assert server.active_mobile_devices == {DEVICE}
        release.set()
        await first_task
        assert not server.active_mobile_devices
        await server._handle_connection(_websocket(server))
        assert len(constructed) == 2
        assert not server.active_handlers

    asyncio.run(exercise())
