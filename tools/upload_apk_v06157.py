"""用**官方上传接口**把 v0.61.57（vc143）的包装上去 —— 修上一次的漏步。

## 上一次错在哪（诊断结论，不是猜测）
`apk_serve_state()`（main.py:1786）的校验要求 `apk_url` 里带**内容指纹**
（`?h=<sha256 前 APK_HASH_PREFIX 位>`）。上一版脚本手写了
`apk_url=/d/yuki-143.apk`（**无指纹**）→ `apk_token()` 取不到 → 判定
「还没有上传安装包」→ `/api/app/version` 返回 `latest:null`。

而**正确路径**是 `POST /admin/releases/apk`（main.py:1936）：它把包写到
`static/d/yuki-<code>.apk`、从磁盘实读 size + sha256、
**回填 `apk_url`（带指纹）与 `apk_size`** 到那一版的行里，
并且（因为是最新版）同步刷官网固定址 `static/d/yuki.apk`。

所以这个脚本走那个接口 —— 不手写 url，让服务端算。
"""
import hashlib
import json
import os
import urllib.error
import urllib.request
import uuid

BASE = "https://sy.example.com:11445"
REPO = r"C:\Users\<用户名>\Desktop\项目1\Yuki初雪"
APK = REPO + r"\Yuki初雪_v0.61.57_debug.apk"
CODE = 143


def call(method, path, body=None, token=None, timeout=180):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:400]}


def upload_apk(token, path, code):
    """multipart/form-data —— 不用 requests（环境里不一定有），手拼即可。"""
    with open(path, "rb") as f:
        blob = f.read()
    b = uuid.uuid4().hex
    parts = []
    parts.append(
        f'--{b}\r\nContent-Disposition: form-data; name="version_code"\r\n\r\n{code}\r\n'.encode()
    )
    parts.append(
        f'--{b}\r\nContent-Disposition: form-data; name="file"; '
        f'filename="yuki-{code}.apk"\r\n'
        f'Content-Type: application/vnd.android.package-archive\r\n\r\n'.encode()
    )
    parts.append(blob + b"\r\n")
    parts.append(f"--{b}--\r\n".encode())
    body = b"".join(parts)

    req = urllib.request.Request(BASE + "/api/admin/releases/apk", data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={b}")
    req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            return r.status, json.loads(r.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:400]}


def show_row(token, tag):
    s, r = call("GET", "/api/admin/releases", token=token)
    rows = r if isinstance(r, list) else r.get("releases", r.get("items", []))
    row = next((x for x in rows if int(x.get("version_code", 0)) == CODE), None)
    print(f"── {tag} ──")
    if row:
        for k in ("version_code", "version_name", "apk_url", "apk_size", "published_at"):
            print(f"   {k} = {row.get(k)}")
    else:
        print("   （没找到 vc143 那一行）")
    return row


def main():
    size = os.path.getsize(APK)
    sha = hashlib.sha256(open(APK, "rb").read()).hexdigest()
    print(f"本地 APK：{size:,} 字节 / sha256 {sha}")

    s, r = call("POST", "/api/admin/login", {"username": "admin", "password": "<管理员密码>"})
    tok = r.get("token", "")
    print(f"① 登录 -> {s}，令牌长度 {len(tok)}")
    if not tok:
        print("✗ 拿不到令牌，中止")
        return 1

    show_row(tok, "上传前")

    s, r = upload_apk(tok, APK, CODE)
    print(f"② 上传 -> {s} {str(r)[:300]}")

    row = show_row(tok, "上传后")

    # ③ 验活：老版本号问，应当拿到新版
    s, v = call("GET", "/api/app/version?code=142")
    print(f"③ /api/app/version?code=142 -> {s}")
    latest = (v or {}).get("latest") or {}
    print(f"   latest = {latest.get('version_code')} / {latest.get('version_name')}")
    print(f"   blocked = {(v or {}).get('blocked')}  reason = {(v or {}).get('reason')}")
    ok = latest.get("version_code") == CODE
    print(f"   ✅ 生效" if ok else "   ✗ 仍未生效")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())