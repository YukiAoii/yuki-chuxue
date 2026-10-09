"""发布 v0.61.57（versionCode 143）—— 传包 + 写发布记录 + 验活。

用管理员凭据（用户 2026-10-01 明确授权：「<管理员密码> 就是运营后端的凭据你用就行」）。

⚠️ 顺序：先把 APK 放进 static/d/，再写发布记录 —— 反过来会出现"版本已发布但下载 404"的窗口。
⚠️ 不动生产其他文件，只加一个 yuki-143.apk。

本轮（0.61.57）用户可见改动：
  主动消息独立气泡 / 消息列表未读提示 / 美化设置页（全局背景 + 气泡 + 顶栏透明度）/ 遮罩改高斯模糊

⚠️ 更新日志覆盖范围：**只覆盖 0.61.57 一版**。
   上一个已发布版是 vc142/0.61.56（查线上 `/api/app/version?code=142` 确认），
   中间没有未上传的版本 —— 所以不需要像上次那样覆盖两版。

## 🚨 发布是**两步**，这个脚本只做第一步（建记录）

    ① `POST /api/admin/releases`      ← 本脚本：建版本行 + 写 notes（**apk_url 留空**）
    ② `POST /api/admin/releases/apk`  ← `upload_apk_v06157.py`：传包

**第 ② 步不可省、也不可手写 `apk_url` 代替。** 服务端在 ② 里才做这些事：
提交包文件、从磁盘实读 size/sha256、把带**内容指纹**的 `apk_url`（`?h=<sha256 前 16 位>`）
与 `apk_size` **回填**进版本行、并同步刷新官网固定址 `static/d/yuki.apk`。

⚠️ 若手写一个不带指纹的 `apk_url`（如 `/d/yuki-143.apk`），`apk_serve_state()`
（`main.py:1786`）会判「还没有上传安装包」→ `/api/app/version` 返回 `latest:null`、
`blocked:true`，**版本不生效**（本轮真实踩过，详见 `项目改动记录` 的「必读」段）。
"""
import hashlib
import json
import os
import shutil
import urllib.error
import urllib.request

BASE = "https://sy.example.com:11445"
REPO = r"C:\Users\<用户名>\Desktop\项目1\Yuki初雪"
APK_SRC = REPO + r"\Yuki初雪_v0.61.57_debug.apk"
APK_DST = r"backend\static\d\yuki-143.apk"
# ⚠️ 留空：apk_url 由上传接口（②）回填带指纹的地址，**不要手写**。见文件头说明。
APK_URL = ""
CODE, NAME = 143, "0.61.57"

# 更新日志（8 条）。用户要求「专业化角度」表述 ——
# 与 progress.md 里记的那 8 条**逐字一致**（两处不能漂）。
NOTES = "\n".join([
    "［修复］Ta 主动发来的消息被当作「重新生成的内容」",
    "［新增］Ta 主动发消息时，聊天页给它独立气泡与标识",
    "［新增］消息列表的未读提示（退出对话后仍能看到谁在等你）",
    "［新增］设置 → 美化：全局自定义背景",
    "［新增］设置 → 美化：气泡不透明度可调",
    "［新增］设置 → 美化：聊天页顶栏不透明度可调",
    "［优化］背景遮罩改为高斯模糊（浓度即模糊程度）",
    "［优化］美化设置带实时预览，调完立刻看到效果",
])


def http(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:300]}


def main():
    # ① 放包（先包后记录）
    size = os.path.getsize(APK_SRC)
    sha = hashlib.sha256(open(APK_SRC, "rb").read()).hexdigest()
    shutil.copyfile(APK_SRC, APK_DST)
    print(f"① 已放包：{APK_DST}（{size:,} 字节）")
    print(f"   sha256 = {sha}")

    # ② 管理员登录
    s, r = http("POST", "/api/admin/login", {"username": "admin", "password": "<管理员密码>"})
    tok = r.get("token", "")
    print(f"② 管理员登录 -> {s}，令牌长度 {len(tok)}")
    if not tok:
        print("✗ 拿不到管理员令牌，中止（不猜）")
        return 1

    # ③ 确认包在线上可取
    try:
        with urllib.request.urlopen(BASE + APK_URL, timeout=60) as res:
            body = res.read()
        got = len(body)
        got_sha = hashlib.sha256(body).hexdigest()
        print(f"③ 线上取包 -> {res.status}，{got:,} 字节，与本地一致 = {got == size}")
        print(f"   线上 sha256 = {got_sha}")
        print(f"   逐字节一致 = {got_sha == sha}")
        if got != size or got_sha != sha:
            print("✗ 线上包与本地不一致，中止")
            return 1
    except Exception as e:
        print("✗ 线上取不到包：", e)
        return 1

    # ④ 写发布记录
    s, r = http("POST", "/api/admin/releases", {
        "version_code": CODE,
        "version_name": NAME,
        "notes": NOTES,
        "force": False,
        "apk_url": APK_URL,
    }, token=tok)
    print(f"④ 写发布记录 -> {s} {str(r)[:200]}")

    # ⑤ 验活
    s, v = http("GET", "/api/app/version?code=142")
    print(f"⑤ /api/app/version?code=142 -> {s}")
    latest = (v or {}).get("latest", {})
    print(f"   返回 version_name = {latest.get('version_name')} / code = {latest.get('version_code')}")
    print(f"   与目标一致 = {latest.get('version_code') == CODE}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())