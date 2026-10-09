"""End-to-end regressions for OAuth grants used by the MCP auth middleware."""

from __future__ import annotations

import base64
import hashlib
import json
import time
import urllib.parse

import pytest

from ombrebrain.security.public_origin import (
    configured_public_origin,
    normalize_http_resource,
    normalize_public_origin,
)
from server_app import HTTPRuntimeSettings, MCPAuthMiddleware
from web import oauth as oauth_mod
from web.request_limits import is_sse_endpoint_path


class FakeMCP:
    def __init__(self):
        self.routes = {}

    def custom_route(self, path, methods):
        def decorator(fn):
            for method in methods:
                self.routes[(method, path)] = fn
            return fn

        return decorator


class SyntheticURL:
    def __init__(self, scheme="http", netloc="internal.local:8000"):
        self.scheme = scheme
        self.netloc = netloc


class SyntheticRequest:
    def __init__(
        self,
        body=None,
        *,
        method="POST",
        query_params=None,
        path_params=None,
        headers=None,
        scheme="http",
        netloc="internal.local:8000",
        client_host="198.51.100.20",
    ):
        self._body = {} if body is None else body
        self.method = method
        self.query_params = query_params or {}
        self.path_params = path_params or {}
        self.headers = headers or {
            "content-type": "application/json",
            "host": netloc,
        }
        self.url = SyntheticURL(scheme, netloc)
        self.client = type("Client", (), {"host": client_host})()

    async def json(self):
        return self._body

    async def form(self):
        return self._body


class RecordingASGIApp:
    def __init__(self):
        self.scopes = []

    async def __call__(self, scope, _receive, send):
        self.scopes.append(scope)
        await send(
            {
                "type": "http.response.start",
                "status": 204,
                "headers": [],
            }
        )
        await send({"type": "http.response.body", "body": b""})


async def _empty_receive():
    return {"type": "http.request", "body": b"", "more_body": False}


def _collect_into(messages):
    async def send(message):
        messages.append(message)

    return send


def _payload(response):
    return json.loads(response.body)


@pytest.fixture
def public_oauth(monkeypatch, tmp_path):
    buckets = tmp_path / "buckets"
    buckets.mkdir()
    config = {
        "buckets_dir": str(buckets),
        "mcp_require_auth": True,
        "mcp_auth_mode": "oauth",
        # Exercise both complete-connector input and default-port removal.
        "deployment": {"public_url": "HTTPS://Public.Example:443/mcp/"},
    }
    monkeypatch.setattr(oauth_mod.sh, "config", config)
    monkeypatch.setenv("OMBRE_DASHBOARD_PASSWORD", "synthetic-password")
    monkeypatch.setenv("OMBRE_TRUSTED_PROXY_CIDRS", "127.0.0.0/8,::1/128")

    oauth_mod._oauth_clients.clear()
    oauth_mod._oauth_codes.clear()
    oauth_mod._mcp_tokens.clear()
    oauth_mod._mcp_token_resources.clear()
    oauth_mod._mcp_refresh_tokens.clear()
    oauth_mod._oauth_registration_source_attempts.clear()
    oauth_mod._oauth_registration_global_attempts.clear()
    oauth_mod.sh._login_failures.clear()
    oauth_mod.sh._login_locked_until.clear()
    oauth_mod.sh._login_source_lru.clear()
    oauth_mod.sh._login_global_attempts.clear()

    mcp = FakeMCP()
    oauth_mod.register(mcp)
    return mcp.routes, config, HTTPRuntimeSettings.from_config(config)


