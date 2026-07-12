import socket
from unittest.mock import patch

import pytest

from core.utils.endpoint_security import validate_mcp_endpoint


def _dns_result(*addresses):
    return [
        (
            socket.AF_INET6 if ":" in address else socket.AF_INET,
            socket.SOCK_STREAM,
            socket.IPPROTO_TCP,
            "",
            (address, 443, 0, 0) if ":" in address else (address, 443),
        )
        for address in addresses
    ]


@pytest.mark.parametrize(
    "endpoint",
    [
        "ws://10.0.0.1/mcp/test",
        "ws://172.16.0.1/mcp/test",
        "ws://192.168.1.1/mcp/test",
        "ws://127.0.0.1/mcp/test",
        "ws://169.254.169.254/mcp/test",
        "ws://[::1]/mcp/test",
        "ws://[fc00::1]/mcp/test",
        "ws://[fe80::1]/mcp/test",
    ],
)
def test_rejects_private_loopback_and_link_local_ip_literals(endpoint):
    assert validate_mcp_endpoint(endpoint) is False


@pytest.mark.parametrize(
    "endpoint",
    [
        "",
        " http://example.com/mcp/test",
        "http://example.com/mcp/test",
        "ws-extra://example.com/mcp/test",
        "ws:///mcp/test",
        "ws://user:password@example.com/mcp/test",
        "ws://example.com:invalid/mcp/test",
        "ws://example.com/tools/test",
        "ws://example.com/mcp/call",
        "ws://example.com/mcp/test?apiKey=secret",
        "ws://bad_host.example/mcp/test",
        "ws://example.com/mcp/test#fragment",
    ],
)
def test_rejects_invalid_endpoint_formats_without_dns(endpoint):
    with patch("core.utils.endpoint_security.socket.getaddrinfo") as getaddrinfo:
        assert validate_mcp_endpoint(endpoint) is False
        getaddrinfo.assert_not_called()


def test_rejects_hostname_resolving_to_private_address():
    with patch(
        "core.utils.endpoint_security.socket.getaddrinfo",
        return_value=_dns_result("169.254.169.254"),
    ):
        assert validate_mcp_endpoint("wss://metadata.example/mcp/test") is False


def test_rejects_hostname_if_any_resolved_address_is_not_public():
    with patch(
        "core.utils.endpoint_security.socket.getaddrinfo",
        return_value=_dns_result("93.184.216.34", "127.0.0.1"),
    ):
        assert validate_mcp_endpoint("wss://mixed.example/mcp/test") is False


def test_rejects_hostname_when_dns_resolution_fails():
    with patch(
        "core.utils.endpoint_security.socket.getaddrinfo",
        side_effect=socket.gaierror("not found"),
    ):
        assert validate_mcp_endpoint("wss://missing.example/mcp/test") is False


def test_accepts_public_ip_literal_without_dns():
    with patch("core.utils.endpoint_security.socket.getaddrinfo") as getaddrinfo:
        assert validate_mcp_endpoint("wss://8.8.8.8/mcp/test") is True
        getaddrinfo.assert_not_called()


def test_accepts_hostname_when_all_resolved_addresses_are_public():
    with patch(
        "core.utils.endpoint_security.socket.getaddrinfo",
        return_value=_dns_result(
            "93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946"
        ),
    ) as getaddrinfo:
        assert validate_mcp_endpoint("wss://public.example/mcp/test") is True
        getaddrinfo.assert_called_once_with(
            "public.example",
            443,
            family=socket.AF_UNSPEC,
            type=socket.SOCK_STREAM,
        )


def test_util_keeps_legacy_import_path():
    from core.utils.util import validate_mcp_endpoint as legacy_validator

    assert legacy_validator is validate_mcp_endpoint
