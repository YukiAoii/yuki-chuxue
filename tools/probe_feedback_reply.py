"""反馈「回复」全链路验收（v0.58.0）。

## 最要紧的一条：老库
线上那张 feedback 表是上一版建的、**没有** reply/replied_at/user_id 三列。
`CREATE TABLE IF NOT EXISTS` 不会补列 —— 所以这里先**手工造一张旧结构的表**，
再让新代码去启动，看它会不会把列补上。
跳过这一步的话，本地新库永远测不出线上那个 500。

流程：造旧表 → 起服务（应自动 ALTER）→ 查列 → 注册/登录 → 带令牌发反馈
      → 管理员登录 → 后台列表 → 回复 → 用户侧看到回复
"""
import io
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request

PY = r"C:\Users\<用户名>\AppData\Local\Programs\Python\Python314\python.exe"
SRC = r"C:\Users\<用户名>\Desktop\项目1\Yuki初雪\backend"
DIR = r"C:\Users\<用户名>~1\AppData\Local\Temp\2\fbprobe"
PORT = 18088
BASE = f"http://127.0.0.1:{PORT}"


def http(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:200]}


def main():
    shutil.rmtree(DIR, ignore_errors=True)
    os.makedirs(DIR, exist_ok=True)

    # ① 造一张**旧结构**的 feedback 表（模拟线上）
    db = os.path.join(DIR, "yuki.db")   # ⚠️ 就是 YUKI_DATA_DIR 下的 yuki.db，不是 data/ 子目录
    con = sqlite3.connect(db)
    con.execute(
        "CREATE TABLE feedback (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL DEFAULT 'other',"
        " content TEXT NOT NULL, contact TEXT NOT NULL DEFAULT '', ip TEXT NOT NULL DEFAULT '',"
        " ua TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL)"
    )
    con.execute("INSERT INTO feedback(kind, content, created_at) VALUES('bug','旧库留下的老反馈','2026-10-01T00:00:00+00:00')")
    con.commit()
    con.close()
    print("① 已造旧结构 feedback 表（无 reply/replied_at/user_id）")

    env = dict(os.environ, YUKI_DATA_DIR=DIR, YUKI_ADMIN_USER="admin", YUKI_ADMIN_PASSWORD="probe-admin-pw-1234")
    proc = subprocess.Popen(
        [PY, "-m", "uvicorn", "main:app", "--host", "127.0.0.1", "--port", str(PORT)],
        cwd=SRC, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        for _ in range(30):
            try:
                s, _b = http("GET", "/health")
                if s == 200:
                    break
            except Exception:
                pass
            time.sleep(1)
        else:
            print("✗ 服务没起来"); return 1

        # ② 起来之后列补上了吗
        con = sqlite3.connect(db)
        cols = {r[1] for r in con.execute("PRAGMA table_info(feedback)")}
        con.close()
        need = {"reply", "replied_at", "user_id"}
        print("② 启动后 feedback 的列:", sorted(cols))
        print("   三列都在 =", need.issubset(cols))
        if not need.issubset(cols):
            print("✗ 迁移没生效 —— 线上就会 500"); return 1

        # ③ 注册 + 登录
        http("POST", "/api/v1/auth/register", {"nickname": "fbprobe", "password": "probe12345"})
        _s, login = http("POST", "/api/v1/auth/login", {"account": "fbprobe", "password": "probe12345"})
        tok = login.get("token", "")
        print("③ 用户令牌长度 =", len(tok))

        # ④ 带令牌发一条反馈（要能挂到 user_id 上）
        s, r = http("POST", "/api/feedback", {"kind": "bug", "content": "带令牌发的反馈"}, token=tok)
        print("④ 发反馈 ->", s, r.get("hint", r))

        # ⑤ 管理员登录
        s, admin = http("POST", "/api/admin/login", {"username": "admin", "password": "probe-admin-pw-1234"})
        atok = admin.get("token", "")
        print("⑤ 管理员登录 ->", s, "令牌长度 =", len(atok))

        if atok:
            s, lst = http("GET", "/api/admin/feedback?limit=10", token=atok)
            print("⑥ 后台列表 ->", s, "总数 =", lst.get("total"), "未回复 =", lst.get("unreplied"))
            items = lst.get("items", [])
            mine = [x for x in items if x["content"] == "带令牌发的反馈"]
            if mine:
                fid = mine[0]["id"]
                s, rep = http("POST", f"/api/admin/feedback/{fid}/reply", {"reply": "收到了，这个问题下版修"}, token=atok)
                print("⑦ 回复 ->", s, rep.get("reply"))
                s, mine_list = http("GET", "/api/v1/feedback/mine", token=tok)
                got = [x for x in mine_list.get("items", []) if x["id"] == fid]
                print("⑧ 用户侧看到回复 ->", s, got[0]["reply"] if got else "（没拿到）")
            else:
                print("✗ 后台列表里找不到刚发的那条")

        # ⑨ 老库那条老反馈还在吗
        s, lst = http("GET", "/api/admin/feedback?limit=50", token=atok) if atok else (0, {})
        print("⑨ 旧数据没丢 =", any(x["content"] == "旧库留下的老反馈" for x in lst.get("items", [])))
        return 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except Exception:
            proc.kill()


sys.exit(main())