async def _issue_complete_grant(routes):
    callback = "https://client.example/callback"
    register_response = await routes[("POST", "/oauth/register")](
        SyntheticRequest(
            {
                "redirect_uris": [callback],
                "client_name": "Synthetic MCP Client",
            }
        )
    )
    assert register_response.status_code == 201
    client_id = _payload(register_response)["client_id"]

    verifier = "v" * 64
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode()).digest()
    ).rstrip(b"=").decode()
    resource = "https://public.example/mcp"
    authorize_response = await routes[("POST", "/oauth/authorize")](
        SyntheticRequest(
            {
                "password": "synthetic-password",
                "client_id": client_id,
                "redirect_uri": callback,
                "state": "synthetic-state",
                "scope": "mcp",
                "resource": resource,
                "code_challenge": challenge,
            }
        )
    )
    assert authorize_response.status_code == 302
    redirect_query = urllib.parse.parse_qs(
        urllib.parse.urlsplit(authorize_response.headers["location"]).query
    )
    code = redirect_query["code"][0]

    token_response = await routes[("POST", "/oauth/token")](
        SyntheticRequest(
            {
                "grant_type": "authorization_code",
                "code": code,
                "code_verifier": verifier,
                "client_id": client_id,
                "redirect_uri": callback,
                "resource": "https://PUBLIC.example:443/mcp/",
            }
        )
    )
    assert token_response.status_code == 200
    return client_id, resource, _payload(token_response)


@pytest.mark.asyncio
async def test_token_200_is_accepted_by_mcp_behind_untrusted_internal_proxy(
    public_oauth,
):
    """Configured public origin prevents token-200 / internal-resource-401 loops."""

    routes, _config, settings = public_oauth
    discovery = await routes[
        ("GET", "/.well-known/oauth-protected-resource/{resource_path:path}")
    ](
        SyntheticRequest(
            method="GET",
            path_params={"resource_path": "mcp"},
            # These forwarding headers are intentionally untrusted.  The
            # configured public URL, not attacker-controlled headers, wins.
            headers={
                "host": "internal.local:8000",
                "x-forwarded-proto": "http",
                "x-forwarded-host": "internal.local:8000",
            },
        )
    )
    assert _payload(discovery)["resource"] == "https://public.example/mcp"

    _client_id, resource, token = await _issue_complete_grant(routes)
    assert oauth_mod._mcp_token_resources[token["access_token"]] == resource

    downstream = RecordingASGIApp()
    middleware = MCPAuthMiddleware(
        downstream,
        auth_required=True,
        token_validator=oauth_mod._is_valid_mcp_token,
        public_origin=settings.public_origin,
    )
    messages = []
    scope = {
        "type": "http",
        "method": "POST",
        "scheme": "http",
        "path": "/mcp",
        "client": ("198.51.100.20", 54321),
        "headers": [
            (b"host", b"internal.local:8000"),
            # RFC auth schemes are case-insensitive; some clients use lower or
            # mixed case despite token_type being returned as Bearer.
            (
                b"authorization",
                f"bEaReR   {token['access_token']}".encode("ascii"),
            ),
        ],
    }

    await middleware(scope, _empty_receive, _collect_into(messages))

    assert downstream.scopes == [scope]
    assert messages[0]["status"] == 204


@pytest.mark.asyncio
async def test_refresh_token_and_both_http_transports_keep_public_resource(
    public_oauth,
):
    routes, _config, settings = public_oauth
    client_id, resource, token = await _issue_complete_grant(routes)
    refresh_response = await routes[("POST", "/oauth/token")](
        SyntheticRequest(
            {
                "grant_type": "refresh_token",
                "refresh_token": token["refresh_token"],
                "client_id": client_id,
                "resource": resource,
            }
        )
    )
    assert refresh_response.status_code == 200
    refreshed = _payload(refresh_response)
    assert oauth_mod._mcp_token_resources[refreshed["access_token"]] == resource

    for path, matcher in (("/mcp", None), ("/sse", is_sse_endpoint_path)):
        downstream = RecordingASGIApp()
        kwargs = {} if matcher is None else {"path_matcher": matcher}
        middleware = MCPAuthMiddleware(
            downstream,
            auth_required=True,
            token_validator=oauth_mod._is_valid_mcp_token,
            public_origin=settings.public_origin,
            resource_path="/mcp",
            **kwargs,
        )
        messages = []
        scope = {
            "type": "http",
            "method": "GET" if path == "/sse" else "POST",
            "scheme": "http",
            "path": path,
            "client": ("198.51.100.20", 54321),
            "headers": [
                (b"host", b"proxy.internal:8000"),
                (
                    b"authorization",
                    f"Bearer {refreshed['access_token']}".encode("ascii"),
                ),
            ],
        }
        await middleware(scope, _empty_receive, _collect_into(messages))
        assert downstream.scopes == [scope]
        assert messages[0]["status"] == 204


