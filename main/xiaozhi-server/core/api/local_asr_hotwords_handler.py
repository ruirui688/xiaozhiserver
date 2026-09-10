"""Authenticated, server-local ASR hotword configuration and change feed."""

import asyncio
import hmac
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from aiohttp import web

from core.api.base_handler import BaseHandler


class LocalAsrHotwordsHandler(BaseHandler):
    MAX_REPLACEMENTS = 500
    MAX_TERM_LENGTH = 128

    def __init__(self, config):
        super().__init__(config)
        default_path = Path(__file__).resolve().parents[2] / "data" / ".local_asr_hotwords.json"
        self.path = Path(config.get("server", {}).get("local_asr_hotwords_path", default_path))
        self._lock = asyncio.Lock()
        self._listeners = set()

    def _authorized(self, request):
        value = request.headers.get("Authorization", "")
        if not value.startswith("Bearer "):
            return False
        token = value[7:]
        secrets = (self.config.get("server", {}).get("auth_key", ""),
                   self.config.get("manager-api", {}).get("secret", ""))
        return bool(token) and any(secret and hmac.compare_digest(token, str(secret)) for secret in secrets)

    @staticmethod
    def _empty():
        return {"version": 0, "updated_at": None, "enabled": True, "replacements": []}

    def _read(self):
        try:
            with self.path.open(encoding="utf-8") as stream:
                value = json.load(stream)
            if not isinstance(value, dict):
                return self._empty()
            result = self._empty()
            result.update(value)
            result["replacements"] = value.get("replacements") if isinstance(value.get("replacements"), list) else []
            return result
        except (FileNotFoundError, json.JSONDecodeError, OSError):
            return self._empty()

    def _response(self, body, status=200, request=None):
        response = web.Response(text=json.dumps(body, ensure_ascii=False), content_type="application/json", status=status)
        self._add_cors_headers(response, request)
        return response

    async def handle_get(self, request):
        if not self._authorized(request):
            return self._response({"success": False, "message": "未授权"}, 401, request)
        return self._response(self._read(), request=request)

    async def handle_version(self, request):
        if not self._authorized(request):
            return self._response({"success": False, "message": "未授权"}, 401, request)
        return self._response({"version": self._read()["version"]}, request=request)

    async def handle_save(self, request):
        if not self._authorized(request):
            return self._response({"success": False, "message": "未授权"}, 401, request)
        try:
            body = await request.json()
        except Exception:
            return self._response({"success": False, "message": "请求体不是有效JSON"}, 400, request)
        replacements = body.get("replacements", []) if isinstance(body, dict) else None
        if not isinstance(replacements, list) or len(replacements) > self.MAX_REPLACEMENTS:
            return self._response({"success": False, "message": "replacements数量无效"}, 400, request)
        normalized, sources, edges = [], set(), {}
        for item in replacements:
            if not isinstance(item, dict):
                return self._response({"success": False, "message": "replacement格式无效"}, 400, request)
            source, target = str(item.get("source") or "").strip(), str(item.get("target") or "").strip()
            if not source or not target or len(source) > self.MAX_TERM_LENGTH or len(target) > self.MAX_TERM_LENGTH:
                return self._response({"success": False, "message": "source/target不能为空且长度不能超过128"}, 400, request)
            if source in sources:
                return self._response({"success": False, "message": "source不能重复"}, 400, request)
            sources.add(source)
            edges[source] = target
            normalized.append({"source": source, "target": target, "enabled": item.get("enabled", True) is not False})
        for start in edges:
            seen = set()
            current = start
            while current in edges:
                if current in seen:
                    return self._response({"success": False, "message": "禁止循环映射"}, 400, request)
                seen.add(current)
                current = edges[current]
        async with self._lock:
            old = self._read()
            result = {"version": int(old.get("version", 0)) + 1,
                      "updated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                      "enabled": body.get("enabled", True) is not False,
                      "replacements": normalized}
            self.path.parent.mkdir(parents=True, exist_ok=True)
            fd, temporary = tempfile.mkstemp(prefix=".local_asr_hotwords.", dir=str(self.path.parent))
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as stream:
                    json.dump(result, stream, ensure_ascii=False, separators=(",", ":"))
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, self.path)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
            for queue in tuple(self._listeners):
                queue.put_nowait(result["version"])
        return self._response(result, request=request)

    async def handle_events(self, request):
        if not self._authorized(request):
            return self._response({"success": False, "message": "未授权"}, 401, request)
        response = web.StreamResponse(status=200, headers={"Content-Type": "text/event-stream", "Cache-Control": "no-cache", "Connection": "keep-alive"})
        await response.prepare(request)
        queue = asyncio.Queue()
        self._listeners.add(queue)
        try:
            while True:
                version = await queue.get()
                await response.write((f"event: local_asr_hotwords_changed\ndata: {json.dumps({'version': version})}\n\n").encode())
        except (asyncio.CancelledError, ConnectionResetError, RuntimeError):
            pass
        finally:
            self._listeners.discard(queue)
        return response

    async def handle_options(self, request):
        response = web.Response(status=204)
        self._add_cors_headers(response, request)
        return response
