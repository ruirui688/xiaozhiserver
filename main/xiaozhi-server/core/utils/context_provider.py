"""Connection-local, expiring snapshots of untrusted external context."""

import hashlib
import html
import ipaddress
import json
import socket
import threading
import time
from datetime import datetime, timezone
from typing import Dict, Any
from urllib.parse import urlsplit

import httpx

from config.logger import setup_logging
from core.utils.prompt_budget import compact_dynamic_context

TAG = __name__
DEFAULT_TTL_SECONDS = 300
RETRY_SECONDS = 5


def validate_context_url(url: str) -> bool:
    """Only allow public HTTP(S) targets; never follow an unchecked redirect."""
    try:
        parsed = urlsplit(url)
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname
                or parsed.username is not None or parsed.password is not None
                or parsed.fragment):
            return False
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        addresses = socket.getaddrinfo(parsed.hostname, port, type=socket.SOCK_STREAM)
        return bool(addresses) and all(ipaddress.ip_address(item[4][0]).is_global for item in addresses)
    except (ValueError, OSError, TypeError):
        return False


def _ttl(provider):
    try:
        return max(5, min(3600, int(provider.get("ttl_seconds", DEFAULT_TTL_SECONDS))))
    except (TypeError, ValueError, OverflowError):
        return DEFAULT_TTL_SECONDS


def _timestamp(value):
    return datetime.fromtimestamp(value, timezone.utc).isoformat(timespec="seconds")


class ContextDataProvider:
    """Fetch once per TTL; read snapshots without putting HTTP on the reply path."""

    def __init__(self, config: Dict[str, Any], logger=None):
        self.config = config
        self.logger = logger or setup_logging()
        self.context_data = ""
        self._snapshots = {}
        self._retry_at = {}
        self._lock = threading.Lock()
        self._fetch_lock = threading.Lock()
        self._refreshing = False

    def _sources(self, device_id):
        providers = self.config.get("context_providers") or []
        if not isinstance(providers, list):
            return []
        sources = []
        for index, provider in enumerate(providers, 1):
            if not isinstance(provider, dict) or not provider.get("url"):
                continue
            # Key the entire request configuration, without exposing it in prompts/logs.
            identity = json.dumps(provider, sort_keys=True, default=str)
            key = (device_id, index, hashlib.sha256(identity.encode()).hexdigest())
            sources.append((key, index, provider))
        return sources

    def _render(self, sources):
        now = time.monotonic()
        with self._lock:
            current = [self._snapshots[key] for key, _, _ in sources
                       if key in self._snapshots and self._snapshots[key]["expires"] > now]
        return compact_dynamic_context("\n\n".join(item["text"] for item in current))

    def _due(self, key, now):
        snapshot = self._snapshots.get(key)
        return (not snapshot or snapshot["expires"] <= now) and self._retry_at.get(key, 0) <= now

    def get_cached(self, device_id: str, *, refresh=False) -> str:
        """Return only fresh data, optionally start one bounded background refresh."""
        sources = self._sources(device_id)
        if not sources:
            return ""
        if refresh:
            with self._lock:
                start = not self._refreshing and any(self._due(key, time.monotonic()) for key, _, _ in sources)
                if start:
                    self._refreshing = True
            if start:
                worker = threading.Thread(target=self._refresh_background, args=(device_id,),
                                          name="prompt-context-refresh", daemon=True)
                try:
                    worker.start()
                except Exception:
                    with self._lock:
                        self._refreshing = False
                    raise
        return self._render(sources)

    def _refresh_background(self, device_id):
        try:
            self.fetch_all(device_id)
        finally:
            with self._lock:
                self._refreshing = False

    def fetch_all(self, device_id: str) -> str:
        """Blocking prewarm, used off the reply path; obey the same TTL/retry state."""
        sources = self._sources(device_id)
        with self._fetch_lock:
            for key, index, provider in sources:
                with self._lock:
                    if not self._due(key, time.monotonic()):
                        continue
                    self._retry_at[key] = time.monotonic() + RETRY_SECONDS
                try:
                    url = provider["url"]
                    if not validate_context_url(url):
                        raise ValueError("invalid_context_target")
                    headers = provider.get("headers")
                    headers = dict(headers) if isinstance(headers, dict) else {}
                    headers["device-id"] = device_id
                    response = httpx.get(url, headers=headers, timeout=3, follow_redirects=False,
                                         trust_env=False)
                    if response.status_code != 200:
                        raise ValueError("context_http_error")
                    result = response.json()
                    if not isinstance(result, dict) or result.get("code") != 0:
                        raise ValueError("context_response_error")
                    text = self._format_data(index, provider, result.get("data"))
                    with self._lock:
                        self._snapshots[key] = {"text": text, "expires": time.monotonic() + _ttl(provider)}
                        self._retry_at.pop(key, None)
                except Exception as error:
                    with self._lock:
                        self._retry_at[key] = time.monotonic() + RETRY_SECONDS
                    self.logger.bind(tag=TAG).warning(
                        "上下文接口 #{} 刷新失败: errorType={}", index, type(error).__name__
                    )
        self.context_data = self._render(sources)
        return self.context_data

    @staticmethod
    def _format_data(index, provider, data):
        safe = lambda value: html.escape(str(value), quote=False)
        if isinstance(data, dict):
            lines = [f"- {safe(key)}：{safe(value)}" for key, value in data.items()]
        elif isinstance(data, list):
            lines = [f"- {safe(item)}" for item in data]
        else:
            lines = [f"- {safe(data)}"]
        fetched_at = time.time()
        ttl = _ttl(provider)
        # Use a configured display name or stable index, never a URL/header/secret.
        source = safe(str(provider.get("name") or f"context_provider_{index}")[:80])
        metadata = (f"来源：{source}；采集时间：{_timestamp(fetched_at)}；"
                    f"有效期：{ttl}秒；到期时间：{_timestamp(fetched_at + ttl)}")
        return metadata + "\n" + compact_dynamic_context("\n".join(lines))
