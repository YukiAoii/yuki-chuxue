from pathlib import Path

from utils import get_version


ROOT = Path(__file__).resolve().parents[1]


def test_root_and_hot_update_version_copies_match_runtime_version():
    """Release and hot-update version sources must never drift apart."""
    root_version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
    runtime_version = (ROOT / "src" / "VERSION").read_text(encoding="utf-8").strip()

    assert root_version == runtime_version
    assert get_version() == root_version
