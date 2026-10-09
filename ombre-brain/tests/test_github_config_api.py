import json
from pathlib import Path

import pytest
import yaml

import web.github as github_web


class FakeMCP:
    def __init__(self) -> None:
        self.routes = {}

    def custom_route(self, path, methods):
        def decorator(handler):
            for method in methods:
                self.routes[(method, path)] = handler
            return handler

        return decorator


class JsonRequest:
    def __init__(self, body=None) -> None:
        self._body = body

    async def json(self):
        return self._body


def _payload(response) -> dict:
    return json.loads(response.body.decode("utf-8"))


def _setup(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    github_config: dict,
):
    config_path = tmp_path / "config.yaml"
    config_path.write_text(
        yaml.safe_dump(
            {"unrelated": {"keep": True}, "github_sync": github_config},
            allow_unicode=True,
        ),
        encoding="utf-8",
    )
    restart_intervals = []
    monkeypatch.setenv("OMBRE_CONFIG_PATH", str(config_path))
    monkeypatch.delenv("OMBRE_GITHUB_TOKEN", raising=False)
    monkeypatch.setattr(github_web.sh, "_require_auth", lambda _request: None)
    monkeypatch.setattr(
        github_web.sh,
        "config",
        {"github_sync": dict(github_config)},
    )
    monkeypatch.setattr(github_web.sh, "github_sync_instance", None)
    monkeypatch.setattr(
        github_web.sh,
        "restart_github_auto_task",
        restart_intervals.append,
    )
    mcp = FakeMCP()
    github_web.register(mcp)
    return mcp, config_path, restart_intervals


@pytest.mark.asyncio
async def test_partial_update_keeps_blank_secret_and_repo_without_leaking_token(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    original = {
        "token": "saved-secret",
        "repo": "owner/repo",
        "branch": "main",
        "path_prefix": "ombre",
        "auto_interval_minutes": 60,
    }
    mcp, config_path, restarts = _setup(monkeypatch, tmp_path, original)

    response = await mcp.routes[("POST", "/api/github/config")](
        JsonRequest({
            "token": "",
            "repo": "",
            "branch": "release",
            "path_prefix": "",
            "auto_interval_minutes": 30,
        })
    )
    payload = _payload(response)
    persisted = yaml.safe_load(config_path.read_text(encoding="utf-8"))

    assert response.status_code == 200
    assert payload == {
        "ok": True,
        "message": "配置已保存",
        "configured": True,
        "token_set": True,
    }
    assert "saved-secret" not in response.body.decode("utf-8")
    assert persisted["unrelated"] == {"keep": True}
    assert persisted["github_sync"] == {
        "token": "saved-secret",
        "repo": "owner/repo",
        "branch": "release",
        "path_prefix": "",
        "auto_interval_minutes": 30,
    }
    assert github_web.sh.github_sync_instance.token == "saved-secret"
    assert github_web.sh.github_sync_instance.repo == "owner/repo"
    assert restarts == [30]

    status_response = await mcp.routes[("GET", "/api/github/status")](
        JsonRequest()
    )
    status_payload = _payload(status_response)
    assert status_payload["token_set"] is True
    assert status_payload["repo"] == "owner/repo"
    assert status_payload["branch"] == "release"
    assert status_payload["path_prefix"] == ""
    assert "token" not in status_payload
    assert "saved-secret" not in status_response.body.decode("utf-8")


@pytest.mark.asyncio
async def test_only_explicit_clear_true_erases_saved_github_credentials(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    original = {
        "token": "saved-secret",
        "repo": "owner/repo",
        "branch": "main",
        "path_prefix": "ombre",
        "auto_interval_minutes": 60,
    }
    mcp, config_path, restarts = _setup(monkeypatch, tmp_path, original)

    # An empty partial form is a no-op, not the historical implicit clear.
    keep_response = await mcp.routes[("POST", "/api/github/config")](
        JsonRequest({"token": "", "repo": ""})
    )
    kept = yaml.safe_load(config_path.read_text(encoding="utf-8"))["github_sync"]
    assert keep_response.status_code == 200
    assert kept["token"] == "saved-secret"
    assert kept["repo"] == "owner/repo"

    clear_response = await mcp.routes[("POST", "/api/github/config")](
        JsonRequest({"clear": True})
    )
    payload = _payload(clear_response)
    persisted = yaml.safe_load(config_path.read_text(encoding="utf-8"))

    assert clear_response.status_code == 200
    assert payload == {
        "ok": True,
        "message": "已清空 GitHub 同步配置",
        "configured": False,
        "token_set": False,
    }
    assert persisted["unrelated"] == {"keep": True}
    assert persisted["github_sync"] == {
        "repo": "",
        "branch": "main",
        "path_prefix": "ombre",
        "auto_interval_minutes": 0,
    }
    assert github_web.sh.github_sync_instance is None
    assert restarts == [60, 0]


@pytest.mark.asyncio
async def test_clear_flag_requires_an_actual_boolean(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    mcp, config_path, restarts = _setup(
        monkeypatch,
        tmp_path,
        {"token": "saved-secret", "repo": "owner/repo"},
    )
    before = config_path.read_bytes()

    response = await mcp.routes[("POST", "/api/github/config")](
        JsonRequest({"clear": "true"})
    )

    assert response.status_code == 400
    assert config_path.read_bytes() == before
    assert restarts == []
