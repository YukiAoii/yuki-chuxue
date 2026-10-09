import asyncio
import json

import pytest

from web import meta


class FakeMCP:
    def __init__(self):
        self.routes = {}

    def custom_route(self, path, methods):
        def decorator(fn):
            for method in methods:
                self.routes[(method, path)] = fn
            return fn
        return decorator


class JsonRequest:
    def __init__(self, body):
        self.body = body

    async def json(self):
        return self.body


@pytest.mark.asyncio
async def test_restart_requires_auth_and_explicit_confirmation(monkeypatch):
    mcp = FakeMCP()
    monkeypatch.setattr(meta.sh, "_require_auth", lambda _request: None)
    meta.register(mcp)
    route = mcp.routes[("POST", "/api/restart")]

    rejected = await route(JsonRequest({}))

    assert rejected.status_code == 400
    assert json.loads(rejected.body)["error"] == "confirm=true required"


@pytest.mark.asyncio
async def test_restart_is_scheduled_after_response(monkeypatch):
    mcp = FakeMCP()
    restarted = asyncio.Event()
    monkeypatch.setattr(meta.sh, "_require_auth", lambda _request: None)
    monkeypatch.setattr(meta, "_restart_self", restarted.set)
    monkeypatch.setattr(meta._asyncio, "sleep", lambda _delay: _instant())
    meta.register(mcp)

    response = await mcp.routes[("POST", "/api/restart")](JsonRequest({"confirm": True}))
    await asyncio.wait_for(restarted.wait(), timeout=1)

    assert response.status_code == 200
    assert json.loads(response.body) == {"ok": True, "restarting": True}


async def _instant():
    return None
