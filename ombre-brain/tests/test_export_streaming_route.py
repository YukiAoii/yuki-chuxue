"""Export route must stream a temp file and serialize concurrent jobs."""

import asyncio
import json
import os
import threading

import pytest
from starlette.responses import FileResponse

from web import import_api as import_web


class _MCP:
    def __init__(self):
        self.routes = {}

    def custom_route(self, path, methods):
        def decorator(handler):
            for method in methods:
                self.routes[(method, path)] = handler
            return handler

        return decorator


class _Manager:
    async def get_stats(self):
        return {"dynamic_count": 1}


class _Embedding:
    db_path = ""
    model = ""
    backend = ""
    _backend = None


async def _consume_response(response, *, headers=None, fail_send=False):
    messages = []

    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(message):
        if fail_send:
            raise ConnectionError("client disconnected")
        messages.append(message)

    await response(
        {
            "type": "http",
            "method": "GET",
            "path": "/api/export",
            "headers": headers or [],
        },
        receive,
        send,
    )
    return messages


@pytest.mark.asyncio
async def test_export_uses_file_response_and_holds_lock_until_transfer_finishes(
    monkeypatch, tmp_path
):
    vault = tmp_path / "vault"
    bucket = vault / "dynamic" / "memory.md"
    bucket.parent.mkdir(parents=True)
    bucket.write_text("---\nid: stream-route\n---\nbody\n", encoding="utf-8")

    monkeypatch.setattr(import_web.sh, "_require_auth", lambda _request: None)
    monkeypatch.setattr(import_web.sh, "config", {"buckets_dir": str(vault)})
    monkeypatch.setattr(import_web.sh, "bucket_mgr", _Manager(), raising=False)
    monkeypatch.setattr(import_web.sh, "embedding_engine", _Embedding(), raising=False)
    monkeypatch.setattr(import_web.sh, "version", "test", raising=False)
    mcp = _MCP()
    import_web.register(mcp)
    handler = mcp.routes[("GET", "/api/export")]

    first = await handler(object())
    assert isinstance(first, FileResponse)
    archive_path = first.path
    assert os.path.isfile(archive_path)

    concurrent = await handler(object())
    assert concurrent.status_code == 409
    assert "已有导出任务" in json.loads(concurrent.body)["error"]

    messages = await _consume_response(first)
    assert any(message["type"] == "http.response.body" for message in messages)
    assert not os.path.exists(archive_path)

    after_cleanup = await handler(object())
    assert isinstance(after_cleanup, FileResponse)
    await _consume_response(after_cleanup)


@pytest.mark.asyncio
@pytest.mark.parametrize("failure_mode", ["range", "disconnect"])
async def test_export_always_cleans_file_and_releases_reservation(
    monkeypatch, tmp_path, failure_mode
):
    vault = tmp_path / "vault"
    bucket = vault / "dynamic" / "memory.md"
    bucket.parent.mkdir(parents=True)
    bucket.write_text("---\nid: cleanup-route\n---\nbody\n", encoding="utf-8")

    monkeypatch.setattr(import_web.sh, "_require_auth", lambda _request: None)
    monkeypatch.setattr(import_web.sh, "config", {"buckets_dir": str(vault)})
    monkeypatch.setattr(import_web.sh, "bucket_mgr", _Manager(), raising=False)
    monkeypatch.setattr(import_web.sh, "embedding_engine", _Embedding(), raising=False)
    monkeypatch.setattr(import_web.sh, "version", "test", raising=False)
    mcp = _MCP()
    import_web.register(mcp)
    handler = mcp.routes[("GET", "/api/export")]

    response = await handler(object())
    archive_path = response.path
    if failure_mode == "range":
        await _consume_response(
            response,
            headers=[(b"range", b"bytes=999999999-")],
        )
    else:
        with pytest.raises(ConnectionError, match="client disconnected"):
            await _consume_response(response, fail_send=True)

    assert not os.path.exists(archive_path)
    retry = await handler(object())
    assert isinstance(retry, FileResponse)
    await _consume_response(retry)


@pytest.mark.asyncio
async def test_export_double_cancel_reaps_builder_before_releasing_lock(
    monkeypatch,
    tmp_path,
):
    vault = tmp_path / "vault"
    vault.mkdir()
    entered = threading.Event()
    release = threading.Event()
    orphan = tmp_path / "late-export.zip"

    def blocking_builder(*_args):
        orphan.write_bytes(b"late archive")
        entered.set()
        release.wait(timeout=2)
        return str(orphan), {"file_count": 0}

    monkeypatch.setattr(import_web.sh, "_require_auth", lambda _request: None)
    monkeypatch.setattr(import_web.sh, "config", {"buckets_dir": str(vault)})
    monkeypatch.setattr(import_web.sh, "bucket_mgr", _Manager(), raising=False)
    monkeypatch.setattr(import_web.sh, "embedding_engine", _Embedding(), raising=False)
    monkeypatch.setattr(import_web.sh, "version", "test", raising=False)
    monkeypatch.setattr(import_web, "build_export_archive_file", blocking_builder)
    mcp = _MCP()
    import_web.register(mcp)
    handler = mcp.routes[("GET", "/api/export")]

    task = asyncio.create_task(handler(object()))
    while not entered.is_set():
        await asyncio.sleep(0)
    task.cancel()
    await asyncio.sleep(0)
    task.cancel()
    await asyncio.sleep(0)
    assert task.done() is False

    release.set()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert not orphan.exists()

    def fast_builder(*_args):
        path = tmp_path / "retry.zip"
        path.write_bytes(b"retry archive")
        return str(path), {"file_count": 0}

    monkeypatch.setattr(import_web, "build_export_archive_file", fast_builder)
    retry = await handler(object())
    assert isinstance(retry, FileResponse)
    await _consume_response(retry)
