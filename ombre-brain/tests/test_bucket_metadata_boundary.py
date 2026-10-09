"""Untrusted Markdown frontmatter boundary tests."""

import json

from bucket_manager import BucketManager


def _write_bucket(tmp_path, frontmatter: str):
    path = tmp_path / "untrusted.md"
    path.write_text(f"---\n{frontmatter}\n---\nbody\n", encoding="utf-8")
    return path


def test_numeric_dashboard_fields_are_normalized_before_json_consumers(
    tmp_path, test_config
):
    manager = BucketManager(test_config, embedding_engine=None)
    path = _write_bucket(
        tmp_path,
        "\n".join(
            (
                "id: numeric-boundary",
                'importance: \"<img src=x onerror=alert(1)>\"',
                'activation_count: \"Infinity\"',
                'valence: \"V9.7\"',
            )
        ),
    )

    bucket = manager._load_bucket(str(path))

    assert bucket is not None
    assert bucket["metadata"]["importance"] == 5
    assert bucket["metadata"]["activation_count"] == 0
    assert bucket["metadata"]["valence"] == 1.0


def test_recursive_yaml_alias_is_rejected(tmp_path, test_config):
    manager = BucketManager(test_config, embedding_engine=None)
    path = _write_bucket(
        tmp_path,
        "\n".join(
            (
                "id: recursive-alias",
                "payload: &loop",
                "  self: *loop",
            )
        ),
    )

    assert manager._load_bucket(str(path)) is None


def test_alias_shared_across_top_level_fields_cannot_reset_budget(
    tmp_path, test_config
):
    manager = BucketManager(test_config, embedding_engine=None)
    path = _write_bucket(
        tmp_path,
        "\n".join(
            (
                "id: shared-alias",
                "first: &shared [one, two]",
                "second: *shared",
            )
        ),
    )

    assert manager._load_bucket(str(path)) is None


def test_excessive_metadata_depth_is_rejected(tmp_path, test_config):
    manager = BucketManager(test_config, embedding_engine=None)
    nested = "leaf"
    for _ in range(20):
        nested = f"[{nested}]"
    path = _write_bucket(tmp_path, f"id: deep-metadata\npayload: {nested}")

    assert manager._load_bucket(str(path)) is None


def test_yaml_set_and_binary_scalars_are_rejected(tmp_path, test_config):
    manager = BucketManager(test_config, embedding_engine=None)
    set_path = _write_bucket(
        tmp_path,
        "id: yaml-set\npayload: !!set\n  ? untrusted",
    )
    assert manager._load_bucket(str(set_path)) is None

    binary_path = tmp_path / "binary.md"
    binary_path.write_text(
        "---\nid: yaml-binary\npayload: !!binary SGVsbG8=\n---\nbody\n",
        encoding="utf-8",
    )
    assert manager._load_bucket(str(binary_path)) is None


def test_nonfinite_yaml_scalars_normalize_to_json_safe_values(
    tmp_path,
    test_config,
):
    manager = BucketManager(test_config, embedding_engine=None)
    path = _write_bucket(
        tmp_path,
        "id: nonfinite\nvalence: .nan\npayload: .inf",
    )

    bucket = manager._load_bucket(str(path))

    assert bucket is not None
    assert bucket["metadata"]["valence"] == 0.5
    assert bucket["metadata"]["payload"] is None
    json.dumps(bucket["metadata"], allow_nan=False)
