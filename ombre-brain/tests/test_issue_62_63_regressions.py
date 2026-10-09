"""Issues 62/63 regressions for digest perspective and proxy OAuth guidance."""

from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]


@pytest.mark.asyncio
async def test_digest_injects_shared_perspective_rule(tmp_path, monkeypatch):
    from dehydrator import DIGEST_PROMPT, Dehydrator, _perspective_rule

    engine = Dehydrator(
        {
            "buckets_dir": str(tmp_path),
            "human": "小明",
            "dehydration": {"api_key": "test-key", "api_format": "gemini"},
        }
    )
    captured: dict[str, object] = {}

    async def fake_chat(system_prompt, user_content, **kwargs):
        captured.update(
            system_prompt=system_prompt,
            user_content=user_content,
            kwargs=kwargs,
        )
        return "[]"

    monkeypatch.setattr(engine, "_chat", fake_chat)
    try:
        assert await engine._api_digest("我和小明一起整理项目") == []
    finally:
        engine.close()

    assert captured["system_prompt"] == DIGEST_PROMPT + _perspective_rule("小明")
    assert "AI 自身永远用「我」" in str(captured["system_prompt"])
    assert "严禁把「小明」的动作/情绪归给「我」" in str(
        captured["system_prompt"]
    )


def test_readme_explains_managed_proxy_oauth_public_origin_contract():
    readme = (ROOT / "README.md").read_text(encoding="utf-8")

    assert "OAuth 元数据、授权端点和 `/mcp` resource 的权威外部来源" in readme
    assert "X-Forwarded-Proto" in readme
    assert "X-Forwarded-Host" in readme
    assert "OMBRE_TRUSTED_PROXY_CIDRS" in readme
    assert "不要把 `0.0.0.0/0` 加入可信代理" in readme
    assert "OAuth 元数据或授权链接生成 `http://`" in readme
