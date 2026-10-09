from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_dashboard_version_check_uses_github_api_before_raw_cdn_fallback():
    api_url = "https://api.github.com/repos/P0luz/Ombre-Brain/contents/VERSION?ref=main"
    raw_url = "https://raw.githubusercontent.com/P0luz/Ombre-Brain/main/VERSION?t="

    for rel_path in ("frontend/dashboard.html",):
        html = (ROOT / rel_path).read_text(encoding="utf-8")

        assert api_url in html
        assert raw_url in html
        assert html.index(api_url) < html.index(raw_url)


def test_dashboard_hot_update_surfaces_csrf_proxy_guidance():
    html = (ROOT / "frontend" / "dashboard.html").read_text(encoding="utf-8")
    block = html[html.index("window.doHotUpdate = async function()") :]
    block = block[: block.index("window.checkGitHubVersion = async function()")]

    assert "fetch(BASE + '/api/do-update'" in block
    assert "authFetch(BASE + '/api/do-update'" not in block
    assert "热更新不是可重试写操作" in block
    assert "failure.error === 'Cross-origin request rejected'" in block
    assert "这不是 CORS 缺失" in block
    assert "OMBRE_TRUSTED_PROXY_CIDRS" in block


def test_dashboard_has_dedicated_faq_tab_and_view():
    html = (ROOT / "frontend" / "dashboard.html").read_text(encoding="utf-8")
    faq_view = html[html.index('id="faq-view"') : html.index('<!-- Logs Tab View')]
    logs_view = html[html.index('id="logs-view"') : html.index('<!-- Settings Tab View -->')]

    faq_url = "https://docs.qq.com/doc/DRHp6UW9oYmd3QW5Z"
    assert 'data-tab="faq"' in html
    assert '<span class="tab-en">FAQ</span>' in html
    assert 'id="faq-section"' in faq_view
    assert faq_url in faq_view
    assert 'target="_blank"' in faq_view
    assert 'rel="noopener noreferrer"' in faq_view
    assert 'id="faq-section"' not in logs_view
    assert "getElementById('faq-view').style.display = target === 'faq'" in html
