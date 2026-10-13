"""Real loopback WebSocket + Opus, synthetic server messages, no paid provider calls."""

import asyncio
import json
import wave

import opuslib_next
import pytest
from websockets.asyncio.client import connect
from websockets.asyncio.server import serve

from tools.mobile_voice_probe import HELLO, ProbeError, exchange, load_audio, main, secure_url, validate_ticket


def test_probe_is_dry_run_without_credentials_or_network(monkeypatch, capsys):
    monkeypatch.delenv("MOBILE_TEST_TOKEN", raising=False)
    assert main([]) == 0
    assert json.loads(capsys.readouterr().out)["network_requests"] == 0


@pytest.mark.parametrize("url", ["http://example.test", "https://secret@example.test",
                                  "https://example.test/?token=secret", "https://example.test/#secret"])
def test_probe_requires_credential_free_tls_endpoint(url):
    with pytest.raises(ProbeError):
        secure_url(url, "https")


def test_ticket_cannot_forward_credentials_to_untrusted_hostname():
    ticket = {"websocketUrl": "wss://other.invalid/xiaozhi/v1/", "token": "hidden"}
    with pytest.raises(ProbeError, match="untrusted_endpoint"):
        validate_ticket(ticket, "expected.invalid")


def test_load_audio_encodes_real_opus_and_validates_format(tmp_path):
    path = tmp_path / "synthetic.wav"
    with wave.open(str(path), "wb") as stream:
        stream.setnchannels(1)
        stream.setsampwidth(2)
        stream.setframerate(16000)
        stream.writeframes(bytes(1920))
    packets = load_audio(path)
    assert len(packets) == 21
    decoder = opuslib_next.Decoder(16000, 1)
    assert all(len(decoder.decode(packet, 960)) == 1920 for packet in packets)
    with wave.open(str(path), "wb") as stream:
        stream.setnchannels(2)
        stream.setsampwidth(2)
        stream.setframerate(16000)
        stream.writeframes(bytes(3840))
    with pytest.raises(ProbeError, match="audio_requires"):
        load_audio(path)


def test_actual_websocket_negotiates_uploads_and_receives_unsolicited_reminder():
    async def scenario():
        captured = []
        encoder = opuslib_next.Encoder(16000, 1, opuslib_next.APPLICATION_VOIP)
        packet = encoder.encode(bytes(1920), 960)

        async def handler(ws):
            captured.append(json.loads(await ws.recv()))
            await ws.send(json.dumps({"type": "speech_route", "input": "server", "output": "server"}))
            await ws.send(json.dumps({"type": "hello"}))
            captured.append(json.loads(await ws.recv()))
            captured.append(await ws.recv())
            await ws.send(json.dumps({"type": "stt", "text": "测试语音内容不进入报告"}))
            # A notification is server-originated; no new client listen.detect or STT precedes it.
            for message in ({"type": "tts", "state": "start"},
                            {"type": "tts", "state": "sentence_start", "text": "日程提醒：合成测试"}):
                await ws.send(json.dumps(message))
            await ws.send(packet)
            await ws.send(json.dumps({"type": "tts", "state": "stop"}))

        async with serve(handler, "127.0.0.1", 0) as server:
            port = server.sockets[0].getsockname()[1]
            async with connect(f"ws://127.0.0.1:{port}") as ws:
                report = await exchange(ws, [packet], 2, pace_seconds=0)
        assert captured == [HELLO, {"type": "listen", "state": "start", "mode": "auto"}, packet]
        assert report["uploaded_packets"] == report["decoded_packets"] == 1
        assert report["decoded_samples"] == 1440  # Same Opus frame decoded as the App's 24k output.
        assert report["reminder_audio_packets"] == report["reminder_text_events"] == 1
        assert "合成测试" not in json.dumps(report, ensure_ascii=False)
    asyncio.run(scenario())


def test_bad_route_rejects_before_any_audio_upload():
    async def scenario():
        async def handler(ws):
            await ws.recv()
            await ws.send(json.dumps({"type": "speech_route", "input": "device", "output": "server"}))
            await ws.wait_closed()
        async with serve(handler, "127.0.0.1", 0) as server:
            async with connect(f"ws://127.0.0.1:{server.sockets[0].getsockname()[1]}") as ws:
                with pytest.raises(ProbeError, match="unsupported_speech_route"):
                    await exchange(ws, [b"must-not-send"], 1)
    asyncio.run(scenario())
