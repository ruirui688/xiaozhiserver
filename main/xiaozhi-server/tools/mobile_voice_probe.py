"""Bounded, opt-in mobile protocol probe. No microphone, playback or credentials in reports.

Run with the project's Conda Python. Default is a dry run. --execute provisions
a real mobile identity and can incur ASR/LLM/TTS charges and tool side effects.
Use a dedicated test user/agent and a stable installation UUID, never admin keys.
"""

import argparse
import asyncio
import json
import os
import re
import time
import uuid
import wave
from pathlib import Path
from urllib.parse import urlsplit

import aiohttp
import opuslib_next
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed


HELLO = {
    "type": "hello", "version": 1, "transport": "websocket",
    "audio_params": {"format": "opus", "sample_rate": 16000, "channels": 1, "frame_duration": 60},
    "features": {"aec": False, "speech": {"version": 1, "input": [], "output": []}},
}


class ProbeError(ValueError):
    """Safe fixed error codes only; never put upstream bodies/tokens in exceptions."""


def secure_url(value, scheme, host=None):
    url = urlsplit(value)
    if (url.scheme != scheme or not url.hostname or url.username or url.password
            or url.query or url.fragment or (host and url.hostname != host)):
        raise ProbeError("untrusted_endpoint")
    return value.rstrip("/")


def load_audio(path):
    """At most 30s of mono 16-bit/16k WAV; preserve input, keep frames in bounded memory."""
    if Path(path).stat().st_size > 1_000_000:
        raise ProbeError("audio_too_large")
    with wave.open(str(path), "rb") as source:
        if (source.getnchannels(), source.getsampwidth(), source.getframerate(), source.getcomptype()) != (1, 2, 16000, "NONE"):
            raise ProbeError("audio_requires_16khz_mono_pcm16_wav")
        if not 1 <= source.getnframes() <= 16000 * 30:
            raise ProbeError("audio_duration_out_of_bounds")
        pcm = source.readframes(source.getnframes())
    encoder = opuslib_next.Encoder(16000, 1, opuslib_next.APPLICATION_VOIP)
    # Silence finishes VAD in auto mode; no synthetic listen.stop disguised as microphone mute.
    pcm += bytes(1920 * 20)
    return [encoder.encode(pcm[i:i + 1920].ljust(1920, b"\0"), 960)
            for i in range(0, len(pcm), 1920)]


def validate_ticket(ticket, host):
    if not isinstance(ticket, dict):
        raise ProbeError("invalid_ticket")
    secure_url(ticket.get("websocketUrl", ""), "wss", host)
    for key, pattern in (("deviceId", r"mobile-[0-9a-f]{24}"),
                         ("clientId", r"mobile-voice-[A-Za-z0-9-]{1,64}")):
        if not re.fullmatch(pattern, str(ticket.get(key, ""))):
            raise ProbeError("invalid_ticket_identity")
    token = ticket.get("token")
    if not isinstance(token, str) or not 1 <= len(token) <= 4096 or "\r" in token or "\n" in token:
        raise ProbeError("invalid_ticket_token")
    return ticket


async def exchange(ws, packets, wait_seconds, *, handshake_seconds=20, pace_seconds=0.06):
    """Real mobile wire protocol; tests may supply a loopback socket and synthetic Opus."""
    report = {"hello": False, "server_speech_route": False, "uploaded_packets": 0,
              "stt_events": 0, "tts_starts": 0, "tts_stops": 0,
              "decoded_packets": 0, "decoded_samples": 0, "reminder_text_events": 0,
              "reminder_audio_packets": 0}
    await ws.send(json.dumps(HELLO))
    deadline = time.monotonic() + handshake_seconds
    while not (report["hello"] and report["server_speech_route"]):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ProbeError("handshake_timeout")
        raw = await asyncio.wait_for(ws.recv(), remaining)
        if not isinstance(raw, str):
            raise ProbeError("audio_before_handshake")
        message = json.loads(raw)
        kind = message.get("type")
        if kind in {"error", "need_bind"}:
            raise ProbeError("session_rejected")
        if kind == "hello":
            report["hello"] = True
        if kind == "speech_route":
            if message.get("status") == "error" or message.get("input") != "server" or message.get("output") != "server":
                raise ProbeError("unsupported_speech_route")
            report["server_speech_route"] = True

    await ws.send(json.dumps({"type": "listen", "state": "start", "mode": "auto"}))
    decoder = opuslib_next.Decoder(24000, 1)

    async def send_audio():
        for packet in packets:
            await ws.send(packet)
            report["uploaded_packets"] += 1
            await asyncio.sleep(pace_seconds)

    sender = asyncio.create_task(send_audio())
    deadline = time.monotonic() + wait_seconds
    reminder_sentence = False
    try:
        while time.monotonic() < deadline:
            if sender.done():
                sender.result()  # Surface sender failure; never report a successful receive-only test.
            try:
                raw = await asyncio.wait_for(ws.recv(), deadline - time.monotonic())
            except asyncio.TimeoutError:
                break
            except ConnectionClosed as error:
                report["close_code"] = error.rcvd.code if error.rcvd is not None else 1006
                break
            if isinstance(raw, bytes):
                if len(raw) > 16384:
                    raise ProbeError("oversized_audio_packet")
                pcm = decoder.decode(raw, 2880)
                report["decoded_packets"] += 1
                report["decoded_samples"] += len(pcm) // 2
                if reminder_sentence:
                    report["reminder_audio_packets"] += 1
                continue
            message = json.loads(raw)
            if message.get("type") in {"error", "need_bind"}:
                raise ProbeError("runtime_rejected")
            if message.get("type") == "stt":
                report["stt_events"] += 1
            if message.get("type") == "tts":
                state = message.get("state")
                if state in {"start", "stop"}:
                    report["tts_starts" if state == "start" else "tts_stops"] += 1
                    reminder_sentence = False
                elif state == "sentence_start":
                    reminder_sentence = str(message.get("text", "")).startswith("日程提醒：")
                    if reminder_sentence:
                        report["reminder_text_events"] += 1
    finally:
        sender.cancel()
        await asyncio.gather(sender, return_exceptions=True)
    return report


