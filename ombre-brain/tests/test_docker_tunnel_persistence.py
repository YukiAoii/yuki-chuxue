from pathlib import Path

import pytest
import yaml

from web import tunnel


ROOT = Path(__file__).resolve().parents[1]
EDGE_DEFAULT = (
    "${TUNNEL_EDGE-region1.v2.argotunnel.com:7844,"
    "region2.v2.argotunnel.com:7844}"
)
TRUSTED_PROXY_DEFAULT = (
    "${OMBRE_TRUSTED_PROXY_CIDRS:-127.0.0.0/8,::1/128}"
)
BIND_ADDRESS_DEFAULT = "${OMBRE_BIND_ADDRESS:-127.0.0.1}"
INSECURE_MCP_DEFAULT = "${OMBRE_ALLOW_INSECURE_MCP:-false}"
MCP_AUTH_MODE_DEFAULT = "${OMBRE_MCP_AUTH_MODE:-}"
MCP_TOKEN_DEFAULT = "${OMBRE_MCP_TOKEN:-}"
HOST_PORT_DEFAULT = "${OMBRE_HOST_PORT:-18001}"


def _compose(name: str) -> dict:
    with (ROOT / "deploy" / name).open("r", encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def _environment(service: dict) -> dict[str, str]:
    raw = service["environment"]
    if isinstance(raw, dict):
        return raw
    result = {}
    for entry in raw:
        key, _, value = entry.partition("=")
        result[key] = value
    return result


@pytest.mark.parametrize(
    ("compose_name", "service_name", "source"),
    [
        ("docker-compose.yml", "ombre-brain", "${OMBRE_HOST_VAULT_DIR:-../buckets}"),
        ("docker-compose.user.yml", "ombre-brain", "${OMBRE_HOST_VAULT_DIR:-./buckets}"),
    ],
)
def test_single_instance_compose_has_dns_fallback_and_persistent_bind(
    compose_name: str,
    service_name: str,
    source: str,
):
    service = _compose(compose_name)["services"][service_name]
    environment = _environment(service)

    assert environment["TUNNEL_EDGE"] == EDGE_DEFAULT
    assert environment["TUNNEL_TRANSPORT_PROTOCOL"] == "${TUNNEL_TRANSPORT_PROTOCOL:-http2}"
    assert environment["OMBRE_TRUSTED_PROXY_CIDRS"] == TRUSTED_PROXY_DEFAULT
    assert environment["OMBRE_BIND_ADDRESS"] == BIND_ADDRESS_DEFAULT
    assert environment["OMBRE_ALLOW_INSECURE_MCP"] == INSECURE_MCP_DEFAULT
    assert environment["OMBRE_MCP_AUTH_MODE"] == MCP_AUTH_MODE_DEFAULT
    assert environment["OMBRE_MCP_TOKEN"] == MCP_TOKEN_DEFAULT
    assert environment["OMBRE_HOST_VAULT_DIR"] == "${OMBRE_HOST_VAULT_DIR:-}"
    assert service["ports"] == [f"{BIND_ADDRESS_DEFAULT}:{HOST_PORT_DEFAULT}:8000"]
    assert service["volumes"] == [
        {"type": "bind", "source": source, "target": "/app/buckets"}
    ]


def test_multi_instance_compose_keeps_vaults_isolated_and_uses_dns_fallback():
    services = _compose("docker-compose.multi.yml")["services"]

    for owner, source_var, token_var in (
        ("ming", "OMBRE_MING_VAULT_DIR", "OMBRE_MING_MCP_TOKEN"),
        ("hong", "OMBRE_HONG_VAULT_DIR", "OMBRE_HONG_MCP_TOKEN"),
    ):
        service = services[owner]
        environment = _environment(service)
        assert environment["TUNNEL_EDGE"] == EDGE_DEFAULT
        assert environment["TUNNEL_TRANSPORT_PROTOCOL"] == "${TUNNEL_TRANSPORT_PROTOCOL:-http2}"
        assert environment["OMBRE_TRUSTED_PROXY_CIDRS"] == TRUSTED_PROXY_DEFAULT
        assert environment["OMBRE_BIND_ADDRESS"] == BIND_ADDRESS_DEFAULT
        assert environment["OMBRE_ALLOW_INSECURE_MCP"] == INSECURE_MCP_DEFAULT
        assert environment["OMBRE_MCP_AUTH_MODE"] == MCP_AUTH_MODE_DEFAULT
        assert environment["OMBRE_MCP_TOKEN"] == f"${{{token_var}:-}}"
        assert environment["OMBRE_HOST_VAULT_DIR"] == f"${{{source_var}:-}}"
        port = "18001" if owner == "ming" else "18002"
        assert service["ports"] == [f"{BIND_ADDRESS_DEFAULT}:{port}:8000"]
        assert service["volumes"] == [{
            "type": "bind",
            "source": f"${{{source_var}:-./buckets-{owner}}}",
            "target": "/app/buckets",
        }]


def test_testing_compose_declares_loopback_boundary_for_no_auth_mcp():
    service = _compose("docker-compose.testing.yml")["services"]["ombre-brain"]
    environment = _environment(service)

    assert environment["OMBRE_MCP_REQUIRE_AUTH"] == "false"
    assert environment["OMBRE_BIND_ADDRESS"] == "127.0.0.1"


def test_tunnel_token_config_lives_inside_persistent_buckets(monkeypatch, tmp_path):
    monkeypatch.setattr(tunnel.sh, "config", {"buckets_dir": str(tmp_path)})
    payload = {"token": "test-token", "auto_start": True}

    tunnel._save_tunnel_config(payload)

    config_path = tmp_path / ".tunnel_config.json"
    assert config_path.is_file()
    assert tunnel._load_tunnel_config() == payload


def test_env_example_uses_current_container_mount_and_tunnel_endpoints():
    text = (ROOT / ".env.example").read_text(encoding="utf-8")

    assert "/app/buckets" in text
    assert "region1.v2.argotunnel.com:7844" in text
    assert "region2.v2.argotunnel.com:7844" in text
    assert "TUNNEL_TRANSPORT_PROTOCOL=http2" in text
    assert "OMBRE_TRUSTED_PROXY_CIDRS" in text
    assert "OMBRE_BIND_ADDRESS=127.0.0.1" in text
    assert "OMBRE_ALLOW_INSECURE_MCP=false" in text
