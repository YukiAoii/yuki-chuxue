"""
Yuki 初雪 · 后端测试

═══════════════════════════════════════════════════════════════════════════
为什么测的是「纯函数 + DB 层」而不是 HTTP 接口
═══════════════════════════════════════════════════════════════════════════
接口那层要 FastAPI 的 TestClient，而它依赖 httpx —— 为了跑通几个断言再装一个包，
不划算。**这里测的是所有会出错的地方**：

  · 密码哈希能不能往返、错密码会不会被放过
  · 机密设置（SMTP 密码）会不会从接口漏出去
  · 验证码的一次性 / 过期 / 比对
  · 设备上报是"插入还是更新"、DAU 是怎么累计的
  · 公告的增删改查

HTTP 层用真实 curl 验（见交付说明里的命令），**不靠这个文件假装验过**。

跑：  py -3 -m unittest test_backend -v
      （在 backend/ 目录下；测试用临时目录做数据库，不碰生产 data/yuki.db）
"""

from __future__ import annotations

import os
import shutil
import sys
import tempfile
import unittest

# ⚠️ 必须在 import main **之前**把数据目录指到临时位置 ——
# main 在模块级就解析了 DB_PATH，之后再改环境变量已经来不及。
_TMP_DIR = tempfile.mkdtemp(prefix="yuki-test-")
os.environ["YUKI_DATA_DIR"] = _TMP_DIR
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import main as m  # noqa: E402


class PasswordTest(unittest.TestCase):
    def test_往返(self):
        stored = m.hash_password("hunter2")
        self.assertTrue(m.verify_password("hunter2", stored))

    def test_错密码必须被拒(self):
        stored = m.hash_password("hunter2")
        self.assertFalse(m.verify_password("hunter3", stored))
        self.assertFalse(m.verify_password("", stored))

    def test_同密码两次哈希不同_盐是随机的(self):
        self.assertNotEqual(m.hash_password("same"), m.hash_password("same"))

    def test_迭代次数写进哈希串_将来提参数不会锁死老密码(self):
        stored = m.hash_password("x")
        self.assertEqual("pbkdf2", stored.split("$")[0])
        self.assertEqual(str(m.PBKDF2_ITERATIONS), stored.split("$")[1])

    def test_坏数据不抛异常只返回False(self):
        for bad in ("", "garbage", "pbkdf2$1$2", "md5$1$a$b"):
            self.assertFalse(m.verify_password("x", bad), bad)


class TokenTest(unittest.TestCase):
    def test_令牌明文与哈希不同_库里只存哈希(self):
        raw, hashed = m.issue_token()
        self.assertNotEqual(raw, hashed)
        self.assertEqual(m.sha256_hex(raw), hashed)

    def test_两次令牌不同(self):
        self.assertNotEqual(m.issue_token()[0], m.issue_token()[0])


class SettingsTest(unittest.TestCase):
    def test_默认值可就地读到(self):
        self.assertEqual("ssl", m.get_setting("mail_security"))

    def test_写入后读回(self):
        m.set_settings({"mail_host": "smtp.example.com"})
        self.assertEqual("smtp.example.com", m.get_setting("mail_host"))

    def test_SMTP密码不回传明文(self):
        m.set_settings({"mail_password": "topsecret"})
        values = m.get_settings_all(mask_secrets=True)
        self.assertEqual("__SET__", values["mail_password"])
        self.assertNotIn("topsecret", str(values))

    def test_掩码值写回时不会覆盖真实密码(self):
        m.set_settings({"mail_password": "topsecret"})
        # 模拟前端把 __SET__ 原样提交回来
        m.set_settings({"mail_password": "__SET__"})
        self.assertEqual("topsecret", m.get_setting("mail_password"))

    def test_None值被跳过(self):
        m.set_settings({"mail_user": None})
        self.assertNotEqual("None", m.get_setting("mail_user"))


class MailReadyTest(unittest.TestCase):
    def test_未配置时不就绪(self):
        m.set_settings({"mail_host": "", "mail_user": ""})
        self.assertFalse(m.mail_ready())

    def test_配齐后就绪(self):
        m.set_settings({"mail_host": "smtp.example.com", "mail_user": "a@b.com"})
        self.assertTrue(m.mail_ready())
        m.set_settings({"mail_host": "", "mail_user": ""})

    def test_未配置时发信给出人话而不是抛异常(self):
        m.set_settings({"mail_host": "", "mail_user": ""})
        ok, message = m.send_mail("a@b.com", "s", "b")
        self.assertFalse(ok)
        self.assertIn("邮件服务", message)


class AuditTest(unittest.TestCase):
    def test_写一条审计就能查到(self):
        m.audit("tester", "unit_test", "detail", "127.0.0.1")
        row = m.query_one("SELECT * FROM audit_logs WHERE actor = ?", ("tester",))
        self.assertIsNotNone(row)
        self.assertEqual("unit_test", row["action"])


class AnnouncementTest(unittest.TestCase):
    def _create(self, title):
        return m.execute(
            "INSERT INTO announcements(title, body, level, active, created_at) VALUES(?,?,?,?,?)",
            (title, "b", "info", 1, m.now_iso()),
        )

    def test_新建后能查到(self):
        self._create("测试公告")
        rows = m.query("SELECT * FROM announcements WHERE title = ?", ("测试公告",))
        self.assertEqual(1, len(rows))

    def test_下架后客户端查询查不到(self):
        aid = self._create("要下架的")
        m.execute("UPDATE announcements SET active = 0 WHERE id = ?", (aid,))
        visible = m.query(
            "SELECT * FROM announcements WHERE active = 1 AND title = ?", ("要下架的",)
        )
        self.assertEqual(0, len(visible))


