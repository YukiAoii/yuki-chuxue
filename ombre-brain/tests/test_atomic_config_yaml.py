import errno

import pytest
import yaml

import utils


def test_read_config_yaml_uses_config_path_and_validates_mapping(
    monkeypatch: pytest.MonkeyPatch, tmp_path
) -> None:
    config_path = tmp_path / "config.yaml"
    monkeypatch.setenv("OMBRE_CONFIG_PATH", str(config_path))
    config_path.write_text("deployment:\n  public_url: https://ob.example\n", encoding="utf-8")

    assert utils.read_config_yaml() == {
        "deployment": {"public_url": "https://ob.example"}
    }

    config_path.write_text("- not\n- a mapping\n", encoding="utf-8")
    with pytest.raises(ValueError, match="top level must be a mapping"):
        utils.read_config_yaml()


def test_atomic_config_update_falls_back_for_single_file_bind_mount(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path,
) -> None:
    config_path = tmp_path / "config.yaml"
    config_path.write_text("existing: keep\n", encoding="utf-8")
    monkeypatch.setenv("OMBRE_CONFIG_PATH", str(config_path))

    def bind_mount_replace(_source, _target) -> None:
        raise OSError(errno.EBUSY, "Device or resource busy")

    monkeypatch.setattr(utils.os, "replace", bind_mount_replace)
    monkeypatch.setattr(utils, "_is_exact_linux_mount_point", lambda _path: True)

    persisted = utils.atomic_update_config_yaml(
        lambda config: config.__setitem__("dehydration", {"api_key": "new-key"})
    )

    assert persisted == {
        "existing": "keep",
        "dehydration": {"api_key": "new-key"},
    }
    assert yaml.safe_load(config_path.read_text(encoding="utf-8")) == persisted
    assert list(tmp_path.glob("*config.yaml.tmp.*")) == []


def test_atomic_config_update_creates_an_explicit_missing_parent(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path,
) -> None:
    config_path = tmp_path / "new" / "nested" / "config.yaml"
    monkeypatch.setenv("OMBRE_CONFIG_PATH", str(config_path))

    persisted = utils.atomic_update_config_yaml(
        lambda config: config.__setitem__("deployment", {"profile": "local"})
    )

    assert persisted == {"deployment": {"profile": "local"}}
    assert yaml.safe_load(config_path.read_text(encoding="utf-8")) == persisted


def test_atomic_config_update_does_not_hide_other_replace_failures(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path,
) -> None:
    config_path = tmp_path / "config.yaml"
    config_path.write_text("existing: keep\n", encoding="utf-8")
    monkeypatch.setenv("OMBRE_CONFIG_PATH", str(config_path))

    def denied_replace(_source, _target) -> None:
        raise OSError(errno.EACCES, "Permission denied")

    monkeypatch.setattr(utils.os, "replace", denied_replace)

    with pytest.raises(OSError, match="Permission denied"):
        utils.atomic_update_config_yaml(
            lambda config: config.__setitem__("new", "value")
        )

    assert yaml.safe_load(config_path.read_text(encoding="utf-8")) == {
        "existing": "keep"
    }
    assert list(tmp_path.glob("*config.yaml.tmp.*")) == []


def test_busy_replace_is_not_overwritten_when_target_is_not_a_mount_point(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path,
) -> None:
    config_path = tmp_path / "config.yaml"
    original = b"existing: keep\n"
    config_path.write_bytes(original)
    monkeypatch.setenv("OMBRE_CONFIG_PATH", str(config_path))
    monkeypatch.setattr(
        utils.os,
        "replace",
        lambda _source, _target: (_ for _ in ()).throw(
            OSError(errno.EBUSY, "Device or resource busy")
        ),
    )
    monkeypatch.setattr(utils, "_is_exact_linux_mount_point", lambda _path: False)

    with pytest.raises(OSError, match="Device or resource busy"):
        utils.atomic_update_config_yaml(
            lambda config: config.__setitem__("new", "value")
        )

    assert config_path.read_bytes() == original
    assert list(tmp_path.glob("*config.yaml.tmp.*")) == []


def test_bind_mount_write_failure_restores_previous_config(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path,
) -> None:
    config_path = tmp_path / "config.yaml"
    original = b"existing: keep\n"
    config_path.write_bytes(original)
    monkeypatch.setenv("OMBRE_CONFIG_PATH", str(config_path))
    monkeypatch.setattr(
        utils.os,
        "replace",
        lambda _source, _target: (_ for _ in ()).throw(
            OSError(errno.EBUSY, "Device or resource busy")
        ),
    )
    monkeypatch.setattr(utils, "_is_exact_linux_mount_point", lambda _path: True)

    real_fsync = utils.os.fsync
    fsync_calls = 0

    def fail_target_fsync_once(descriptor: int) -> None:
        nonlocal fsync_calls
        fsync_calls += 1
        if fsync_calls == 2:
            raise OSError(errno.EIO, "simulated target fsync failure")
        real_fsync(descriptor)

    monkeypatch.setattr(utils.os, "fsync", fail_target_fsync_once)

    with pytest.raises(OSError, match="simulated target fsync failure"):
        utils.atomic_update_config_yaml(
            lambda config: config.__setitem__("new", "value")
        )

    assert config_path.read_bytes() == original
    assert list(tmp_path.glob("*config.yaml.tmp.*")) == []
