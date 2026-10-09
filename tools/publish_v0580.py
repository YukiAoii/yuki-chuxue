"""发布 v0.58.0（versionCode 83）—— 传包 + 写发布记录 + 验活。

用管理员凭据（用户 2026-10-01 明确授权：「<管理员密码> 就是运营后端的凭据你用就行」）。

⚠️ 顺序：先把 APK 放进 static/d/，再写发布记录 —— 反过来会出现"版本已发布但下载 404"的窗口。
⚠️ 不动生产其他文件，只加一个 yuki-83.apk。
"""
import json
import os
import shutil
import sys
import urllib.error
import urllib.request

BASE = "https://sy.example.com:11445"
APK_SRC = r"<构建目录>\app\build\outputs\apk\debug\app-debug.apk"
APK_DST = r"backend\static\d\yuki-83.apk"
APK_URL = "/d/yuki-83.apk"
CODE, NAME = 83, "0.58.0"


def http(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:300]}


def main():
    # ① 传包
    size = os.path.getsize(APK_SRC)
    shutil.copyfile(APK_SRC, APK_DST)
    print(f"① 已放包：{APK_DST}（{size:,} 字节）")

    # ② 管理员登录
    s, r = http("POST", "/api/admin/login", {"username": "admin", "password": "<管理员密码>"})
    tok = r.get("token", "")
    print(f"② 管理员登录 -> {s}，令牌长度 {len(tok)}")
    if not tok:
        print("✗ 拿不到管理员令牌，中止（不猜）"); return 1

    # ③ 确认包在线上可取（先包后记录）
    try:
        with urllib.request.urlopen(BASE + APK_URL, timeout=60) as res:
            got = len(res.read())
        print(f"③ 线上取包 -> {res.status}，{got:,} 字节，与本地一致 = {got == size}")
        if got != size:
            print("✗ 线上包大小对不上，中止"); return 1
    except Exception as e:
        print("✗ 线上取不到包：", e); return 1

    # ④ 写发布记录
    s, r = http("POST", "/api/admin/releases", {
        "version_code": CODE,
        "version_name": NAME,
        "notes": "新图标与收款码 / 人设市场（底栏）/ Yuki初雪Pro 免费分组 / 备注与通用设定开关 / 修复市场\"连不上服务器\"",
        "force": False,
        "apk_url": APK_URL,
    }, token=tok)
    print(f"④ 写发布记录 -> {s} {str(r)[:200]}")

    # ⑤ 验活：客户端看到的版本
    s, v = http("GET", f"/api/app/version?code=82")
    print(f"⑤ /api/app/version?code=82 -> {s}")
    print("   ", json.dumps(v, ensure_ascii=False)[:300])
    return 0


sys.exit(main())
