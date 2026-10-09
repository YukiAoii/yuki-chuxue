import asyncio
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pytest

from web import _shared as sh
from web import auth as auth_web
from web import oauth as oauth_web


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
    def __init__(self, body=None, *, cookies=None):
        self._body = {} if body is None else body
        self.headers = {}
        self.cookies = cookies or {}
        self.client = type("Client", (), {"host": "127.0.0.1"})()

    async def json(self):
        return self._body


@pytest.fixture
def isolated_auth_dir(tmp_path, monkeypatch):
    monkeypatch.setitem(sh.config, "buckets_dir", str(tmp_path))
    monkeypatch.delenv("OMBRE_DASHBOARD_PASSWORD", raising=False)
    sh._sessions.clear()
    oauth_web._oauth_codes.clear()
    oauth_web._mcp_tokens.clear()
    oauth_web._mcp_token_resources.clear()
    oauth_web._mcp_refresh_tokens.clear()
    return tmp_path


def test_security_question_update_cannot_restore_a_concurrent_old_password(
    isolated_auth_dir, monkeypatch
):
    auth_file = isolated_auth_dir / ".dashboard_auth.json"
    auth_file.write_text(
        json.dumps(
            {
                "password_hash": "hash:old-password",
                "security_question": "old question",
                "security_answer_hash": "hash:old-answer",
            }
        ),
        encoding="utf-8",
    )
    answer_hash_started = threading.Event()
    allow_answer_hash = threading.Event()

    def controlled_hash(secret):
        if secret == "new-answer":
            answer_hash_started.set()
            assert allow_answer_hash.wait(timeout=2)
        return f"hash:{secret}"

    monkeypatch.setattr(sh, "_hash_secret", controlled_hash)
    with ThreadPoolExecutor(max_workers=1) as executor:
        qa_update = executor.submit(
            sh._save_security_qa, "new question", "new-answer"
        )
        assert answer_hash_started.wait(timeout=2)
        sh._save_password_hash("new-password")
        allow_answer_hash.set()
        qa_update.result(timeout=2)

    saved = json.loads(auth_file.read_text(encoding="utf-8"))
    assert saved["password_hash"] == "hash:new-password"
    assert saved["security_question"] == "new question"
    assert saved["security_answer_hash"] == "hash:new-answer"


def test_legacy_rehash_is_compare_and_swap_not_a_password_rollback(
    isolated_auth_dir, monkeypatch
):
    auth_file = isolated_auth_dir / ".dashboard_auth.json"
    auth_file.write_text(
        json.dumps({"password_hash": "legacy-hash"}), encoding="utf-8"
    )
    rehash_ready = threading.Event()
    allow_rehash = threading.Event()

    monkeypatch.setattr(
        sh,
        "_verify_secret",
        lambda secret, stored: secret == "old-password" and stored == "legacy-hash",
    )

    def controlled_needs_rehash(stored):
        assert stored == "legacy-hash"
        rehash_ready.set()
        assert allow_rehash.wait(timeout=2)
        return True

    monkeypatch.setattr(sh, "_needs_rehash", controlled_needs_rehash)
    monkeypatch.setattr(sh, "_hash_secret", lambda secret: f"hash:{secret}")

    with ThreadPoolExecutor(max_workers=1) as executor:
        old_login = executor.submit(sh._verify_any_password, "old-password")
        assert rehash_ready.wait(timeout=2)
        sh._save_password_hash("new-password")
        allow_rehash.set()
        assert old_login.result(timeout=2) is False

    saved = json.loads(auth_file.read_text(encoding="utf-8"))
    assert saved["password_hash"] == "hash:new-password"


def test_setup_lock_serializes_different_event_loops():
    lock = auth_web._CrossLoopSemaphore(1)
    state_lock = threading.Lock()
    state = {"active": 0, "maximum": 0}

    async def critical_section():
        async with lock:
            with state_lock:
                state["active"] += 1
                state["maximum"] = max(state["maximum"], state["active"])
            await asyncio.sleep(0.02)
            with state_lock:
                state["active"] -= 1

    with ThreadPoolExecutor(max_workers=8) as executor:
        list(executor.map(lambda _index: asyncio.run(critical_section()), range(8)))

    assert state["maximum"] == 1


