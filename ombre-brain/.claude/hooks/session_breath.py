#!/usr/bin/env python3
# ============================================================
# SessionStart Hook: auto-breath on session start
# 对话开始钩子：自动浮现记忆
#
# On SessionStart, this script calls the Ombre Brain MCP server's
# /breath-hook endpoint, printing the result to stdout so Claude
# sees it as session context.
#
# 不调用 /dream-hook：dream（做梦消化）按设计哲学不是义务，不该在每次
# 会话开始被自动触发，只应在需要消化时由模型主动调用 dream 工具。
#
# Config:
#   OMBRE_HOOK_URL   — override the server URL
#                      (default: http://localhost:18001 — OB 的默认对外端口；
#                      Docker 用户对外映射同样是 18001，见 README「快速开始」)
#   OMBRE_HOOK_TOKEN — hook token，对应服务端 config.yaml 的 hooks.token /
#                      环境变量 OMBRE_HOOK_TOKEN；未设置时走 Dashboard 登录态
#                      （本地已登录的浏览器 cookie 场景），公网自托管强烈建议设置
#   OMBRE_HOOK_SKIP  — set to "1" to disable the hook temporarily
# ============================================================

import os
import sys
import urllib.request
import urllib.error

def main():
    # Allow disabling the hook via env var
    if os.environ.get("OMBRE_HOOK_SKIP") == "1":
        sys.exit(0)

    base_url = os.environ.get("OMBRE_HOOK_URL", "http://localhost:18001").rstrip("/")
    token = os.environ.get("OMBRE_HOOK_TOKEN", "").strip()

    # --- Breath — surface unresolved memories ---
    _call_endpoint(base_url, "/breath-hook", token)


def _call_endpoint(base_url, path, token):
    headers = {"Accept": "text/plain"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        f"{base_url}{path}",
        headers=headers,
        method="GET",
    )
    try:
        with urllib.request.urlopen(req, timeout=8) as response:
            raw = response.read().decode("utf-8")
            output = raw.strip()
            if output:
                print(output)
    except urllib.error.HTTPError as e:
        # 不静默吞错：打印可诊断的一行到 stderr，但不阻断会话启动（exit 0）。
        # 401 最常见原因：OMBRE_HOOK_TOKEN 未设置/不匹配，且未登录 Dashboard。
        print(f"[ombre-brain hook] {path} -> HTTP {e.code}（未授权？检查 OMBRE_HOOK_TOKEN 或改用 OMBRE_HOOK_SKIP=1 关闭）", file=sys.stderr)
    except (urllib.error.URLError, OSError) as e:
        print(f"[ombre-brain hook] {path} 连接失败：{e}", file=sys.stderr)


if __name__ == "__main__":
    main()
