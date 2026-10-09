"""Regression tests for bounded-memory persistent log readers."""

from __future__ import annotations

import json

import errors
from web import system as system_web


def test_recent_errors_reads_newest_records_from_bounded_tail(monkeypatch, tmp_path):
    errors.configure_errors_path(str(tmp_path))
    log_path = tmp_path / ".logs" / "errors.jsonl"
    records = [
        {"level": "W", "detail": "warning"},
        {"level": "E", "detail": "error"},
        {"level": "F", "detail": "fatal"},
    ]
    payload = ("x" * 4096) + "\n" + "\n".join(
        json.dumps(item) for item in records
    ) + "\n"
    log_path.write_text(payload, encoding="utf-8")
    monkeypatch.setattr(errors, "_MAX_ERROR_TAIL_SCAN_BYTES", 512)

    found = errors.recent_errors(limit=2, min_level="W")

    assert [item["detail"] for item in found] == ["fatal", "error"]


def test_dashboard_log_reader_filters_and_preserves_chronological_order(tmp_path):
    log_path = tmp_path / "server.log"
    log_path.write_text(
        "x" * 4096
        + "\n"
        + "[1] app INFO: old info\n"
        + "[2] app WARNING: first warning\n"
        + "[3] app ERROR: final error\n",
        encoding="utf-8",
    )

    lines = system_web._read_filtered_log_tail(
        str(log_path),
        keep=("WARNING", "ERROR"),
        limit=2,
        max_bytes=256,
    )

    assert lines == [
        "[2] app WARNING: first warning",
        "[3] app ERROR: final error",
    ]


def test_dashboard_log_reader_discards_partial_line_at_byte_cap(tmp_path):
    log_path = tmp_path / "server.log"
    log_path.write_bytes(b"prefix\n" + b"A" * 2048)

    lines = system_web._read_filtered_log_tail(
        str(log_path),
        keep=None,
        limit=10,
        max_bytes=128,
    )

    assert lines == []
