"""Independent WebSocket audio-file upload protocol."""
import asyncio, json
MAX_AUDIO_FILE_BYTES = 10 * 1024 * 1024
DEFAULT_TIMEOUT_MS = 5000
async def _finish(conn, reason="complete"):
    upload = getattr(conn, "audio_file_upload", None)
    if not upload or upload.get("finished"): return
    upload["finished"] = True; conn.audio_file_upload = None
    data = bytes(upload["data"])
    if not data or len(data) > MAX_AUDIO_FILE_BYTES: return
    conn.asr_audio.clear(); conn.asr_audio.append(data); conn.client_voice_stop = True
    if conn.asr.interface_type.value == "stream": await conn.asr._send_stop_request()
    else: await conn.asr.handle_voice_stop(conn, [data])
async def _timeout(conn, upload, timeout):
    try:
        await asyncio.sleep(timeout)
        if getattr(conn, "audio_file_upload", None) is upload: await _finish(conn, "timeout")
    except asyncio.CancelledError: pass
async def handle_audio_file_message(conn, message):
    if isinstance(message, str):
        try: payload = json.loads(message)
        except (TypeError, json.JSONDecodeError): return False
        if payload.get("type") != "audio_file": return False
        state = payload.get("state")
        if state == "start":
            if getattr(conn, "audio_file_upload", None) is not None: return True
            frames = payload.get("frames")
            if frames is not None and (isinstance(frames, bool) or not isinstance(frames, int) or frames <= 0): return True
            upload = {"data": bytearray(), "meta": payload, "finished": False}; conn.audio_file_upload = upload
            upload["timeout_task"] = asyncio.create_task(_timeout(conn, upload, int(payload.get("timeout_ms", DEFAULT_TIMEOUT_MS))/1000))
        elif state == "end":
            upload = getattr(conn, "audio_file_upload", None)
            if upload and upload.get("timeout_task"): upload["timeout_task"].cancel()
            await _finish(conn, "complete")
        return True
    upload = getattr(conn, "audio_file_upload", None)
    if upload is not None:
        if len(upload["data"]) + len(message) <= MAX_AUDIO_FILE_BYTES: upload["data"].extend(message)
        else: upload["data"].clear(); await _finish(conn, "invalid")
        return True
    return False
