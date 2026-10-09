"""构建期 cloudflared 下载脚本的架构映射 / URL / 重试回归测试（用户反馈 #3）。

只测纯逻辑（不真的联网下载）：架构名映射、release URL 拼接、重试后成功/失败。
"""
import importlib.util
import hashlib
from pathlib import Path

import pytest

_MOD_PATH = Path(__file__).resolve().parents[1] / "deploy" / "fetch_cloudflared.py"
_spec = importlib.util.spec_from_file_location("fetch_cloudflared", _MOD_PATH)
fc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fc)


def test_dockerfile_cloudflared_install_fails_closed():
    dockerfile = (_MOD_PATH.parents[1] / "Dockerfile").read_text(encoding="utf-8")
    install_step = dockerfile.split("# Install dependencies first", 1)[0]

    assert "RUN set -eu;" in install_step
    assert (
        "python /tmp/fetch_cloudflared.py /usr/local/bin/cloudflared;"
        in install_step
    )
    assert "&& chmod +x /usr/local/bin/cloudflared;" not in install_step


def test_arch_mapping_covers_common_platforms():
    assert fc.cloudflared_arch("x86_64") == "amd64"
    assert fc.cloudflared_arch("aarch64") == "arm64"
    assert fc.cloudflared_arch("armv7l") == "arm"
    assert fc.cloudflared_arch("i686") == "386"
    assert fc.cloudflared_arch("AMD64") == "amd64"  # 大小写不敏感


def test_arch_mapping_rejects_unknown():
    with pytest.raises(SystemExit):
        fc.cloudflared_arch("sparc64")


def test_release_url_shape():
    url = fc.release_url("amd64")
    assert url.startswith("https://github.com/cloudflare/cloudflared/releases/download/")
    assert f"/{fc._CLOUDFLARED_VERSION}/" in url
    assert "/latest/" not in url
    assert url.endswith("cloudflared-linux-amd64")


@pytest.mark.parametrize("arch", ["386", "amd64", "arm", "arm64"])
def test_each_supported_arch_has_a_pinned_sha256(arch):
    digest = fc.expected_sha256(arch)

    assert len(digest) == 64
    assert int(digest, 16) >= 0


def test_expected_sha256_rejects_unknown_arch():
    with pytest.raises(SystemExit):
        fc.expected_sha256("sparc64")


def test_download_retries_then_succeeds(monkeypatch, tmp_path):
    calls = {"n": 0}
    payload = b"BINARY"

    class _FakeResp:
        headers = {}

        def __init__(self):
            self._read = False

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self, *_a):
            if self._read:
                return b""
            self._read = True
            return payload

    def _fake_urlopen(req, timeout=0):
        calls["n"] += 1
        if calls["n"] < 3:
            raise OSError("502 Bad Gateway")
        return _FakeResp()

    monkeypatch.setattr(fc.urllib.request, "urlopen", _fake_urlopen)
    monkeypatch.setattr(fc.time, "sleep", lambda *_a: None)

    dest = tmp_path / "cloudflared"
    fc.download(
        "https://example/cf",
        str(dest),
        expected_digest=hashlib.sha256(payload).hexdigest(),
        attempts=5,
    )
    assert calls["n"] == 3
    assert dest.read_bytes() == payload
    assert not (tmp_path / "cloudflared.part").exists()


def test_download_fails_after_all_retries(monkeypatch, tmp_path):
    def _always_fail(req, timeout=0):
        raise OSError("502 Bad Gateway")

    monkeypatch.setattr(fc.urllib.request, "urlopen", _always_fail)
    monkeypatch.setattr(fc.time, "sleep", lambda *_a: None)

    with pytest.raises(SystemExit):
        fc.download(
            "https://example/cf",
            str(tmp_path / "cloudflared"),
            expected_digest=hashlib.sha256(b"expected").hexdigest(),
            attempts=3,
        )


def test_checksum_mismatch_retries_without_replacing_existing_binary(
    monkeypatch, tmp_path
):
    calls = {"n": 0}

    class _FakeResp:
        headers = {}

        def __init__(self):
            self._read = False

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self, *_args):
            if self._read:
                return b""
            self._read = True
            return b"tampered"

    def _fake_urlopen(_req, timeout=0):
        calls["n"] += 1
        return _FakeResp()

    monkeypatch.setattr(fc.urllib.request, "urlopen", _fake_urlopen)
    monkeypatch.setattr(fc.time, "sleep", lambda *_a: None)

    dest = tmp_path / "cloudflared"
    dest.write_bytes(b"known-good")

    with pytest.raises(SystemExit, match="SHA-256"):
        fc.download(
            "https://example/cf",
            str(dest),
            expected_digest=hashlib.sha256(b"expected").hexdigest(),
            attempts=2,
        )

    assert calls["n"] == 2
    assert dest.read_bytes() == b"known-good"
    assert not (tmp_path / "cloudflared.part").exists()


def test_download_rejects_oversized_declared_length(monkeypatch, tmp_path):
    class _FakeResp:
        headers = {"Content-Length": "5"}

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        @staticmethod
        def read(*_args):
            raise AssertionError("body must not be read after oversized declaration")

    monkeypatch.setattr(fc, "_MAX_DOWNLOAD_BYTES", 4)
    monkeypatch.setattr(
        fc.urllib.request,
        "urlopen",
        lambda _request, timeout=0: _FakeResp(),
    )
    monkeypatch.setattr(fc.time, "sleep", lambda *_a: None)
    dest = tmp_path / "cloudflared"

    with pytest.raises(SystemExit):
        fc.download(
            "https://example/cf",
            str(dest),
            expected_digest=hashlib.sha256(b"1234").hexdigest(),
            attempts=1,
        )

    assert not dest.exists()
    assert not (tmp_path / "cloudflared.part").exists()


def test_download_rejects_oversized_stream_without_content_length(
    monkeypatch, tmp_path
):
    class _FakeResp:
        headers = {}

        def __init__(self):
            self._chunks = iter((b"123", b"45", b""))

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self, *_args):
            return next(self._chunks)

    monkeypatch.setattr(fc, "_MAX_DOWNLOAD_BYTES", 4)
    monkeypatch.setattr(
        fc.urllib.request,
        "urlopen",
        lambda _request, timeout=0: _FakeResp(),
    )
    monkeypatch.setattr(fc.time, "sleep", lambda *_a: None)
    dest = tmp_path / "cloudflared"

    with pytest.raises(SystemExit):
        fc.download(
            "https://example/cf",
            str(dest),
            expected_digest=hashlib.sha256(b"1234").hexdigest(),
            attempts=1,
        )

    assert not dest.exists()
    assert not (tmp_path / "cloudflared.part").exists()


def test_main_binds_release_url_to_matching_arch_digest(monkeypatch, tmp_path):
    captured = {}

    monkeypatch.setattr(fc, "cloudflared_arch", lambda: "arm64")

    def _fake_download(url, dest, *, expected_digest, attempts=5):
        captured.update(
            url=url,
            dest=dest,
            expected_digest=expected_digest,
            attempts=attempts,
        )

    monkeypatch.setattr(fc, "download", _fake_download)
    dest = str(tmp_path / "cloudflared")

    assert fc.main(["fetch_cloudflared.py", dest]) == 0
    assert captured == {
        "url": fc.release_url("arm64"),
        "dest": dest,
        "expected_digest": fc.expected_sha256("arm64"),
        "attempts": 5,
    }
