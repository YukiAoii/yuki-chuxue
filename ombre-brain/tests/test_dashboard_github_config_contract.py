from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DASHBOARD = ROOT / "frontend" / "dashboard.html"


def _function_source(name: str, next_name: str) -> str:
    html = DASHBOARD.read_text(encoding="utf-8")
    start = html.index(f"async function {name}(")
    end = html.index(f"async function {next_name}(", start)
    return html[start:end]


def test_github_status_hydrates_non_secret_fields_after_page_reload() -> None:
    source = _function_source("loadGithubStatus", "saveGithubConfig")

    assert "repoEl.value = d.repo || ''" in source
    assert "branchEl.value = d.branch || 'main'" in source
    assert "prefixEl.value = d.path_prefix || ''" in source
    assert ".placeholder = '当前: ' + d.repo" not in source
    assert ".placeholder = '当前: ' + d.branch" not in source


def test_github_token_remains_write_only_and_blank_means_keep() -> None:
    load_source = _function_source("loadGithubStatus", "saveGithubConfig")
    save_source = _function_source("saveGithubConfig", "validateGithub")

    assert "tokenEl.placeholder = d.token_set" in load_source
    assert "已配置（留空 = 保留现有 Token）" in load_source
    assert "tokenEl.value =" not in load_source
    assert "body: JSON.stringify({token, repo, branch, path_prefix: prefix" in save_source
    assert "document.getElementById('gh-token').value = ''" in save_source


def test_github_status_box_is_hidden_when_runtime_is_not_configured() -> None:
    source = _function_source("loadGithubStatus", "saveGithubConfig")

    assert "box.style.display = d.configured ? '' : 'none'" in source
