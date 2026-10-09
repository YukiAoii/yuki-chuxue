"""
========================================
web/dashboard.py — 仪表板页面 + 静态资源 + 健康检查
========================================

承载根路径仪表板、前端静态资源（icon/favicon/manifest/字体）、/favicon.ico 跳转、
以及 /health 健康检查。

对外暴露：register(mcp)。
========================================
"""

import os

from starlette.requests import Request
from starlette.responses import Response

from . import _shared as sh


def register(mcp) -> None:

    @mcp.custom_route("/", methods=["GET"])
    async def root_dashboard(request: Request) -> Response:
        """Serve dashboard HTML directly at root.

        历史上 / 会 307 → /dashboard，但叠加 Cloudflare Tunnel 的 Always Use HTTPS /
        Page Rule 时容易触发 ERR_TOO_MANY_REDIRECTS。直接返回 HTML，少一次跳转，
        既能修复回环，也省一个 RTT。
        """
        from starlette.responses import HTMLResponse
        dashboard_path = os.path.join(sh.repo_root, "frontend", "dashboard.html")
        try:
            with open(dashboard_path, "r", encoding="utf-8") as f:
                html = f.read()
            # U-09 fix: cache-bust static SVG assets so logo updates are visible
            # without manual hard-refresh after upgrade. 只动字面量 /static/*.svg URL。
            for asset in ("/static/icon.svg", "/static/favicon.svg"):
                html = html.replace(asset, f"{asset}?v={sh.version}")
            # 别让浏览器缓存仪表板 HTML：否则改了 dashboard.html 重新下发后，
            # 用户看到的还是旧版面（U-09 只 cache-bust 了 SVG，HTML 本身没设）。
            # HTML 很小、又是每次从磁盘读，禁缓存代价可忽略，省掉「为什么改了没生效」。
            return HTMLResponse(
                html,
                headers={"Cache-Control": "no-cache, no-store, must-revalidate"},
            )
        except FileNotFoundError:
            # 走到这里 = 部署目录里缺 frontend/dashboard.html。它本应随仓库一起下发
            # （已纳入 git，未被 .gitignore 排除），最常见原因是克隆/部署了旧版本。
            return HTMLResponse(
                "<h1>dashboard.html not found</h1>"
                "<p>The packaged frontend asset is missing.</p>"
                "<p>This file ships with the repo (it is committed and NOT git-ignored). "
                "A missing file almost always means an outdated checkout — "
                "run <code>git pull origin main</code> / re-clone, or rebuild your Docker image, "
                "then restart.</p>",
                status_code=404,
            )

    # iter 1.7 §C/§H: serve frontend static assets (icon.svg, favicon.svg, manifest.json)
    # 安全要点：必须白名单过滤文件名，绝不能让 request 直接拼路径，
    # 否则会被 ?name=../../etc/passwd 这种「目录穿越」攻击拿走任意文件。
    @mcp.custom_route("/static/{name}", methods=["GET"])
    async def static_asset(request: Request) -> Response:
        from starlette.responses import Response as _Resp, JSONResponse
        name = request.path_params.get("name", "")
        allowed = {
            "icon.svg": "image/svg+xml",
            "favicon.svg": "image/svg+xml",
            "manifest.json": "application/manifest+json",
            "RRPL.ttf": "font/truetype",
        }
        if name not in allowed:
            return JSONResponse({"error": "not found"}, status_code=404)
        path = os.path.join(sh.repo_root, "frontend", name)
        try:
            with open(path, "rb") as f:
                return _Resp(f.read(), media_type=allowed[name])
        except FileNotFoundError:
            return JSONResponse({"error": "not found"}, status_code=404)

    # 浏览器打开任意页都会自动请求 /favicon.ico，301 永久重定向到 SVG 版本。
    @mcp.custom_route("/favicon.ico", methods=["GET"])
    async def favicon_redirect(request: Request) -> Response:
        from starlette.responses import RedirectResponse
        return RedirectResponse(url="/static/favicon.svg", status_code=301)

    # ── 临时诊断端点（2026-10-06，Yuki 接入侧加的，定位完即撤）─────────────────
    # 用途：定位「每新建一个角色常驻 ~2MB」的根因 —— 看清多桶注册表里**实际驻留了几个桶**。
    # 只在本机（服务只绑 127.0.0.1）、只回**计数**（不回 persona id / 路径 / 内容）。
    @mcp.custom_route("/api/persona/debug", methods=["GET"])
    async def persona_debug(request: Request) -> Response:
        from starlette.responses import JSONResponse
        import gc as _gc
        from collections import Counter
        from persona_bucket import _bundles, _MAX_BUNDLES
        counter = Counter(type(o).__name__ for o in _gc.get_objects())
        return JSONResponse(
            {
                "bundles": len(_bundles),
                "maxBundles": _MAX_BUNDLES,
                "gcObjects": sum(counter.values()),
                # 前 25 类（按实例数）—— 用来 diff「建角色前后」找出到底什么在涨
                "top": counter.most_common(25),
            },
            headers={"Cache-Control": "no-store"},
        )

    # 第二个诊断端点（同上，定位完撤）：`tracemalloc` 按**代码行**给出内存增长。
    # 用法：?action=start 开追踪 → ?action=reset 设基线 → 做操作 → ?action=diff 看增量 Top。
    @mcp.custom_route("/api/persona/trace", methods=["GET"])
    async def persona_trace(request: Request) -> Response:
        from starlette.responses import JSONResponse
        import tracemalloc
        from urllib.parse import parse_qs
        q = parse_qs(request.url.query or "")
        action = (q.get("action") or ["diff"])[0]
        store = globals().setdefault("_PERSONA_TRACE_BASE", {})
        if action == "who":
            # 定位"谁攥着"用：数一数各组件类还活着几个，并给"引用者"（带 frame 行号）。
            import gc as _gc
            names = [
                "EmbeddingOutbox", "BucketManager", "EmbeddingEngine", "DecayEngine", "ImportEngine",
                "MigrateEngine", "Dehydrator", "DeletionRequestStore", "SourceStore",
                "YouService", "ThemService",
            ]
            alive = {}
            for n in names:
                alive[n] = len([o for o in _gc.get_objects() if type(o).__name__ == n])
            sample = []
            for probe in ("EmbeddingOutbox", "Dehydrator"):
                objs = [o for o in _gc.get_objects() if type(o).__name__ == probe]
                for o in objs[:1]:
                    for r in _gc.get_referrers(o):
                        t = type(r).__name__
                        loc = ""
                        try:
                            if t == "frame":
                                loc = f" @{r.f_code.co_filename.split(chr(92))[-1]}:{r.f_lineno}"
                        except Exception:
                            pass
                        sample.append(f"{probe} <- {t}{loc}: {repr(r)[:70]}")
            return JSONResponse({"alive": alive, "sample": sample[:12]})
        if action == "referrers":
            # 判别用：谁攥着 C 层资源？直接看 sqlite3 连接/游标**被哪些类型的对象引用**。
            import gc as _gc
            import sqlite3 as _sq
            conns = [o for o in _gc.get_objects() if isinstance(o, _sq.Connection)]
            curs = [o for o in _gc.get_objects() if isinstance(o, _sq.Cursor)]
            ref: dict = {}
            caches: dict = {}
            holders: dict = {}
            for c in conns[:60]:
                for r in _gc.get_referrers(c):
                    k = type(r).__name__
                    ref[k] = ref.get(k, 0) + 1
                    if k == "_lru_cache_wrapper":
                        w = getattr(r, "__wrapped__", None)
                        name = (
                            f"{getattr(w, '__module__', '?')}.{getattr(w, '__name__', getattr(w, '__qualname__', '?'))}"
                            if w is not None
                            else repr(r)[:60]
                        )
                        try:
                            info = r.cache_info()
                            name += f" maxsize={info.maxsize} currsize={info.currsize}"
                        except Exception:
                            pass
                        try:
                            code = getattr(w, "__code__", None)
                            if code is not None:
                                name += f" @ {code.co_filename}:{code.co_firstlineno}"
                        except Exception:
                            pass
                        caches[name] = caches.get(name, 0) + 1
            # 再往上一层：谁攥着 Dehydrator（连接的主人）
            samples: list = []
            for d in [o for o in _gc.get_objects() if type(o).__name__ == "Dehydrator"][:4]:
                for r in _gc.get_referrers(d):
                    k = type(r).__name__
                    holders[k] = holders.get(k, 0) + 1
                    if len(samples) < 8:
                        samples.append(f"{k}: {repr(r)[:110]}")
            return JSONResponse(
                {
                    "connections": len(conns),
                    "cursors": len(curs),
                    "referrerTypes": sorted(ref.items(), key=lambda kv: -kv[1])[:12],
                    "lruCaches": sorted(caches.items(), key=lambda kv: -kv[1])[:10],
                    "dehydratorHolders": sorted(holders.items(), key=lambda kv: -kv[1])[:10],
                    "sampledReferrers": samples,
                }
            )
        if action == "purge":
            # 判别用：把多桶注册表**整个清空**再 GC —— 看内存到底回不回来。
            import gc as _gc
            from persona_bucket import _bundles
            n = len(_bundles)
            _bundles.clear()
            _gc.collect()
            return JSONResponse({"purged": n, "bundles": len(_bundles)})
        if action == "start":
            if not tracemalloc.is_tracing():
                tracemalloc.start(12)
            return JSONResponse({"tracing": True})
        if action == "stop":
            tracemalloc.stop()
            return JSONResponse({"tracing": False})
        if not tracemalloc.is_tracing():
            return JSONResponse({"error": "not tracing; call ?action=start first"}, status_code=409)
        snap = tracemalloc.take_snapshot()
        if action == "reset":
            store["base"] = snap
            return JSONResponse({"baseline": True})
        base = store.get("base")
        if base is None:
            store["base"] = snap
            return JSONResponse({"baseline": "auto-set"})
        rows = []
        for s in snap.compare_to(base, "lineno")[:15]:
            frame = s.traceback[0]
            rows.append({"at": f"{frame.filename}:{frame.lineno}", "sizeKB": round(s.size_diff / 1024, 1), "count": s.count_diff})
        return JSONResponse({"diff": rows}, headers={"Cache-Control": "no-store"})

    @mcp.custom_route("/health", methods=["GET"])
    async def health_check(request: Request) -> Response:
        from starlette.responses import JSONResponse
        # Public infrastructure probes must be O(1) and reveal no vault size,
        # engine state, filesystem path, or raw exception.  Authenticated
        # /api/status and /api/system/diagnostics own detailed health checks.
        return JSONResponse(
            {"status": "ok"},
            headers={"Cache-Control": "no-store"},
        )
