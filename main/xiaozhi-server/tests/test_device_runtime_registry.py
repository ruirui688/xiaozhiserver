from types import SimpleNamespace

from core.device_runtime_registry import (
    get_connection,
    register_connection,
    unregister_connection,
)


def make_conn(device_id, client_id=None):
    headers = {"device-id": device_id}
    if client_id:
        headers["client-id"] = client_id
    return SimpleNamespace(device_id=device_id, headers=headers)


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


def test_runtime_registry_skips_manager_debug_connections():
    conn = make_conn("3c:dc:75:fe:26:c0", "manager-debug-abc")

    register_connection(conn)
    try:
        assert get_connection("3c:dc:75:fe:26:c0") is None
    finally:
        unregister_connection(conn)
