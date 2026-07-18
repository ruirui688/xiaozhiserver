from types import SimpleNamespace

from config import settings

settings.config_file_valid = True

from core.device_runtime_registry import (
    get_connection,
    get_connections_for_agent,
    register_connection,
    unregister_connection,
)


def make_conn(device_id, client_id=None, agent_id=None):
    headers = {"device-id": device_id}
    if client_id:
        headers["client-id"] = client_id
    return SimpleNamespace(
        device_id=device_id,
        headers=headers,
        config={"agent_id": agent_id} if agent_id else {},
    )


def test_runtime_registry_matches_mac_aliases():
    conn = make_conn(
        "3c:dc:75:fe:26:c0",
        "GID_default@@@3c_dc_75_fe_26_c0@@@3c_dc_75_fe_26_c0",
    )

    register_connection(conn)
    try:
        assert get_connection("3c:dc:75:fe:26:c0") is conn
        assert get_connection("3c_dc_75_fe_26_c0") is conn
        assert get_connection("GID_default@@@3c_dc_75_fe_26_c0@@@3c_dc_75_fe_26_c0") is conn
    finally:
        unregister_connection(conn)


def test_stale_connection_unregister_does_not_remove_replacement():
    old_conn = make_conn("3c:dc:75:fe:26:c0", "client-1")
    new_conn = make_conn("3c:dc:75:fe:26:c0", "client-1")

    register_connection(old_conn)
    register_connection(new_conn)
    try:
        unregister_connection(old_conn)

        assert get_connection("3c:dc:75:fe:26:c0") is new_conn
        assert get_connection("3c_dc_75_fe_26_c0") is new_conn
        assert get_connection("client-1") is new_conn
    finally:
        unregister_connection(new_conn)


def test_runtime_registry_lists_unique_connections_for_agent():
    first = make_conn("AA:BB:CC:DD:EE:01", "client-1", "agent-a")
    second = make_conn("AA:BB:CC:DD:EE:02", "client-2", "agent-a")
    other = make_conn("AA:BB:CC:DD:EE:03", "client-3", "agent-b")

    for conn in (first, second, other):
        register_connection(conn)
    try:
        assert get_connections_for_agent("agent-a") == [first, second]
    finally:
        for conn in (first, second, other):
            unregister_connection(conn)
