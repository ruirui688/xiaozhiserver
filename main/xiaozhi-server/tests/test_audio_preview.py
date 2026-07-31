import asyncio
import json
import sys
from pathlib import Path
from types import SimpleNamespace


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings

settings.config_file_valid = True

from core.auth import AuthManager
from core.handle import sendAudioHandle
from core.observability.audio_preview import (
    AudioPreviewHub,
    PREVIEW_CLIENT_PREFIX,
    PREVIEW_QUEUE_SIZE,
    _PreviewSubscriber,
)


class FakeLogger:
    def bind(self, **_kwargs):
        return self

    def info(self, _message):
        return None

    def warning(self, _message):
        return None


class FakePreviewWebSocket:
    def __init__(self, auth_message):
        self.auth_message = auth_message
        self.sent = []
        self.close_code = None
        self._closed = asyncio.Event()

    async def recv(self):
        return self.auth_message

    async def send(self, payload):
        self.sent.append(payload)

    async def close(self, code=1000, reason=""):
        self.close_code = code
        self._closed.set()

    def __aiter__(self):
        return self

    async def __anext__(self):
        await self._closed.wait()
        raise StopAsyncIteration


def _auth_message(auth, device_id="68:ee:00:00:72:18", client_suffix="one"):
    client_id = PREVIEW_CLIENT_PREFIX + client_suffix
    return json.dumps(
        {
            "type": "auth",
            "deviceId": device_id,
            "clientId": client_id,
            "token": auth.generate_token(client_id, device_id),
        }
    )


def test_preview_ticket_requires_dedicated_client_prefix_and_short_lifetime(monkeypatch):
    auth = AuthManager("secret", expire_seconds=3600)
    hub = AudioPreviewHub(auth, FakeLogger())
    client_id = PREVIEW_CLIENT_PREFIX + "valid"
    token = auth.generate_token(client_id, "device-a")

    assert hub._verify_ticket(token, client_id, "device-a") is True
    assert hub._verify_ticket(token, "ordinary-client", "device-a") is False
    assert hub._verify_ticket(token, client_id, "device-b") is False

    timestamp = int(token.rsplit(".", 1)[1])
    monkeypatch.setattr("core.observability.audio_preview.time.time", lambda: timestamp + 61)
    assert hub._verify_ticket(token, client_id, "device-a") is False


def test_only_one_listener_can_subscribe_to_each_device():
    async def scenario():
        auth = AuthManager("secret", expire_seconds=3600)
        hub = AudioPreviewHub(auth, FakeLogger())
        first = FakePreviewWebSocket(_auth_message(auth, client_suffix="first"))
        second = FakePreviewWebSocket(_auth_message(auth, client_suffix="second"))

        first_task = asyncio.create_task(hub.handle_connection(first))
        for _ in range(20):
            if any('"type": "ready"' in str(item) for item in first.sent):
                break
            await asyncio.sleep(0)

        await hub.handle_connection(second)
        assert second.close_code == 4409
        assert any('"type": "listener_busy"' in str(item) for item in second.sent)

        await first.close()
        await first_task

    asyncio.run(scenario())


def test_listener_joining_mid_stream_receives_current_audio_metadata():
    async def scenario():
        auth = AuthManager("secret", expire_seconds=3600)
        hub = AudioPreviewHub(auth, FakeLogger())
        hub.publish_stream_start("68:ee:00:00:72:18", "sentence-a", 24000, 60)
        websocket = FakePreviewWebSocket(_auth_message(auth, client_suffix="midstream"))

        task = asyncio.create_task(hub.handle_connection(websocket))
        for _ in range(20):
            controls = [json.loads(item) for item in websocket.sent if isinstance(item, str)]
            if any(item.get("type") == "stream_start" for item in controls):
                break
            await asyncio.sleep(0)

        controls = [json.loads(item) for item in websocket.sent if isinstance(item, str)]
        stream_start = next(item for item in controls if item.get("type") == "stream_start")
        assert stream_start["sampleRate"] == 24000
        assert stream_start["sentenceId"] == "sentence-a"
        await websocket.close()
        await task

    asyncio.run(scenario())


def test_slow_preview_queue_drops_backlog_and_marks_discontinuity():
    hub = AudioPreviewHub(AuthManager("secret"), FakeLogger())
    subscriber = _PreviewSubscriber(
        websocket=None,
        client_id=PREVIEW_CLIENT_PREFIX + "slow",
        queue=asyncio.Queue(maxsize=PREVIEW_QUEUE_SIZE),
    )
    for index in range(PREVIEW_QUEUE_SIZE):
        subscriber.queue.put_nowait(f"old-{index}".encode())

    hub._enqueue(subscriber, b"latest-opus")

    assert subscriber.queue.qsize() == 2
    assert json.loads(subscriber.queue.get_nowait())["type"] == "discontinuity"
    assert subscriber.queue.get_nowait() == b"latest-opus"


def test_device_send_success_copies_exact_packet_without_waiting_for_preview():
    class PreviewHub:
        def __init__(self):
            self.packets = []

        def publish_audio(self, device_id, packet):
            self.packets.append((device_id, packet))

    class DeviceWebSocket:
        async def send(self, payload):
            assert payload == b"exact-opus"

    async def scenario():
        preview = PreviewHub()
        conn = SimpleNamespace(
            conn_from_mqtt_gateway=False,
            websocket=DeviceWebSocket(),
            server=SimpleNamespace(audio_preview_hub=preview),
            device_id="device-a",
            logger=FakeLogger(),
        )
        flow_control = {"packet_count": 0, "sequence": 0}
        await sendAudioHandle._do_send_audio(conn, b"exact-opus", flow_control)
        assert preview.packets == [("device-a", b"exact-opus")]
        assert flow_control == {"packet_count": 1, "sequence": 1}

    asyncio.run(scenario())
