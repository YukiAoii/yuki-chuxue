"""Real Docker HTTP regression for public-origin persistence and OAuth binding.

Run only against an isolated disposable service.  The test saves a synthetic
public origin, asks the process to restart, then completes DCR + PKCE and proves
that a token returned with HTTP 200 is accepted by the locally addressed MCP
endpoint even though its OAuth resource uses the configured external origin.
"""

from __future__ import annotations

import base64
import hashlib
import os
import time
from urllib.parse import parse_qs, urlsplit

import httpx
import pytest


BASE_URL = os.environ.get("OMBRE_DOCKER_PUBLIC_OAUTH_URL", "").strip().rstrip("/")
PASSWORD = os.environ.get("OMBRE_DOCKER_PUBLIC_OAUTH_PASSWORD", "").strip()
PUBLIC_ORIGIN = "https://public.example"

pytestmark = pytest.mark.skipif(
    not BASE_URL or not PASSWORD,
    reason="isolated Docker public-OAuth service is not configured",
)


def _wait_healthy(timeout_seconds: float = 30.0) -> None:
    deadline = time.monotonic() + timeout_seconds
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            response = httpx.get(
                f"{BASE_URL}/health", timeout=2.0, trust_env=False
            )
            if response.status_code == 200:
                return
        except (httpx.HTTPError, OSError) as exc:
            last_error = exc
        time.sleep(0.25)
    raise AssertionError(f"Docker service did not become healthy: {last_error}")


def _login(client: httpx.Client) -> None:
    response = client.post("/auth/login", json={"password": PASSWORD})
    assert response.status_code == 200, response.text
    assert response.json()["ok"] is True


def test_public_address_survives_restart_and_token_200_reaches_mcp() -> None:
    _wait_healthy()
    with httpx.Client(base_url=BASE_URL, timeout=30.0, trust_env=False) as client:
        _login(client)
        # Simulate a managed proxy presenting an internal Host to the app while
        # the browser correctly reports that its fetch came from the same public
        # Dashboard origin. This is the exact 2.7.0 false-positive shape.
        browser_headers = {
            "Host": "internal.service:8000",
            "Origin": PUBLIC_ORIGIN,
            "Sec-Fetch-Site": "same-origin",
        }

        edit_probe = client.patch(
            "/api/bucket/csrf-regression-missing/edit",
            headers=browser_headers,
            json={"importance": 8},
        )
        assert edit_probe.status_code == 404, edit_probe.text
        assert "Cross-origin" not in edit_probe.text

        key_save = client.post(
            "/api/env-config",
            headers=browser_headers,
            json={
                "updates": {
                    "OMBRE_COMPRESS_API_KEY": "synthetic-compress-key",
                    "OMBRE_EMBED_API_KEY": "synthetic-embed-key",
                }
            },
        )
        assert key_save.status_code == 200, key_save.text
        assert key_save.json()["ok"] is True
        assert set(key_save.json()["updated"]) >= {
            "OMBRE_COMPRESS_API_KEY",
            "OMBRE_EMBED_API_KEY",
        }

        saved = client.post(
            "/api/config",
            headers=browser_headers,
            json={
                "deployment": {
                    "public_url": "HTTPS://Public.Example:443/mcp/"
                },
                "mcp_require_auth": True,
                "mcp_auth_mode": "oauth",
                "persist": True,
            },
        )
        assert saved.status_code == 200, saved.text
        saved_payload = saved.json()
        assert saved_payload["deployment"]["public_url"] == PUBLIC_ORIGIN
        assert saved_payload["restart_required"] is True

        # Exercise the second writer that used to be unable to save and make
        # sure Dashboard immediately reads its desired values from disk.
        onboarding = client.post(
            "/api/onboarding/apply",
            headers=browser_headers,
            json={
                "profile": "public_secure",
                "options": {"public_url": "public.example/mcp"},
                "confirm": True,
            },
        )
        assert onboarding.status_code == 200, onboarding.text
        desired = client.get("/api/config")
        assert desired.status_code == 200, desired.text
        desired_payload = desired.json()
        assert desired_payload["deployment"]["public_url"] == PUBLIC_ORIGIN
        assert desired_payload["mcp_require_auth"] is True
        assert desired_payload["mcp_auth_mode"] == "oauth"
        assert desired_payload["transport"] == "streamable-http"

        restarting = client.post(
            "/api/restart",
            headers=browser_headers,
            json={"confirm": True},
        )
        assert restarting.status_code == 200, restarting.text
        assert restarting.json()["restarting"] is True

    # The endpoint deliberately sends its response before the 0.8 s delayed
    # exec.  Do not mistake the still-healthy old process for the restarted one.
    time.sleep(1.2)
    _wait_healthy()
    with httpx.Client(base_url=BASE_URL, timeout=30.0, trust_env=False) as client:
        _login(client)
        configured_origin_probe = client.patch(
            "/api/bucket/configured-origin-missing/edit",
            headers={
                "Host": "internal.service:8000",
                "Origin": PUBLIC_ORIGIN,
            },
            json={"importance": 8},
        )
        assert configured_origin_probe.status_code == 404, configured_origin_probe.text
        assert "Cross-origin" not in configured_origin_probe.text
        effective = client.get("/api/config")
        assert effective.status_code == 200, effective.text
        effective_payload = effective.json()
        assert effective_payload["deployment"] == {
            "public_url": PUBLIC_ORIGIN,
            "public_url_effective": PUBLIC_ORIGIN,
        }
        assert effective_payload["restart_required"] is False

        metadata = client.get("/.well-known/oauth-protected-resource/mcp")
        assert metadata.status_code == 200, metadata.text
        assert metadata.json()["resource"] == f"{PUBLIC_ORIGIN}/mcp"

        callback = "https://client.example/callback"
        registration = client.post(
            "/oauth/register",
            json={
                "redirect_uris": [callback],
                "client_name": "Docker Public Origin Regression",
            },
        )
        assert registration.status_code == 201, registration.text
        client_id = registration.json()["client_id"]

        verifier = "v" * 64
        challenge = base64.urlsafe_b64encode(
            hashlib.sha256(verifier.encode()).digest()
        ).rstrip(b"=").decode()
        resource = f"{PUBLIC_ORIGIN}/mcp"
        authorized = client.post(
            "/oauth/authorize",
            data={
                "password": PASSWORD,
                "client_id": client_id,
                "redirect_uri": callback,
                "state": "docker-regression",
                "scope": "mcp",
                "resource": resource,
                "code_challenge": challenge,
            },
            follow_redirects=False,
        )
        assert authorized.status_code == 302, authorized.text
        code = parse_qs(urlsplit(authorized.headers["location"]).query)["code"][0]

        exchanged = client.post(
            "/oauth/token",
            data={
                "grant_type": "authorization_code",
                "code": code,
                "code_verifier": verifier,
                "client_id": client_id,
                "redirect_uri": callback,
                "resource": "https://PUBLIC.example:443/mcp/",
            },
        )
        assert exchanged.status_code == 200, exchanged.text
        token = exchanged.json()["access_token"]

        mcp_response = client.post(
            "/mcp",
            headers={
                "Authorization": f"bEaReR {token}",
                "Accept": "application/json, text/event-stream",
            },
            json={
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-03-26",
                    "capabilities": {},
                    "clientInfo": {"name": "docker-regression", "version": "1"},
                },
            },
        )
        assert mcp_response.status_code == 200, mcp_response.text
        assert mcp_response.status_code != 401
