import json

import pytest

from web import auth as auth_web
from web import _shared as shared_web


class FakeMCP:
    def __init__(self):
        self.routes = {}

    def custom_route(self, path, methods):
        def decorator(handler):
            for method in methods:
                self.routes[(method, path)] = handler
            return handler

        return decorator


class JsonRequest:
    def __init__(self, body):
        self._body = body
        self.headers = {"Host": "localhost"}
        self.cookies = {}
        self.client = type("Client", (), {"host": "127.0.0.1"})()

    async def json(self):
        return self._body


def _payload(response):
    return json.loads(response.body)


@pytest.mark.parametrize(
    ("left", "right", "expected"),
    [
        ("密码", "密码", True),
        ("密码", "密碼", False),
        ("密码", "ascii", False),
        ("ascii", "ascii", True),
    ],
)
def test_constant_time_text_compare_accepts_unicode(left, right, expected):
    assert shared_web._constant_time_text_equal(left, right) is expected


def test_environment_password_rejects_unicode_without_type_error(monkeypatch):
    monkeypatch.setenv("OMBRE_DASHBOARD_PASSWORD", "ascii-secret")

    assert shared_web._verify_password_for_rotation("错误密码") is None


@pytest.mark.asyncio
async def test_environment_password_login_succeeds_from_trusted_docker_gateway(
    monkeypatch, tmp_path
):
    monkeypatch.setenv("OMBRE_DASHBOARD_PASSWORD", "proxy-secret")
    monkeypatch.setenv("OMBRE_TRUSTED_PROXY_CIDRS", "172.17.0.1/32")
    monkeypatch.setitem(shared_web.config, "buckets_dir", str(tmp_path))
    shared_web._sessions.clear()
    shared_web._login_failures.clear()
    shared_web._login_locked_until.clear()
    shared_web._login_source_lru.clear()
    shared_web._login_global_attempts.clear()
    mcp = FakeMCP()
    auth_web.register(mcp)
    request = JsonRequest({"password": "proxy-secret"})
    request.headers = {
        "host": "ombre.example:18080",
        "x-forwarded-for": "198.51.100.23",
        "x-forwarded-host": "ombre.example:18080",
        "x-forwarded-proto": "https",
    }
    request.client = type("Client", (), {"host": "172.17.0.1"})()

    try:
        response = await mcp.routes[("POST", "/auth/login")](request)
    finally:
        shared_web._sessions.clear()
        shared_web._login_failures.clear()
        shared_web._login_locked_until.clear()
        shared_web._login_source_lru.clear()
        shared_web._login_global_attempts.clear()

    assert response.status_code == 200
    assert _payload(response) == {"ok": True}
    cookie = response.headers["set-cookie"]
    assert "ombre_session=" in cookie
    assert "Secure" in cookie


def test_remote_setup_rejects_unicode_token_without_type_error(monkeypatch):
    monkeypatch.setenv("OMBRE_SETUP_TOKEN", "ascii-secret")
    request = JsonRequest({})
    request.headers["X-Ombre-Setup-Token"] = "错误令牌"
    request.client = type("Client", (), {"host": "203.0.113.10"})()

    assert auth_web._setup_request_allowed(request) is False


@pytest.fixture
def auth_routes(monkeypatch):
    monkeypatch.setattr(auth_web.sh, "_is_setup_needed", lambda: True)
    monkeypatch.setattr(auth_web.sh, "_require_auth", lambda _request: None)
    monkeypatch.setattr(auth_web.sh, "_load_auth_data", lambda: {
        "security_answer_hash": "configured"
    })
    monkeypatch.setattr(auth_web.sh, "_login_retry_after", lambda _request: 0)
    mcp = FakeMCP()
    auth_web.register(mcp)
    return mcp.routes


@pytest.mark.asyncio
@pytest.mark.parametrize("path", ["/auth/setup", "/auth/change-password", "/auth/security-question"])
async def test_auth_mutations_reject_non_object_json(auth_routes, path):
    response = await auth_routes[("POST", path)](JsonRequest(["not", "an", "object"]))

    assert response.status_code == 400
    assert _payload(response)["error"] == "JSON body must be an object"


@pytest.mark.asyncio
@pytest.mark.parametrize("path", ["/auth/login", "/auth/recover"])
async def test_public_auth_mutations_count_non_object_json_as_failure(
    auth_routes, monkeypatch, path
):
    failures = []
    monkeypatch.setattr(
        auth_web.sh, "_record_login_failure", lambda request: failures.append(request)
    )

    response = await auth_routes[("POST", path)](JsonRequest("not-an-object"))

    assert response.status_code == 400
    assert failures


@pytest.mark.asyncio
async def test_setup_rejects_oversized_password_without_saving(auth_routes, monkeypatch):
    saved = []
    monkeypatch.setattr(auth_web.sh, "_save_password_hash", saved.append)

    response = await auth_routes[("POST", "/auth/setup")](
        JsonRequest({"password": "x" * 1025})
    )

    assert response.status_code == 400
    assert saved == []


@pytest.mark.asyncio
async def test_recover_does_not_clear_failures_for_invalid_new_password(
    auth_routes, monkeypatch
):
    successes = []
    saved = []
    monkeypatch.setattr(
        auth_web.sh,
        "_verify_security_answer_for_rotation",
        lambda _answer: object(),
    )
    monkeypatch.setattr(
        auth_web.sh, "_record_login_success", lambda request: successes.append(request)
    )
    monkeypatch.setattr(auth_web.sh, "_save_password_hash", lambda *args, **kwargs: saved.append(args))

    response = await auth_routes[("POST", "/auth/recover")](
        JsonRequest({"answer": "correct", "new_password": "short"})
    )

    assert response.status_code == 400
    assert successes == []
    assert saved == []
