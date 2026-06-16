"""Runtime registry for active device connections."""

from typing import Dict, Optional, Set, TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler


_connections: Dict[str, "ConnectionHandler"] = {}


def _normalize_key(value: str) -> str:
    return value.strip().lower()


def _alias_keys(value: str) -> Set[str]:
    if not value:
        return set()

    normalized = _normalize_key(value)
    keys = {normalized}
    keys.add(normalized.replace(":", "_"))

    if "@@@" in normalized:
        keys.update(part for part in normalized.split("@@@") if part)

    expanded_keys = set(keys)
    for key in keys:
        expanded_keys.add(key.replace("_", ":"))
    return expanded_keys


def _connection_keys(conn: "ConnectionHandler") -> Set[str]:
    keys = set()
    keys.update(_alias_keys(conn.device_id or ""))
    if conn.headers:
        keys.update(_alias_keys(conn.headers.get("device-id", "") or ""))
        keys.update(_alias_keys(conn.headers.get("client-id", "") or ""))
    return keys


def is_debug_connection(conn: "ConnectionHandler") -> bool:
    client_id = ""
    if conn.headers:
        client_id = conn.headers.get("client-id", "") or ""
    return client_id.startswith("manager-debug-")


def register_connection(conn: "ConnectionHandler") -> None:
    if not conn.device_id or is_debug_connection(conn):
        return
    keys = _connection_keys(conn)
    for key in keys:
        _connections[key] = conn
    conn._runtime_registry_keys = keys


def unregister_connection(conn: "ConnectionHandler") -> None:
    keys = getattr(conn, "_runtime_registry_keys", None) or _connection_keys(conn)
    for key in keys:
        if _connections.get(key) is conn:
            _connections.pop(key, None)
    conn._runtime_registry_keys = set()


def get_connection(device_id: str) -> Optional["ConnectionHandler"]:
    for key in _alias_keys(device_id):
        conn = _connections.get(key)
        if conn:
            return conn
    return None