async def run_probe(args, packets, token):
    api = secure_url(args.api, "https")
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=15)) as client:
        async with client.post(api + "/mobile/voice-session", allow_redirects=False,
                               headers={"Authorization": "Bearer " + token, "X-Workspace-Id": args.workspace_id},
                               json={"agentId": args.agent_id, "installationId": args.installation_id}) as response:
            if response.status != 200 or response.content_length and response.content_length > 16384:
                raise ProbeError("ticket_http_failed")
            try:
                raw = await response.content.readexactly(16385)
            except asyncio.IncompleteReadError as complete:
                raw = complete.partial
            if len(raw) > 16384:
                raise ProbeError("ticket_response_too_large")
            body = json.loads(raw)
            if body.get("code") != 0:
                raise ProbeError("ticket_denied")
    ticket = validate_ticket(body.get("data"), args.ws_host or urlsplit(api).hostname)
    async with connect(ticket["websocketUrl"], additional_headers={
            "Authorization": "Bearer " + ticket["token"], "Device-Id": ticket["deviceId"],
            "Client-Id": ticket["clientId"], "Protocol-Version": "1"},
            open_timeout=15, close_timeout=5, max_size=32768, max_queue=32) as ws:
        return await exchange(ws, packets, args.wait_seconds)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true", help="Explicitly allow real provisioning/model/tool calls")
    parser.add_argument("--api", help="HTTPS management base URL ending in /xiaozhi")
    parser.add_argument("--ws-host", help="Explicit trusted WSS hostname if different from management hostname")
    parser.add_argument("--agent-id")
    parser.add_argument("--workspace-id")
    parser.add_argument("--installation-id", help="Stable test UUID; reuse it to avoid creating many devices")
    parser.add_argument("--wav", type=Path, help="Dedicated test utterance: mono 16kHz PCM16 WAV, <=30s")
    parser.add_argument("--wait-seconds", type=int, default=90)
    parser.add_argument("--expect-reminder", action="store_true", help="Fail unless a reminder text event is also observed")
    args = parser.parse_args(argv)
    if not args.execute:
        print(json.dumps({"mode": "dry_run", "network_requests": 0,
                          "note": "Use --execute only with a dedicated test account and authorized model budget."}))
        return 0
    try:
        if not all((args.api, args.agent_id, args.workspace_id, args.installation_id, args.wav)):
            raise ProbeError("missing_arguments")
        if not 35 <= args.wait_seconds <= 180:
            raise ProbeError("wait_out_of_bounds_35_to_180")
        if str(uuid.UUID(args.installation_id)) != args.installation_id.lower():
            raise ProbeError("invalid_installation_uuid")
        secure_url(args.api, "https")
        token = os.environ.get("MOBILE_TEST_TOKEN", "")
        if not token or len(token) > 8192 or "\n" in token or "\r" in token:
            raise ProbeError("missing_or_invalid_test_token")
        packets = load_audio(args.wav)
        report = asyncio.run(run_probe(args, packets, token))
        report["mode"] = "live_protocol_probe_not_acoustic_test"
        report["passed"] = bool(report["uploaded_packets"] == len(packets) and report["stt_events"]
                                and report["decoded_packets"] and report["tts_stops"]
                                and report.get("close_code", 1000) == 1000
                                and (not args.expect_reminder or report["reminder_audio_packets"]))
        print(json.dumps(report))
        return 0 if report["passed"] else 2
    except Exception as error:
        # Upstream exceptions can contain URL/query/header data. Only fixed codes/types leave this boundary.
        print(json.dumps({"passed": False, "error": str(error) if isinstance(error, ProbeError) else type(error).__name__}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
