import ipaddress
import socket
from urllib.parse import urlparse


MCP_ENDPOINT_SCHEMES = frozenset({"ws", "wss"})
MCP_ENDPOINT_PATH_MARKER = "/mcp/"
MCP_ENDPOINT_FORBIDDEN_TERMS = ("key", "call")


def _is_public_ip(address: str) -> bool:
    try:
        return ipaddress.ip_address(address).is_global
    except ValueError:
        return False


def _is_valid_hostname(hostname: str) -> bool:
    if not hostname or len(hostname) > 253:
        return False

    try:
        ascii_hostname = hostname.rstrip(".").encode("idna").decode("ascii")
    except UnicodeError:
        return False

    if not ascii_hostname:
        return False

    for label in ascii_hostname.split("."):
        if (
            not label
            or len(label) > 63
            or label.startswith("-")
            or label.endswith("-")
            or not all(char.isalnum() or char == "-" for char in label)
        ):
            return False
    return True


def _hostname_resolves_to_public_ips(hostname: str, port: int) -> bool:
    try:
        addr_info = socket.getaddrinfo(
            hostname,
            port,
            family=socket.AF_UNSPEC,
            type=socket.SOCK_STREAM,
        )
    except (socket.gaierror, OSError, UnicodeError):
        return False

    addresses = {item[4][0] for item in addr_info if item[4]}
    return bool(addresses) and all(_is_public_ip(address) for address in addresses)


def validate_mcp_endpoint(mcp_endpoint: str) -> bool:
    """Validate an MCP WebSocket endpoint and reject non-public targets."""
    if not isinstance(mcp_endpoint, str) or not mcp_endpoint:
        return False
    if mcp_endpoint != mcp_endpoint.strip():
        return False

    try:
        parsed = urlparse(mcp_endpoint)
        port = parsed.port
    except ValueError:
        return False

    if parsed.scheme.lower() not in MCP_ENDPOINT_SCHEMES:
        return False
    if not parsed.hostname or parsed.username is not None or parsed.password is not None:
        return False
    if parsed.fragment:
        return False

    endpoint_lower = mcp_endpoint.lower()
    if any(term in endpoint_lower for term in MCP_ENDPOINT_FORBIDDEN_TERMS):
        return False
    if MCP_ENDPOINT_PATH_MARKER not in parsed.path:
        return False

    hostname = parsed.hostname
    try:
        address = ipaddress.ip_address(hostname)
    except ValueError:
        if not _is_valid_hostname(hostname):
            return False
        resolved_port = port or (443 if parsed.scheme.lower() == "wss" else 80)
        return _hostname_resolves_to_public_ips(hostname, resolved_port)

    return address.is_global