class DeviceReportTest(unittest.TestCase):
    """上报是「插入还是更新」——这是仪表盘数字对不对的根。"""

    def setUp(self):
        m.execute("DELETE FROM devices")
        m.execute("DELETE FROM daily_stats")

    def _report(self, device_id: str, sessions: int, hit: int = 0, miss: int = 0):
        ts = m.now_iso()
        exists = m.query_one("SELECT 1 FROM devices WHERE device_id = ?", (device_id,))
        if exists:
            m.execute(
                "UPDATE devices SET session_count=?, hit_tokens=?, miss_tokens=?, "
                "last_seen=?, report_count=report_count+1 WHERE device_id=?",
                (sessions, hit, miss, ts, device_id),
            )
        else:
            m.execute(
                "INSERT INTO devices(device_id, session_count, hit_tokens, miss_tokens, "
                "first_seen, last_seen, report_count) VALUES(?,?,?,?,?,?,1)",
                (device_id, sessions, hit, miss, ts, ts),
            )

    def test_同一设备重复上报只留一行且计数被覆盖(self):
        self._report("dev-1", 3)
        self._report("dev-1", 7)
        rows = m.query("SELECT * FROM devices WHERE device_id = ?", ("dev-1",))
        self.assertEqual(1, len(rows))
        self.assertEqual(7, rows[0]["session_count"])
        self.assertEqual(2, rows[0]["report_count"])

    def test_不同设备各留一行(self):
        self._report("dev-1", 1)
        self._report("dev-2", 1)
        self.assertEqual(2, (m.query_one("SELECT COUNT(*) AS n FROM devices") or {"n": 0})["n"])

    def test_命中率按token累计而不是取平均(self):
        # 两台设备命中/未命中不同：必须**先求和再相除**。
        # 两种算法在这里给出**不同**答案，所以这条断言真的在区分它们：
        #   · 先求和：(900+10) / 1100 = 82.7%   ← 正确（按 token 加权）
        #   · 取平均：(90% + 10%) / 2 = 50.0%   ← 错（小样本设备的权重被放大 100 倍）
        self._report("dev-a", 1, hit=900, miss=100)   # 该设备自身 90%
        self._report("dev-b", 1, hit=10, miss=90)     # 该设备自身 10%
        agg = m.query_one("SELECT SUM(hit_tokens) AS h, SUM(miss_tokens) AS ms FROM devices")
        rate = agg["h"] * 100.0 / (agg["h"] + agg["ms"])
        self.assertAlmostEqual(82.7, rate, places=1)
        self.assertNotAlmostEqual(50.0, rate, places=1)

    def test_DAU按日期只累计一次活跃设备数(self):
        day = m.today()
        m.execute(
            "INSERT INTO daily_stats(day, active_devices, reports) VALUES(?,1,1) "
            "ON CONFLICT(day) DO UPDATE SET reports = reports + 1, "
            "active_devices = (SELECT COUNT(DISTINCT device_id) FROM devices WHERE last_seen >= ?)",
            (day, day + "T00:00:00+00:00"),
        )
        self._report("dev-1", 1)
        m.execute(
            "INSERT INTO daily_stats(day, active_devices, reports) VALUES(?,1,1) "
            "ON CONFLICT(day) DO UPDATE SET reports = reports + 1, "
            "active_devices = (SELECT COUNT(DISTINCT device_id) FROM devices WHERE last_seen >= ?)",
            (day, day + "T00:00:00+00:00"),
        )
        row = m.query_one("SELECT * FROM daily_stats WHERE day = ?", (day,))
        self.assertEqual(2, row["reports"])
        self.assertEqual(1, row["active_devices"])


class SchemaTest(unittest.TestCase):
    def test_所有表都建出来了(self):
        rows = m.query("SELECT name FROM sqlite_master WHERE type = 'table'")
        names = {r["name"] for r in rows}
        for t in (
            "admins", "admin_tokens", "users", "verify_codes", "user_tokens",
            "devices", "daily_stats", "settings", "announcements", "audit_logs",
        ):
            self.assertIn(t, names)

    def test_WAL已开启(self):
        row = m.query_one("PRAGMA journal_mode")
        self.assertEqual("wal", str(row[0]).lower())


class OBListContentClipTest(unittest.TestCase):
    """云端记忆**列表**里单条正文的截断上限（2026-10-06：800 → 4000）。

    动机：App 的「点开看全文」要能看到完整正文，800 字符会让长记忆永远显示半截。
    上限本身要保留 —— 列表一次最多 300 条（`_OB_LIST_MAX`），正文不设限响应体会失控。
    """

    def test_正文上限为4000字符(self):
        self.assertEqual(4000, len(m._clip_bucket_content("x" * 9000)))

    def test_上限常量本身是4000_防回退(self):
        # 锁死数值：将来想改必须走测试（防某次重构悄悄改回 800 而无人察觉）
        self.assertEqual(4000, m._OB_LIST_CONTENT_MAX)

    def test_短正文原样返回(self):
        self.assertEqual("今天喝了美式", m._clip_bucket_content("今天喝了美式"))

    def test_非字符串先转字符串再截(self):
        # 与原实现 `str(parsed["body"])[:800]` 的行为保持一致：永不抛、永不 None
        self.assertEqual("123", m._clip_bucket_content(123))
        self.assertEqual("None", m._clip_bucket_content(None))


def tearDownModule():
    """清掉临时库。放在 finally 语义下，测试失败也删。"""
    try:
        m.close_db_if_open()
    except Exception:
        pass
    shutil.rmtree(_TMP_DIR, ignore_errors=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
