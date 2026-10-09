"""SQLite cache lifecycle regressions for Dehydrator."""

import gc

from dehydrator import Dehydrator


def _dehydrator(tmp_path) -> Dehydrator:
    return Dehydrator(
        {
            "buckets_dir": str(tmp_path),
            "dehydration": {
                "api_key": "test-key",
                "model": "test-model",
                "base_url": "http://127.0.0.1:9/v1",
            },
        }
    )


def test_close_releases_cache_file_and_is_idempotent(tmp_path):
    dehydrator = _dehydrator(tmp_path)
    db_path = tmp_path / "dehydration_cache.db"

    dehydrator.close()
    dehydrator.close()
    db_path.unlink()

    assert not db_path.exists()


def test_finalizer_releases_cache_file_when_instance_is_discarded(tmp_path):
    dehydrator = _dehydrator(tmp_path)
    db_path = tmp_path / "dehydration_cache.db"

    del dehydrator
    gc.collect()
    db_path.unlink()

    assert not db_path.exists()
