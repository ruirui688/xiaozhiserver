"""Runtime registry for active device connections."""

from typing import Dict, List, Optional, Set, TYPE_CHECKING

from config.logger import setup_logging

if TYPE_CHECKING:
    from core.connection import ConnectionHandler


TAG = __name__
logger = setup_logging()
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


def _client_id(conn: "ConnectionHandler") -> str:
    if not conn.headers:
        return ""
    return conn.headers.get("client-id", "") or ""


def register_connection(conn: "ConnectionHandler") -> None:
    client_id = _client_id(conn)
    conn._runtime_registry_keys = set()
    if not conn.device_id:
        logger.bind(tag=TAG).warning("跳过运行时设备注册: 缺少device-id, client-id={}", client_id)
        return
    keys = _connection_keys(conn)
    for key in keys:
        existing = _connections.get(key)
        if existing is not None and existing is not conn:
            logger.bind(tag=TAG).warning(
                "覆盖已有运行时连接: key={}, old_device_id={}, new_device_id={}",
                key,
                existing.device_id,
                conn.device_id,
            )
        _connections[key] = conn
    conn._runtime_registry_keys = keys
    logger.bind(tag=TAG).info(
        "运行时设备连接已注册: device-id={}, client-id={}, keys={}",
        conn.device_id,
        client_id,
        sorted(keys),
    )


def unregister_connection(conn: "ConnectionHandler") -> None:
    external_tts = getattr(conn, "external_tts_coordinator", None)
    if external_tts is not None:
        external_tts.connection_closed(conn)
    keys = getattr(conn, "_runtime_registry_keys", set())
    if not keys:
        return
    for key in keys:
        if _connections.get(key) is conn:
            _connections.pop(key, None)
    conn._runtime_registry_keys = set()
    if keys:
        logger.bind(tag=TAG).info(
            "运行时设备连接已注销: device-id={}, client-id={}, keys={}",
            conn.device_id,
            _client_id(conn),
            sorted(keys),
        )


def get_connection(device_id: str) -> Optional["ConnectionHandler"]:
    for key in _alias_keys(device_id):
        conn = _connections.get(key)
        if conn:
            return conn
    return None


def get_connections_for_agent(agent_id: str) -> List["ConnectionHandler"]:
    normalized_agent_id = str(agent_id or "").strip()
    if not normalized_agent_id:
        return []
    result = []
    seen = set()
    for conn in _connections.values():
        identity = id(conn)
        if identity in seen:
            continue
        seen.add(identity)
        config = getattr(conn, "config", {}) or {}
        if str(config.get("agent_id") or "").strip() == normalized_agent_id:
            result.append(conn)
    return result


def registered_keys() -> Set[str]:
    return set(_connections.keys())
