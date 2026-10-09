from pathlib import Path

import pytest
import yaml

import utils


@pytest.mark.parametrize("data_env", ["OMBRE_BUCKETS_DIR", "OMBRE_VAULT_DIR"])
def test_render_uses_data_disk_config_and_migrates_legacy_cwd(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    data_env: str,
) -> None:
    legacy_dir = tmp_path / "legacy-code"
    data_dir = tmp_path / "persistent-data"
    legacy_dir.mkdir()
    data_dir.mkdir()
    legacy = {
        "unrelated": {"keep": True},
        "github_sync": {
            "token": "saved-secret",
            "repo": "owner/repo",
            "branch": "main",
            "path_prefix": "ombre",
        },
    }
    legacy_path = legacy_dir / "config.yaml"
    legacy_path.write_text(
        yaml.safe_dump(legacy, allow_unicode=True),
        encoding="utf-8",
    )

    monkeypatch.chdir(legacy_dir)
    monkeypatch.setenv("RENDER", "true")
    monkeypatch.delenv("OMBRE_CONFIG_PATH", raising=False)
    monkeypatch.delenv("OMBRE_BUCKETS_DIR", raising=False)
    monkeypatch.delenv("OMBRE_VAULT_DIR", raising=False)
    monkeypatch.setenv(data_env, str(data_dir))

    resolved = Path(utils.config_file_path())

    assert resolved == data_dir / "config.yaml"
    assert yaml.safe_load(resolved.read_text(encoding="utf-8")) == legacy
    assert yaml.safe_load(legacy_path.read_text(encoding="utf-8")) == legacy
    assert list(data_dir.glob(".config.yaml.migrate.*")) == []


def test_render_migration_never_overwrites_existing_persistent_config(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    legacy_dir = tmp_path / "legacy-code"
    data_dir = tmp_path / "persistent-data"
    legacy_dir.mkdir()
    data_dir.mkdir()
    (legacy_dir / "config.yaml").write_text(
        "github_sync:\n  repo: legacy/repo\n",
        encoding="utf-8",
    )
    persistent_path = data_dir / "config.yaml"
    persistent_path.write_text(
        "github_sync:\n  repo: persistent/repo\n",
        encoding="utf-8",
    )
    monkeypatch.chdir(legacy_dir)
    monkeypatch.setenv("RENDER", "true")
    monkeypatch.setenv("OMBRE_BUCKETS_DIR", str(data_dir))
    monkeypatch.delenv("OMBRE_VAULT_DIR", raising=False)
    monkeypatch.delenv("OMBRE_CONFIG_PATH", raising=False)

    assert Path(utils.config_file_path()) == persistent_path
    assert yaml.safe_load(persistent_path.read_text(encoding="utf-8")) == {
        "github_sync": {"repo": "persistent/repo"}
    }


def test_render_migration_does_not_clobber_concurrently_created_config(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    legacy_dir = tmp_path / "legacy-code"
    data_dir = tmp_path / "persistent-data"
    legacy_dir.mkdir()
    data_dir.mkdir()
    (legacy_dir / "config.yaml").write_text(
        "github_sync:\n  repo: legacy/repo\n",
        encoding="utf-8",
    )
    persistent_path = data_dir / "config.yaml"
    real_link = utils.os.link

    def create_target_just_before_publish(source: str, target: str) -> None:
        Path(target).write_text(
            "github_sync:\n  repo: concurrent/repo\n",
            encoding="utf-8",
        )
        real_link(source, target)

    monkeypatch.chdir(legacy_dir)
    monkeypatch.setenv("RENDER", "true")
    monkeypatch.setenv("OMBRE_BUCKETS_DIR", str(data_dir))
    monkeypatch.delenv("OMBRE_VAULT_DIR", raising=False)
    monkeypatch.delenv("OMBRE_CONFIG_PATH", raising=False)
    monkeypatch.setattr(utils.os, "link", create_target_just_before_publish)

    assert Path(utils.config_file_path()) == persistent_path
    assert yaml.safe_load(persistent_path.read_text(encoding="utf-8")) == {
        "github_sync": {"repo": "concurrent/repo"}
    }
    assert list(data_dir.glob(".config.yaml.migrate.*")) == []


def test_explicit_config_path_still_wins_on_render(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    explicit = tmp_path / "explicit" / "settings.yaml"
    monkeypatch.setenv("RENDER", "true")
    monkeypatch.setenv("OMBRE_BUCKETS_DIR", str(tmp_path / "data"))
    monkeypatch.setenv("OMBRE_CONFIG_PATH", str(explicit))

    assert Path(utils.config_file_path()) == explicit
    assert not (tmp_path / "data" / "config.yaml").exists()