@pytest.mark.asyncio
async def test_oauth_and_middleware_keep_their_shared_startup_snapshot(
    public_oauth,
):
    routes, config, settings = public_oauth
    assert settings.public_origin == "https://public.example"

    # A Dashboard save updates desired config but is documented as requiring a
    # restart.  Existing routes and middleware must not split during the gap.
    config["deployment"]["public_url"] = "https://new.example"
    discovery = await routes[("GET", "/.well-known/oauth-authorization-server")](
        SyntheticRequest(method="GET")
    )
    assert _payload(discovery)["issuer"] == "https://public.example"
    assert settings.public_origin == "https://public.example"


@pytest.mark.asyncio
async def test_stale_refresh_grant_cannot_return_token_200_then_mcp_401(
    public_oauth,
):
    routes, _config, _settings = public_oauth
    client_id, _resource, token = await _issue_complete_grant(routes)
    oauth_mod._mcp_refresh_tokens[token["refresh_token"]]["resource"] = (
        "https://previous.example/mcp"
    )

    response = await routes[("POST", "/oauth/token")](
        SyntheticRequest(
            {
                "grant_type": "refresh_token",
                "refresh_token": token["refresh_token"],
                "client_id": client_id,
                "resource": "https://public.example/mcp",
            }
        )
    )

    assert response.status_code == 400
    assert _payload(response)["error"] == "invalid_grant"
    assert "reauthorization required" in _payload(response)["error_description"]


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("HTTPS://Example.COM:443/mcp/", "https://example.com"),
        ("http://Example.COM:80", "http://example.com"),
        ("https://[2001:DB8::1]:443/mcp", "https://[2001:db8::1]"),
        ("https://example.com:8443/", "https://example.com:8443"),
        ("https://user@example.com", ""),
        ("https://ob_example", ""),
        ("https://empty..example", ""),
        ("https://" + "a" * 64 + ".example", ""),
        ("https://010.0.0.1", ""),
        ("https://2130706433", ""),
        ("https://0x7f000001", ""),
        ("https://017700000001", ""),
        ("https://0x7f.1", ""),
        ("https://example.com:0/mcp", ""),
        ("https://example.com/other", ""),
        ("https://example.com?next=evil", ""),
        ("https://" + "a" * 2048 + ".example", ""),
    ],
)
def test_public_origin_normalization(raw, expected):
    assert normalize_public_origin(raw) == expected


def test_resource_normalization_equates_default_ports_and_rejects_queries():
    assert normalize_http_resource("https://EXAMPLE.com:443/mcp/") == (
        "https://example.com/mcp"
    )
    assert normalize_http_resource("https://example.com/mcp") == (
        "https://example.com/mcp"
    )
    assert normalize_http_resource("https://example.com/mcp?aud=other") == ""
    assert configured_public_origin(
        {"deployment": {"public_url": "https://example.com/mcp"}}
    ) == "https://example.com"


def test_exact_resource_validation_handles_default_port_equivalence(monkeypatch):
    oauth_mod._mcp_tokens.clear()
    oauth_mod._mcp_token_resources.clear()
    oauth_mod._mcp_tokens["synthetic-access"] = time.time() + 60
    oauth_mod._mcp_token_resources["synthetic-access"] = (
        "https://public.example:443/mcp"
    )

    assert oauth_mod._is_valid_mcp_token(
        "synthetic-access", resource="https://public.example/mcp"
    )