def test_session_creation_rolls_back_when_private_state_cannot_persist(
    isolated_auth_dir, monkeypatch
):
    existing = "e" * 43
    sh._sessions[existing] = time.time() + 60
    monkeypatch.setattr(
        sh,
        "_atomic_write_private_json",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(OSError("disk full")),
    )

    with pytest.raises(sh.AuthPersistenceError):
        sh._create_session()

    assert list(sh._sessions) == [existing]


def test_authorization_code_exchange_is_single_use_across_threads(
    isolated_auth_dir, monkeypatch
):
    code_data = {
        "client_id": "client-1",
        "redirect_uri": "https://client.example/callback",
        "code_challenge": "",
        "resource": "https://ombre.example/mcp",
        "scope": "mcp",
        "expires": time.time() + 60,
    }
    oauth_web._oauth_codes["one-code"] = dict(code_data)
    monkeypatch.setattr(
        oauth_web, "_persist_mcp_token_state", lambda *_args, **_kwargs: None
    )

    with ThreadPoolExecutor(max_workers=20) as executor:
        results = list(
            executor.map(
                lambda _index: oauth_web._commit_authorization_code_exchange(
                    "one-code", code_data, "https://ombre.example/mcp"
                ),
                range(20),
            )
        )

    assert sum(result is not None for result in results) == 1
    assert "one-code" not in oauth_web._oauth_codes
    assert len(oauth_web._mcp_tokens) == 1
    assert len(oauth_web._mcp_refresh_tokens) == 1


def test_refresh_rotation_is_single_use_across_threads(
    isolated_auth_dir, monkeypatch
):
    refresh_data = {
        "client_id": "client-1",
        "resource": "https://ombre.example/mcp",
        "expires": time.time() + 60,
    }
    oauth_web._mcp_refresh_tokens["one-refresh"] = dict(refresh_data)
    monkeypatch.setattr(
        oauth_web, "_persist_mcp_token_state", lambda *_args, **_kwargs: None
    )

    with ThreadPoolExecutor(max_workers=20) as executor:
        results = list(
            executor.map(
                lambda _index: oauth_web._commit_refresh_token_rotation(
                    "one-refresh", refresh_data, "https://ombre.example/mcp"
                ),
                range(20),
            )
        )

    assert sum(result is not None for result in results) == 1
    assert "one-refresh" not in oauth_web._mcp_refresh_tokens
    assert len(oauth_web._mcp_tokens) == 1
    assert len(oauth_web._mcp_refresh_tokens) == 1


def test_oauth_revoke_does_not_claim_success_when_persistence_fails(
    isolated_auth_dir, monkeypatch
):
    oauth_web._mcp_tokens["access"] = time.time() + 60
    oauth_web._mcp_refresh_tokens["refresh"] = {
        "client_id": "client-1",
        "expires": time.time() + 60,
        "resource": "https://ombre.example/mcp",
    }
    monkeypatch.setattr(
        sh,
        "_atomic_write_private_json",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(OSError("read only")),
    )

    with pytest.raises(oauth_web.OAuthPersistenceError):
        oauth_web.revoke_all_mcp_grants()

    assert "access" in oauth_web._mcp_tokens
    assert "refresh" in oauth_web._mcp_refresh_tokens


def test_oauth_revoke_invalidates_inflight_authorization_generation(
    isolated_auth_dir, monkeypatch
):
    generation = oauth_web._oauth_grant_generation_snapshot()
    monkeypatch.setattr(
        oauth_web, "_persist_mcp_token_state", lambda *_args, **_kwargs: None
    )

    oauth_web.revoke_all_mcp_grants()
    stored = oauth_web._store_authorization_code(
        "late-code",
        {"expires": time.time() + 60},
        generation,
    )

    assert stored is False
    assert "late-code" not in oauth_web._oauth_codes


@pytest.mark.asyncio
async def test_logout_returns_503_and_keeps_session_when_revoke_is_not_durable(
    isolated_auth_dir, monkeypatch
):
    token = "s" * 43
    sh._sessions[token] = time.time() + 60
    monkeypatch.setattr(
        sh,
        "_persist_sessions_locked",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            sh.AuthPersistenceError("read only")
        ),
    )
    mcp = FakeMCP()
    auth_web.register(mcp)

    response = await mcp.routes[("POST", "/auth/logout")](
        JsonRequest(cookies={"ombre_session": token})
    )

    assert response.status_code == 503
    assert response.headers["cache-control"] == "no-store"
    assert token in sh._sessions


