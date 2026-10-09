import os

import pytest


GEMINI_OPENAI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"


@pytest.mark.asyncio
async def test_query_cache_uses_the_provider_bounded_prefix_as_one_identity(tmp_path):
    """长查询尾部不得滞留内存，也不得把同一 provider 输入拆成多份。"""
    import embedding_engine as embedding_module

    buckets_dir = tmp_path / "buckets"
    buckets_dir.mkdir()
    engine = embedding_module.EmbeddingEngine(
        {
            "buckets_dir": str(buckets_dir),
            "embedding": {"enabled": False},
        }
    )

    class RecordingBackend:
        def __init__(self):
            self.provider_inputs = []

        async def generate_async(self, text):
            # 真实 provider 最多只接收这个前缀；记录实际请求，不保留
            # 调用方的私密尾部。
            self.provider_inputs.append(text[:embedding_module._MAX_INPUT_CHARS])
            return [0.25, 0.75]

    backend = RecordingBackend()
    engine._backend = backend
    prefix = "q" * embedding_module._MAX_INPUT_CHARS
    first = prefix + "-first-private-tail"
    second = prefix + "-second-private-tail"

    assert embedding_module._MAX_INPUT_CHARS == 2000
    assert await engine._generate_async(first) == [0.25, 0.75]
    assert await engine._generate_async(second) == [0.25, 0.75]

    assert backend.provider_inputs == [prefix]
    assert len(engine._query_cache) == 1
    assert first not in engine._query_cache
    assert second not in engine._query_cache
    assert all(
        len(str(key)) <= embedding_module._MAX_INPUT_CHARS
        for key in engine._query_cache
    )


def test_gemini_openai_compat_uses_bare_model_name():
    from embedding_engine import APIEmbeddingEngine

    engine = APIEmbeddingEngine(
        api_key="test-key",
        base_url=GEMINI_OPENAI_BASE_URL,
        model="gemini-embedding-001",
    )

    assert engine.model_name() == "gemini-embedding-001"


def test_gemini_openai_compat_strips_native_models_prefix():
    from embedding_engine import APIEmbeddingEngine

    engine = APIEmbeddingEngine(
        api_key="test-key",
        base_url=GEMINI_OPENAI_BASE_URL,
        model="models/gemini-embedding-001",
    )

    assert engine.model_name() == "gemini-embedding-001"


def test_gemini_openai_compat_default_dim_matches_gemini_embedding_001(tmp_path):
    from embedding_engine import EmbeddingEngine

    buckets_dir = tmp_path / "buckets"
    os.makedirs(buckets_dir, exist_ok=True)
    engine = EmbeddingEngine(
        {
            "buckets_dir": str(buckets_dir),
            "embedding": {
                "enabled": True,
                "api_key": "test-key",
                "api_format": "openai_compat",
                "base_url": GEMINI_OPENAI_BASE_URL,
                "model": "gemini-embedding-001",
            },
        }
    )

    assert engine.status()["vector_dim"] == 3072


def test_siliconflow_short_bge_name_is_canonicalized():
    from embedding_engine import APIEmbeddingEngine

    engine = APIEmbeddingEngine(
        api_key="test-key",
        base_url="https://api.siliconflow.cn/v1",
        model="bge-m3",
    )

    assert engine.model_name() == "BAAI/bge-m3"


def test_provider_detection_uses_exact_hostname():
    from ombrebrain.integrations.provider_detect import (
        is_gemini_native_host,
        is_siliconflow_endpoint,
        normalize_model_for_endpoint,
    )

    lookalike = "https://api.siliconflow.cn.evil.example/v1?next=api.siliconflow.cn"
    assert is_siliconflow_endpoint(lookalike) is False
    assert normalize_model_for_endpoint("bge-m3", lookalike) == "bge-m3"
    assert is_gemini_native_host(
        "https://example.test/v1?next=generativelanguage.googleapis.com"
    ) is False


def test_local_mode_ignores_stale_cloud_endpoint_and_secret(monkeypatch, tmp_path):
    from embedding_engine import EmbeddingEngine

    monkeypatch.delenv("OMBRE_OLLAMA_URL", raising=False)
    buckets_dir = tmp_path / "buckets"
    buckets_dir.mkdir()
    engine = EmbeddingEngine(
        {
            "buckets_dir": str(buckets_dir),
            "embedding": {
                "enabled": True,
                "api_key": "siliconflow-secret",
                "api_format": "ollama",
                "base_url": "https://api.siliconflow.cn/v1",
                "model": "BAAI/bge-m3",
            },
        }
    )

    assert engine.enabled is True
    assert engine._backend.api_key == "ollama"
    assert engine._backend.base_url in {
        "http://127.0.0.1:11434/v1",
        "http://ombre-ollama:11434/v1",
    }
    assert engine._backend.model_name() == "bge-m3"


def test_local_mode_preserves_custom_ollama_endpoint(monkeypatch, tmp_path):
    from embedding_engine import EmbeddingEngine

    monkeypatch.delenv("OMBRE_OLLAMA_URL", raising=False)
    buckets_dir = tmp_path / "buckets"
    buckets_dir.mkdir()
    engine = EmbeddingEngine(
        {
            "buckets_dir": str(buckets_dir),
            "embedding": {
                "enabled": True,
                "api_key": "must-not-be-forwarded",
                "api_format": "local",
                "base_url": "https://ollama.example/v1",
                "model": "bge-m3",
            },
        }
    )

    assert engine._backend.base_url == "https://ollama.example/v1"
    assert engine._backend.api_key == "ollama"


def test_dedicated_ollama_url_wins_over_embedding_base(monkeypatch, tmp_path):
    from embedding_engine import EmbeddingEngine

    monkeypatch.setenv("OMBRE_OLLAMA_URL", "http://ollama.lan:11434/v1")
    buckets_dir = tmp_path / "buckets"
    buckets_dir.mkdir()
    engine = EmbeddingEngine(
        {
            "buckets_dir": str(buckets_dir),
            "embedding": {
                "enabled": True,
                "api_format": "ollama",
                "base_url": "https://api.siliconflow.cn/v1",
                "model": "bge-m3",
            },
        }
    )

    assert engine._backend.base_url == "http://ollama.lan:11434/v1"


def test_local_error_hint_does_not_misreport_cloud_key_problem():
    from embedding_engine import _humanize_api_error

    class LocalModelError(Exception):
        status_code = 400

    hint = _humanize_api_error(
        LocalModelError("model does not exist"),
        api_format="ollama",
        base_url="http://127.0.0.1:11434/v1",
    )

    assert "本地 Ollama" in hint
    assert "bge-m3" in hint
    assert "API key" not in hint
