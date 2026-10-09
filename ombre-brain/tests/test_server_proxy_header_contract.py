from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_uvicorn_does_not_preempt_application_proxy_trust_policy() -> None:
    source = (ROOT / "src" / "server.py").read_text(encoding="utf-8")
    call_start = source.index("uvicorn.run(")
    call_end = source.index("\n        )", call_start)
    call = source[call_start:call_end]

    assert "proxy_headers=False" in call
    assert "OMBRE_TRUSTED_PROXY_CIDRS" in source[call_start - 400:call_start]