@pytest.mark.asyncio
async def test_password_change_stops_before_write_when_session_revoke_fails(
    isolated_auth_dir, monkeypatch
):
    saved_passwords = []
    monkeypatch.setattr(sh, "_require_auth", lambda _request: None)
    auth_file = isolated_auth_dir / ".dashboard_auth.json"
    auth_file.write_text(
        json.dumps({"password_hash": "hash:old-password"}),
        encoding="utf-8",
    )
    proof = sh.CredentialProof(
        "password_hash",
        "hash:old-password",
        sh._credential_generation_snapshot(),
    )
    monkeypatch.setattr(
        sh, "_verify_password_for_rotation", lambda _password: proof
    )
    monkeypatch.setattr(sh, "_hash_secret", lambda value: f"hash:{value}")
    monkeypatch.setattr(
        sh,
        "_revoke_all_sessions",
        lambda: (_ for _ in ()).throw(sh.AuthPersistenceError("disk full")),
    )
    monkeypatch.setattr(
        sh,
        "_save_prehashed_password",
        lambda password, **_kwargs: saved_passwords.append(password),
    )
    mcp = FakeMCP()
    auth_web.register(mcp)

    response = await mcp.routes[("POST", "/auth/change-password")](
        JsonRequest({"current": "old-password", "new": "new-password"})
    )

    assert response.status_code == 503
    assert saved_passwords == []


def test_concurrent_old_password_rotations_have_exactly_one_winner(
    isolated_auth_dir, monkeypatch
):
    auth_file = isolated_auth_dir / ".dashboard_auth.json"
    auth_file.write_text(
        json.dumps(
            {
                "password_hash": "hash:old-password",
                "security_question": "question",
                "security_answer_hash": "hash:answer",
            }
        ),
        encoding="utf-8",
    )
    generation = sh._credential_generation_snapshot()
    proof_one = sh.CredentialProof(
        "password_hash", "hash:old-password", generation
    )
    proof_two = sh.CredentialProof(
        "password_hash", "hash:old-password", generation
    )
    monkeypatch.setattr(
        oauth_web, "_persist_mcp_token_state", lambda *_args: None
    )

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(
            executor.map(
                lambda item: auth_web._commit_password_rotation(*item),
                [
                    (proof_one, "hash:new-password-one"),
                    (proof_two, "hash:new-password-two"),
                ],
            )
        )

    assert sum(result is not None for result in results) == 1
    saved = json.loads(auth_file.read_text(encoding="utf-8"))
    assert saved["password_hash"] in {
        "hash:new-password-one",
        "hash:new-password-two",
    }
    assert saved["security_answer_hash"] == "hash:answer"
    assert len(sh._sessions) == 1


def test_oauth_persistence_failure_cannot_publish_new_password_with_old_grants(
    isolated_auth_dir, monkeypatch
):
    auth_file = isolated_auth_dir / ".dashboard_auth.json"
    auth_file.write_text(
        json.dumps({"password_hash": "hash:old-password"}),
        encoding="utf-8",
    )
    proof = sh.CredentialProof(
        "password_hash",
        "hash:old-password",
        sh._credential_generation_snapshot(),
    )
    oauth_web._mcp_tokens["old-access"] = time.time() + 60
    oauth_web._mcp_refresh_tokens["old-refresh"] = {
        "client_id": "client-1",
        "resource": "https://ombre.example/mcp",
        "expires": time.time() + 60,
    }
    monkeypatch.setattr(
        oauth_web,
        "_persist_mcp_token_state",
        lambda *_args: (_ for _ in ()).throw(
            oauth_web.OAuthPersistenceError("disk full")
        ),
    )

    with pytest.raises(oauth_web.OAuthPersistenceError):
        auth_web._commit_password_rotation(proof, "hash:new-password")

    saved = json.loads(auth_file.read_text(encoding="utf-8"))
    assert saved["password_hash"] == "hash:old-password"
    assert "old-access" in oauth_web._mcp_tokens
    assert "old-refresh" in oauth_web._mcp_refresh_tokens


