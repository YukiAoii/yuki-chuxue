"""
Yuki 初雪 · 后端端到端自检

═══════════════════════════════════════════════════════════════════════════
为什么要有这个文件
═══════════════════════════════════════════════════════════════════════════
`test_backend.py` 测的是纯函数与 DB 层，它**证明不了 HTTP 那层是通的** ——
路由注册错、依赖注入错、静态挂载抢了 API 路由，这些问题只有真打一遍才暴露。

所以这里是**真发 HTTP** 的自检：服务起好后跑一遍，十几秒内知道整个后端活没活。

  · 只用标准库（urllib），不需要 requests
  · **幂等、不留痕**：测试用的设备、公告都会在结束前删掉，改动的设置会写回原值
  · 退出码非 0 = 有失败，可以直接挂进部署脚本

用法：
    py -3 e2e_check.py                                  # 默认 http://127.0.0.1:11445
    py -3 e2e_check.py http://127.0.0.1:11445 你的密码
    （不给密码时只跑不需要登录的那几项 —— 也能验出服务是否活着）
═══════════════════════════════════════════════════════════════════════════
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:11445").rstrip("/")
PASSWORD = sys.argv[2] if len(sys.argv) > 2 else ""
USERNAME = sys.argv[3] if len(sys.argv) > 3 else "admin"

TEST_DEVICE = "e2e-selfcheck-device"
TEST_EMAIL = "e2e-selfcheck@example.invalid"

_passed = 0
_failed = 0
_failures: list[str] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    global _passed, _failed
    if cond:
        _passed += 1
        print("  [ok]   " + name)
    else:
        _failed += 1
        _failures.append((name + " " + detail).strip())
        print("  [FAIL] " + name + " " + detail)


def call(method: str, path: str, body: dict | None = None, token: str | None = None):
    """返回 (状态码, 解析后的 body 或 None)；连不上时返回 (0, None)。"""
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as res:
            raw = res.read().decode("utf-8", "replace")
            try:
                return res.status, json.loads(raw)
            except json.JSONDecodeError:
                return res.status, {"raw": raw}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw}
    except Exception as e:
        print(f"  [!!]   {method} {path} 连接失败：{type(e).__name__}: {e}")
        return 0, None


print(f"\n端到端自检 → {BASE}\n" + "-" * 62)

# ═══════════════════ 1. 公开接口 ═══════════════════
print("\n[1] 公开接口")

status, health = call("GET", "/health")
check("GET /health 返回 200", status == 200, f"实际 {status}")
check("health.ok 为真", bool(health and health.get("ok")))
check("health 带 admin_ready 字段", bool(health and "admin_ready" in health))
check("health.time 是 ISO 时间串", bool(health and len(str(health.get("time", ""))) >= 19))

status, anns = call("GET", "/api/app/announcements")
check("GET /api/app/announcements 返回 200", status == 200, f"实际 {status}")
check("公告接口返回 items 数组", isinstance(anns, dict) and isinstance(anns.get("items"), list))

# 静态资源：Nginx 会剥掉 /yuki/ 前缀，所以后端看到的就是根路径
status, index = call("GET", "/")
check("GET / 返回后台首页", status == 200, f"实际 {status}")
check("首页是 HTML（不是 JSON 兜底）",
      bool(index and "<!DOCTYPE html" in str(index.get("raw", ""))))

status, _ = call("GET", "/admin.css")
check("GET /admin.css 返回 200（静态挂载在根，不是 /admin）", status == 200, f"实际 {status}")

status, _ = call("GET", "/admin.js")
check("GET /admin.js 返回 200", status == 200, f"实际 {status}")

# ═══════════════════ 2. 鉴权 ═══════════════════
print("\n[2] 鉴权")

status, _ = call("GET", "/api/admin/dashboard")
check("未带令牌访问仪表盘 = 401", status == 401, f"实际 {status}")

status, _ = call("GET", "/api/admin/dashboard", token="garbage-token")
check("伪造令牌 = 401", status == 401, f"实际 {status}")

token = None
if PASSWORD:
    status, login = call("POST", "/api/admin/login", {"username": USERNAME, "password": PASSWORD})
    check("管理员登录成功", status == 200, f"实际 {status} {login}")
    token = (login or {}).get("token")
    check("登录返回了 token", bool(token))

    status, _ = call("POST", "/api/admin/login",
                     {"username": USERNAME, "password": "definitely-wrong"})
    check("错密码 = 401", status == 401, f"实际 {status}")
else:
    print("  [skip] 未提供密码 —— 跳过需要登录的检查（把密码作为第 2 个参数传进来即可完整跑）")

# ═══════════════════ 3. 登录后的检查 ═══════════════════
if token:
    print("\n[3] 仪表盘")

    status, dash = call("GET", "/api/admin/dashboard", token=token)
    check("仪表盘返回 200", status == 200, f"实际 {status}")
    cards = (dash or {}).get("cards") or {}
    for key in ("devices", "active_7d", "sessions", "messages", "memories",
                "personas", "users", "hit_tokens", "miss_tokens", "hit_rate"):
        check("仪表盘含 " + key, key in cards)
    check("仪表盘含 trend 数组", isinstance((dash or {}).get("trend"), list))
    check("仪表盘含 versions 数组", isinstance((dash or {}).get("versions"), list))
    check("仪表盘含 recent 数组", isinstance((dash or {}).get("recent"), list))

    print("\n[4] 设备上报（模拟 App）")

    status, _ = call("POST", "/api/app/report", {
        "device_id": TEST_DEVICE,
        "model": "自检机型",
        "android_version": "14",
        "app_version": "0.0.0-e2e",
        "persona_count": 2, "session_count": 5, "message_count": 42,
        "memory_count": 3, "hit_tokens": 900, "miss_tokens": 100,
    })
    check("上报返回 200（无需令牌）", status == 200, f"实际 {status}")

    status, devs = call("GET", "/api/admin/devices?q=" + TEST_DEVICE, token=token)
    items = (devs or {}).get("items") or []
    check("上报后能在设备列表里查到", len(items) == 1, f"实际 {len(items)} 条")
    if items:
        check("会话数被正确写入", items[0].get("session_count") == 5, str(items[0].get("session_count")))
        check("机型被正确写入", items[0].get("model") == "自检机型", str(items[0].get("model")))

    # 再报一次：必须是"更新"，而不是"新增一行"
    call("POST", "/api/app/report", {"device_id": TEST_DEVICE, "session_count": 9})
    status, devs2 = call("GET", "/api/admin/devices?q=" + TEST_DEVICE, token=token)
    items2 = (devs2 or {}).get("items") or []
    check("同一设备重复上报不新增行", len(items2) == 1, f"实际 {len(items2)} 行")
    if items2:
        check("会话数被覆盖为最新值", items2[0].get("session_count") == 9, str(items2[0].get("session_count")))
        check("上报次数在累加", items2[0].get("report_count", 0) >= 2)

    status, _ = call("POST", "/api/app/report", {"device_id": "   "})
    check("空 device_id 被拒（400）", status == 400, f"实际 {status}")

    print("\n[5] 设置（邮件服务）")

    status, s1 = call("GET", "/api/admin/settings", token=token)
    check("读设置返回 200", status == 200, f"实际 {status}")
    values = (s1 or {}).get("values") or {}
    for key in ("mail_host", "mail_port", "mail_security", "mail_user",
                "mail_password", "mail_from", "mail_from_name"):
        check("设置含 " + key, key in values)

    original_host = values.get("mail_host", "")
    status, _ = call("PUT", "/api/admin/settings",
                     {"values": {"mail_host": "smtp.selfcheck.local"}}, token=token)
    check("写设置返回 200", status == 200, f"实际 {status}")
    status, s2 = call("GET", "/api/admin/settings", token=token)
    check("写入的值读得回来",
          ((s2 or {}).get("values") or {}).get("mail_host") == "smtp.selfcheck.local")

    call("PUT", "/api/admin/settings", {"values": {"mail_host": original_host}}, token=token)

    status, s3 = call("GET", "/api/admin/settings", token=token)
    check("SMTP 密码不会以明文回传",
          ((s3 or {}).get("values") or {}).get("mail_password") in ("", "__SET__"))

    print("\n[6] 测试发信（预期失败：尚未配置 SMTP）")

    status, t1 = call("POST", "/api/admin/mail/test", {"to": TEST_EMAIL}, token=token)
    check("测试发信接口可达", status == 200, f"实际 {status}")
    check("未配置时返回人话说明，而不是抛异常",
          isinstance(t1, dict) and t1.get("ok") is False and "邮件服务" in str(t1.get("message", "")))

    print("\n[7] 账号：注册 / 登录 / 资料 / 找回密码（开发文档 §19）")

    reg_nick = "自检用户"
    reg_pwd = "selfcheck123"

    status, r1 = call("POST", "/api/v1/auth/register", {"nickname": reg_nick, "password": reg_pwd})
    check("注册返回 200", status == 200, f"实际 {status} {r1}")
    uid = (r1 or {}).get("uid")
    check("注册返回**数字** uid（不是随机串）", bool(uid) and str(uid).isdigit(), str(uid))
    check("注册返回 access 令牌", bool((r1 or {}).get("token")))
    check("注册返回 refresh 令牌", bool((r1 or {}).get("refresh")))
    new_user = (r1 or {}).get("user") or {}
    check("用户资料含 uid 与昵称",
          new_user.get("uid") == str(uid) and new_user.get("nickname") == reg_nick)
    check("用户资料里没有密码字段", "password" not in str(new_user).lower())

    status, _ = call("POST", "/api/v1/auth/register", {"nickname": reg_nick, "password": "whatever1"})
    check("昵称重复被拒（409）", status == 409, f"实际 {status}")

    status, _ = call("POST", "/api/v1/auth/register", {"nickname": "另一个自检", "password": "123"})
    check("密码太短被拒（400）", status == 400, f"实际 {status}")

    status, l1 = call("POST", "/api/v1/auth/login", {"account": reg_nick, "password": reg_pwd})
    check("用昵称登录成功", status == 200, f"实际 {status}")
    check("昵称登录也返回 uid", str((l1 or {}).get("uid")) == str(uid))

    status, l2 = call("POST", "/api/v1/auth/login", {"account": str(uid), "password": reg_pwd})
    check("用 uid 登录成功", status == 200, f"实际 {status}")

    status, _ = call("POST", "/api/v1/auth/login", {"account": reg_nick, "password": "wrong-pass"})
    check("错密码 = 401", status == 401, f"实际 {status}")

    status, _ = call("POST", "/api/v1/auth/login", {"account": "根本不存在的人", "password": "x"})
    check("账号不存在时同样返回 401（不泄露账号是否存在）", status == 401, f"实际 {status}")

    user_token = (l1 or {}).get("token")
    status, me = call("GET", "/api/v1/me", token=user_token)
    check("令牌能读到自己", status == 200 and ((me or {}).get("user") or {}).get("uid") == str(uid))

    status, _ = call("GET", "/api/v1/me")
    check("不带令牌读资料 = 401", status == 401, f"实际 {status}")

    status, n1 = call("PUT", "/api/v1/user/nickname", {"nickname": "自检改名"}, token=user_token)
    check("改昵称成功", status == 200, f"实际 {status} {n1}")

    refresh = (l1 or {}).get("refresh")
    status, rf = call("POST", "/api/v1/auth/refresh", {"refresh": refresh})
    check("刷新令牌成功", status == 200, f"实际 {status}")
    check("刷新后拿到新的 access", bool((rf or {}).get("token")))
    status, _ = call("POST", "/api/v1/auth/refresh", {"refresh": refresh})
    check("旧 refresh 用一次即失效（防重放）", status == 401, f"实际 {status}")

    status, c1 = call("POST", "/api/v1/auth/password/send-code", {"email": TEST_EMAIL})
    check("找回密码的验证码接口可用", status == 200, f"实际 {status}")
    check("未配邮件时明确标记降级", (c1 or {}).get("degraded") is True)
    check("响应里不含验证码本身", "code" not in (c1 or {}))

    status, codes = call("GET", "/api/admin/mail/pending-codes", token=token)
    pending = (codes or {}).get("items") or []
    real_code = next((it.get("code", "") for it in pending if it.get("email") == TEST_EMAIL), "")
    check("后台能看到待用验证码（自用兜底通道）", len(real_code) == 6, real_code)

    if real_code:
        status, _ = call("POST", "/api/v1/auth/password/reset",
                         {"email": TEST_EMAIL, "code": real_code, "new_password": "newpass123"})
        check("未绑定邮箱就找回密码，给出明确错误（404）", status == 404, f"实际 {status}")

    status, _ = call("POST", "/api/v1/auth/logout", token=user_token)
    check("登出成功", status == 200, f"实际 {status}")
    status, _ = call("GET", "/api/v1/me", token=user_token)
    check("登出后旧令牌失效", status == 401, f"实际 {status}")

    print("\n[8] 公告增删改")

    status, a1 = call("POST", "/api/admin/announcements",
                      {"title": "自检公告", "body": "这条会在自检结束时删除",
                       "level": "info", "active": True},
                      token=token)
    check("新建公告返回 200", status == 200, f"实际 {status}")
    aid = (a1 or {}).get("id")

    status, a2 = call("GET", "/api/app/announcements")
    check("客户端能拉到这条公告",
          any(i.get("title") == "自检公告" for i in ((a2 or {}).get("items") or [])))

    if aid:
        call("PATCH", f"/api/admin/announcements/{aid}",
             {"title": "自检公告", "body": "x", "level": "info", "active": False}, token=token)
        status, a3 = call("GET", "/api/app/announcements")
        check("下线后客户端拉不到",
              not any(i.get("title") == "自检公告" for i in ((a3 or {}).get("items") or [])))

        status, _ = call("DELETE", f"/api/admin/announcements/{aid}", token=token)
        check("删除公告返回 200", status == 200, f"实际 {status}")

    status, _ = call("POST", "/api/admin/announcements", {"title": "   "}, token=token)
    check("空标题被拒（400）", status == 400, f"实际 {status}")

    print("\n[9] 操作日志")

    status, logs = call("GET", "/api/admin/audit?page=1&size=20", token=token)
    check("能读操作日志", status == 200, f"实际 {status}")
    actions = [i.get("action") for i in ((logs or {}).get("items") or [])]
    check("登录动作被记录", "login" in actions, str(actions))
    check("设置变更被记录", "settings_update" in actions, str(actions))
    check("公告操作被记录", "announcement_create" in actions, str(actions))

    print("\n[10] 清理自检痕迹")

    status, _ = call("DELETE", f"/api/admin/devices/{TEST_DEVICE}", token=token)
    check("测试设备已删除", status == 200, f"实际 {status}")

    status, devs3 = call("GET", "/api/admin/devices?q=" + TEST_DEVICE, token=token)
    check("确认删干净", len(((devs3 or {}).get("items") or [])) == 0)

print("\n" + "=" * 62)
print(f"通过 {_passed} 项，失败 {_failed} 项")
if _failures:
    print("\n失败项：")
    for f in _failures:
        print("  - " + f)
print("=" * 62 + "\n")

sys.exit(1 if _failed else 0)
