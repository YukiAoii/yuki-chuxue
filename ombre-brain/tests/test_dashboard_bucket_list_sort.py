import json
from datetime import datetime

import pytest

import web.buckets as buckets_web


class FakeMCP:
    def __init__(self):
        self.routes = {}

    def custom_route(self, path, methods):
        def decorator(handler):
            for method in methods:
                self.routes[(method, path)] = handler
            return handler

        return decorator


class ListRequest:
    def __init__(self, sort_mode=None):
        self.query_params = {} if sort_mode is None else {"sort": sort_mode}


class FakeBucketManager:
    def __init__(self, buckets):
        self.buckets = buckets

    async def list_all(self, *, include_archive=False):
        assert include_archive is True
        return list(self.buckets)


class FakeDecayEngine:
    def calculate_score(self, metadata):
        return float(metadata.get("test_score", 0.0))


def _bucket(bucket_id, *, created="", last_active="", score=0.0, deleted=False):
    metadata = {
        "name": bucket_id,
        "created": created,
        "last_active": last_active,
        "test_score": score,
    }
    if deleted:
        metadata["deleted_at"] = "2026-01-01T00:00:00Z"
    return {"id": bucket_id, "metadata": metadata, "content": bucket_id}


async def _list(monkeypatch, buckets, sort_mode=None):
    manager = FakeBucketManager(buckets)
    monkeypatch.setattr(buckets_web.sh, "_require_auth", lambda _request: None)
    monkeypatch.setattr(buckets_web.sh, "bucket_mgr", manager, raising=False)
    monkeypatch.setattr(
        buckets_web.sh, "decay_engine", FakeDecayEngine(), raising=False
    )
    mcp = FakeMCP()
    buckets_web.register(mcp)

    response = await mcp.routes[("GET", "/api/buckets")](ListRequest(sort_mode))
    payload = json.loads(response.body.decode("utf-8"))
    return response, payload


@pytest.mark.asyncio
async def test_bucket_list_default_keeps_score_order_with_stable_id_ties(monkeypatch):
    response, payload = await _list(
        monkeypatch,
        [
            _bucket("low", score=1),
            _bucket("tie-z", score=5),
            _bucket("tie-a", score=5),
        ],
    )

    assert response.status_code == 200
    assert [item["id"] for item in payload] == ["tie-a", "tie-z", "low"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("sort_mode", "expected"),
    [
        (
            "created_desc",
            ["later", "earlier", "old-a", "old-b", "missing-a", "missing-b"],
        ),
        (
            "created_asc",
            ["old-a", "old-b", "earlier", "later", "missing-a", "missing-b"],
        ),
    ],
)
async def test_bucket_list_sorts_real_instants_and_keeps_unknown_times_last(
    monkeypatch, sort_mode, expected
):
    # Lexical order is deliberately misleading here: 01:00Z is later than
    # 08:30+08:00 (00:30Z). The route must compare parsed instants.
    buckets = [
        _bucket("missing-b", created="not-a-time"),
        _bucket("old-b", created="2026-01-01T00:00:00Z"),
        _bucket("later", created="2026-01-01T01:00:00Z"),
        _bucket("missing-a"),
        _bucket("earlier", created="2026-01-01T08:30:00+08:00"),
        _bucket("old-a", created="2026-01-01T00:00:00Z"),
        _bucket("deleted", created="2030-01-01T00:00:00Z", deleted=True),
    ]

    response, payload = await _list(monkeypatch, buckets, sort_mode)

    assert response.status_code == 200
    assert [item["id"] for item in payload] == expected


@pytest.mark.asyncio
async def test_bucket_list_rejects_unknown_sort_mode(monkeypatch):
    response, payload = await _list(monkeypatch, [_bucket("one")], "newest")

    assert response.status_code == 400
    assert payload == {
        "error": "invalid sort mode",
        "allowed": ["created_asc", "created_desc", "score"],
    }


@pytest.mark.asyncio
async def test_bucket_list_returns_server_normalized_display_instants(monkeypatch):
    response, payload = await _list(
        monkeypatch,
        [
            _bucket(
                "timed",
                created="2026-01-01T00:00:01Z",
                last_active="2026-01-01T08:00:02+08:00",
            ),
            _bucket("invalid", created="not-a-time"),
        ],
    )

    assert response.status_code == 200
    by_id = {item["id"]: item for item in payload}
    epoch = round(datetime.fromisoformat("2026-01-01T00:00:00+00:00").timestamp() * 1000)
    assert by_id["timed"]["created_epoch_ms"] == epoch + 1000
    assert by_id["timed"]["last_active_epoch_ms"] == epoch + 2000
    assert by_id["invalid"]["created_epoch_ms"] is None
    assert by_id["invalid"]["last_active_epoch_ms"] is None