def test_rotation_window_blocks_stale_code_and_grant_publication(
    isolated_auth_dir, monkeypatch
):
    auth_file = isolated_auth_dir / ".dashboard_auth.json"
    auth_file.write_text(
        json.dumps({"password_hash": "hash:old-password"}),
        encoding="utf-8",
    )
    proof = sh.CredentialProof(
        "password_hash",
        "hash:old-password",
        sh._credential_generation_snapshot(),
    )
    code_data = {
        "client_id": "client-1",
        "redirect_uri": "https://client.example/callback",
        "code_challenge": "",
        "resource": "https://ombre.example/mcp",
        "scope": "mcp",
        "expires": time.time() + 60,
        "credential_generation": proof.generation,
    }
    oauth_web._oauth_codes["pre-rotation-code"] = dict(code_data)

    revoke_persist_started = threading.Event()
    release_revoke = threading.Event()
    store_attempted = threading.Event()
    exchange_attempted = threading.Event()

    def blocking_persist(*_args):
        revoke_persist_started.set()
        assert release_revoke.wait(timeout=2)

    def store_late_code():
        store_attempted.set()
        return oauth_web._store_authorization_code(
            "late-code", {"expires": time.time() + 60}, proof
        )

    def exchange_old_code():
        exchange_attempted.set()
        return oauth_web._commit_authorization_code_exchange(
            "pre-rotation-code",
            code_data,
            "https://ombre.example/mcp",
        )

    monkeypatch.setattr(oauth_web, "_persist_mcp_token_state", blocking_persist)
    with ThreadPoolExecutor(max_workers=3) as executor:
        rotation = executor.submit(
            auth_web._commit_password_rotation,
            proof,
            "hash:new-password",
        )
        assert revoke_persist_started.wait(timeout=2)
        late_code = executor.submit(store_late_code)
        old_exchange = executor.submit(exchange_old_code)
        assert store_attempted.wait(timeout=2)
        assert exchange_attempted.wait(timeout=2)
        assert not late_code.done()
        assert not old_exchange.done()
        release_revoke.set()

        assert rotation.result(timeout=2) is not None
        assert late_code.result(timeout=2) is False
        assert old_exchange.result(timeout=2) is None

    assert oauth_web._oauth_codes == {}
    assert oauth_web._mcp_tokens == {}
    assert oauth_web._mcp_refresh_tokens == {}


def test_stale_security_answer_proof_cannot_overwrite_new_recovery_state(
    isolated_auth_dir, monkeypatch
):
    auth_file = isolated_auth_dir / ".dashboard_auth.json"
    auth_file.write_text(
        json.dumps(
            {
                "password_hash": "hash:old-password",
                "security_question": "old question",
                "security_answer_hash": "hash:old-answer",
            }
        ),
        encoding="utf-8",
    )
    old_answer_proof = sh.CredentialProof(
        "security_answer_hash",
        "hash:old-answer",
        sh._credential_generation_snapshot(),
    )
    monkeypatch.setattr(sh, "_hash_secret", lambda value: f"hash:{value}")

    assert sh._save_security_qa("new question", "new-answer") is True
    result = auth_web._commit_password_rotation(
        old_answer_proof, "hash:attacker-password"
    )

    assert result is None
    saved = json.loads(auth_file.read_text(encoding="utf-8"))
    assert saved["password_hash"] == "hash:old-password"
    assert saved["security_question"] == "new question"
    assert saved["security_answer_hash"] == "hash:new-answer"


def test_stale_password_proof_cannot_issue_dashboard_session(
    isolated_auth_dir, monkeypatch
):
    auth_file = isolated_auth_dir / ".dashboard_auth.json"
    auth_file.write_text(
        json.dumps({"password_hash": "hash:old-password"}),
        encoding="utf-8",
    )
    proof = sh.CredentialProof(
        "password_hash",
        "hash:old-password",
        sh._credential_generation_snapshot(),
    )
    monkeypatch.setattr(sh, "_hash_secret", lambda value: f"hash:{value}")

    assert sh._save_password_hash("new-password") is True

    assert sh._create_session_for_credential(proof) is None
    assert sh._sessions == {}
