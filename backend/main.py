"""
Yuki 初雪 · 后端服务（单进程 · 零外部服务依赖）

═══════════════════════════════════════════════════════════════════════════
这个后端是**可选增强**，不是 App 的前提
═══════════════════════════════════════════════════════════════════════════
客户端是 BYOK + 纯本地的：不连这个服务，App 的全部功能都照常可用。
它只提供四类"本地做不到"的东西：

  1. **公告 / 更新日志** —— 客户端启动时拉一次（离线时静默跳过）
  2. **账号**（邮箱验证码）—— 换设备时把资料带过去
  3. **设备与用量统计** —— 管理后台「仪表盘」的数据来源
  4. **邮件服务自助配置** —— 由你在后台填 SMTP，不用改代码

所以：**这里任何接口挂掉，都不该影响 App 的聊天、记忆、人设。**

═══════════════════════════════════════════════════════════════════════════
部署形态
═══════════════════════════════════════════════════════════════════════════
  · 监听 127.0.0.1:11445（**只对本机**，外网统一由 Nginx 的 /yuki/ 收口）
  · 数据：SQLite（WAL），文件在 backend/data/yuki.db
  · 依赖：fastapi + uvicorn + Pillow（Pillow 只用于上传时的图片三档压缩）
      —— 密码哈希用 hashlib、邮件用 smtplib、令牌用 secrets，都是标准库。
      不为一个自用后端再多背一份依赖。

启动：  py -3 -m uvicorn main:app --host 127.0.0.1 --port 11445
  或：  start_backend.bat
"""

from __future__ import annotations

import asyncio
import functools
import hashlib
import hmac
import io
import json
import logging
import difflib
import os
import re
import secrets
import shutil
import smtplib
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage
from email.utils import formataddr
from pathlib import Path, PurePosixPath
from typing import Any

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image, ImageOps
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

# ═══════════════════════════════════════════════════════════════════════════
# 配置
# ═══════════════════════════════════════════════════════════════════════════

BASE_DIR = Path(__file__).resolve().parent
# ⚠️ **只在一条版本记录都没有时兜底**（见文件末尾的 /api/site）。
#    有版本行时官网展示的是那张表里的 version_name —— 别再手动改这里了。
SITE_VERSION = "0.50.0"
STATIC_DIR = BASE_DIR / "static"
# 数据目录可被环境变量改写 —— 测试用临时目录跑，绝不碰生产的 yuki.db
DATA_DIR = Path(os.environ.get("YUKI_DATA_DIR", str(BASE_DIR / "data")))
DB_PATH = DATA_DIR / "yuki.db"

HOST = os.environ.get("YUKI_HOST", "127.0.0.1")
# ⚠️ 这是**内部**端口：外网永远不直接连它，统一由 Nginx 的 11445(HTTPS) 收口。
# 用 11446 而不是 11445，是为了把"对外端口"与"内部端口"分开 ——
# 否则 Nginx 与后端会抢同一个端口，起不来。
PORT = int(os.environ.get("YUKI_PORT", "11446"))

APP_NAME = "Yuki 初雪"
API_PREFIX = "/api"

# 账号接口按开发文档 §19 的路径（`/api/v1/...`）。
# 与后台自己的 `/api/admin/...` 分开：前者是 App 用的，后者只有你知道。
API_V1 = "/api/v1"

# 令牌有效期（天）。access 短、refresh 长：
# access 短一点，是偷了也不久；refresh 长一点，用户不用天天登录。
ACCESS_DAYS = 7
REFRESH_DAYS = 30

# 管理员令牌有效期（小时）。后台是低频操作，给足一天。
ADMIN_TOKEN_HOURS = 24
# 验证码有效期（分钟）
CODE_TTL_MINUTES = 10
# 同一邮箱两次请求验证码的最小间隔（秒）—— 防刷
CODE_RESEND_SECONDS = 60

# ── 密码格式（只在校验「设置密码」时用；登录绝不校验）──────────────────────
# 用户要求：只支持数字、英文大小写和符号；不支持容易被 SQL 注入的格式。
#
# ⚠️⚠️ 必须说清楚：本项目**所有 SQL 都是参数化查询**，密码里有没有引号与
#     SQL 注入**毫无关系**。这条白名单是**体验与一致性**要求（免得用户设出
#     自己都打不出来的怪字符），**不是**安全补丁 —— 别把它当成防住了注入，
#     那会让人在别处放松警惕。
#
# ⚠️ 登录路径**不做**格式校验：老用户的密码可能含白名单外的字符，
#     一校验就把他们挡在门外了。
PASSWORD_MIN = 8
PASSWORD_MAX = 64
# 用户 2026-10-02：「密码格式只支持数字和英文」—— 符号位留空。
# ⚠️ 只影响**设置新密码**；老密码里带符号的照样能登录（登录不跑这套校验）。
PASSWORD_SYMBOLS = ""
_PASSWORD_ALLOWED = frozenset(
    "abcdefghijklmnopqrstuvwxyz"
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "0123456789"
) | frozenset(PASSWORD_SYMBOLS)

# 待完成注册的占位行多久算过期（小时）：超过之后同一个昵称可被重新注册，
# 免得用户在第一屏中途退出就把昵称永久占住了。
PENDING_REGISTRATION_HOURS = 24
# 占位行的哨兵哈希。`verify_password` 要求 pbkdf2 前缀，非该前缀一律 False ——
# 这个账号在设密码之前**永远登录不进来**。
PENDING_PASSWORD_SENTINEL = "!"


def password_error(password: str) -> str | None:
    """密码格式校验：合规返回 None，否则返回给用户看的原因。

    ⚠️ 前端会做同一套预校验（体验更好），但**后端是权威** —— curl 绕不过去。
    """
    if len(password) < PASSWORD_MIN:
        return f"密码至少 {PASSWORD_MIN} 位"
    if len(password) > PASSWORD_MAX:
        return f"密码最多 {PASSWORD_MAX} 位"
    if any(c not in _PASSWORD_ALLOWED for c in password):
        return "密码只能用数字和英文大小写"
    # 符号已不允许，所以"两类字符"只可能是：小写字母 / 大写字母 / 数字 之间的组合
    kinds = sum((
        any(c.islower() for c in password),
        any(c.isupper() for c in password),
        any(c.isdigit() for c in password),
    ))
    if kinds < 2:
        return "密码里至少要有两类字符（小写字母 / 大写字母 / 数字）"
    return None


# ═══════════════════════════════════════════════════════════════════════════
# 数据库
# ═══════════════════════════════════════════════════════════════════════════

_conn: sqlite3.Connection | None = None
_db_lock = threading.RLock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS admins (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT NOT NULL UNIQUE,
    pwd_hash      TEXT NOT NULL,
    created_at    TEXT NOT NULL,
    last_login_at TEXT
);

CREATE TABLE IF NOT EXISTS admin_tokens (
    token_hash TEXT PRIMARY KEY,
    username   TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    created_at TEXT NOT NULL
);

-- ⚠️ 认证模型按开发文档 §19/§32：
--    · 注册只要 **昵称 + 密码**（零外部依赖，不需要邮件服务）
--    · **uid 是数字**（10001 起），它既是展示用的账号 ID，也是登录凭证
--    · 邮箱是**可选绑定**，绑了才能"找回密码"
--    这与"邮箱验证码直接注册"是两种模型；文档选的是这一种，我们照做。
CREATE TABLE IF NOT EXISTS users (
    id            TEXT PRIMARY KEY,           -- 内部主键（uuid），对外不暴露
    uid           INTEGER NOT NULL UNIQUE,    -- 数字 UID：登录凭证 + 我的页展示
    nickname      TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    avatar_url    TEXT,                       -- 全局头像（我的页显示）
    email         TEXT UNIQUE,                -- 可空：绑定后才有，用于找回密码
    created_at    TEXT NOT NULL,
    last_login_at TEXT,
    status        TEXT NOT NULL DEFAULT 'active'
);

CREATE TABLE IF NOT EXISTS verify_codes (
    email      TEXT PRIMARY KEY,
    code       TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    sent_at    TEXT NOT NULL,
    used       INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS user_tokens (
    token_hash TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    created_at TEXT NOT NULL
);

-- 「这个软件专属」的数据：设备与用量。客户端上报，不依赖账号。
CREATE TABLE IF NOT EXISTS devices (
    device_id       TEXT PRIMARY KEY,
    alias           TEXT NOT NULL DEFAULT '',
    model           TEXT NOT NULL DEFAULT '',
    android_version TEXT NOT NULL DEFAULT '',
    app_version     TEXT NOT NULL DEFAULT '',
    persona_count   INTEGER NOT NULL DEFAULT 0,
    session_count   INTEGER NOT NULL DEFAULT 0,
    message_count   INTEGER NOT NULL DEFAULT 0,
    memory_count    INTEGER NOT NULL DEFAULT 0,
    hit_tokens      INTEGER NOT NULL DEFAULT 0,
    miss_tokens     INTEGER NOT NULL DEFAULT 0,
    user_id         TEXT,
    first_seen      TEXT NOT NULL,
    last_seen       TEXT NOT NULL,
    report_count    INTEGER NOT NULL DEFAULT 0
);

-- 「用户 ↔ 设备」关联（v0.61.23 上报重写）。
--
-- 为什么单独一张表、而不是只用 devices.user_id：
--   · 一台设备可能换过账号（登录 A 又登 B）——单列只能记住"最近一个"；
--   · 一个账号可能有多台设备（换机、平板 + 手机）——那正是后台"这个用户在用哪些设备"要看的东西；
--   · 有了它，用户详情才能把"人"与"设备上的统计"真正连起来。
-- ⚠️ 老版本客户端不带身份上报 → 只写 devices（本表不动），兼容不变。
CREATE TABLE IF NOT EXISTS user_devices (
    user_id     TEXT NOT NULL,
    device_id   TEXT NOT NULL,
    first_seen  TEXT NOT NULL,
    last_seen   TEXT NOT NULL,
    reports     INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, device_id)
);

CREATE TABLE IF NOT EXISTS daily_stats (
    day            TEXT PRIMARY KEY,
    active_devices INTEGER NOT NULL DEFAULT 0,
    reports        INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT
);

CREATE TABLE IF NOT EXISTS announcements (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    title      TEXT NOT NULL,
    body       TEXT NOT NULL DEFAULT '',
    level      TEXT NOT NULL DEFAULT 'info',
    active     INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL
);

-- 版本发布表（v0.50.0）：应用内更新的唯一事实来源。
--
-- ⚠️ 为什么是**新表**而不是给 settings / announcements 加列：
-- 本文件的建表只跑 `CREATE TABLE IF NOT EXISTS`（见 db()），**没有 ALTER TABLE、
-- 没有迁移系统**。给一张**已存在**的表加列，在用户那台机器的库上**根本不会生效**，
-- 而且不报错 —— 那是最难查的一类故障。新表则相反：不存在就建，零风险。
--
-- version_code 作**主键**：一个 versionCode 一行，天然去重（重复上报同一个版本
-- 只会覆盖那一行，不会堆出两条互相矛盾的记录）。
CREATE TABLE IF NOT EXISTS app_releases (
    version_code       INTEGER PRIMARY KEY,
    version_name       TEXT NOT NULL,
    -- 更新日志：给用户看的那几行。纯文本，客户端按行渲染。
    notes              TEXT NOT NULL DEFAULT '',
    -- 发布方显式标记的强制更新（"这一版必须升"）
    force              INTEGER NOT NULL DEFAULT 0,
    -- 低于这个 versionCode 一律不可用（"旧版已不再支持"）。
    -- 与 force 的区别：force 针对**这一版**，min_supported_code 一次配置覆盖**所有旧版**。
    min_supported_code INTEGER NOT NULL DEFAULT 0,
    -- ⚠️ apk_url 里带**内容指纹**：`/d/yuki.apk?h=<sha256 前 16 位>`。
    --    **只有上传接口会写它**；发布接口写空串，表示"这一版还没有包"。
    --    对外宣称"有更新"之前会核对指纹与磁盘文件是否一致，
    --    理由与后果见下面的 apk_serve_state()。
    apk_url            TEXT NOT NULL DEFAULT '',
    apk_size           INTEGER NOT NULL DEFAULT 0,
    published_at       TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    actor      TEXT NOT NULL,
    action     TEXT NOT NULL,
    detail     TEXT NOT NULL DEFAULT '',
    ip         TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL
);

-- 管理员密码重置码（v0.50.5）。
--
-- 为什么需要它：管理员密码是**首次启动随机生成、只打印一次**的（见 bootstrap_admin），
-- 忘了就再也找不回来（PBKDF2 不可逆）。以前唯一的出路是删库重建 ——
-- 那会连带抹掉**全部用户、设备、版本记录**，代价离谱。
--
-- ⚠️ 存的是 **code_hash**（PBKDF2），不是明文码：
--    这个库如果被拖走，明文码等于一把现成的钥匙。
-- ⚠️ `tries` 是**猜错次数**，到上限就作废 —— 6 位数字只有 100 万种，
--    不限制的话离线暴力几秒就破了（虽然在线接口另有 IP 限流兜底）。
CREATE TABLE IF NOT EXISTS admin_reset_codes (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    code_hash  TEXT NOT NULL,
    to_email   TEXT NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    used       INTEGER NOT NULL DEFAULT 0,
    tries      INTEGER NOT NULL DEFAULT 0
);

-- 官网 / App 的反馈（v0.57.0）。
--
-- ⚠️ 为什么自建而不是等别处：官网的反馈表单要**立刻可用**，不能依赖
--    另一个进程先部署好。字段按用户定的口径：内容（必填）+ 联系方式（可选）。
-- ⚠️ `ip` 只为**限流**（同 IP 每分钟若干次）与排查，不外显。
CREATE TABLE IF NOT EXISTS feedback (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    kind       TEXT NOT NULL DEFAULT 'other',
    content    TEXT NOT NULL,
    contact    TEXT NOT NULL DEFAULT '',
    ip         TEXT NOT NULL DEFAULT '',
    ua         TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    -- v0.58.0：后台可以回复（用户要求「后端必须有能回复反馈的功能」）。
    -- ⚠️ 加列之前这里是**只写不读**的：反馈进了库，但后台页没有这一页，
    --    除了直接查 sqlite 谁也看不到 —— 那不叫反馈功能，那叫回收站。
    reply      TEXT NOT NULL DEFAULT '',
    replied_at TEXT NOT NULL DEFAULT '',
    -- 登录用户留 id（没登录是空串）。客户端要认出"哪几条是我的、回了没有"，只能靠它。
    user_id    TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen DESC);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_users_created ON users(created_at DESC);

-- ── 人设市场（开发文档 §11.2 数据模型 / §19.4 接口）────────────────────────
--
-- ⚠️ 为什么是**新建表**而不是复用 users/devices：市场是独立领域，与账号/统计没有
--    外键耦合；把它塞进已有表只会重演文件顶部警告过的那个坑 ——
--    给一张**已存在**的表加列，`CREATE TABLE IF NOT EXISTS` 根本不会执行，
--    在用户那台机器的库上静默失效。新表则相反：不存在就建，零风险。
--
-- ⚠️ id 用 TEXT（`p_` / `c_` 前缀 + 随机 hex）而非自增整数：文档 §11.2 的客户端
--    实体就是 `@PrimaryKey val id: String`，两端 id 同型 —— 客户端拿服务端的 id
--    可以直接塞进本地 Room，不需要再做一次 id 映射。
-- ⚠️⚠️ 【功能已于 2026-10-01 下线】
--     人设市场的全部路由与读写函数都已删除（`/api/v1/market/*`、`_mkt_*`、
--     `MarketPersonaIn` …）。但这三张表**刻意保留、没有 DROP**：
--     **删功能不等于删数据** —— 留着可逆，也不占什么资源。
--     恢复指引见 `docs/archive/人设市场_下线说明.md`。
CREATE TABLE IF NOT EXISTS market_personas (
    id                TEXT PRIMARY KEY,
    author_id         TEXT NOT NULL,
    -- 作者昵称/头像在**写入那一刻**从 users 表快照一份。
    -- ⚠️ 这是**快照**：作者以后改昵称，历史上传不会跟着变 —— 与 §11.2 客户端实体
    --    冗余存了这两个字段的口径一致。作者身份只认服务端，绝不接受客户端传入。
    author_nickname   TEXT NOT NULL DEFAULT '',
    author_avatar_url TEXT,
    title             TEXT NOT NULL,
    thumbnail_url     TEXT NOT NULL DEFAULT '',
    -- 小红书式多图（v0.58.0）：**相对** URL 的 JSON 数组，如 ["/uploads/a.png", ...]。
    -- ⚠️ 线上那张市场表**已经存在**，`CREATE TABLE IF NOT EXISTS` 不给它补这一列 ——
    --    老库必须走 `_migrate_market_columns` 的 ALTER。老数据默认 '[]' → 空数组。
    images            TEXT NOT NULL DEFAULT '[]',
    description       TEXT NOT NULL DEFAULT '',
    -- 0/1 布尔（文档 DAO 用 `WHERE isPublic = 1` 筛选）
    is_public         INTEGER NOT NULL DEFAULT 1,
    -- 完整人设正文；is_public=0 时**不下发**（见详情接口 §11.1 的"下载后才可见"规则）
    full_content      TEXT,
    view_count        INTEGER NOT NULL DEFAULT 0,
    like_count        INTEGER NOT NULL DEFAULT 0,
    comment_count     INTEGER NOT NULL DEFAULT 0,
    -- ISO(TEXT)：与全库既有时间戳同型（now_iso 的产物）。市场条用**微秒**精度写入，避免同秒并列。
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS market_comments (
    id                TEXT PRIMARY KEY,
    persona_id        TEXT NOT NULL,
    author_id         TEXT NOT NULL,
    author_nickname   TEXT NOT NULL DEFAULT '',
    author_avatar_url TEXT,
    -- 为空 = 主楼；非空 = 回复（只允许一级，见评论接口）
    parent_id         TEXT,
    content           TEXT NOT NULL,
    like_count        INTEGER NOT NULL DEFAULT 0,
    created_at        TEXT NOT NULL
);

-- 点赞表用**联合主键**天然去重：同一个人对同一人设只可能有一行 ——
-- 于是"取消点赞"就是 DELETE、"是否点过"就是 SELECT 1，不需要额外的唯一索引。
CREATE TABLE IF NOT EXISTS market_likes (
    persona_id TEXT NOT NULL,
    user_id    TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (persona_id, user_id)
);

CREATE TABLE IF NOT EXISTS comment_likes (
    comment_id TEXT NOT NULL,
    user_id    TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (comment_id, user_id)
);

-- ⚠️ 为什么服务端看不到明文：密钥在客户端由用户密码派生（PBKDF2）并加密后上传，
--    **密钥从不离开设备**。这里存的 data 是一团再也读不懂的 base64。
--    「聊天记录 / 记忆」永不经过这张表，也不会经过任何接口（红线）。
--    kind 预留：将来若要同步别的密文块，不必再开表。
CREATE TABLE IF NOT EXISTS user_blobs (
    user_id    TEXT NOT NULL,
    kind       TEXT NOT NULL,
    data       TEXT NOT NULL,
    rev        INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (user_id, kind)
);

-- ⚠️ 人设↔账号 归属注册表（2026-10-06 · 心潮多租户）：
--    服务端对人设内容一无所知（E2E 密文，无法反查 personaId→user），归属改由
--    **首次上报登记**建立：App 上报事件 / 显式 register 时写一行，此后写入即锁定。
--    别的账号再碰同一 persona_id 一律 404（与"未登记"不可区分，不给探测空间）。
--    persona_name 是可选展示名（明文，服务器可见 —— 由 App 注册时上报）。
CREATE TABLE IF NOT EXISTS persona_owners (
    persona_id   TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL,
    persona_name TEXT NOT NULL DEFAULT '',
    created_at   TEXT NOT NULL,
    last_seen_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_persona_owners_user ON persona_owners(user_id);

CREATE INDEX IF NOT EXISTS idx_market_personas_public  ON market_personas(is_public, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_market_comments_persona ON market_comments(persona_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_market_likes_persona    ON market_likes(persona_id);
CREATE INDEX IF NOT EXISTS idx_comment_likes_comment   ON comment_likes(comment_id);
"""


def db() -> sqlite3.Connection:
    """取连接（懒初始化，进程内单连接 + 锁）。

    自用后端的并发量以"个位数请求/秒"计，**一个连接加一把锁**比连接池更简单，
    也彻底避开 SQLite 的跨线程问题（`check_same_thread=False` + 我们自己加锁）。
    """
    global _conn
    if _conn is None:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        _conn = sqlite3.connect(DB_PATH, check_same_thread=False)
        _conn.row_factory = sqlite3.Row
        # WAL：读写不互斥，进程被杀也不会留下半截事务
        _conn.execute("PRAGMA journal_mode=WAL")
        _conn.execute("PRAGMA synchronous=NORMAL")
        _conn.executescript(SCHEMA)
        # ⚠️ 线上那张 feedback 表是**已经存在**的 —— `CREATE TABLE IF NOT EXISTS`
        #    对已存在的表**一个列都不会补**。上面 DDL 里新加的 reply/replied_at/user_id
        #    只对全新库生效，所以老库必须走 ALTER（幂等，每次启动跑一遍没代价）。
        #    漏了这一步的后果很典型：**本地新库一切正常、线上响应回复就 500**。
        _migrate_feedback_columns(_conn)
        _migrate_market_columns(_conn)
        _conn.commit()
    return _conn


def _migrate_feedback_columns(conn: sqlite3.Connection) -> None:
    """给**已存在**的 feedback 表补上 v0.58.0 的三列（幂等）。

    ⚠️ 为什么必须有：`CREATE TABLE IF NOT EXISTS` 对已存在的表不补列。
       线上那张表是上一版建的，没有 reply / replied_at / user_id ——
       只改 DDL 的话，**本地新建库一切正常，线上回复一调就 500**，
       而且是要等真有人回复才发现。这类"只在老库上出现"的故障，用 ALTER 一次性挡掉。
    """
    have = {row["name"] for row in conn.execute("PRAGMA table_info(feedback)")}
    for name, ddl in (
        ("reply", "TEXT NOT NULL DEFAULT ''"),
        ("replied_at", "TEXT NOT NULL DEFAULT ''"),
        ("user_id", "TEXT NOT NULL DEFAULT ''"),
    ):
        if name not in have:
            conn.execute(f"ALTER TABLE feedback ADD COLUMN {name} {ddl}")


def _migrate_market_columns(conn: sqlite3.Connection) -> None:
    """给**已存在**的 market_personas 表补上 v0.58.0 的 images 列（幂等）。

    ⚠️ 与 `_migrate_feedback_columns` 是同一条理由：`CREATE TABLE IF NOT EXISTS`
       对已存在的表**一个列都不会补**。线上那张市场表是上一版建的，
       只改 DDL 不改这里的话 —— **本地新建库一切正常，线上创建人设一调就 500**
       （INSERT 会带上 images 列，而老表根本没有这一列）。用 ALTER 一次性挡掉。
    """
    have = {row["name"] for row in conn.execute("PRAGMA table_info(market_personas)")}
    if "images" not in have:
        conn.execute("ALTER TABLE market_personas ADD COLUMN images TEXT NOT NULL DEFAULT '[]'")


def close_db_if_open() -> None:
    """关连接。生产由 lifespan 收尾；测试直接调它（否则临时目录删不掉 —— Windows 上文件被占用）。"""
    global _conn
    with _db_lock:
        if _conn is not None:
            _conn.commit()
            _conn.close()
            _conn = None


def query(sql: str, args: tuple = ()) -> list[sqlite3.Row]:
    with _db_lock:
        return db().execute(sql, args).fetchall()


def query_one(sql: str, args: tuple = ()) -> sqlite3.Row | None:
    rows = query(sql, args)
    return rows[0] if rows else None


def execute(sql: str, args: tuple = ()) -> int:
    """执行写操作，返回 lastrowid。"""
    with _db_lock:
        conn = db()
        cur = conn.execute(sql, args)
        conn.commit()
        return cur.lastrowid or 0


def _console(text: str) -> None:
    """往控制台打印，**编码编不出来也不许抛**。

    ⚠️ 这个函数是被一次真实的启动失败逼出来的：
    启动横幅里有个 `⚠`（U+26A0），而 Windows 控制台是 GBK 码页 ——
    `print` 直接抛 UnicodeEncodeError，**把整个服务打挂了**。
    一行装饰性日志让后端起不来，是极不划算的失败模式。

    所以：编不出来就替换成 `?`，绝不让日志影响服务本身。
    """
    try:
        print(text, flush=True)
    except UnicodeEncodeError:
        enc = getattr(sys.stdout, "encoding", None) or "utf-8"
        print(text.encode(enc, errors="replace").decode(enc, errors="replace"), flush=True)


def now_iso() -> str:
    """UTC 时间的 ISO 串（带时区）。

    ⚠️ 用 `datetime.now(timezone.utc)` 而不是已废弃的 `utcnow()` ——
    后者在 3.12+ 会告警，且返回的是"裸时间"（没有时区），
    一旦将来要跨时区显示就会算错。
    """
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def today() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def parse_iso(s: str) -> datetime:
    dt = datetime.fromisoformat(s)
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


# ═══════════════════════════════════════════════════════════════════════════
# 密码与令牌
# ═══════════════════════════════════════════════════════════════════════════

PBKDF2_ITERATIONS = 120_000


def hash_password(password: str, salt: str | None = None) -> str:
    """PBKDF2-HMAC-SHA256。

    存成 `pbkdf2$迭代次数$盐$摘要` —— 把参数**写进哈希串本身**，
    将来提高迭代次数时老密码仍能验证（否则一改参数全体用户登不上）。
    """
    salt = salt or secrets.token_hex(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), PBKDF2_ITERATIONS)
    return f"pbkdf2${PBKDF2_ITERATIONS}${salt}${dk.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        scheme, iters, salt, digest = stored.split("$")
        if scheme != "pbkdf2":
            return False
        dk = hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), int(iters))
        # 定长比较，避免计时侧信道
        return hmac.compare_digest(dk.hex(), digest)
    except Exception:
        return False


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def issue_token() -> tuple[str, str]:
    """返回 (明文令牌, 哈希)。**库只存哈希** —— 拖库也拿不到可用的令牌。"""
    raw = secrets.token_urlsafe(32)
    return raw, sha256_hex(raw)


# ═══════════════════════════════════════════════════════════════════════════
# 设置（键值表）—— 邮件配置就住在这里，由后台自助填写
# ═══════════════════════════════════════════════════════════════════════════

DEFAULT_SETTINGS: dict[str, str] = {
    # 邮件服务（用户自己在后台填）
    "mail_host": "",
    "mail_port": "465",
    "mail_security": "ssl",          # ssl | starttls | none
    "mail_user": "",
    "mail_password": "",
    "mail_from": "",
    "mail_from_name": APP_NAME,
    # 客户端行为开关
    "allow_register": "1",
    "announce_client_poll": "1",
}

SECRET_SETTING_KEYS = {"mail_password"}


def get_setting(key: str, default: str = "") -> str:
    row = query_one("SELECT value FROM settings WHERE key = ?", (key,))
    if row is not None:
        return row["value"]
    if key in DEFAULT_SETTINGS:
        return DEFAULT_SETTINGS[key]
    return default


def get_settings_all(mask_secrets: bool = True) -> dict[str, str]:
    out = dict(DEFAULT_SETTINGS)
    for row in query("SELECT key, value FROM settings"):
        out[row["key"]] = row["value"]
    if mask_secrets:
        for k in SECRET_SETTING_KEYS:
            if out.get(k):
                # 不回传明文；用固定标记表示"已配置"
                out[k] = "__SET__"
    return out


def set_settings(values: dict[str, Any]) -> int:
    """批量写设置。**值为 None 或 "__SET__" 的跳过后台掩码值**（避免把掩码写回库）。"""
    n = 0
    for k, v in values.items():
        if v is None:
            continue
        v = str(v)
        if k in SECRET_SETTING_KEYS and v == "__SET__":
            continue   # 前端没改密码，原样保留
        execute(
            "INSERT INTO settings(key, value) VALUES(?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (k, v),
        )
        n += 1
    return n


def audit(actor: str, action: str, detail: str = "", ip: str = "") -> None:
    execute(
        "INSERT INTO audit_logs(actor, action, detail, ip, created_at) VALUES(?,?,?,?,?)",
        (actor, action, detail, ip, now_iso()),
    )


# ═══════════════════════════════════════════════════════════════════════════
# 邮件
# ═══════════════════════════════════════════════════════════════════════════

def mail_ready() -> bool:
    return bool(get_setting("mail_host") and get_setting("mail_user"))


def _mail_html(subject: str, body: str) -> str:
    """把纯文本正文渲染成**排版好的 HTML 邮件**（v0.61.24.4）。

    ## 为什么放在引擎层、而不是逐封写模板
    逐封写模板要改三个调用点，且**将来新增的邮件会忘记带 HTML**。
    这里统一生成：调用方照旧只给纯文本，HTML 版**自动**产出。

    ## 两条硬约束
    · **邮件客户端一律禁用 JavaScript**（Gmail / QQ / Outlook 全都禁）——
      所以这里只有排版，**任何"交互"都做不了**。这是行业限制。
    · 样式必须**内联**：邮件客户端会剥掉 `<style>` 块与外部 CSS。
    """
    import html as _html
    import re as _re

    lines = [ln.strip() for ln in (body or "").splitlines() if ln.strip()]
    # 认出「验证码 / 重置码：XXXX」那一行 → 单独放大展示（其余行照常排）
    # ⚠️ 真实正文写的是「你的验证码**是**：8421」——"验证码"和冒号之间会夹一两个字，
    #    所以允许 0~3 个非冒号字符（否则那行认不出来、验证码就不会被放大）。
    code_re = _re.compile(r".*(?:验证码|重置码)[^：:\s]{0,3}[：:]\s*(\S+)\s*$")
    code, rows = "", []
    for ln in lines:
        m = code_re.match(ln)
        if m and not code:
            code = m.group(1)
            continue
        rows.append(
            '<p style="margin:0 0 10px;font-size:14px;line-height:1.75;color:#3b4250;">'
            f"{_html.escape(ln)}</p>"
        )

    code_block = (
        '<div style="margin:20px 0 24px;padding:18px 0;text-align:center;'
        'background:#f2f5fb;border-radius:12px;">'
        '<div style="font-size:12px;color:#8a91a0;margin-bottom:8px;">本次验证码</div>'
        f'<div style="font-size:30px;font-weight:700;letter-spacing:6px;color:#1B4FD8;">{_html.escape(code)}</div>'
        "</div>"
        if code
        else ""
    )

    return (
        '<!DOCTYPE html><html><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1"></head>'
        '<body style="margin:0;padding:24px 12px;background:#f5f7fa;'
        "font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;\">"
        '<div style="max-width:480px;margin:0 auto;background:#ffffff;border-radius:16px;'
        'overflow:hidden;box-shadow:0 2px 10px rgba(27,79,216,0.06);">'
        '<div style="padding:20px 26px;background:#2F6BFF;">'
        '<div style="font-size:17px;font-weight:600;color:#ffffff;letter-spacing:1px;">Yuki 初雪</div>'
        f'<div style="font-size:12px;color:#dce6ff;margin-top:3px;">{_html.escape(subject)}</div>'
        "</div>"
        f'<div style="padding:24px 26px 10px;">{code_block}{"".join(rows)}</div>'
        '<div style="padding:14px 26px 20px;border-top:1px solid #eceff4;">'
        '<div style="font-size:11.5px;color:#9aa1ae;line-height:1.7;">'
        "这封邮件由系统自动发出，请不要直接回复。</div></div>"
        "</div></body></html>"
    )


def send_mail(to: str, subject: str, body: str, html: str | None = None) -> tuple[bool, str]:
    """发一封信。

    返回 (成功?, 说明)。**失败必须带人话说明** —— 后台的「测试发信」按钮
    就是靠这句话告诉用户"是密码错了还是端口不通"。
    """
    host = get_setting("mail_host")
    if not host:
        return False, "邮件服务尚未配置：请到「邮件服务」页填写 SMTP 服务器"
    port = int(get_setting("mail_port") or "465")
    security = get_setting("mail_security") or "ssl"
    user = get_setting("mail_user")
    password = get_setting("mail_password")
    sender = get_setting("mail_from") or user
    from_name = get_setting("mail_from_name") or APP_NAME

    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = formataddr((from_name, sender))
    msg["To"] = to
    # 纯文本兜底（纯文本阅读器 / 老客户端）+ HTML 版（排版美化）。
    # ⚠️ v0.61.24.4：HTML **不传就自动由正文生成** —— 三个调用点一行都不用改，
    #    将来新增的邮件也自动享有（见 _mail_html 的注释）。
    msg.set_content(body)
    msg.add_alternative(html if html is not None else _mail_html(subject, body), subtype="html")

    try:
        if security == "ssl":
            with smtplib.SMTP_SSL(host, port, timeout=15) as s:
                if user:
                    s.login(user, password)
                s.send_message(msg)
        else:
            with smtplib.SMTP(host, port, timeout=15) as s:
                if security == "starttls":
                    s.starttls()
                if user:
                    s.login(user, password)
                s.send_message(msg)
        return True, "已发送"
    except smtplib.SMTPAuthenticationError as e:
        return False, f"SMTP 认证失败（账号或授权码不对）：{e}"
    except smtplib.SMTPConnectError as e:
        return False, f"连不上 SMTP 服务器（检查地址/端口/防火墙）：{e}"
    except Exception as e:
        return False, f"发送失败：{type(e).__name__}: {e}"


# ═══════════════════════════════════════════════════════════════════════════
# 认证依赖
# ═══════════════════════════════════════════════════════════════════════════

def client_ip(request: Request) -> str:
    fwd = request.headers.get("x-forwarded-for", "")
    if fwd:
        return fwd.split(",")[0].strip()
    return request.client.host if request.client else ""


def require_admin(
    request: Request,
    authorization: str = Header(default=""),
) -> str:
    """校验管理员令牌，返回用户名。

    ⚠️ 这里用 `Request`（不带默认值）而不是 `Request = None` ——
    FastAPI 遇到后者会把它当成**查询参数**，导致整个路由注册失败。
    """
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(status_code=401, detail="未登录")
    row = query_one(
        "SELECT username, expires_at FROM admin_tokens WHERE token_hash = ?",
        (sha256_hex(token),),
    )
    if row is None:
        raise HTTPException(status_code=401, detail="登录已失效，请重新登录")
    if parse_iso(row["expires_at"]) < datetime.now(timezone.utc):
        execute("DELETE FROM admin_tokens WHERE token_hash = ?", (sha256_hex(token),))
        raise HTTPException(status_code=401, detail="登录已过期，请重新登录")
    return row["username"]


def _auth_row(authorization: str) -> sqlite3.Row:
    """令牌 → (user_id, status)。一次 JOIN 查完，不给每个鉴权请求多加一次查询。"""
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(status_code=401, detail="未登录")
    row = query_one(
        "SELECT t.user_id, t.expires_at, u.status "
        "FROM user_tokens t JOIN users u ON u.id = t.user_id "
        "WHERE t.token_hash = ?",
        (sha256_hex(token),),
    )
    if row is None or parse_iso(row["expires_at"]) < datetime.now(timezone.utc):
        raise HTTPException(status_code=401, detail="登录已失效")
    return row


def _user_id_from_optional_token(raw: str) -> str | None:
    """上报里的**可选** token → user_id（v0.61.23 上报重写）。

    与 `require_user` 的区别：它**不抛**。上报是统计 —— token 过期 / 伪造时应"按匿名记"，
    而不是让整条数据丢掉；老版本客户端更是根本不带它（见 `ReportIn`）。
    """
    token = (raw or "").strip()
    if not token:
        return None
    row = query_one(
        "SELECT user_id, expires_at FROM user_tokens WHERE token_hash = ?",
        (sha256_hex(token),),
    )
    if row is None or parse_iso(row["expires_at"]) < datetime.now(timezone.utc):
        return None
    return row["user_id"]


def require_user(
    authorization: str = Header(default=""),
) -> str:
    """需要**已激活**账号的接口用它。

    ⚠️ 注册到一半的占位账号（`status='pending'`）会被挡在这里 ——
       否则一个还没设密码的账号就能传图、改资料。
    """
    row = _auth_row(authorization)
    if row["status"] != "active":
        raise HTTPException(status_code=401, detail="注册还没完成，请先设置密码")
    return row["user_id"]



def require_pending_user(
    authorization: str = Header(default=""),
) -> str:
    """**只认**注册到一半的占位账号 —— `register/finish` 专用。"""
    row = _auth_row(authorization)
    if row["status"] != "pending":
        raise HTTPException(status_code=401, detail="这个账号的注册已经完成了，直接登录就好")
    return row["user_id"]




# ═══════════════════════════════════════════════════════════════════════════
# 请求模型
# ═══════════════════════════════════════════════════════════════════════════

class LoginIn(BaseModel):
    username: str
    password: str


class SettingsIn(BaseModel):
    values: dict[str, Any] = Field(default_factory=dict)


class TestMailIn(BaseModel):
    to: str


class AnnouncementIn(BaseModel):
    title: str
    body: str = ""
    level: str = "info"
    active: bool = True


class ReleaseIn(BaseModel):
    """发布一个版本（v0.50.0）。

    ⚠️ `version_code` 必填且**由发布方手动填** —— 不从 `version_name` 解析。
    "3.10.0" 和 "3.9.0" 按字符串比会得出 3.10 < 3.9，而按语义相反；
    版本比较**只认整数 code**，`version_name` 仅用于展示。
    """
    version_code: int
    version_name: str
    notes: str = ""
    force: bool = False
    min_supported_code: int = 0
    # 留空则由上传接口（/admin/releases/apk）回填实际 URL 与大小
    apk_url: str = ""


class ReleasePatchIn(BaseModel):
    """改一个**已发布**版本的元数据（v0.61.21 补）。

    ## ⚠️ 为什么必须单独一个模型，不能复用 ReleaseIn
    `version_code` 在 PATCH 里是**路径参数**（`/admin/releases/{code}`），
    请求体里根本没有它 —— 而 [ReleaseIn] 把它标成了必填。
    于是前端「编辑 → 保存」**每次都被 422 拒掉**，界面上表现为"点了没反应"：
    用户 2026-10-04 报的「编辑历史更新日志点确定没有效果」就是它。

    ⚠️ 这里刻意**不收 `version_code`**：改版本号不是这个接口的事 ——
        要改号就是另一条版本（新建 + 删旧的），不该悄悄把主键挪掉。
    """
    version_name: str
    notes: str = ""
    force: bool = False
    min_supported_code: int = 0


class UserPatchIn(BaseModel):
    nickname: str | None = None
    status: str | None = None


class DevicePatchIn(BaseModel):
    alias: str | None = None
    user_id: str | None = None


class ReportIn(BaseModel):
    """客户端上报的软件专属数据。

    ⚠️ v0.61.23：新增**可选** `token` —— 带上就把这台设备与账号关联
    （后台据此显示"某用户在用哪些设备"）；**老版本客户端不带它，一切照旧**
    （按匿名设备记）。空 / 无效 / 过期的 token 一律**不报错** ——
    统计不该因为登录态失效而整条丢掉。
    """

    device_id: str
    # 可选：登录态 access token。空/无效/过期 → 按匿名设备处理（不当错误）
    token: str = ""
    model: str = ""
    android_version: str = ""
    app_version: str = ""
    persona_count: int = 0
    session_count: int = 0
    message_count: int = 0
    memory_count: int = 0
    hit_tokens: int = 0
    miss_tokens: int = 0


# ── 认证（开发文档 §19 / §32）──

class RegisterIn(BaseModel):
    nickname: str
    password: str
    avatar_url: str | None = None


class UserLoginIn(BaseModel):
    """登录。

    `account` 可以是**数字 UID**（如 "10001"）或**昵称** —— 文档的登录页两种都接受
    （用户记不住 uid 时用昵称；昵称重名时用 uid）。
    """
    account: str
    password: str


class RefreshIn(BaseModel):
    refresh: str = ""


class EmailSendCodeIn(BaseModel):
    email: str = ""
    # v0.60.0：找回密码走 UID（见 PasswordResetIn 的注释）。注册那条路仍然只用 email。
    uid: str = ""


class EmailBindIn(BaseModel):
    email: str
    code: str


class PasswordResetIn(BaseModel):
    email: str = ""
    # v0.60.0：**找回密码改用 UID 定位**（用户 2026-10-02：「忘记密码的找回界面需要输入 UID」）。
    # 两个字段都留着：老版本客户端还在发 email，给了 uid 就以 uid 为准。
    uid: str = ""
    code: str
    new_password: str


class AdminForgotIn(BaseModel):
    """申请管理员密码重置码。"""
    #: 用来证明"你确实是这台服务器的管理员"——填**后台配置的发件邮箱**
    verify_email: str
    #: 重置码发到哪个邮箱（发给能收信的那一个）
    to_email: str


class AdminResetIn(BaseModel):
    """用重置码设新密码。"""
    code: str
    new_password: str


class ProfilePatchIn(BaseModel):
    nickname: str | None = None
    avatar_url: str | None = None



class PersonaBlobIn(BaseModel):
    """人设**密文**（端到端加密，服务端不解析内容）。

    rev 是客户端给这次快照打的时间戳 —— 用来做"谁更新"的判定，
    服务端只做**盲存**，不理解里面是什么。
    """
    blob: str
    rev: int = 0


class PasswordChangeIn(BaseModel):
    old_password: str
    new_password: str


# ═══════════════════════════════════════════════════════════════════════════
# 应用
# ═══════════════════════════════════════════════════════════════════════════

def bootstrap_admin() -> str | None:
    """首次启动创建一个管理员。

    密码优先取环境变量 `YUKI_ADMIN_PASSWORD`；没给就**随机生成并打到控制台**
    （绝不写死一个 admin/123456 —— 那等于给公网留了一扇门）。
    返回生成的明文密码（仅在本次生成时非 None）。
    """
    row = query_one("SELECT COUNT(*) AS n FROM admins")
    if row and row["n"] > 0:
        return None

    username = os.environ.get("YUKI_ADMIN_USER", "admin")
    password = os.environ.get("YUKI_ADMIN_PASSWORD") or secrets.token_urlsafe(12)
    execute(
        "INSERT INTO admins(username, pwd_hash, created_at) VALUES(?,?,?)",
        (username, hash_password(password), now_iso()),
    )
    return password


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 用 lifespan 而不是已废弃的 @app.on_event("startup")
    generated = bootstrap_admin()
    # 边框**一律用 ASCII**（= 与 -），不用 ═ / ─ / ⚠ 这类字符 ——
    # Windows 控制台是 GBK 码页，字符集外的字符会抛 UnicodeEncodeError。
    # 这里再走 _console() 兜一层：日志编码绝不该让服务起不来。
    banner = [
        "",
        "=" * 66,
        f"  {APP_NAME} - 后端已启动",
        f"  监听：   http://{HOST}:{PORT}",
        f"  数据库： {DB_PATH}",
        "-" * 66,
    ]
    if generated:
        banner += [
            "  [!] 首次启动，已创建管理员账号（请立即记下并妥善保存）：",
            f"      用户名：{os.environ.get('YUKI_ADMIN_USER', 'admin')}",
            f"      密  码：{generated}",
            "",
            "  这个密码只显示这一次；忘了只能改库或删 data/yuki.db 重建。",
            "-" * 66,
        ]
    if not mail_ready():
        banner += [
            "  邮件服务：未配置 —— 到后台「邮件服务」页填 SMTP 后即可自动发信。",
            "  未配置期间：注册验证码会显示在后台「邮件服务」页，可直接取用。",
            "-" * 66,
        ]
    banner.append("=" * 66)
    _console("\n".join(banner))

    yield

    global _conn
    with _db_lock:
        if _conn is not None:
            _conn.commit()
            _conn.close()
            _conn = None


app = FastAPI(title=f"{APP_NAME} 后端", version="1.0.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "ok": True,
        "app": APP_NAME,
        "time": now_iso(),
        "mail_ready": mail_ready(),
        "admin_ready": (query_one("SELECT COUNT(*) AS n FROM admins") or {"n": 0})["n"] > 0,
    }


# ═══════════════════════════════════════════════════════════════════════════
# 管理后台 API
# ═══════════════════════════════════════════════════════════════════════════

@app.post(f"{API_PREFIX}/admin/login")
def admin_login(body: LoginIn, request: Request) -> dict[str, Any]:
    row = query_one("SELECT * FROM admins WHERE username = ?", (body.username,))
    if row is None or not verify_password(body.password, row["pwd_hash"]):
        audit(body.username, "login_failed", ip=client_ip(request))
        raise HTTPException(status_code=401, detail="用户名或密码不对")

    raw, hashed = issue_token()
    expires = datetime.now(timezone.utc) + timedelta(hours=ADMIN_TOKEN_HOURS)
    execute(
        "INSERT INTO admin_tokens(token_hash, username, expires_at, created_at) VALUES(?,?,?,?)",
        (hashed, body.username, expires.isoformat(timespec="seconds"), now_iso()),
    )
    execute("UPDATE admins SET last_login_at = ? WHERE username = ?", (now_iso(), body.username))
    audit(body.username, "login", ip=client_ip(request))
    return {"token": raw, "username": body.username, "expires_at": expires.isoformat(timespec="seconds")}


# ═══════════════════════════════════════════════════════════════════════════
# 管理员密码找回（v0.50.5）
# ═══════════════════════════════════════════════════════════════════════════
#
# ## 为什么要有它
# 管理员密码是**首次启动随机生成、只打印一次**的（见 bootstrap_admin）。
# PBKDF2 不可逆，忘了就找不回。此前唯一的出路是删 data/yuki.db ——
# 那会把**全部用户、设备、版本记录**一起抹掉，代价完全不成比例。
#
# ## ⚠️ 验证因子是"后台配置的发件邮箱"，这是一个**弱秘密**
# 它出现在**每一封系统邮件的 From 头**里 —— 任何收到过注册验证码的人都知道它。
# 所以这道门必须配护栏，就是下面这三条：
#   ① 限流（防爆破；也防有人拿它当免费邮件轰炸机）
#   ② 重置码一次性 + 30 分钟时效 + 错 5 次作废
#   ③ **重置成功后登记收件邮箱**（admin_recovery_email），此后只能发到那个 ——
#      攻击者必须同时知道发件邮箱**且**能收到那封信才行。
#      第一次允许任意指定，是因为那时还没值可登记（先有鸡还是先有蛋）。
#      想换邮箱：直接改 settings 里的 admin_recovery_email。
#
# ⚠️ 不走邮件、也不需要知道原密码 —— 这正是它必须被限流的原因。

_admin_reset_hits: dict[str, list[float]] = {}

ADMIN_RESET_MINUTES = 30
ADMIN_RESET_MAX_TRIES = 5


def _reset_rate_ok(key: str, limit: int, window_s: int) -> bool:
    """滑动窗口限流。内存实现，重启即清空 —— 对"防爆破/防轰炸"够用。"""
    now = time.time()
    hits = [t for t in _admin_reset_hits.get(key, []) if now - t < window_s]
    if len(hits) >= limit:
        _admin_reset_hits[key] = hits
        return False
    hits.append(now)
    _admin_reset_hits[key] = hits
    return True


@app.post(f"{API_PREFIX}/admin/password/forgot")
def admin_password_forgot(body: AdminForgotIn, request: Request) -> dict[str, Any]:
    """申请重置码。**无需鉴权** —— 忘了密码的人本来就登不进来。"""
    ip = client_ip(request)

    if not mail_ready():
        raise HTTPException(
            status_code=400,
            detail="后台还没配置邮件服务，发不出重置码。这种情况只能在服务器上直接改 admins 表。",
        )

    # 实际发件地址 = mail_from 优先，为空则用 mail_user（与 send_mail 同口径）
    sender = (get_setting("mail_from") or get_setting("mail_user") or "").strip().lower()
    given = body.verify_email.strip().lower()
    if not sender or given != sender:
        audit("admin", "password_reset_denied", "发件邮箱不符", ip)
        raise HTTPException(status_code=400, detail="发件邮箱不对")

    to_email = body.to_email.strip()
    if "@" not in to_email or len(to_email) < 5:
        raise HTTPException(status_code=400, detail="收件邮箱格式不对")

    # ① 限流：同一 IP 每小时 3 次；全局每小时 10 次
    if not _reset_rate_ok(f"ip:{ip}", 3, 3600):
        audit("admin", "password_reset_denied", "IP 限流", ip)
        raise HTTPException(status_code=429, detail="请求太频繁了，请过一会儿再试")
    if not _reset_rate_ok("global", 10, 3600):
        audit("admin", "password_reset_denied", "全局限流", ip)
        raise HTTPException(status_code=429, detail="请求太频繁了，请过一会儿再试")

    # ③ 已登记恢复邮箱 → 只允许发到它
    recorded = get_setting("admin_recovery_email").strip().lower()
    if recorded and recorded != to_email.lower():
        raise HTTPException(
            status_code=400,
            detail="这个后台已经登记过重置邮箱，只允许发到它（要换就改 settings 里的 admin_recovery_email）",
        )

    code = "%06d" % secrets.randbelow(1000000)
    expires = datetime.now(timezone.utc) + timedelta(minutes=ADMIN_RESET_MINUTES)
    execute(
        "INSERT INTO admin_reset_codes(code_hash, to_email, created_at, expires_at) VALUES(?,?,?,?)",
        (hash_password(code), to_email, now_iso(), expires.isoformat(timespec="seconds")),
    )

    ok, msg = send_mail(
        to_email,
        f"【{APP_NAME}】管理后台密码重置码",
        "\n".join([
            f"你正在重置「{APP_NAME}」管理后台的密码。",
            "",
            f"重置码：{code}",
            f"有效期：{ADMIN_RESET_MINUTES} 分钟，用完即作废。",
            "",
            "如果这不是你本人操作的，请忽略这封邮件 —— 密码不会被改动。",
        ]),
    )
    if not ok:
        audit("admin", "password_reset_send_failed", msg, ip)
        raise HTTPException(status_code=500, detail=f"重置码没能发出去：{msg}")

    audit("admin", "password_reset_requested", f"to={to_email}", ip)
    return {
        "ok": True,
        "expires_minutes": ADMIN_RESET_MINUTES,
        "message": "重置码已发送，请查收邮件",
    }


@app.post(f"{API_PREFIX}/admin/password/reset")
def admin_password_reset(body: AdminResetIn, request: Request) -> dict[str, Any]:
    """用重置码设新密码。"""
    ip = client_ip(request)

    row = query_one("SELECT * FROM admin_reset_codes WHERE used = 0 ORDER BY id DESC LIMIT 1")
    if row is None:
        raise HTTPException(status_code=400, detail="没有可用的重置码，请先获取")
    if row["tries"] >= ADMIN_RESET_MAX_TRIES:
        execute("UPDATE admin_reset_codes SET used = 1 WHERE id = ?", (row["id"],))
        audit("admin", "password_reset_denied", "重试次数超限", ip)
        raise HTTPException(status_code=400, detail="重置码错误次数太多，已作废，请重新获取")
    if parse_iso(row["expires_at"]) < datetime.now(timezone.utc):
        raise HTTPException(status_code=400, detail="重置码已过期，请重新获取")
    if not verify_password(body.code.strip(), row["code_hash"]):
        execute("UPDATE admin_reset_codes SET tries = tries + 1 WHERE id = ?", (row["id"],))
        audit("admin", "password_reset_denied", "重置码不符", ip)
        raise HTTPException(status_code=400, detail="重置码不对")

    if len(body.new_password) < 8:
        raise HTTPException(status_code=400, detail="新密码至少 8 位")

    admin_row = query_one("SELECT username FROM admins ORDER BY id LIMIT 1")
    if admin_row is None:
        raise HTTPException(status_code=500, detail="没有找到管理员账号")

    execute(
        "UPDATE admins SET pwd_hash = ? WHERE username = ?",
        (hash_password(body.new_password), admin_row["username"]),
    )
    # 与用户端重置同一口径：改了密码就把旧会话全部踢掉
    execute("DELETE FROM admin_tokens")
    execute("UPDATE admin_reset_codes SET used = 1 WHERE id = ?", (row["id"],))
    # ③ 首次重置成功后，把这一次的收件邮箱登记下来
    if not get_setting("admin_recovery_email").strip():
        set_settings({"admin_recovery_email": row["to_email"]})

    audit("admin", "password_reset_done", f"username={admin_row['username']}", ip)
    return {"ok": True, "message": "密码已重置，请用新密码登录"}


@app.post(f"{API_PREFIX}/admin/logout")
def admin_logout(
    request: Request,
    actor: str = Depends(require_admin),
    authorization: str = Header(default=""),
) -> dict[str, Any]:
    token = authorization.removeprefix("Bearer ").strip()
    execute("DELETE FROM admin_tokens WHERE token_hash = ?", (sha256_hex(token),))
    audit(actor, "logout", ip=client_ip(request))
    return {"ok": True}


@app.get(f"{API_PREFIX}/admin/me")
def admin_me(actor: str = Depends(require_admin)) -> dict[str, Any]:
    row = query_one("SELECT username, created_at, last_login_at FROM admins WHERE username = ?", (actor,))
    return {
        "username": actor,
        "created_at": row["created_at"] if row else "",
        "last_login_at": row["last_login_at"] if row else "",
        "mail_ready": mail_ready(),
    }


@app.get(f"{API_PREFIX}/admin/dashboard")
def admin_dashboard(actor: str = Depends(require_admin)) -> dict[str, Any]:
    """仪表盘：卡片数字 + 近 14 天趋势 + 最近动作。"""
    total_devices = (query_one("SELECT COUNT(*) AS n FROM devices") or {"n": 0})["n"]
    active_7d = (
        query_one(
            "SELECT COUNT(*) AS n FROM devices WHERE last_seen >= ?",
            ((datetime.now(timezone.utc) - timedelta(days=7)).isoformat(timespec="seconds"),),
        )
        or {"n": 0}
    )["n"]
    total_users = (query_one("SELECT COUNT(*) AS n FROM users") or {"n": 0})["n"]

    agg = query_one(
        "SELECT COALESCE(SUM(session_count),0) AS s, COALESCE(SUM(message_count),0) AS m, "
        "COALESCE(SUM(memory_count),0) AS mem, COALESCE(SUM(persona_count),0) AS p, "
        "COALESCE(SUM(hit_tokens),0) AS hit, COALESCE(SUM(miss_tokens),0) AS miss FROM devices"
    ) or {}
    hit = int(agg["hit"] or 0)
    miss = int(agg["miss"] or 0)
    billed = hit + miss

    trend = query(
        "SELECT day, active_devices, reports FROM daily_stats ORDER BY day DESC LIMIT 14"
    )
    trend_list = [dict(r) for r in trend][::-1]

    recent = query(
        "SELECT actor, action, detail, ip, created_at FROM audit_logs ORDER BY id DESC LIMIT 8"
    )

    versions = query(
        "SELECT app_version, COUNT(*) AS n FROM devices WHERE app_version != '' "
        "GROUP BY app_version ORDER BY n DESC LIMIT 6"
    )

    return {
        "cards": {
            "devices": total_devices,
            "active_7d": active_7d,
            "users": total_users,
            "sessions": int(agg["s"] or 0),
            "messages": int(agg["m"] or 0),
            "memories": int(agg["mem"] or 0),
            "personas": int(agg["p"] or 0),
            "hit_tokens": hit,
            "miss_tokens": miss,
            "hit_rate": round(hit * 100.0 / billed, 1) if billed else 0.0,
        },
        "trend": trend_list,
        "versions": [dict(r) for r in versions],
        "recent": [dict(r) for r in recent],
        "mail_ready": mail_ready(),
    }


@app.get(f"{API_PREFIX}/admin/users")
def admin_users(
    q: str = "",
    page: int = 1,
    size: int = 20,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    page = max(1, page)
    size = min(100, max(1, size))
    where, args = "", ()
    if q.strip():
        # ⚠️ 列名带表前缀：下面带了 JOIN，裸列名会歧义（v0.61.24.1）
        where = "WHERE u.email LIKE ? OR u.nickname LIKE ? OR u.id LIKE ?"
        like = f"%{q.strip()}%"
        args = (like, like, like)

    total = (query_one(f"SELECT COUNT(*) AS n FROM users u {where}", args) or {"n": 0})["n"]
    # 列表里直接带聚合（设备数 / 最近活跃 / 命中·未命中），免得前端"一人一次请求"（N+1）。
    # 设备归属的两路（devices.user_id 与 user_devices）先 UNION 去重再 GROUP BY（v0.61.24.1）。
    rows = query(
        "SELECT u.*, COALESCE(agg.device_count, 0) AS device_count, agg.last_active, "
        "COALESCE(agg.hit_tokens, 0) AS hit_tokens, COALESCE(agg.miss_tokens, 0) AS miss_tokens "
        "FROM users u LEFT JOIN ("
        "  SELECT m.user_id AS uid, COUNT(DISTINCT m.device_id) AS device_count, "
        "         MAX(d.last_seen) AS last_active, "
        "         SUM(d.hit_tokens) AS hit_tokens, SUM(d.miss_tokens) AS miss_tokens "
        "  FROM (SELECT device_id, user_id FROM devices WHERE user_id IS NOT NULL "
        "        UNION SELECT device_id, user_id FROM user_devices) m "
        "  JOIN devices d ON d.device_id = m.device_id "
        "  GROUP BY m.user_id"
        ") agg ON agg.uid = u.id "
        f"{where} ORDER BY u.created_at DESC LIMIT ? OFFSET ?",
        args + (size, (page - 1) * size),
    )
    return {"total": total, "page": page, "size": size, "items": [dict(r) for r in rows]}


@app.patch(f"{API_PREFIX}/admin/users/{{user_id}}")
def admin_patch_user(
    user_id: str,
    body: UserPatchIn,
    request: Request,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    row = query_one("SELECT * FROM users WHERE id = ?", (user_id,))
    if row is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    if body.nickname is not None:
        execute("UPDATE users SET nickname = ? WHERE id = ?", (body.nickname, user_id))
    if body.status is not None:
        if body.status not in ("active", "banned"):
            raise HTTPException(status_code=400, detail="状态只能是 active 或 banned")
        execute("UPDATE users SET status = ? WHERE id = ?", (body.status, user_id))
    audit(actor, "user_update", f"{user_id} {body.model_dump(exclude_none=True)}", client_ip(request))
    return {"ok": True}


@app.get(f"{API_PREFIX}/admin/users/{{user_id}}")
def admin_user_detail(
    user_id: str,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    """**用户详情**（v0.61.23 用户管理系统）。

    设备列表取**两路的并集**：
      · `user_devices` —— 客户端带身份上报时自动建立（v0.61.23 起）；
      · `devices.user_id` —— 老数据 / 管理员手工指认的归属。
    这样"手工认领的旧设备"与"自动关联的新设备"在同一张列表里，不会漏。

    统计口径：每个数都是**该用户名下各设备上报值之和**（换机后旧设备仍计入 ——
    "这个账号一共用过多少"与"当前在用哪台"是两个问题，后者由 devices 列表回答）。
    """
    row = query_one("SELECT * FROM users WHERE id = ?", (user_id,))
    if row is None:
        raise HTTPException(status_code=404, detail="用户不存在")

    rows = query(
        "SELECT * FROM devices WHERE user_id = ? "
        "OR device_id IN (SELECT device_id FROM user_devices WHERE user_id = ?) "
        "ORDER BY last_seen DESC",
        (user_id, user_id),
    )
    devices = [dict(d) for d in rows]

    hit = sum(int(d.get("hit_tokens") or 0) for d in devices)
    miss = sum(int(d.get("miss_tokens") or 0) for d in devices)
    total_tokens = hit + miss
    summary = {
        "device_count": len(devices),
        "persona_count": sum(int(d.get("persona_count") or 0) for d in devices),
        "session_count": sum(int(d.get("session_count") or 0) for d in devices),
        "message_count": sum(int(d.get("message_count") or 0) for d in devices),
        "memory_count": sum(int(d.get("memory_count") or 0) for d in devices),
        "hit_tokens": hit,
        "miss_tokens": miss,
        # 命中率百分比：没有请求时给 None（界面显示 "—"）—— 不是 0，两者含义不同
        "hit_rate": round(hit * 100.0 / total_tokens, 1) if total_tokens > 0 else None,
        "last_active": max((d.get("last_seen") or "" for d in devices), default="") or None,
    }
    return {"user": _user_public(row), "devices": devices, "summary": summary}


@app.post(f"{API_PREFIX}/admin/users/{{user_id}}/password/reset")
def admin_reset_user_password(
    user_id: str,
    request: Request,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    """**给用户重置密码**（v0.61.23）：随机生成新密码，**一次性**回给管理员。

    ⚠️ 明文只在这一个响应里出现 —— 库里存的是哈希，服务端事后也拿不回来。
       管理员要当场抄给用户（用户自己也可以在 App 里改）。
    ⚠️ 与"用户自助找回密码"（邮件验证码那条路）互不影响：这条走管理员权限，
       不需要用户能收信、也不需要记得旧密码 —— 那正是它存在的理由。
    """
    row = query_one("SELECT * FROM users WHERE id = ?", (user_id,))
    if row is None:
        raise HTTPException(status_code=404, detail="用户不存在")

    new_password = secrets.token_urlsafe(9)
    execute(
        "UPDATE users SET password_hash = ? WHERE id = ?",
        (hash_password(new_password), user_id),
    )
    audit(actor, "user_password_reset", user_id, client_ip(request))
    return {"ok": True, "password": new_password}


@app.get(f"{API_PREFIX}/admin/devices")
def admin_devices(
    q: str = "",
    page: int = 1,
    size: int = 20,
    orphan: bool = False,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    """设备列表。

    `orphan=true` 只列**还没归属任何账号**的设备（v0.61.24.1）——
    用户管理页把"设备"并进来之后，"匿名设备"仍需要一个去处：
    绝大多数用户不注册，这些设备永远不会有 user_id。
    """
    page = max(1, page)
    size = min(100, max(1, size))
    clauses: list[str] = []
    args_list: list[Any] = []
    if q.strip():
        clauses.append("(device_id LIKE ? OR alias LIKE ? OR model LIKE ?)")
        like = f"%{q.strip()}%"
        args_list += [like, like, like]
    if orphan:
        clauses.append("(user_id IS NULL AND device_id NOT IN (SELECT device_id FROM user_devices))")
    where = ("WHERE " + " AND ".join(clauses)) if clauses else ""
    args = tuple(args_list)

    total = (query_one(f"SELECT COUNT(*) AS n FROM devices {where}", args) or {"n": 0})["n"]
    rows = query(
        f"SELECT * FROM devices {where} ORDER BY last_seen DESC LIMIT ? OFFSET ?",
        args + (size, (page - 1) * size),
    )
    return {"total": total, "page": page, "size": size, "items": [dict(r) for r in rows]}


@app.patch(f"{API_PREFIX}/admin/devices/{{device_id}}")
def admin_patch_device(
    device_id: str,
    body: DevicePatchIn,
    request: Request,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    if query_one("SELECT 1 FROM devices WHERE device_id = ?", (device_id,)) is None:
        raise HTTPException(status_code=404, detail="设备不存在")
    if body.alias is not None:
        execute("UPDATE devices SET alias = ? WHERE device_id = ?", (body.alias, device_id))
    if body.user_id is not None:
        execute("UPDATE devices SET user_id = ? WHERE device_id = ?", (body.user_id or None, device_id))
    audit(actor, "device_update", f"{device_id} {body.model_dump(exclude_none=True)}", client_ip(request))
    return {"ok": True}


@app.delete(f"{API_PREFIX}/admin/devices/{{device_id}}")
def admin_delete_device(
    device_id: str,
    request: Request,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    execute("DELETE FROM devices WHERE device_id = ?", (device_id,))
    audit(actor, "device_delete", device_id, client_ip(request))
    return {"ok": True}


# ── 设置（邮件服务就在这里自助填写）──────────────────────────────────────

@app.get(f"{API_PREFIX}/admin/settings")
def admin_get_settings(actor: str = Depends(require_admin)) -> dict[str, Any]:
    return {"values": get_settings_all(mask_secrets=True), "mail_ready": mail_ready()}


@app.put(f"{API_PREFIX}/admin/settings")
def admin_put_settings(
    body: SettingsIn,
    request: Request,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    n = set_settings(body.values)
    audit(actor, "settings_update", f"{n} 项", client_ip(request))
    return {"ok": True, "updated": n, "values": get_settings_all(mask_secrets=True)}


@app.post(f"{API_PREFIX}/admin/mail/test")
def admin_test_mail(
    body: TestMailIn,
    request: Request,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    """真发一封测试信。这是"邮件服务做好了"的唯一证据 —— 配置存下来不算。"""
    ok, message = send_mail(
        body.to,
        f"【{APP_NAME}】邮件服务测试",
        "这是一封测试邮件。\n\n"
        "如果你收到了它，说明后台的 SMTP 配置是通的，"
        "注册验证码可以正常发送。\n\n"
        f"—— {APP_NAME} 后端",
    )
    audit(actor, "mail_test", f"{body.to} → {'ok' if ok else message}", client_ip(request))
    return {"ok": ok, "message": message}


@app.get(f"{API_PREFIX}/admin/mail/pending-codes")
def admin_pending_codes(actor: str = Depends(require_admin)) -> dict[str, Any]:
    """最近未使用的验证码。

    存在的理由：**邮件还没配好时，注册不能就此卡死**。
    （同一台机器上的另一个项目就踩过"邮件服务未配置 → 注册死锁"的坑。）
    自用场景下，你在这里读到码、输进 App 即可完成注册。
    """
    rows = query(
        "SELECT email, code, expires_at, sent_at FROM verify_codes "
        "WHERE used = 0 ORDER BY sent_at DESC LIMIT 10"
    )
    now = datetime.now(timezone.utc)
    out = []
    for r in rows:
        expired = parse_iso(r["expires_at"]) < now
        out.append({**dict(r), "expired": expired})
    return {"items": out, "mail_ready": mail_ready()}


# ── 公告 ────────────────────────────────────────────────────────────────

@app.get(f"{API_PREFIX}/admin/announcements")
def admin_list_announcements(actor: str = Depends(require_admin)) -> dict[str, Any]:
    rows = query("SELECT * FROM announcements ORDER BY id DESC LIMIT 100")
    return {"items": [dict(r) for r in rows]}


@app.post(f"{API_PREFIX}/admin/announcements")
def admin_create_announcement(
    body: AnnouncementIn,
    request: Request,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    if not body.title.strip():
        raise HTTPException(status_code=400, detail="标题不能为空")
    new_id = execute(
        "INSERT INTO announcements(title, body, level, active, created_at) VALUES(?,?,?,?,?)",
        (body.title.strip(), body.body, body.level, 1 if body.active else 0, now_iso()),
    )
    audit(actor, "announcement_create", body.title, client_ip(request))
    return {"ok": True, "id": new_id}


@app.patch(f"{API_PREFIX}/admin/announcements/{{aid}}")
def admin_update_announcement(
    aid: int,
    body: AnnouncementIn,
    request: Request,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    if query_one("SELECT 1 FROM announcements WHERE id = ?", (aid,)) is None:
        raise HTTPException(status_code=404, detail="公告不存在")
    execute(
        "UPDATE announcements SET title=?, body=?, level=?, active=? WHERE id=?",
        (body.title.strip(), body.body, body.level, 1 if body.active else 0, aid),
    )
    audit(actor, "announcement_update", f"#{aid}", client_ip(request))
    return {"ok": True}


@app.delete(f"{API_PREFIX}/admin/announcements/{{aid}}")
def admin_delete_announcement(
    aid: int,
    request: Request,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    execute("DELETE FROM announcements WHERE id = ?", (aid,))
    audit(actor, "announcement_delete", f"#{aid}", client_ip(request))
    return {"ok": True}


# ── 版本发布（v0.50.0）──────────────────────────────────────────────
#
# 应用内更新的**唯一事实来源**。发布方在这里维护"有哪些版本、这一版改了什么、
# 是否强制、APK 在哪"，客户端只读 `GET /api/app/version`。

# APK 的相对 URL：与官网下载页同一个文件（static/d/yuki.apk）。
# ⚠️ 用**相对路径**而不是绝对 URL —— 与 admin_upload 同一个理由：
#    后端不硬编码自己的域名，换端口/反代/域名都不用改库里的数据。
# 官网下载页用的**固定**地址：永远指向最新那一版（上传时会同步刷新那份副本）。
# 官网的 <a href="d/yuki.apk"> 是写死的，不能跟着版本号变。
APK_URL_PATH = "/d/yuki.apk"
APK_DIR = STATIC_DIR / "d"
APK_FILE = APK_DIR / "yuki.apk"
# 13.8MB 的包，留足余量；远大于图片那 5MB，但仍是硬上限
MAX_APK_BYTES = 200 * 1024 * 1024


# ── APK 内容指纹 ──────────────────────────────────────────────────────
#
# 要解决的问题：服务器上**只有一个** `static/d/yuki.apk`。
# 如果发布一条新版本行时从磁盘读大小，它就会**继承上一版包**的大小 ——
# 于是"发了新版本却忘了传包"时：客户端下载到上一版的包，字节数又恰好对得上，
# **完整性校验反而"通过"**，接着把这个错误的包交给安装器。
#
# 修法：让 apk_url 带上**内容指纹**（`/d/yuki.apk?h=<sha256 前 16 位>`），
# 且**只有上传接口会写它**。`GET /api/app/version` 在对外宣称"有更新"之前，
# 先核对磁盘上那个文件与指纹、字节数是否一致；不一致就当**没有可用的包**，
# 直接返回 `latest: null`。
#
# 这是 fail-closed：宁可不提示更新，也绝不提示用户去装一个错版本。
#
# 为什么指纹放 URL 里、而不是加一个数据库列：本文件**没有迁移系统**
# （见 SCHEMA 那段注释），给一张已存在的表加列不会生效而且不报错 ——
# 那是最难查的一类故障。查询串不改表、不改客户端协议，还顺带让"换包即换 URL"。
APK_HASH_PREFIX = 16

# 指纹缓存：13MB 的包每次重算没必要。键=路径，值=(size, mtime_ns, sha256)
_apk_hash_cache: dict[str, tuple[int, int, str]] = {}


def apk_sha256(path: Path) -> str:
    """文件的 sha256（十六进制小写）。读不到返回空串，调用方按"不可用"处理。"""
    try:
        st = path.stat()
    except OSError:
        return ""
    key = str(path)
    hit = _apk_hash_cache.get(key)
    if hit is not None and hit[0] == st.st_size and hit[1] == st.st_mtime_ns:
        return hit[2]
    digest = hashlib.sha256()
    try:
        with path.open("rb") as f:
            for chunk in iter(lambda: f.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError:
        return ""
    out = digest.hexdigest()
    _apk_hash_cache[key] = (st.st_size, st.st_mtime_ns, out)
    return out


def apk_token(url: str) -> str:
    """从 apk_url 的查询串里取出指纹：`/d/yuki.apk?h=ab12` → `ab12`。"""
    if not url or "?" not in url:
        return ""
    for part in url.split("?", 1)[1].split("&"):
        if part.startswith("h="):
            return part[2:]
    return ""


def apk_path_for(version_code: int) -> Path:
    """这一版的包文件：`static/d/yuki-<code>.apk`。

    ## 为什么要一版一个文件（2026-09-29）
    原来只有一个 `static/d/yuki.apk`。新包一覆盖，**旧版本行立刻失效**：
    它的 `apk_url` 里那个指纹指向的是已经被替换掉的文件。后果有两个 ——
    · 后台显示"磁盘上的包与这一版记录的不是同一个文件"（看着像故障，其实是必然）；
    · **回滚失效**：想退回上一版，只能删掉最新那行版本记录；而删完之后
      "最新"就变成那个已被判不可用的旧行 → `GET /api/app/version` 返回
      `latest: null` → 用户**什么更新提示都收不到**。
    一版一个文件之后：各版本互不作废、回滚可用、指纹也不再churn。
    """
    return APK_DIR / f"yuki-{int(version_code)}.apk"


def apk_path_from_url(url: str) -> Path | None:
    """把 `apk_url` 映射回磁盘路径。**只接受 `/d/<简单文件名>.apk`**。

    ⚠️ 这里必须防路径穿越：`apk_url` 虽然只由上传接口写入，
    但库里的行也可能被手工改过。任何分隔符、`..`、非 `.apk` 后缀一律拒绝 ——
    放行一个 `../../x` 就等于给了一个读任意文件的入口。
    """
    if not url:
        return None
    path_part = url.split("?", 1)[0]
    if not path_part.startswith("/d/"):
        return None
    name = path_part[len("/d/"):]
    if not name or name.endswith("/"):
        return None
    if "/" in name or "\\" in name or ".." in name:
        return None
    if not name.endswith(".apk"):
        return None
    return APK_DIR / name


def apk_serve_state(row: Any) -> tuple[bool, int, str]:
    """这一版声明的安装包，此刻**真能下载到正确的包**吗？

    返回 (可用?, 磁盘实际字节数, 原因)。原因只用于后台展示与排查，不上屏给用户。
    """
    url = str(row["apk_url"] or "")
    if not url:
        return False, 0, "还没有上传安装包"
    want = apk_token(url)
    if not want:
        return False, 0, "安装包地址里没有指纹（旧数据或手工写入）"
    # ⚠️ 核对的是**这一版自己的文件**，不是那个"最新副本"
    path = apk_path_from_url(url)
    if path is None:
        return False, 0, "安装包地址不合法（只接受 /d/ 下的 .apk）"
    try:
        size = path.stat().st_size
    except OSError:
        return False, 0, f"服务器上找不到安装包文件（{path.name}）"
    digest = apk_sha256(path)
    if not digest:
        return False, 0, "读不到安装包内容"
    if digest[:APK_HASH_PREFIX] != want:
        return False, size, "磁盘上的包与这一版记录的不是同一个文件"
    recorded = int(row["apk_size"] or 0)
    if recorded != size:
        return False, size, "库里记的大小与文件实际大小不符"
    return True, size, ""


@app.get(f"{API_PREFIX}/admin/releases")
def admin_list_releases(actor: str = Depends(require_admin)) -> dict[str, Any]:
    """按 version_code 倒序列出全部版本（新的在最前）。

    ⚠️ 额外回报 `apk_ready` —— 后台的"缺安装包"判据**必须以它为准**，
        不能只看 `apk_size > 0`：那个数只说明"服务器上某个文件有多大"，
        不说明"这个包属于这一版"。判据见 apk_serve_state()。
    """
    rows = query("SELECT * FROM app_releases ORDER BY version_code DESC")
    items = []
    for r in rows:
        d = dict(r)
        ready, actual, why = apk_serve_state(r)
        d["apk_ready"] = ready
        d["apk_actual_size"] = actual
        d["apk_issue"] = why
        items.append(d)
    return {"items": items}


@app.post(f"{API_PREFIX}/admin/releases")
def admin_upsert_release(
    body: ReleaseIn,
    request: Request,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    """新增或更新一个版本（以 version_code 为键 upsert）。

    ⚠️ 用 upsert 而不是"已存在就报错"：发布流程里最常见的动作是
    **改更新日志的错别字**，为这个动作要求先删再建是没必要的摩擦。
    """
    if body.version_code <= 0:
        raise HTTPException(status_code=400, detail="version_code 必须是正整数")
    if not body.version_name.strip():
        raise HTTPException(status_code=400, detail="version_name 不能为空")

    execute(
        "INSERT INTO app_releases(version_code, version_name, notes, force, "
        "min_supported_code, apk_url, apk_size, published_at) "
        "VALUES(?,?,?,?,?,?,?,?) "
        "ON CONFLICT(version_code) DO UPDATE SET "
        "version_name=excluded.version_name, notes=excluded.notes, force=excluded.force, "
        "min_supported_code=excluded.min_supported_code",
        (
            body.version_code, body.version_name.strip(), body.notes,
            1 if body.force else 0, body.min_supported_code,
            # ⚠️ 两条硬规则：
            #   1. 新版本行一律**不带包**（URL 空、大小 0）。绝不从磁盘读大小 ——
            #      那是**上一版**的包，会让"缺安装包"这个守护永远不触发。
            #   2. ON CONFLICT 的 SET 里**不包含** apk_url / apk_size ——
            #      这样"改个错别字"重发一次不会把已经绑好的包清掉。
            #    包只由上传接口绑定（见 admin_upload_apk）。
            "",
            0,
            now_iso(),
        ),
    )
    audit(actor, "release_upsert", f"{body.version_name}({body.version_code})", client_ip(request))
    return {"ok": True}


@app.patch(f"{API_PREFIX}/admin/releases/{{code}}")
def admin_patch_release(
    code: int,
    body: ReleasePatchIn,
    request: Request,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    """改一个已发布版本的元数据（不改 version_code 本身）。

    ⚠️ v0.61.21：入参从 [ReleaseIn] 换成 [ReleasePatchIn] —— 见后者的 KDoc
    （前者要求请求体里带 `version_code`，而它在路径里，于是每次保存都 422）。
    """
    if query_one("SELECT 1 FROM app_releases WHERE version_code = ?", (code,)) is None:
        raise HTTPException(status_code=404, detail="这个版本不存在")
    execute(
        "UPDATE app_releases SET version_name=?, notes=?, force=?, min_supported_code=? WHERE version_code=?",
        (
            body.version_name.strip(), body.notes,
            1 if body.force else 0, body.min_supported_code, code,
        ),
    )
    audit(actor, "release_update", f"#{code}", client_ip(request))
    return {"ok": True}


@app.delete(f"{API_PREFIX}/admin/releases/{{code}}")
def admin_delete_release(
    code: int,
    request: Request,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    """删掉一个版本记录。

    ⚠️ 只删**记录**，不动 `static/d/yuki.apk`。那个文件是**共享的**（官网下载页
    也用它），删它会让所有还没更新的用户下载失败 —— 这是本接口最容易写错的地方。
    """
    execute("DELETE FROM app_releases WHERE version_code = ?", (code,))
    audit(actor, "release_delete", f"#{code}", client_ip(request))
    return {"ok": True}


@app.post(f"{API_PREFIX}/admin/releases/apk")
async def admin_upload_apk(
    request: Request,
    file: UploadFile = File(...),
    # 可选：指定绑给哪一版。留空 = 绑给最新那一版。
    version_code: str = Form(""),
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    """上传 APK 覆盖 `static/d/yuki.apk`，并把**实际大小**同步进最新版本行。

    ## ⚠️ 为什么不复用 /admin/upload
    那个接口的后缀白名单（`ALLOWED_IMAGE_EXT`）是**安全边界**：它是"唯一一个把
    外部文件写进服务端"的入口，白名单就是为了让那条路只能写图片。
    为了传 apk 去放宽它，等于让**头像/收款码那条路**也能塞任意文件 ——
    不该为一个新需求去削弱一条既有的边界。所以这里**另开一条**，各自守各自的。

    ## 大小从磁盘实读
    客户端拿 `apk_size` 做下载完整性校验。若这里报一个错的数，
    客户端会把**下载完整的包判成损坏**并反复重下。所以写完立刻 `stat()`。
    """
    ext = Path(file.filename or "").suffix.lower()
    if ext != ".apk":
        raise HTTPException(status_code=400, detail="只能上传 .apk 安装包")

    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="文件是空的，没读到位")
    if len(data) > MAX_APK_BYTES:
        raise HTTPException(
            status_code=400,
            detail=f"安装包太大了（上限 {MAX_APK_BYTES // 1024 // 1024}MB）",
        )

    APK_DIR.mkdir(parents=True, exist_ok=True)

    # 绑定给哪一版：显式给了 version_code 就用它（回滚时重传旧版用得上），
    # 否则用**最新那一版**（发布方通常是先建版本行、再传包）。
    bound: int | None = None
    if version_code.strip():
        try:
            bound = int(version_code.strip())
        except ValueError:
            raise HTTPException(status_code=400, detail="version_code 必须是整数")
        if query_one("SELECT 1 FROM app_releases WHERE version_code = ?", (bound,)) is None:
            raise HTTPException(status_code=404, detail=f"版本 {bound} 不存在，请先发布它")
    else:
        latest = query_one(
            "SELECT version_code FROM app_releases ORDER BY version_code DESC LIMIT 1"
        )
        bound = int(latest["version_code"]) if latest is not None else None

    # ⚠️ 写进**这一版自己的**文件（见 apk_path_for 的注释）：
    #    覆盖式单文件会让旧版本行立刻失效、回滚也失效。
    target = apk_path_for(bound) if bound is not None else APK_FILE
    target.write_bytes(data)

    # 写完立刻从磁盘取大小与指纹 —— **不采信调用方上报的数字**，
    # 上报值可能过时或写错，而客户端拿这个数做下载完整性校验。
    size = target.stat().st_size
    digest = apk_sha256(target)
    if not digest:
        raise HTTPException(status_code=500, detail="安装包写盘后读不到内容，请重试")

    # 官网那个固定地址（d/yuki.apk）指向最新版 → 同步刷一份副本。
    #
    # ⚠️ **只有传的"就是最新那一版"时才刷**。无条件刷会在"回填旧版"时出两个问题：
    #    · 官网下载页被降级成旧包；
    #    · 最新版那一行指向的正是 d/yuki.apk —— 它会被改成旧内容，指纹当场对不上，
    #      于是**最新版变成不可用**，用户反而收不到更新。
    #    这个坑是写探针时才想到的（探针里"给 71 传包"那一步就会踩中）。
    newest = query_one("SELECT MAX(version_code) AS c FROM app_releases")
    is_newest = (bound is not None and newest is not None and newest["c"] is not None
                 and int(newest["c"]) == bound)
    if is_newest:
        try:
            APK_FILE.write_bytes(data)
        except OSError:
            pass

    # 指纹进 URL：客户端拿到的地址与"服务器此刻真正会吐出的字节"绑死
    url = f"/d/{target.name}?h={digest[:APK_HASH_PREFIX]}"

    if bound is not None:
        execute(
            "UPDATE app_releases SET apk_url=?, apk_size=? WHERE version_code=?",
            (url, size, bound),
        )

    audit(actor, "release_apk", f"{len(data)} bytes", client_ip(request))
    return {
        "ok": True,
        "url": url,
        "size": size,
        "sha256": digest,
        # None 表示"库里还没有任何版本行，这个包暂时没有归属"
        "bound_to": bound,
    }


# ── 图片上传（作者头像 / 收款码，用户要求在后台自己传）────────────────────
#
# ⚠️ 这是**唯一**一个把外部文件写进服务端的入口，所以守两条：
#    ① 类型白名单 —— 只认图片后缀。别的地方都不该有写文件的能力。
#    ② 体积上限 5MB —— 后台只用来传头像与收款码，用不到更大的。
# 文件名用**随机串**而不是原文件名：原文件名可能带路径分隔符（`../../x.py`）
# 或不可见字符，直接拼路径就是一次路径穿越。

UPLOAD_DIR = Path(os.environ.get("YUKI_UPLOAD_DIR", str(STATIC_DIR / "uploads")))
ALLOWED_IMAGE_EXT = {".png", ".jpg", ".jpeg", ".webp", ".gif"}
MAX_UPLOAD_BYTES = 5 * 1024 * 1024

# ── 图片三档（v0.58.x · 人设市场优化）──────────────────────────────────────
# 背景：服务器出口带宽实测约 300 KB/s。列表里直接放 1–3 MB 的原图，一屏 6 张要
# 十几秒；3 个人同时逛就能把出口占满。所以**落盘那一刻**就压好三档，客户端按场景取：
#   thumb 400px / q80  → 列表卡片        medium 1080px / q80 → 详情页图集
#   original   原尺寸 / q85 → 全屏看原图
# ⚠️ 缩尺寸与压质量**一起做**：只降质量不缩尺寸，图会真的糊。
# ⚠️ 文件名 = **原始上传字节**的 sha256 前 16 位 → 同一张图传两次文件名完全一样；
#    也因此可以给 `/uploads/` 挂 `immutable` 长缓存（同 URL 必同内容）。
_IMG_THUMB_MAX = 400
_IMG_MEDIUM_MAX = 1080
_IMG_THUMB_Q = 80
_IMG_MEDIUM_Q = 80
_IMG_ORIGINAL_Q = 85
# 字节预算：列表卡片的缩略图必须"小到不心疼带宽"。
# ⚠️ 光靠"固定质量"押不准 —— 纯噪声/高细节照片在 1080px q80 下能到 400KB+。
#    所以编码走 [_encode_bounded]：**先降质量、再按需缩尺寸**，直到进预算。
_IMG_THUMB_TARGET = 30 * 1024
_IMG_MEDIUM_TARGET = 150 * 1024
_IMG_QUALITY_FLOOR = 55     # 质量下限（再低就真的糊了）
# 长边下限**按档位比例**算：缩略档上限才 400，若用一个固定的 480 当底线，
# "缩尺寸"这条路会被整条堵死，纯噪声图就只能超预算。
_IMG_SIDE_FLOOR_RATIO = 0.6
# 单档最多编码几次（质量阶梯 + 尺寸阶梯的总上限）。
# ⚠️ 没有这个闸，理论上一张极端图能把每一档都跑满 6+ 次编码 —— 每一次都是几百毫秒级。
_IMG_MAX_ENCODE_ATTEMPTS = 6
# 像素上限：**在真正解码之前**就挡掉"小文件、巨像素"的图。
# ⚠️ Pillow 自带一道闸（`MAX_IMAGE_PIXELS`=89.5M，>2× 即 179M 抛错），但那道闸
#    放行到 179M 像素 —— RGB 解出来就是 ~700MB，小内存服务器直接被打死。
#    手机照最大也就 48MP，这里给到 64MP 已经绰绰有余。
_IMG_MAX_PIXELS = 64_000_000

# 「这个相对 URL 指向的文件在不在」的进程内缓存。
# ⚠️ 成立前提：uploads 下的文件**只增不改**（内容哈希命名，永不覆盖同名）。
_upload_exists_cache: dict[str, bool] = {}


def _upload_exists(rel_url: str) -> bool:
    if not rel_url:
        return False
    hit = _upload_exists_cache.get(rel_url)
    if hit is None:
        hit = (UPLOAD_DIR / PurePosixPath(rel_url).name).is_file()
        _upload_exists_cache[rel_url] = hit
    return hit


def _variant_url(original_url: str, kind: str) -> str:
    """`/uploads/ab12.webp` + `thumb` → `/uploads/ab12_thumb.webp`。

    ⚠️ 后缀一律 `.webp`（三档都是我们压出来的 WebP）。老图是 `<日期>_<随机>.jpg`
       这种命名，派生出来的 `_thumb.webp` 根本不存在 —— 调用方靠
       [_upload_exists] 判断后**回落原图**，绝不让读接口 500。
    """
    if not original_url:
        return ""
    p = PurePosixPath(original_url)
    return str(p.with_name(f"{p.stem}_{kind}.webp"))


def _fit_long_side(img: Image.Image, max_side: int) -> Image.Image:
    """把长边缩到不超过 max_side（横竖都按长边算）。已经够小就原样返回。"""
    longest = max(img.width, img.height)
    if longest <= max_side:
        return img
    scale = max_side / longest
    return img.resize(
        (max(1, round(img.width * scale)), max(1, round(img.height * scale))),
        Image.LANCZOS,
    )


def _encode_bounded(
    img: Image.Image,
    *,
    quality: int,
    max_side: int | None,
    target: int | None,
) -> bytes:
    """把图编码成 WebP，并尽量压进 `target` 字节预算。

    ⚠️ **先降质量、再缩尺寸** —— 只降质量不缩尺寸，图会真的糊；只缩尺寸不降质量，
       又到不了预算。两个一起调才既清楚又小。
    `target is None` = 不限（原图档）。实在压不进去时返回当前最小结果：
    宁可略超预算，也不把图缩成一团马赛克。
    """
    cur = _fit_long_side(img, max_side) if max_side else img
    side_floor = max(1, round(max_side * _IMG_SIDE_FLOOR_RATIO)) if max_side else 0
    q = quality
    attempts = 0
    while True:
        buf = io.BytesIO()
        # ⚠️ method=4 而不是 6：实测（1800x1200 三档）2221ms → 1353ms，省 40% CPU，
        #    而体积只差 0.5%–4%（medium 398842 vs 402692 字节）。
        #    对一台自用服务器，"每次上传少占 0.9 秒 CPU"比那几 KB 值。
        cur.save(buf, format="WEBP", quality=q, method=4)
        data = buf.getvalue()
        attempts += 1
        if target is None or len(data) <= target:
            return data
        if attempts >= _IMG_MAX_ENCODE_ATTEMPTS:
            return data          # 闸到了：交当前最好结果，别把 CPU 烧在极端图上
        if q > _IMG_QUALITY_FLOOR:
            q = max(_IMG_QUALITY_FLOOR, q - 10)
            continue
        if max(cur.width, cur.height) <= side_floor:
            return data
        # 质量已到下限还超预算 → 尺寸退一档（-20%）再试
        cur = cur.resize(
            (max(1, round(cur.width * 0.8)), max(1, round(cur.height * 0.8))),
            Image.LANCZOS,
        )


def _write_webp_variants(data: bytes) -> tuple[str, str, str] | None:
    """把上传的原始字节压成三档 WebP 落盘，返回 `(thumb, medium, original)` 相对 URL。

    失败（不是图片 / 解不开 / 存不下）返回 `None`，由调用方回 400 —— **不吞异常**。
    """
    digest = hashlib.sha256(data).hexdigest()[:16]
    try:
        with Image.open(io.BytesIO(data)) as src:
            # ⚠️ 必须在**触发解码之前**查像素数：`Image.open` 只读文件头（`size` 已可用），
            #    而 `exif_transpose` / `convert` 会真的把像素解出来。
            #    "小文件、巨像素"是典型解压炸弹 —— 实测一张 **757KB** 的 PNG 可声明
            #    256M 像素；Pillow 自带闸门要 >179M 像素才抛错，179M 以下照解，
            #    RGB 出来就是 ~700MB，小内存服务器直接被打死。
            if src.width * src.height > _IMG_MAX_PIXELS:
                return None
            # ⚠️ 先吃 EXIF 旋转：手机竖拍的照片像素其实是横的、靠 EXIF 标记转 90°。
            #    不转的话缩出来的缩略图是躺着的。
            im = ImageOps.exif_transpose(src) or src
            # WebP 支持带透明度；其余模式（调色板 P / 灰度 L / CMYK）统一成 RGB 系列，
            # 免得 save 抛 "cannot write mode P as WEBP"。
            if im.mode not in ("RGB", "RGBA"):
                im = im.convert("RGBA" if "A" in im.getbands() else "RGB")

            UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
            # (名字后缀, 起始质量, 长边上限, 字节预算) —— "" 即原图档，不缩不限
            tiers = (
                ("", _IMG_ORIGINAL_Q, None, None),
                ("medium", _IMG_MEDIUM_Q, _IMG_MEDIUM_MAX, _IMG_MEDIUM_TARGET),
                ("thumb", _IMG_THUMB_Q, _IMG_THUMB_MAX, _IMG_THUMB_TARGET),
            )
            urls: dict[str, str] = {}
            for suffix, quality, max_side, target in tiers:
                name = f"{digest}{'_' + suffix if suffix else ''}.webp"
                dest = UPLOAD_DIR / name
                # 同名即同内容：已存在就不重写（省一次 IO，也让"传两次"真正幂等）
                if not dest.is_file():
                    dest.write_bytes(
                        _encode_bounded(im, quality=quality, max_side=max_side, target=target)
                    )
                urls[suffix or "original"] = f"/uploads/{name}"
                # 刚写下去的三档，**确定存在**：顺手把存在性缓存置真。
                # 否则若之前因故缓存过 False（比如某档写失败过一次），
                # 这里补写成功后缓存仍是旧的 False，市场会一直回落到原图。
                _upload_exists_cache[f"/uploads/{name}"] = True
    except Exception:
        return None
    return urls["thumb"], urls["medium"], urls["original"]


def _pixel_overflow(data: bytes) -> bool:
    """这份字节是不是"小文件、巨像素"的图（**只读文件头**，不解码）。

    只用来在失败时给一句更准的话 —— 判据本身与 [_write_webp_variants] 里那道闸一致。
    """
    try:
        with Image.open(io.BytesIO(data)) as probe:
            return probe.width * probe.height > _IMG_MAX_PIXELS
    except Exception:
        return False


@app.post(f"{API_PREFIX}/admin/upload")
async def admin_upload(
    request: Request,
    file: UploadFile = File(...),
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    """上传一张图片，返回**相对** URL（如 `/uploads/20260927_a1b2c3.png`）。

    返回相对路径是刻意的：后端不硬编码自己的外网域名 —— 换端口、换反代、
    换域名都不用改数据库里的数据。拼绝对地址是客户端的事
    （它内置了 `ServerConfig.BASE_URL`）。
    """
    ext = Path(file.filename or "").suffix.lower()
    if ext not in ALLOWED_IMAGE_EXT:
        raise HTTPException(
            status_code=400,
            detail="只能上传图片（png / jpg / jpeg / webp / gif）",
        )

    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="文件是空的，没读到位")
    if len(data) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=400, detail="图片太大了（上限 5MB）")

    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    fname = datetime.now(timezone.utc).strftime("%Y%m%d") + "_" + secrets.token_hex(6) + ext
    (UPLOAD_DIR / fname).write_bytes(data)

    url = f"/uploads/{fname}"
    audit(actor, "upload", url, client_ip(request))
    return {"ok": True, "url": url}


# ── 面向普通用户的图片上传（v0.58.0）────────────────────────────────
#
# 用户要求：「市场缺的图片通道后端补必须能用」。
#
# ⚠️ 为什么不直接把 `/api/admin/upload` 放开：那个接口是**后台**用的，
#    放开等于把"任何人往服务器写文件"直接开放。这里重新收紧两件事：
#    · 必须登录（`require_user`）；
#    · **每人每小时限量** —— 写文件是持久副作用，不限量等于给磁盘开了个口子。
#    体积与后缀沿用后台那套白名单，不另定一份（两份白名单迟早会漂）。
#
# ⚠️ 文档 §25.6 原本设计走 OSS 预签名（客户端直传对象存储）。本项目**没有 OSS**，
#    所以落盘在本机 `static/uploads/`（与后台同一目录、同一套命名规则）。
#    将来真接了 OSS，客户端只需要换这一个接口的返回，其它都不用动。

_UPLOAD_HITS: dict[str, list[float]] = {}
_UPLOAD_WINDOW = 3600.0     # 1 小时
_UPLOAD_MAX_PER_WINDOW = 30  # 每人每小时 30 张（发人设封面绰绰有余）


@app.post(f"{API_V1}/upload/image")
async def upload_image(
    request: Request,
    file: UploadFile = File(...),
    user_id: str = Depends(require_user),
) -> dict[str, Any]:
    """上传一张图片（已登录用户），返回**相对** URL（如 `/uploads/20261001_a1b2c3.png`）。

    ⚠️ 返回相对路径是刻意的（与后台那个同一条理由）：后端不硬编码自己的外网域名，
       换端口/换反代/换域名都不用改库里的数据；拼绝对地址是客户端的事。
       ⚠️ 但客户端**必须记得拼** —— 直接拿相对路径去请求会失败（`ServerConfig.url()`）。
    """
    now = time.time()
    hits = [t for t in _UPLOAD_HITS.get(user_id, []) if now - t < _UPLOAD_WINDOW]
    if len(hits) >= _UPLOAD_MAX_PER_WINDOW:
        raise HTTPException(status_code=429, detail="传得太频繁了，过一会儿再试")
    hits.append(now)
    _UPLOAD_HITS[user_id] = hits

    ext = Path(file.filename or "").suffix.lower()
    if ext not in ALLOWED_IMAGE_EXT:
        raise HTTPException(status_code=400, detail="只能上传图片（png / jpg / jpeg / webp / gif）")

    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="文件是空的，没读到位")
    if len(data) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=400, detail="图片太大了（上限 5MB）")

    # ⚠️ 落盘前先解一次真图：既挡住"改了后缀的假图片"，也是压三档的前提。
    # ⚠️⚠️ **必须丢到线程池里做**（run_in_threadpool）。这是 `async def` 端点，
    #    直接在这里同步压图会把**整个事件循环**占住 —— 实测单张 1.8MB 图三档编码
    #    要 ~4.7 秒、5MB 图 ~7.5 秒，这期间 /health 与市场列表**全部无响应**。
    #    客户端又是一次并发发最多 9 张，串起来就是几十秒的全站假死 ——
    #    正是要修的那个"3 人并发全站瘫痪"。
    variants = await run_in_threadpool(_write_webp_variants, data)
    if variants is None:
        detail = (
            "这张图的像素太大了（上限 6400 万像素），先压小一点再传"
            if _pixel_overflow(data)
            else "这张图解不开，换一张试试"
        )
        raise HTTPException(status_code=400, detail=detail)
    thumb_url, medium_url, original_url = variants

    # `url` 给**原图**档；另外两档一并返回，由调用方按场景自己挑。
    # ⚠️ 服务端**不再**派生档位 —— 原先那层派生属于人设市场（已下线）。
    #    要哪一档，直接在 `url` / `mediumUrl` / `thumbUrl` 里选。
    return {"ok": True, "url": original_url, "thumbUrl": thumb_url, "mediumUrl": medium_url}


@app.get(f"{API_PREFIX}/app/changelog")
def app_changelog(limit: int = 30) -> dict[str, Any]:
    """更新日志（公开、无需鉴权）—— 客户端「关于」页的「更新日志」入口用它列历史版本。

    ## 与 `GET /api/app/version` 的分工
    那个回答"**要不要**更新"（只给最新一版，且包不可用就什么都不给）；
    这个回答"**都更新过什么**"（列历史，纯展示）。

    ## ⚠️ 只列**装得出来**的版本
    判据与更新接口**同源**（`apk_serve_state`）：没绑好包的版本不出现在这里 ——
    否则用户会看到"0.50.2 修了什么什么"，却根本升不到那一版，比不显示更糟。

    ## ⚠️ 不回传 `apk_url`
    这一页只展示日志。下载地址属于"更新流程"，由 `/api/app/version` 负责 ——
    少一个字段就少一处能被动过手脚的入口。
    """
    limit = max(1, min(100, limit))
    rows = query(
        "SELECT version_code, version_name, notes, force, min_supported_code, "
        "apk_url, apk_size, published_at FROM app_releases "
        "ORDER BY version_code DESC LIMIT ?",
        (limit,),
    )
    items = []
    for r in rows:
        ready, _, _ = apk_serve_state(r)
        if not ready:
            continue
        items.append({
            "version_code": int(r["version_code"]),
            "version_name": str(r["version_name"]),
            "notes": str(r["notes"] or ""),
            "force": bool(r["force"]),
            "published_at": str(r["published_at"] or ""),
        })
    return {"items": items}


@app.get(f"{API_PREFIX}/admin/audit")
def admin_audit(
    page: int = 1,
    size: int = 30,
    actor: str = Depends(require_admin),
) -> dict[str, Any]:
    page = max(1, page)
    size = min(200, max(1, size))
    total = (query_one("SELECT COUNT(*) AS n FROM audit_logs") or {"n": 0})["n"]
    rows = query(
        "SELECT * FROM audit_logs ORDER BY id DESC LIMIT ? OFFSET ?",
        (size, (page - 1) * size),
    )
    return {"total": total, "page": page, "size": size, "items": [dict(r) for r in rows]}


# ═══════════════════════════════════════════════════════════════════════════
# 客户端 API（App 调用；除注册登录外都不需要令牌）
# ═══════════════════════════════════════════════════════════════════════════

@app.get(f"{API_PREFIX}/app/announcements")
def app_announcements() -> dict[str, Any]:
    """App 启动时拉一次。**不做鉴权** —— 公告是公开信息，
    而且客户端离线时压根不会调它（调用方静默失败即可）。"""
    rows = query(
        "SELECT id, title, body, level, created_at FROM announcements "
        "WHERE active = 1 ORDER BY id DESC LIMIT 20"
    )
    return {"items": [dict(r) for r in rows]}


@app.get(f"{API_PREFIX}/app/version")
def app_version(code: int = 0) -> dict[str, Any]:
    """检测更新（v0.50.0）。**不做鉴权** —— 与公告同理：

    更新检查是**公开信息**，而且它在启动时就会被调用，那时用户可能还没登录。
    绝大多数用户根本不注册账号，要求令牌等于这个功能对多数人不可用。

    ## 入参 `code`
    客户端把**自己的 versionCode** 传进来（可选）。传了服务端就能顺手算好 `force`，
    客户端少一次判断；不传也照样返回最新版，客户端自己比对。

    ## ⚠️ `force` 只由**服务端**算，但客户端**仍要独立复算一次**
    不是因为不信任 —— 而是客户端本地就能测这条分支（不必连服务端造数据），
    而且万一将来服务端算法改了，客户端的行为不会跟着漂。
    两边算法**必须一致**：
        force = row.force  or  (code > 0 and code < row.min_supported_code)

    ## 没有版本行时返回 `{"latest": null}`
    ⚠️ 这是**正常状态**（刚部署、还没发过版），不是错误。客户端拿到 null
    **什么都不做** —— 若把它当异常处理，首次部署的所有用户都会看到报错。
    """
    row = query_one(
        "SELECT version_code, version_name, notes, force, min_supported_code, "
        "apk_url, apk_size, published_at FROM app_releases "
        "ORDER BY version_code DESC LIMIT 1"
    )
    if row is None:
        return {"latest": None, "force": False, "min_supported_code": 0}

    # ⚠️ fail-closed：包不可服务时**不许**对外说"有新版本"。
    #    否则用户下载到的是上一版的包，而字节数又恰好对得上 ——
    #    客户端那份完整性校验会"通过"，然后把错误的包交给安装器。
    #    "没有更新提示" 永远好过 "提示了一个会装错版本的更新"。
    ready, actual_size, why = apk_serve_state(row)
    if not ready:
        return {
            "latest": None,
            "force": False,
            "min_supported_code": 0,
            "blocked": True,
            "reason": why,   # 只给排查用，客户端不读它
        }

    latest = dict(row)
    latest["apk_size"] = actual_size   # 以磁盘实际为准，不用库里那个数
    min_supported = int(latest.get("min_supported_code") or 0)
    forced = bool(latest.get("force")) or (code > 0 and code < min_supported)
    return {
        "latest": latest,
        "force": forced,
        "min_supported_code": min_supported,
    }


@app.post(f"{API_PREFIX}/app/report")
def app_report(body: ReportIn, request: Request) -> dict[str, Any]:
    """设备与用量上报（软件专属数据的来源）。

    ⚠️ **客户端必须在设置里有开关，且默认关闭** —— 上报是可选行为。
    这条接口本身不校验令牌：令牌意味着账号，而绝大多数用户不注册。
    """
    if not body.device_id.strip():
        raise HTTPException(status_code=400, detail="device_id 不能为空")

    ts = now_iso()
    # v0.61.23：把设备与账号关联（可选）。解析失败一律当匿名 —— 上报是统计，
    # 不能因为 token 过期整条丢掉；老客户端不带 token，走的就是这条路。
    user_id = _user_id_from_optional_token(body.token)
    exists = query_one("SELECT 1 FROM devices WHERE device_id = ?", (body.device_id,))
    if exists:
        execute(
            # ⚠️ user_id 用 COALESCE：**带了身份才覆盖**，匿名上报不动已有归属（兼容老客户端）
            "UPDATE devices SET model=?, android_version=?, app_version=?, persona_count=?, "
            "session_count=?, message_count=?, memory_count=?, hit_tokens=?, miss_tokens=?, "
            "user_id=COALESCE(?, user_id), "
            "last_seen=?, report_count=report_count+1 WHERE device_id=?",
            (
                body.model, body.android_version, body.app_version, body.persona_count,
                body.session_count, body.message_count, body.memory_count,
                body.hit_tokens, body.miss_tokens, user_id, ts, body.device_id,
            ),
        )
    else:
        execute(
            "INSERT INTO devices(device_id, model, android_version, app_version, persona_count, "
            "session_count, message_count, memory_count, hit_tokens, miss_tokens, user_id, "
            "first_seen, last_seen, report_count) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1)",
            (
                body.device_id, body.model, body.android_version, body.app_version,
                body.persona_count, body.session_count, body.message_count, body.memory_count,
                body.hit_tokens, body.miss_tokens, user_id, ts, ts,
            ),
        )

    # 用户 ↔ 设备关联（v0.61.23）——只在这条上报**带了有效身份**时维护。
    # 老版本客户端（无 token）永远走不到这里，兼容不变。
    if user_id:
        execute(
            "INSERT INTO user_devices(user_id, device_id, first_seen, last_seen, reports) "
            "VALUES(?,?,?,?,1) ON CONFLICT(user_id, device_id) "
            "DO UPDATE SET last_seen=excluded.last_seen, reports=reports+1",
            (user_id, body.device_id, ts, ts),
        )

    day = today()
    execute(
        "INSERT INTO daily_stats(day, active_devices, reports) VALUES(?,1,1) "
        "ON CONFLICT(day) DO UPDATE SET reports = reports + 1, "
        "active_devices = (SELECT COUNT(DISTINCT device_id) FROM devices WHERE last_seen >= ?)",
        (day, day + "T00:00:00+00:00"),
    )
    return {"ok": True}


# ═══════════════════════════════════════════════════════════════════════════
# 账号：注册 / 登录 / 刷新 / 登出（开发文档 §19.1 / §32）
# ═══════════════════════════════════════════════════════════════════════════
#
# 相比上一版的**模型变化**（文档选的是这一种，我们照做）：
#   · 注册只要 **昵称 + 密码** —— 零外部依赖，没有邮件服务也能开账号
#   · **uid 是数字**（10001 起）：既是展示用的账号 ID，也是登录凭证
#   · 登录接受 **uid 或昵称**
#   · 邮箱变成**可选绑定**：绑了才能"找回密码"
#
# 令牌用自研随机串而不是 JWT：客户端只把它当**不透明字符串**存，行为上与 JWT 等价；
# 而库里**只存哈希**，拖库拿不到可用令牌 —— 这比"格式是不是标准"重要得多。
# 为"看起来标准"引一个 PyJWT，不划算。


def _user_public(row: sqlite3.Row) -> dict[str, Any]:
    """对外用户资料。

    ⚠️ **绝不能**把 `password_hash` 放进来 —— 少写这一句就是把密码哈希送出去。
    """
    return {
        "id": row["id"],
        "uid": str(row["uid"]),
        "nickname": row["nickname"],
        "avatarUrl": row["avatar_url"],
        "email": row["email"],
    }


def _next_uid() -> int:
    """数字 UID：10001 起递增。"""
    row = query_one("SELECT COALESCE(MAX(uid), 10000) AS m FROM users")
    return int(row["m"]) + 1 if row else 10001


def _issue_tokens(user_id: str) -> dict[str, str]:
    """发一对令牌：access（[ACCESS_DAYS] 天）+ refresh（[REFRESH_DAYS] 天）。"""
    now = datetime.now(timezone.utc)
    access, access_hash = issue_token()
    refresh, refresh_hash = issue_token()
    execute(
        "INSERT INTO user_tokens(token_hash, user_id, expires_at, created_at) VALUES(?,?,?,?)",
        (access_hash, user_id, (now + timedelta(days=ACCESS_DAYS)).isoformat(timespec="seconds"), now_iso()),
    )
    execute(
        "INSERT INTO user_tokens(token_hash, user_id, expires_at, created_at) VALUES(?,?,?,?)",
        (refresh_hash, user_id, (now + timedelta(days=REFRESH_DAYS)).isoformat(timespec="seconds"), now_iso()),
    )
    return {"token": access, "refresh": refresh}


@app.post(f"{API_V1}/auth/register")
def auth_register(body: RegisterIn) -> dict[str, Any]:
    nickname = body.nickname.strip()
    if not nickname:
        raise HTTPException(status_code=400, detail="请填写昵称")
    if len(nickname) > 20:
        raise HTTPException(status_code=400, detail="昵称最多 20 个字")
    # 密码格式统一走 password_error（用户要求：只允许数字/大小写英文/符号）
    _perr = password_error(body.password)
    if _perr:
        raise HTTPException(status_code=400, detail=_perr)

    # 昵称是登录凭证之一 → 必须唯一，否则"用昵称登录"就有歧义
    if query_one("SELECT 1 FROM users WHERE nickname = ?", (nickname,)) is not None:
        raise HTTPException(status_code=409, detail="这个昵称已经被用了，换一个")

    uid = _next_uid()
    user_id = "u_" + secrets.token_hex(8)
    execute(
        "INSERT INTO users(id, uid, nickname, password_hash, avatar_url, created_at, last_login_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (user_id, uid, nickname, hash_password(body.password), body.avatar_url, now_iso(), now_iso()),
    )
    row = query_one("SELECT * FROM users WHERE id = ?", (user_id,))
    return {"uid": str(uid), "user": _user_public(row), **_issue_tokens(user_id)}


class RegisterStartIn(BaseModel):
    nickname: str
    email: str
    code: str


class RegisterFinishIn(BaseModel):
    password: str
    avatar_url: str | None = None


@app.post(f"{API_V1}/auth/register/cancel")
def auth_register_cancel(user_id: str = Depends(require_pending_user)) -> dict[str, Any]:
    """**放弃注册**：把还没设密码的占位账号删掉（用户 2026-10-02 要求）。

    用户在中途退出（填完邮箱、或停在"设密码"那一步）时，不该在库里留半个账号。
    只删 pending：
    · `require_pending_user` 已经把 active 账号挡在门外（它只认 status='pending'）；
    · DELETE 里再带一次 `status = 'pending'` 兜底 —— 两道锁都指向"只删半成品"。
    """
    execute("DELETE FROM user_tokens WHERE user_id = ?", (user_id,))
    execute("DELETE FROM users WHERE id = ? AND status = 'pending'", (user_id,))
    return {"cancelled": True}

@app.get(f"{API_V1}/auth/email/exists")
def auth_email_exists(email: str) -> dict[str, Any]:
    """**这个邮箱注册过没有？**（用户 2026-10-02：注册时边输边提示）

    ⚠️ 它确实是一个"能问出某邮箱是否注册过"的接口 —— 这是**用户明确要的体验**
        （输入时就提示"已被注册，换一个"），不是疏忽。
        缓解：只回一个布尔、不带任何账号信息；且发码接口本来就会 409 暴露同一件事，
        这个端点没有新增可被利用的信息面。

        真要收紧，可以加频率限制（这里没做，见提交说明）。
    """
    e = email.strip().lower()
    if not e:
        return {"registered": False}
    hit = query_one("SELECT 1 FROM users WHERE email = ? AND status = 'active'", (e,))
    return {"registered": hit is not None}

@app.post(f"{API_V1}/auth/register/start")
def auth_register_start(body: RegisterStartIn) -> dict[str, Any]:
    """注册**第一段**：昵称 + 邮箱 + 验证码 → 占位建号，先把 uid 发下去。

    ## 为什么注册要分两段
    用户要求的流程是「第一屏填完 → 第二屏显示 **uid**（只读、可长按复制）再设密码」。
    uid 由服务端分配，客户端在设密码之前拿不到它 —— 所以 uid 必须在第一段就落地。

    ## 占位行怎么写
    `users.password_hash` 是 `NOT NULL`，所以占位行写一个**哨兵哈希**：
    `verify_password` 要求 pbkdf2 前缀，非该前缀一律 False ——
    这个账号在设密码之前**永远登录不进来**。配合 `status='pending'`，
    `require_user` 也会把它挡在所有需要登录的接口之外。

    ## 存量用户零影响
    老行是 `status='active'` 且哈希是真的，走原来的登录路径，一行都不改。
    """
    nickname = body.nickname.strip()
    if not nickname:
        raise HTTPException(status_code=400, detail="请填写昵称")
    if len(nickname) > 20:
        raise HTTPException(status_code=400, detail="昵称最多 20 个字")
    email = body.email.strip().lower()
    if "@" not in email or email.startswith("@") or email.endswith("@") or len(email) > 120:
        raise HTTPException(status_code=400, detail="邮箱格式不对")

    # 验证码：与「绑定邮箱」同一套判据（未用 / 未过期 / 码一致）
    vrow = query_one("SELECT * FROM verify_codes WHERE email = ?", (email,))
    if vrow is None or vrow["used"]:
        raise HTTPException(status_code=400, detail="请先获取验证码")
    if parse_iso(vrow["expires_at"]) < datetime.now(timezone.utc):
        raise HTTPException(status_code=400, detail="验证码已过期")
    if not hmac.compare_digest(vrow["code"], body.code.strip()):
        raise HTTPException(status_code=400, detail="验证码不对")
    # ⚠️ 邮箱查重**必须带 status 条件**，放在下面和昵称一起做：
    #    这里早先有一处不带条件的查重，会把用户自己上一段的半成品判成"已注册"，
    #    而那个账号没有密码、登不进去 —— 等于用自己的半成品把自己锁在门外。

    now = datetime.now(timezone.utc)

    # ── 先回收**过期的半成品行** ────────────────────────────────────────────
    # 半成品行在服务端只有昵称/邮箱（对话与人设都在客户端），删掉无数据风险。
    # 不回收的话，用户在第一屏中途退出就把昵称永久占住了。
    stale_before = (now - timedelta(hours=PENDING_REGISTRATION_HOURS)).isoformat()
    execute(
        "DELETE FROM user_tokens WHERE user_id IN "
        "(SELECT id FROM users WHERE status = 'pending' AND created_at < ?)",
        (stale_before,),
    )
    execute("DELETE FROM users WHERE status = 'pending' AND created_at < ?", (stale_before,))

    # ── 只有**已激活**的行才算"被占用" ─────────────────────────────────────
    # ⚠️ 半成品行不算占用：它没有密码、登不进去，正是**用户自己上一段留下的**。
    #    早先这里漏了 status 条件，结果用户退出后重来会被 409 挡死 —— 而那个账号
    #    又登不进去，等于用自己的半成品把自己锁在门外（实测踩到）。
    if query_one("SELECT 1 FROM users WHERE email = ? AND status = 'active'", (email,)) is not None:
        raise HTTPException(status_code=409, detail="这个邮箱已经注册过了，直接登录就好")
    if query_one("SELECT 1 FROM users WHERE nickname = ? AND status = 'active'", (nickname,)) is not None:
        raise HTTPException(status_code=409, detail="这个昵称已经被用了，换一个")

    # ── 能接着用就接着用：同邮箱（续上）或同昵称（且没过期）────────────────
    mine = query_one(
        "SELECT * FROM users WHERE status = 'pending' AND (email = ? OR nickname = ?) "
        "ORDER BY created_at DESC",
        (email, nickname),
    )
    if mine is not None:
        user_id = mine["id"]
        uid = int(mine["uid"])
        # 先把**其它**会撞车的半成品行回收掉（只可能撞 email / nickname 的唯一性）
        execute(
            "DELETE FROM user_tokens WHERE user_id IN "
            "(SELECT id FROM users WHERE status = 'pending' AND id != ? AND (email = ? OR nickname = ?))",
            (user_id, email, nickname),
        )
        execute(
            "DELETE FROM users WHERE status = 'pending' AND id != ? AND (email = ? OR nickname = ?)",
            (user_id, email, nickname),
        )
        # 原地复用这一行：**uid 不变**（第二屏已经把 uid 显示给用户了，不能变）
        execute(
            "UPDATE users SET nickname = ?, email = ?, password_hash = ?, created_at = ?, status = 'pending' "
            "WHERE id = ?",
            (nickname, email, PENDING_PASSWORD_SENTINEL, now_iso(), user_id),
        )
        # 上一段发的令牌作废（用户可能换了设备重来）
        execute("DELETE FROM user_tokens WHERE user_id = ?", (user_id,))
    else:
        uid = _next_uid()
        user_id = "u_" + secrets.token_hex(8)
        execute(
            "INSERT INTO users(id, uid, nickname, password_hash, email, created_at, status) "
            "VALUES(?,?,?,?,?,?, 'pending')",
            (user_id, uid, nickname, PENDING_PASSWORD_SENTINEL, email, now_iso()),
        )

    execute("UPDATE verify_codes SET used = 1 WHERE email = ?", (email,))
    row = query_one("SELECT * FROM users WHERE id = ?", (user_id,))
    return {"uid": str(uid), "user": _user_public(row), **_issue_tokens(user_id)}


@app.put(f"{API_V1}/auth/register/finish")
def auth_register_finish(
    body: RegisterFinishIn,
    user_id: str = Depends(require_pending_user),
) -> dict[str, Any]:
    """注册**第二段**：设密码（+ 可选头像）→ 账号激活。

    ⚠️ 密码格式**在这里**校验（不是登录时）—— 老用户的密码可能含白名单外的字符，
       登录路径一校验就把他们挡在门外了。
    """
    err = password_error(body.password)
    if err:
        raise HTTPException(status_code=400, detail=err)
    avatar = (body.avatar_url or "").strip() or None
    execute(
        "UPDATE users SET password_hash = ?, avatar_url = ?, status = 'active', last_login_at = ? WHERE id = ?",
        (hash_password(body.password), avatar, now_iso(), user_id),
    )
    row = query_one("SELECT * FROM users WHERE id = ?", (user_id,))
    return {"uid": str(row["uid"]), "user": _user_public(row)}


@app.post(f"{API_V1}/auth/login")
def auth_login(body: UserLoginIn) -> dict[str, Any]:
    account = body.account.strip()
    # ⚠️ **只认 UID**（用户 2026-10-02：「登录只能用 uid，不能用昵称」）。
    #    昵称是可变、可重复的展示名，拿它当登录凭据既不唯一也容易撞。
    row = query_one("SELECT * FROM users WHERE uid = ?", (int(account),)) if account.isdigit() else None
    if row is None or not verify_password(body.password, row["password_hash"]):
        # 刻意**不区分**"账号不存在"与"密码错" —— 否则等于白送一个枚举昵称的接口
        raise HTTPException(status_code=401, detail="账号或密码不对")
    if row["status"] != "active":
        raise HTTPException(status_code=403, detail="这个账号已被停用")

    execute("UPDATE users SET last_login_at = ? WHERE id = ?", (now_iso(), row["id"]))
    return {"uid": str(row["uid"]), "user": _user_public(row), **_issue_tokens(row["id"])}


@app.post(f"{API_V1}/auth/refresh")
def auth_refresh(body: RefreshIn) -> dict[str, Any]:
    raw = body.refresh.strip()
    if not raw:
        raise HTTPException(status_code=400, detail="缺少 refresh")
    row = query_one("SELECT user_id, expires_at FROM user_tokens WHERE token_hash = ?", (sha256_hex(raw),))
    if row is None or parse_iso(row["expires_at"]) < datetime.now(timezone.utc):
        raise HTTPException(status_code=401, detail="登录已过期，请重新登录")
    user = query_one("SELECT * FROM users WHERE id = ?", (row["user_id"],))
    if user is None:
        raise HTTPException(status_code=401, detail="登录已失效")
    # 旧的 refresh 用掉即作废（防重放）
    execute("DELETE FROM user_tokens WHERE token_hash = ?", (sha256_hex(raw),))
    return {"uid": str(user["uid"]), "user": _user_public(user), **_issue_tokens(user["id"])}


@app.post(f"{API_V1}/auth/logout")
def auth_logout(user_id: str = Depends(require_user)) -> dict[str, Any]:
    execute("DELETE FROM user_tokens WHERE user_id = ?", (user_id,))
    return {"ok": True}


@app.get(f"{API_V1}/me")
def auth_me(user_id: str = Depends(require_user)) -> dict[str, Any]:
    row = query_one("SELECT * FROM users WHERE id = ?", (user_id,))
    if row is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    return {"user": _user_public(row)}


# ── 资料与密码（开发文档 §19.3）──

@app.put(f"{API_V1}/user/nickname")
def user_set_nickname(body: ProfilePatchIn, user_id: str = Depends(require_user)) -> dict[str, Any]:
    nickname = (body.nickname or "").strip()
    if not nickname:
        raise HTTPException(status_code=400, detail="昵称不能为空")
    if len(nickname) > 20:
        raise HTTPException(status_code=400, detail="昵称最多 20 个字")
    if query_one("SELECT 1 FROM users WHERE nickname = ? AND id != ?", (nickname, user_id)) is not None:
        raise HTTPException(status_code=409, detail="这个昵称已经被用了")
    execute("UPDATE users SET nickname = ? WHERE id = ?", (nickname, user_id))
    return {"user": _user_public(query_one("SELECT * FROM users WHERE id = ?", (user_id,)))}


@app.put(f"{API_V1}/user/avatar")
def user_set_avatar(body: ProfilePatchIn, user_id: str = Depends(require_user)) -> dict[str, Any]:
    execute("UPDATE users SET avatar_url = ? WHERE id = ?", (body.avatar_url, user_id))
    return {"user": _user_public(query_one("SELECT * FROM users WHERE id = ?", (user_id,)))}

# ── 人设端到端加密同步（v0.52.0）──
#
# ⚠️ 这两条接口**只搬字节**：人设在客户端用「用户密码派生出来的密钥」加密后上传，
#    服务端既不持密钥、也不解析内容。没有明文，拖库也拿不到任何人设。
#    聊天记录与记忆**永不经过这里**（红线）。
# ⚠️ 三处新接口（persona 读写 + 头像上传）都走 require_user，与 user_set_avatar 同门。

@app.get(f"{API_V1}/user/persona")
def user_get_persona(user_id: str = Depends(require_user)) -> dict[str, Any]:
    """取回本人的设**密文**（没同步过则 blob 为 null）。"""
    row = query_one(
        "SELECT data, rev, updated_at FROM user_blobs WHERE user_id = ? AND kind = 'persona'",
        (user_id,),
    )
    if row is None:
        return {"ok": True, "blob": None, "rev": 0}
    return {
        "ok": True,
        "blob": row["data"],
        "rev": int(row["rev"] or 0),
        "updated_at": row["updated_at"],
    }


@app.put(f"{API_V1}/user/persona")
def user_put_persona(body: PersonaBlobIn, user_id: str = Depends(require_user)) -> dict[str, Any]:
    """盲存人设**密文**（同账号跨设备恢复用）。"""
    blob = (body.blob or "").strip()
    if not blob:
        raise HTTPException(status_code=400, detail="空的人设密文不存")
    # 上限 5MB（v0.61.21 由 2MB 放宽）：人设快照现在**把人设头像也内嵌进来**
    #（base64 后体积约 ×1.33），几张图就能顶到 MB 级；纯文本部分仍然只有几 KB。
    # 留足余量的同时仍然挡住异常体积 —— 这个接口是"盲存密文"，服务端看不见内容，
    # 所以体积是它唯一能自保的闸门。
    if len(blob) > 5 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="人设密文过大（上限 5MB）")
    execute(
        "INSERT INTO user_blobs(user_id, kind, data, rev, updated_at) VALUES(?, 'persona', ?, ?, ?) "
        "ON CONFLICT(user_id, kind) DO UPDATE SET data = excluded.data, "
        "rev = excluded.rev, updated_at = excluded.updated_at",
        (user_id, blob, int(body.rev), now_iso()),
    )
    return {"ok": True, "rev": int(body.rev)}


@app.post(f"{API_V1}/user/avatar/upload")
async def user_upload_avatar(
    request: Request,
    file: UploadFile = File(...),
    user_id: str = Depends(require_user),
) -> dict[str, Any]:
    """上传**当前用户**的头像图，返回相对 URL（客户端随后调 /user/avatar 存下来）。

    白名单与体积上限与 `admin_upload` **同源**（不另立一套判据）；存到
    `uploads/avatars/` 子目录，与后台的收款码 / APK 分开。
    """
    ext = Path(file.filename or "").suffix.lower()
    if ext not in ALLOWED_IMAGE_EXT:
        raise HTTPException(status_code=400, detail="只能上传图片（png / jpg / jpeg / webp / gif）")
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="文件是空的，没读到位")
    if len(data) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=400, detail="图片太大了（上限 5MB）")
    avatar_dir = UPLOAD_DIR / "avatars"
    avatar_dir.mkdir(parents=True, exist_ok=True)
    fname = datetime.now(timezone.utc).strftime("%Y%m%d") + "_" + secrets.token_hex(6) + ext
    (avatar_dir / fname).write_bytes(data)
    url = f"/uploads/avatars/{fname}"
    # 顺手记进账号资料 —— 客户端一次调用就完成"本地 + 服务器"双写，不必再调 /user/avatar。
    execute("UPDATE users SET avatar_url = ? WHERE id = ?", (url, user_id))
    audit(f"user:{user_id}", "avatar_upload", url, client_ip(request))
    return {"ok": True, "url": url}


@app.put(f"{API_V1}/user/password")
def user_set_password(body: PasswordChangeIn, user_id: str = Depends(require_user)) -> dict[str, Any]:
    row = query_one("SELECT * FROM users WHERE id = ?", (user_id,))
    if row is None or not verify_password(body.old_password, row["password_hash"]):
        raise HTTPException(status_code=401, detail="原密码不对")
    # 密码格式统一走 password_error（用户要求：只允许数字/大小写英文/符号）
    _perr = password_error(body.new_password)
    if _perr:
        raise HTTPException(status_code=400, detail=_perr)
    execute("UPDATE users SET password_hash = ? WHERE id = ?", (hash_password(body.new_password), user_id))
    # 改密码 → 所有旧令牌立刻失效。
    # 否则"改了密码以为安全了"是错觉：别处的旧令牌照样能用。
    execute("DELETE FROM user_tokens WHERE user_id = ?", (user_id,))
    return {"ok": True, "relogin": True}


# ── 邮箱绑定与找回密码（开发文档 §19.3）──

def _send_code(email: str, purpose: str) -> dict[str, Any]:
    """发一个验证码。

    邮件**未配置时不报错**，而是走降级：验证码留在库里，
    由管理后台的「待用验证码」页读出来给你（自用场景完全够，
    也避免了另一个项目踩过的"邮件没配好 → 注册死锁"）。
    """
    email = email.strip().lower()
    if "@" not in email or len(email) < 5:
        raise HTTPException(status_code=400, detail="邮箱格式不对")

    prev = query_one("SELECT sent_at FROM verify_codes WHERE email = ?", (email,))
    if prev is not None:
        elapsed = (datetime.now(timezone.utc) - parse_iso(prev["sent_at"])).total_seconds()
        if elapsed < CODE_RESEND_SECONDS:
            raise HTTPException(
                status_code=429,
                detail=f"请求太频繁，请 {int(CODE_RESEND_SECONDS - elapsed)} 秒后再试",
            )

    code = f"{secrets.randbelow(1_000_000):06d}"
    expires = (datetime.now(timezone.utc) + timedelta(minutes=CODE_TTL_MINUTES)).isoformat(timespec="seconds")
    execute(
        "INSERT INTO verify_codes(email, code, expires_at, sent_at, used) VALUES(?,?,?,?,0) "
        "ON CONFLICT(email) DO UPDATE SET code=excluded.code, expires_at=excluded.expires_at, "
        "sent_at=excluded.sent_at, used=0",
        (email, code, expires, now_iso()),
    )

    if mail_ready():
        ok, message = send_mail(
            email,
            f"【{APP_NAME}】{purpose}验证码",
            f"你的验证码是：{code}\n\n{CODE_TTL_MINUTES} 分钟内有效，请勿转发给他人。\n\n—— {APP_NAME}",
        )
        if ok:
            return {"ok": True, "sent": True, "degraded": False}
        return {"ok": False, "sent": False, "degraded": True, "message": message}

    return {
        "ok": True,
        "sent": False,
        "degraded": True,
        "message": "邮件服务尚未配置。验证码已生成，请到管理后台「邮件服务」页查看。",
    }


@app.post(f"{API_V1}/auth/email/send-code")
def auth_email_send_code(body: EmailSendCodeIn) -> dict[str, Any]:
    email = body.email.strip().lower()
    # ⚠️ 已经绑在某个 active 账号上的邮箱，不再发注册验证码
    #    （用户 2026-10-02：「注册被绑过的邮箱不能重复获取验证码进行绑定」）。
    #    放在发码这一步拦，比等到 finish 才报错省一次往返、也少一封无用邮件。
    if query_one("SELECT 1 FROM users WHERE email = ? AND status = 'active'", (email,)) is not None:
        raise HTTPException(status_code=409, detail="这个邮箱已经注册过了，直接登录或用它找回密码")
    return _send_code(email, "绑定邮箱")


@app.post(f"{API_V1}/auth/email/bind")
def auth_email_bind(body: EmailBindIn, user_id: str = Depends(require_user)) -> dict[str, Any]:
    email = body.email.strip().lower()
    row = query_one("SELECT * FROM verify_codes WHERE email = ?", (email,))
    if row is None or row["used"]:
        raise HTTPException(status_code=400, detail="请先获取验证码")
    if parse_iso(row["expires_at"]) < datetime.now(timezone.utc):
        raise HTTPException(status_code=400, detail="验证码已过期")
    if not hmac.compare_digest(row["code"], body.code.strip()):
        raise HTTPException(status_code=400, detail="验证码不对")
    if query_one("SELECT 1 FROM users WHERE email = ? AND id != ?", (email, user_id)) is not None:
        raise HTTPException(status_code=409, detail="这个邮箱已经绑过别的账号了")

    execute("UPDATE verify_codes SET used = 1 WHERE email = ?", (email,))
    execute("UPDATE users SET email = ? WHERE id = ?", (email, user_id))
    return {"user": _user_public(query_one("SELECT * FROM users WHERE id = ?", (user_id,)))}


def _email_of_uid(uid: str) -> str:
    """**UID → 邮箱**（v0.60.0 · 找回密码用）。

    用户要求「忘记密码界面输入 UID」，而验证码只能发到邮箱 —— 所以服务端得先反查。

    ⚠️ 它确实会暴露"某个 UID 是否存在"（404 vs 继续）。这是**必须的**：
        找回密码的场景下，用户输错 UID 却不给任何反馈，他根本不知道下一步该干什么。
        缓解：只回固定的三种文案，不带昵称、注册时间等任何其它信息。
    """
    raw = uid.strip()
    if not raw.isdigit():
        raise HTTPException(status_code=400, detail="UID 是一串数字，比如 10030")
    row = query_one("SELECT email FROM users WHERE uid = ? AND status = 'active'", (int(raw),))
    if row is None:
        raise HTTPException(status_code=404, detail="没有这个 UID")
    if not row["email"]:
        raise HTTPException(status_code=404, detail="这个账号没有绑定邮箱，没法用验证码找回，请用旧密码在设置里改")
    return row["email"]


@app.post(f"{API_V1}/auth/password/send-code")
def auth_password_send_code(body: EmailSendCodeIn) -> dict[str, Any]:
    # 优先 uid（新客户端）；没有就退回 email（老客户端）
    if body.uid.strip():
        return _send_code(_email_of_uid(body.uid), "找回密码")
    email = body.email.strip().lower()
    if not email:
        raise HTTPException(status_code=400, detail="请填写 UID")
    return _send_code(email, "找回密码")


@app.post(f"{API_V1}/auth/password/reset")
def auth_password_reset(body: PasswordResetIn) -> dict[str, Any]:
    # 优先 uid（新客户端）；没有就退回 email（老客户端）
    email = _email_of_uid(body.uid) if body.uid.strip() else body.email.strip().lower()
    if not email:
        raise HTTPException(status_code=400, detail="请填写 UID")
    row = query_one("SELECT * FROM verify_codes WHERE email = ?", (email,))
    if row is None or row["used"]:
        raise HTTPException(status_code=400, detail="请先获取验证码")
    if parse_iso(row["expires_at"]) < datetime.now(timezone.utc):
        raise HTTPException(status_code=400, detail="验证码已过期")
    if not hmac.compare_digest(row["code"], body.code.strip()):
        raise HTTPException(status_code=400, detail="验证码不对")
    user = query_one("SELECT * FROM users WHERE email = ?", (email,))
    if user is None:
        raise HTTPException(status_code=404, detail="这个邮箱没有绑定过账号")
    # 密码格式统一走 password_error（用户要求：只允许数字/大小写英文/符号）
    _perr = password_error(body.new_password)
    if _perr:
        raise HTTPException(status_code=400, detail=_perr)

    execute("UPDATE verify_codes SET used = 1 WHERE email = ?", (email,))
    execute("UPDATE users SET password_hash = ? WHERE id = ?", (hash_password(body.new_password), user["id"]))
    execute("DELETE FROM user_tokens WHERE user_id = ?", (user["id"],))
    return {"ok": True}


# ── 邮箱验证码**登录**（v0.61.24.5，用户 2026-10-05 要求）──────────────────
#
# 与「绑定邮箱 / 注册」那条发码链路**方向正好相反**：
#   · /auth/email/send-code（绑定/注册）：**已注册的邮箱拒绝**（409）；
#   · 这两个（登录）：**只有已注册且正常的邮箱才发** —— 没注册的人该去注册。
#
# ⚠️ 人设同步密钥是「密码 + uid」派生的，而验证码登录**没有明文密码** →
#    客户端在登录成功后要**另外让用户设一次密码**（用户选的方案 (a)）。
#    服务端不掺和这一步：它只负责"把人认出来"。

class EmailLoginIn(BaseModel):
    email: str
    code: str


@app.post(f"{API_V1}/auth/login/email/send-code")
def auth_login_email_send_code(body: EmailSendCodeIn) -> dict[str, Any]:
    """邮箱验证码登录 · 第一步：发码。"""
    email = body.email.strip().lower()
    user = query_one("SELECT * FROM users WHERE email = ?", (email,))
    # ⚠️ 文案**刻意含糊**：不区分"这个邮箱没注册"与"账号被停用" ——
    #    否则这个接口就成了"批量探测哪些邮箱注册过"的工具。
    if user is None or user["status"] != "active":
        raise HTTPException(status_code=404, detail="这个邮箱没有可登录的账号")
    return _send_code(email, "登录")


@app.post(f"{API_V1}/auth/login/email")
def auth_login_email(body: EmailLoginIn) -> dict[str, Any]:
    """邮箱验证码登录 · 第二步：用码换令牌。

    用码校验与 `auth_password_reset` 同一套（used / 过期 / **常数时间**比较）。
    """
    email = body.email.strip().lower()
    row = query_one("SELECT * FROM verify_codes WHERE email = ?", (email,))
    if row is None or row["used"]:
        raise HTTPException(status_code=400, detail="请先获取验证码")
    if parse_iso(row["expires_at"]) < datetime.now(timezone.utc):
        raise HTTPException(status_code=400, detail="验证码已过期")
    if not hmac.compare_digest(row["code"], body.code.strip()):
        raise HTTPException(status_code=400, detail="验证码不对")

    user = query_one("SELECT * FROM users WHERE email = ?", (email,))
    if user is None:
        raise HTTPException(status_code=404, detail="这个邮箱没有绑定过账号")
    if user["status"] != "active":
        raise HTTPException(status_code=403, detail="这个账号已被停用")

    execute("UPDATE verify_codes SET used = 1 WHERE email = ?", (email,))
    execute("UPDATE users SET last_login_at = ? WHERE id = ?", (now_iso(), user["id"]))
    return {"uid": str(user["uid"]), "user": _user_public(user), **_issue_tokens(user["id"])}



# ═══════════════════════════════════════════════════════════════════════════
# 管理后台网页（静态文件）
# ═══════════════════════════════════════════════════════════════════════════
#
# ⚠️ 挂载在**根**上，不是挂 `/admin`。
#
# 原因在 Nginx 那一层：外网入口是 `https://example.com/yuki/`，
# 配置是 `location /yuki/ { proxy_pass http://127.0.0.1:11445/; }` ——
# **末尾的斜杠会把 `/yuki` 前缀剥掉**。所以后端看到的路径是 `/`、`/admin.css`、
# 而不是 `/yuki/`、`/yuki/admin.css`。挂在 `/admin` 上的话，浏览器请求
# `/yuki/admin.css` 会落到后端的 `/admin.css`，**404**。
#
# 顺序很重要：FastAPI/Starlette 按注册先后匹配，这个 mount 放在**最末**，
# 因此前面注册的 `/api/*` 与 `/health` 不会被它抢走。

# ── 官网（用户 2026-09-29 要求）──
# ⚠️ 顺序：这两条必须**注册在末尾那个 mount("/") 之前** —— Starlette 按注册先后匹配，
#    挂在 mount 后面就永远轮不到它们。
@app.get("/api/site")
def site_info() -> Any:
    """官网页脚用的版本信息。

    ## ⚠️ v0.50.0：版本号改从 `app_releases` 读，不再硬编码
    原来这里是 `SITE_VERSION = "0.45.3"`，靠**人工在发版时改那一行**。
    实际发生了两件坏事：
      ① 它漂了 —— 库里的值停在 0.45.3，而项目早到 0.49.0；
      ② 更麻烦的是**两个口径会不一致** —— 官网说一个版本、App 的更新检查
         说另一个（后者读 `app_releases`），用户看到"官网说 0.49、App 说 0.50"。
    现在两处**同源**：都读 `app_releases` 的最新一行。

    读不到（还没发过版）时退回常量 —— 那是"首次部署"的正常状态，官网得有个东西显示。

    包大小与时间仍然**从文件真实读**，不写死 —— 写死的大小在一次重新打包之后就成了撒谎。
    """
    apk = STATIC_DIR / "d" / "yuki.apk"
    try:
        st = apk.stat()
        size, mtime = st.st_size, st.st_mtime
    except OSError:
        size, mtime = 0, 0
    import datetime
    t = datetime.datetime.fromtimestamp(mtime).strftime("%Y-%m-%d") if mtime else ""

    latest = query_one("SELECT * FROM app_releases ORDER BY version_code DESC LIMIT 1")
    version = SITE_VERSION
    if latest is not None:
        ready, _, _ = apk_serve_state(latest)
        # 没绑好包就别在下载页上宣称这个版本（否则页面说的和下载到的是两回事）
        if ready:
            version = str(latest["version_name"])
    return JSONResponse({"version": version, "apk_size": size, "apk_time": t})


# ══════════════════ 免费分组「Yuki初雪Pro」（v0.58.0）══════════════════
#
# 用户要求：「增加一个由我后端服务器下发的动态可在后端修改的免费分组」——
# 也就是**地址与密钥由我一个人在后台维护**，所有客户端只是"用"它。
# 所以下发接口是**公开的**（不需要令牌：不注册也能用的应用，这条也得能用），
# 而"改"只在 `/api/admin/free-group`（要管理员令牌）。
#
# ⚠️ 客户端拿到密钥后**只用来发请求**，界面不显示也不允许修改（见客户端
#    `ProviderGroup.managed`）。这意味着：**这个密钥必然存在于客户端本地** ——
#    "看不到"是界面上的看不到，不是取不出来。别对外声称后者。

log_free = logging.getLogger("yuki.freegroup")

FREE_GROUP_KEY = "free_group"
FREE_GROUP_DEFAULT: dict[str, Any] = {
    "enabled": True,
    "name": "Yuki初雪Pro",
    "base_url": os.environ.get("FREE_GROUP_BASE_URL", ""),  # ⚠️ 同上，部署方自备中转地址,
    "api_key": os.environ.get("FREE_GROUP_API_KEY", ""),   # ⚠️ 源码分发版不内置密钥，部署方自备,
    "notice": "官方免费提供。地址与密钥由服务器维护，不需要填写，也不能修改。",
    # ⚠️ 免费分组**能用哪些模型**由这里定，不下发模型列表接口 ——
    #    免费额度是这边出钱，用哪家、哪个模型更划算只有这边说了算；
    #    客户端"拉列表取第一个"既不稳定（顺序由服务商定，随时会变），
    #    又多一条会失败的请求路径。客户端拿到的就是最终答案。
    #    空表 = 没配 → 客户端那条分组就是"没有模型"的，用户仍可在它上面自己勾。
    #
    # ⚠️ 这个清单是**实测**来的（2026-10-04 用后台那个「拉取真实模型列表」按钮，
    #    拿 FREE_GROUP_BASE_URL + FREE_GROUP_API_KEY 真去问了一次）。原来我按常识填的
    #    `deepseek-chat` **在这家根本没有** —— 客户端会拿它发请求然后报错。
    #    换服务商时：设好上面两个环境变量 → 后台点一次「拉取」→ 挑好 → 保存，别凭印象填。
    ⚠️ 源码分发版**不含**默认服务商，这段列表只是示例 —— 模型名必须与你自己的服务商匹配。
    #    顺序有意义：**第一个是客户端默认用的那个**，所以把便宜的 flash 放前面。
    "models": [
        "deepseek-flash",
        "deepseek-v4-pro",
        "deepseek-v4.1-flash",
    ],
}


def _norm_models(raw: Any) -> list[dict[str, str]]:
    """把 `models` 规范化成 `[{"id": ..., "label": ...}]`。

    ## 为什么要有这个形状
    用户 2026-10-04 的问题：「我拉取之后改了模型名称，用户请求的时候发的是哪个？」
    答：改造之前发的是**改过的名字** —— 所以改名 = 请求必然失败。
    现在拆成两件事：
    · `id`    —— **真正发给服务商**的那个名字，必须是真实存在的模型；
    · `label` —— **只给用户看**的显示名，随便改，不影响请求。
    空 label = 显示 id 本身。

    ## 兼容两种写法（都要能吃）
    · `"deepseek-flash"`                → id 即显示名；
    · `"deepseek-flash=闪电"`            → id=deepseek-flash，显示名=闪电（后台输入框里的写法）；
    · `{"id": "...", "label": "..."}`    → 直接是规范形。
    """
    out: list[dict[str, str]] = []
    if not isinstance(raw, list):
        return out
    for item in raw:
        label = ""
        if isinstance(item, dict):
            mid = str(item.get("id") or "").strip()
            label = str(item.get("label") or "").strip()
        else:
            text = str(item or "").strip()
            if "=" in text:
                mid, _, label = text.partition("=")
                mid, label = mid.strip(), label.strip()
            else:
                mid = text
        if mid:
            out.append({"id": mid, "label": label})
    return out


def free_group_config() -> dict[str, Any]:
    """当前生效的免费分组配置（库里有就以库里为准）。

    ⚠️ 读不出来 / 存的是坏 JSON 都**退回默认值**而不是报错 ——
       这条配置坏掉不该让所有用户的连接设置里凭空少一项。
    """
    cfg = dict(FREE_GROUP_DEFAULT)
    raw = get_setting(FREE_GROUP_KEY, "")
    if raw.strip():
        try:
            saved = json.loads(raw)
            if isinstance(saved, dict):
                cfg.update({k: v for k, v in saved.items() if k in cfg})
        except Exception:
            log_free.warning("免费分组配置不是合法 JSON，改用默认值：%r", raw[:200])
    # ⚠️ 出口统一规范化：库里的老写法（纯字符串）也在这里变成 {id,label}
    cfg["models"] = _norm_models(cfg.get("models"))
    return cfg


@app.get(f"{API_PREFIX}/app/free-group")
def app_free_group() -> Any:
    """下发给客户端。**不需要令牌**（见上）。"""
    cfg = free_group_config()
    base_url = str(cfg.get("base_url") or "").strip()
    api_key = str(cfg.get("api_key") or "").strip()
    # ⚠️ fail-closed：缺地址或缺密钥时回 `enabled: false` ——
    #    下发一条不可用的配置只会让客户端多出一张点了发不出请求的卡片，
    #    而用户会以为是自己手机的问题。
    if not cfg.get("enabled") or not base_url or not api_key:
        return {"enabled": False}
    return {
        "enabled": True,
        "id": "yuki-pro",
        "name": str(cfg.get("name") or "Yuki初雪Pro"),
        "base_url": base_url,
        "api_key": api_key,
        "notice": str(cfg.get("notice") or ""),
        # 已经是 `[{"id":...,"label":...}]`（见 _norm_models）——
        # id 是**真正发给服务商**的名字，label 只给客户端显示用。
        "models": cfg["models"],
    }


class FreeGroupIn(BaseModel):
    enabled: bool = True
    name: str = ""
    base_url: str = ""
    api_key: str = ""
    notice: str = ""
    # 免费分组可用的模型：`id`（真的发给服务商）与 `label`（只给用户看）。
    # 用 Any 收：后台可能发 `"deepseek-flash"` 或 `"deepseek-flash=闪电"` 或对象，
    # 由 _norm_models 统一规范化。
    models: list[Any] = []


# ── 功能开关（v0.58.0）────────────────────────────────────────────────
#
# 用户要求：「当后台全局的通用设定开关是关闭状态的时候，编辑和创建人设界面
# **就没有**通用人设开关」。
#
# ⚠️ 它只控制**要不要展示那个开关**，**不改用户已有的选择** ——
#    关掉之后，已经开了通用设定的老人设照旧生效。
#    远程"顺手"改掉用户的设定是最容易招人骂的一类实现。
# ⚠️ 默认 **on**：这个开关是"出问题时能一键收掉"的保险，不是默认关闭的新功能；
#    默认关会让所有现存用户的功能凭空消失。

FEATURE_PERSONA_GP = "feature_persona_global_prefix"


@app.get(f"{API_PREFIX}/app/features")
def app_features() -> Any:
    """客户端"有哪些功能可用"。**不需要令牌**（未登录也要能用）。"""
    raw = get_setting(FEATURE_PERSONA_GP, "1").strip().lower()
    return {"persona_global_prefix": raw not in ("0", "false", "off", "no", "")}


class FeaturesIn(BaseModel):
    persona_global_prefix: bool = True


@app.get(f"{API_PREFIX}/admin/features")
def admin_get_features(actor: str = Depends(require_admin)) -> Any:
    return app_features()


@app.put(f"{API_PREFIX}/admin/features")
def admin_put_features(
    body: FeaturesIn,
    request: Request,
    actor: str = Depends(require_admin),
) -> Any:
    set_settings({FEATURE_PERSONA_GP: "1" if body.persona_global_prefix else "0"})
    audit(actor, "features_update", f"persona_global_prefix={body.persona_global_prefix}", client_ip(request))
    return {"ok": True, "persona_global_prefix": body.persona_global_prefix}


@app.get(f"{API_PREFIX}/admin/free-group")
def admin_get_free_group(actor: str = Depends(require_admin)) -> Any:
    return free_group_config()


@app.put(f"{API_PREFIX}/admin/free-group")
def admin_put_free_group(
    body: FreeGroupIn,
    request: Request,
    actor: str = Depends(require_admin),
) -> Any:
    """改免费分组（后台用）。改完**下次客户端启动**即生效（它们启动时拉一次）。"""
    cfg = {
        "enabled": bool(body.enabled),
        "name": body.name.strip() or str(FREE_GROUP_DEFAULT["name"]),
        "base_url": body.base_url.strip(),
        "api_key": body.api_key.strip(),
        "notice": body.notice.strip(),
        # ⚠️ 空表要**原样存空表**（那是"我就是要让客户端没有默认模型"的意思），
        #    不能顺手回填默认值 —— 否则后台永远删不掉那条模型。
        "models": _norm_models(body.models),
    }
    set_settings({FREE_GROUP_KEY: json.dumps(cfg, ensure_ascii=False)})
    audit(actor, "free_group_update", cfg["name"], client_ip(request))
    return {"ok": True, "config": cfg}


class FreeGroupProbeIn(BaseModel):
    """探测模型列表的入参：留空就用**已经存着**的地址/密钥。"""

    base_url: str = ""
    api_key: str = ""


def _models_endpoint(base_url: str) -> str:
    """把服务地址补成 `{base}/models`。

    ⚠️ 口径必须与客户端 `DeepSeekClient.normalizeBaseUrl` 一致，否则后台里拉得到、
       客户端却拉不到（或反过来）—— 同一件事两套规矩是这个项目的常见坑。
       规则：末段已经是 v1/v2/v3 这类版本号（或 beta / anthropic）就不再补；
       否则补 `/v1`。所以 `api.deepseek.com` → `…/v1/models`，
       `…/compatible-mode/v1` → 原样接 `/models`。
    """
    b = (base_url or "").strip().rstrip("/")
    if not b:
        return ""
    tail = b.rsplit("/", 1)[-1].lower()
    is_version = len(tail) > 1 and tail[0] == "v" and tail[1:].isdigit()
    if not is_version and tail not in ("beta", "anthropic"):
        b += "/v1"
    return b + "/models"


@app.post(f"{API_PREFIX}/admin/free-group/probe-models")
def admin_probe_free_group_models(
    body: FreeGroupProbeIn,
    request: Request,
    actor: str = Depends(require_admin),
) -> Any:
    """用**真实的服务地址**去拉一次模型清单（后台那个「拉取」按钮）。

    ## 为什么这一步在服务器上做，而不是让客户端自己拉
    用户 2026-10-04 的原话：「免费分组的分组设置的拉取模型列表的按钮改为我后端的」。
    理由和免费分组的地址/密钥由后端维护是同一条：**清单是后端说了算**。
    以后换了服务地址，模型清单必然变 —— 在后台点一下拉取、挑好、保存，
    客户端下次启动就跟着变，**不用发新版，用户也不会有感觉**。

    ## 它只读不写
    这个接口**不改任何配置**，只回一份清单给后台页面。要不要采用、采用哪些，
    由人在页面上决定（见 admin.js 的「快捷添加」）。
    """
    cfg = free_group_config()
    base = body.base_url.strip() or str(cfg.get("base_url") or "").strip()
    key = body.api_key.strip() or str(cfg.get("api_key") or "").strip()
    url = _models_endpoint(base)
    if not url:
        return {"ok": False, "models": [], "error": "服务地址是空的，先填一个"}
    if not key:
        return {"ok": False, "models": [], "error": "密钥是空的，先填一个"}

    try:
        req = urllib.request.Request(
            url,
            headers={"Authorization": f"Bearer {key}", "Accept": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            payload = json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        return {"ok": False, "models": [], "error": f"服务商返回 HTTP {e.code}（地址或密钥不对？）"}
    except Exception as e:  # 网络不通 / 超时 / 不是 JSON —— 都归成一句人话
        return {"ok": False, "models": [], "error": f"没拉到：{type(e).__name__}"}

    raw = payload.get("data") if isinstance(payload, dict) else None
    models: list[str] = []
    if isinstance(raw, list):
        for item in raw:
            if isinstance(item, dict):
                mid = str(item.get("id") or "").strip()
            elif isinstance(item, str):
                mid = item.strip()
            else:
                mid = ""
            if mid:
                models.append(mid)
    models = sorted(set(models))
    audit(actor, "free_group_probe_models", f"{len(models)} 个模型", client_ip(request))
    if not models:
        return {"ok": False, "models": [], "error": "这个地址没返回任何模型（有些中转不实现 /models）"}
    return {"ok": True, "models": models, "error": ""}


# ══════════════════ 反馈（官网表单 / App 入口，v0.57.0）══════════════════
#
# ⚠️ **不需要登录、不需要令牌**：官网的访客没有账号，而反馈是这个站点
#    唯一"能说话"的渠道 —— 给它加一道登录墙等于把它关掉。
# ⚠️ 没有令牌就得自己挡滥用：**同 IP 限流** + 长度上限。

log_fb = logging.getLogger("yuki.feedback")

_FEEDBACK_WINDOW = 60.0
_FEEDBACK_MAX_PER_IP = 5       # 同一 IP 每分钟最多 5 条
_FEEDBACK_CONTENT_MAX = 500    # 与用户定的口径一致：内容 ≤500 字
_FEEDBACK_CONTACT_MAX = 100    # 联系方式 ≤100 字（可选）
_FEEDBACK_REPLY_MAX = 2000
_feedback_hits: dict[str, list[float]] = {}


class FeedbackIn(BaseModel):
    content: str = Field(default="")
    contact: str = Field(default="")
    kind: str = Field(default="other")


def _client_ip(request: Request) -> str:
    """取真实来源 IP。站点在 Nginx 后面，所以先看 `X-Forwarded-For`。"""
    fwd = request.headers.get("x-forwarded-for", "")
    if fwd:
        return fwd.split(",")[0].strip()
    return request.client.host if request.client else ""


@app.post("/api/feedback")
def submit_feedback(
    payload: FeedbackIn,
    request: Request,
    authorization: str = Header(default=""),
) -> Any:
    """收一条反馈（官网表单与 App 入口共用这一个）。"""
    ip = _client_ip(request)
    now = time.time()
    hits = [t for t in _feedback_hits.get(ip, []) if now - t < _FEEDBACK_WINDOW]
    if len(hits) >= _FEEDBACK_MAX_PER_IP:
        raise HTTPException(status_code=429, detail="发得太快了，歇一分钟再试")
    hits.append(now)
    _feedback_hits[ip] = hits

    content = (payload.content or "").strip()
    contact = (payload.contact or "").strip()
    if not content:
        raise HTTPException(status_code=400, detail="写点什么再提交吧")
    if len(content) > _FEEDBACK_CONTENT_MAX:
        raise HTTPException(
            status_code=400,
            detail=f"内容请控制在 {_FEEDBACK_CONTENT_MAX} 字以内（现在 {len(content)} 字）",
        )
    if len(contact) > _FEEDBACK_CONTACT_MAX:
        raise HTTPException(
            status_code=400,
            detail=f"联系方式请控制在 {_FEEDBACK_CONTACT_MAX} 字以内",
        )

    kind = (payload.kind or "other").strip()[:32] or "other"
    # ⚠️ 登录了就记下 user_id（没登录留空串）：客户端认"哪几条是我的"只能靠它。
    #    没登录也能发 —— 官网表单就是匿名的，不该因为没账号就收不到反馈。
    me = _optional_user_id(authorization) or ""
    execute(
        "INSERT INTO feedback(kind, content, contact, ip, ua, created_at, user_id) "
        "VALUES(?,?,?,?,?,?,?)",
        (
            kind,
            content,
            contact,
            ip,
            request.headers.get("user-agent", "")[:300],
            datetime.now(timezone.utc).isoformat(),
            me,
        ),
    )
    log_fb.info("收到反馈 kind=%s ip=%s len=%d", kind, ip, len(content))
    return JSONResponse({"ok": True, "hint": "收到了，谢谢你"})


# ── 反馈的读取与回复（v0.58.0）────────────────────────────────────────
#
# 用户要求：「后端必须有能回复反馈的功能」。
# ⚠️ 在这之前这条链路是**只写不读**的：反馈进了库，但没有列表接口、后台页里也没有这一页，
#    除了直接开 sqlite 谁也看不到。所以这次补的是**整条回路**：
#    后台看得到 → 能回复 → 客户端看得到回复。

def _feedback_out(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "kind": row["kind"],
        "content": row["content"],
        "contact": row["contact"],
        "createdAt": row["created_at"],
        "reply": row["reply"],
        "repliedAt": row["replied_at"],
        # ⚠️ 不回 ip / ua：那是排查用的内部信息，客户端没有任何理由拿到自己的公网 IP 与 UA
        "mine": bool(row["user_id"]),
    }


@app.get(f"{API_PREFIX}/admin/feedback")
def admin_list_feedback(
    limit: int = 50,
    offset: int = 0,
    only_unreplied: bool = False,
    actor: str = Depends(require_admin),
) -> Any:
    """后台看反馈（新的在前）。`only_unreplied=true` 只看还没回的。"""
    limit = max(1, min(limit, 200))
    where = "WHERE reply = ''" if only_unreplied else ""
    rows = query(
        f"SELECT * FROM feedback {where} ORDER BY id DESC LIMIT ? OFFSET ?",
        (limit, max(0, offset)),
    )
    total = query_one(f"SELECT COUNT(*) AS n FROM feedback {where}")["n"]
    unreplied = query_one("SELECT COUNT(*) AS n FROM feedback WHERE reply = ''")["n"]
    return {
        "items": [_feedback_out(r) for r in rows],
        "total": total,
        "unreplied": unreplied,
        "limit": limit,
        "offset": max(0, offset),
    }


class FeedbackReplyIn(BaseModel):
    reply: str = Field(default="")


@app.post(f"{API_PREFIX}/admin/feedback/{{fid}}/reply")
def admin_reply_feedback(
    fid: int,
    body: FeedbackReplyIn,
    request: Request,
    actor: str = Depends(require_admin),
) -> Any:
    """回复一条反馈（后台用）。

    ⚠️ 传空串 = **撤销回复**（把状态退回"未回复"）—— 发错了得能收回来，
        否则那条会永远显示"已回复"而内容还是错的。
    """
    if query_one("SELECT id FROM feedback WHERE id = ?", (fid,)) is None:
        raise HTTPException(status_code=404, detail="这条反馈不在了")
    text = (body.reply or "").strip()
    if len(text) > _FEEDBACK_REPLY_MAX:
        raise HTTPException(
            status_code=400,
            detail=f"回复请控制在 {_FEEDBACK_REPLY_MAX} 字以内（现在 {len(text)} 字）",
        )
    execute(
        "UPDATE feedback SET reply = ?, replied_at = ? WHERE id = ?",
        (text, datetime.now(timezone.utc).isoformat() if text else "", fid),
    )
    audit(actor, "feedback_reply", f"#{fid}", _client_ip(request))
    return {"ok": True, "id": fid, "reply": text}


@app.get(f"{API_V1}/feedback/mine")
def my_feedback(user_id: str = Depends(require_user)) -> Any:
    """本人发过的反馈 + 后台的回复（App 的「我的反馈」用它）。

    ⚠️ 只按 `user_id` 取 —— 当年发的时候没登录（user_id 为空）就查不到自己那条，
       这是**刻意的**：那些条目没有归属，按 IP 认会把同一台路由器后面的人串在一起。
    """
    rows = query(
        "SELECT * FROM feedback WHERE user_id = ? ORDER BY id DESC LIMIT 50",
        (user_id,),
    )
    return {"items": [_feedback_out(r) for r in rows]}


@app.get("/admin")
def admin_page() -> Any:
    """管理后台。

    ⚠️ 它原来占着 `/`（那是对外地址），官网要放在 `/` 上，所以后台挪到这里。
       老书签会看到官网而不是登录页 —— 这是这次调整的代价，已知。
    """
    f = STATIC_DIR / "admin.html"
    if f.exists():
        return FileResponse(f)
    return JSONResponse({"ok": False, "hint": "admin.html 缺失"})


# ⚠️ 静态资源**必须禁缓存**（2026-09-29）。
# 理由：这是个还在天天改的站点，而移动端浏览器对 CSS 的缓存极其顽固 ——
# 「改了代码、刷新看不到」排查了两轮，最后都指向缓存。开发期不值得为那点带宽省时间。
#
# 做法：给所有静态响应补 `Cache-Control: no-cache`（**要求每次校验**，
# 不是禁止缓存 —— 没变的内容仍然走 304，不额外传字节）。
@app.middleware("http")
async def no_cache_static(request, call_next):
    resp = await call_next(request)
    path = request.url.path
    if path.startswith("/api/"):
        return resp
    # ⚠️ 上传目录可以**长缓存**：文件名是内容哈希（老图是 `<日期>_<随机>`），
    #    一律"写一次、永不改同名" —— 同一 URL 永远对应同一份字节，immutable 是安全的。
    #    出口带宽只有 300 KB/s，省掉重复下载对市场加载速度是直接收益。
    # ⚠️ 只给 **200** 加：把 404 也标成 immutable，等于让"图还没传完"这件事被缓存一年。
    if path.startswith("/uploads/") and resp.status_code == 200:
        resp.headers["Cache-Control"] = "public, max-age=31536000, immutable"
        return resp
    resp.headers["Cache-Control"] = "no-cache, must-revalidate"
    return resp



# ============================================================
# 心潮（Xinchao）代理 —— 2026-10-05 新增（Yuki 接入）
# ============================================================
# 心潮独立部署在同一台机器上（说明见 <心潮部署目录>\README-部署.md），只绑定 127.0.0.1:18110，
# 不直接对公网开端口 —— 外网统一走本后端：
#     nginx(example.com:11445) → 127.0.0.1:11446（本进程）→ 127.0.0.1:18110（心潮）
#
# 鉴权：/xinchao/health 与 /dashboard/* 公开（看板口令/限速在心潮自己那层）；
#       其余 /xinchao/* 要求登录令牌（Bearer <用户的 user token>）。
# 多租户说明（2026-10-06 起）：心潮已支持「单实例多状态」（按 personaId 分桶）——
#   state / event / register 三条路由先过 persona_owners 归属校验，再带 `X-Persona-Id` 转发；
#   只有本人能读写自己的人设，别人的 personaId 一律 404。
XINCHAO_BASE = os.environ.get("XINCHAO_BASE_URL", "http://127.0.0.1:18110").rstrip("/")
XINCHAO_CONF_PATH = os.environ.get(
    "XINCHAO_CONF_PATH", r"<心潮部署目录>\xinchao\xinchao.conf"
)
_XINCHAO_TOKEN_CACHE: dict[str, Any] = {"value": "", "mtime": None}


def _xinchao_service_token() -> str:
    """心潮的 SERVICE_TOKEN —— 直接读它的部署配置（单一真源，免二次配置）。

    按 mtime 缓存：文件没改就不重复解析；读不到时返回空串，调用方给明确的 503
    （而不是拿空 token 去撞心潮的 401，让人以为"密码错了"）。
    """
    try:
        mtime = os.path.getmtime(XINCHAO_CONF_PATH)
    except OSError:
        return ""
    if _XINCHAO_TOKEN_CACHE["mtime"] != mtime:
        value = ""
        try:
            with open(XINCHAO_CONF_PATH, "r", encoding="utf-8") as fh:
                for line in fh:
                    if line.startswith("SERVICE_TOKEN="):
                        value = line.split("=", 1)[1].strip()
                        break
        except OSError:
            value = ""
        _XINCHAO_TOKEN_CACHE["value"] = value
        _XINCHAO_TOKEN_CACHE["mtime"] = mtime
    return _XINCHAO_TOKEN_CACHE["value"]


def _xinchao_request(
    method: str,
    path: str,
    *,
    body: bytes | None = None,
    timeout: int = 15,
    persona_id: str | None = None,
) -> tuple[int, bytes, list[tuple[str, str]]]:
    """转发一次请求到心潮。返回 (状态码, 响应体, 要回传给浏览器的响应头)。

    ⚠️ Authorization 固定用本机 SERVICE_TOKEN（服务端代理，不外传用户自己的令牌）。
    ⚠️ 4xx/5xx 也读 body 原样回传 —— 心潮的错误信息对排障有用（urllib 会抛 HTTPError）。
    ⚠️ persona_id 非空时带 `X-Persona-Id` 头 —— 心潮按它选数据桶（单实例多状态）。
       归属校验在调用侧做完（见 _persona_owner_guard），这里只送已核实的 id。
    """
    token = _xinchao_service_token()
    if not token:
        raise HTTPException(status_code=503, detail="心潮服务未配置（读不到 SERVICE_TOKEN）")
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }
    if persona_id:
        headers["X-Persona-Id"] = persona_id
    req = urllib.request.Request(
        XINCHAO_BASE + path,
        data=body,
        method=method,
        headers=headers,
    )

    def _pick(resp: Any) -> list[tuple[str, str]]:
        picked: list[tuple[str, str]] = []
        ctype = resp.headers.get("Content-Type")
        if ctype:
            picked.append(("content-type", ctype))
        # 看板登录靠 set-cookie 会话；心潮每次只设一个 cookie，这里取全量更稳
        for sc in resp.headers.get_all("Set-Cookie") or []:
            picked.append(("set-cookie", sc))
        return picked

    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read(), _pick(resp)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read(), _pick(exc)
    except urllib.error.URLError as exc:
        raise HTTPException(status_code=502, detail=f"心潮连接失败：{exc.reason}")


async def _run_blocking(fn: Any, *args: Any, **kwargs: Any) -> Any:
    """把**阻塞**调用挪到线程池执行。

    ⚠️ 这是「别让一个慢上游卡死全站」的关键（2026-10-06 性能加固）：
    本服务用 `uvicorn` **单进程单 worker** 跑（见 start_backend.bat，无 --workers）。
    `async def` 端点里直接调 `urllib.request.urlopen`（同步 socket I/O，超时 8~120s）会把
    **整个事件循环**钉住 —— 心潮或 OB 一卡，全站所有用户的所有请求跟着卡，表现就是"服务器挂了"。
    丢进线程池后，阻塞只占一个线程，事件循环继续服务其它请求。
    """
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, functools.partial(fn, *args, **kwargs))


# ══════════════════ 人设归属（2026-10-06 · 心潮多租户）══════════════════
#
# 背景：心潮已支持「单实例多状态」（按 personaId 分桶），但心潮自身没有账号概念 ——
#   「这个 personaId 属于谁」只有本服务知道；而服务端又**看不到人设明文**
#   （user_blobs 是端到端加密的密文，无法从中反查 personaId→user）。
#   所以归属用「首次上报登记」建立：App 上报事件 / 显式 register 时写 persona_owners，
#   此后写入即锁定 —— 本人可读写、别人的请求一律 404（与"未登记"不可区分）。

PERSONA_ID_RE = re.compile(r"^[0-9a-zA-Z_-]{1,64}$")


def _valid_persona_id(persona_id: str) -> bool:
    """personaId 白名单（与心潮侧 server 的校验一致）：防路径穿越、防垃圾 id 透传。"""
    return bool(PERSONA_ID_RE.fullmatch(persona_id or ""))


def _json_or_none(raw: bytes | None) -> Any:
    try:
        return json.loads((raw or b"").decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None


def _persona_not_found() -> HTTPException:
    """统一的「查无此人设」响应 —— 不区分「未登记」与「属于他人」，不给探测空间。"""
    return HTTPException(status_code=404, detail="没有找到这个人设的状态（可能尚未接入心潮）")


def _persona_owner_guard(persona_id: str, user_id: str, *, register: bool = False) -> None:
    """人设归属校验 / 首次登记。

    register=False（读路径）：未登记或属于他人 → 404。
    register=True（上报路径）：未登记 → 登记为当前账号；本人 → 刷新 last_seen_at；他人 → 404。
    """
    row = query_one("SELECT user_id FROM persona_owners WHERE persona_id = ?", (persona_id,))
    if row is None:
        if not register:
            raise _persona_not_found()
        execute(
            "INSERT OR IGNORE INTO persona_owners(persona_id, user_id, created_at, last_seen_at) "
            "VALUES(?,?,?,?)",
            (persona_id, user_id, now_iso(), now_iso()),
        )
        # 复查：极端并发下 INSERT OR IGNORE 可能空转（同 id 被他人抢先登记）——以表内为准
        fresh = query_one("SELECT user_id FROM persona_owners WHERE persona_id = ?", (persona_id,))
        if fresh is None or fresh["user_id"] != user_id:
            audit(user_id, "xinchao_persona_denied", f"persona={persona_id} race")
            raise _persona_not_found()
        audit(user_id, "xinchao_persona_register", f"persona={persona_id}")
        return
    if row["user_id"] != user_id:
        audit(user_id, "xinchao_persona_denied", f"persona={persona_id} owner={row['user_id']}")
        raise _persona_not_found()
    if register:
        execute(
            "UPDATE persona_owners SET last_seen_at = ? WHERE persona_id = ?",
            (now_iso(), persona_id),
        )


# ══════════════════ 记忆大脑 OB（2026-10-06 · 记忆云同步）══════════════════
#
# App 的本机记忆是"简单检索"，OB（Ombre Brain，本机 127.0.0.1:18001）才是完整的
# 长期记忆系统（语义检索 / 做梦 / 地标…）。用户 2026-10-06 拍板：记忆**进 OB**（服务端可读）。
#   App → 本服务（归属校验）→ OB MCP（hold 写 / breath·breath_search 读），
#   带 `X-Persona-Id` 落到该人设自己的桶。
# 鉴权用 OB 的 mcp_token（读它自己的部署配置，单一真源，按 mtime 缓存）。

OB_BASE = os.environ.get("OB_BASE_URL", "http://127.0.0.1:18001").rstrip("/")
OB_CONF_PATH = os.environ.get(
    "OB_CONF_PATH", r"<心潮部署目录>\ombre-brain\config.yaml"
)
_OB_TOKEN_CACHE: dict[str, Any] = {"value": "", "mtime": None}


def _ob_mcp_token() -> str:
    """OB 的 mcp_token —— 读它的部署配置（单一真源，免二次配置；按 mtime 缓存）。"""
    try:
        mtime = os.path.getmtime(OB_CONF_PATH)
    except OSError:
        return ""
    if _OB_TOKEN_CACHE["mtime"] != mtime:
        value = ""
        try:
            with open(OB_CONF_PATH, "r", encoding="utf-8") as fh:
                for line in fh:
                    if line.strip().startswith("mcp_token:"):
                        value = line.split(":", 1)[1].strip().strip("\"'")
                        break
        except OSError:
            value = ""
        _OB_TOKEN_CACHE["value"] = value
        _OB_TOKEN_CACHE["mtime"] = mtime
    return _OB_TOKEN_CACHE["value"]


def _ob_mcp_call(tool: str, args: dict, *, persona_id: str, timeout: int = 30) -> tuple[bool, str]:
    """调 OB 的一个 MCP 工具，返回 (是否成功, 结果文本)。

    ⚠️ 中文 JSON 必须由 Python 发（Git Bash 的 curl 会编码坏 → 服务端 parse error）。
    ⚠️ 归属校验在调用侧做完（见 _persona_owner_guard），这里只送已核实的 personaId。
    """
    token = _ob_mcp_token()
    if not token:
        raise HTTPException(status_code=503, detail="记忆大脑未配置（读不到 mcp_token）")
    payload = json.dumps(
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {"name": tool, "arguments": args},
        },
        ensure_ascii=False,
    ).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json; charset=utf-8",
        "X-Persona-Id": persona_id,
    }
    req = urllib.request.Request(OB_BASE + "/mcp", data=payload, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        return False, f"HTTP {exc.code}"
    except urllib.error.URLError as exc:
        raise HTTPException(status_code=502, detail=f"记忆大脑连接失败：{exc.reason}")
    if isinstance(data.get("error"), dict):
        return False, str(data["error"].get("message", "error"))[:300]
    result = data.get("result") or {}
    content = result.get("content") or []
    text = "\n".join(str(part.get("text", "")) for part in content if isinstance(part, dict))
    if result.get("isError"):
        return False, text or "记忆工具报错"
    return True, text


_OB_BUCKETS_CACHE: dict[str, Any] = {"value": "", "mtime": None}

# 单次列表最多返回多少条（2026-10-06）：原实现无上限地扫目录 + 读全文，记忆多的桶会拖慢请求。
_OB_LIST_MAX = 300

# 云端记忆去重的相似度阈值（2026-10-06）。
# 用户报「云端记忆会重复记很多相同记忆」——同一件事被反复提取，OB 的 hold 只会新建。
#
# ⚠️ 阈值是**实测**定的，不是拍的。在真实 OB 数据上量过两簇（见 `_dedup_metrics`）：
#   该拦的（同义改写）：0.571 / 0.667 / 0.857 / 0.889 / 0.900
#   不该拦的（不同事实）：0.105 ~ 0.364
#   ⚠️ 但**纯字面相似度分不开「同义改写」与「差一个字、语义完全不同」**：
#      「用户喜欢猫」vs「用户喜欢狗」= 0.800 —— 比「用户在上大学」vs「用户是一名大学生」(0.571) 还高，
#      可前者是两条不同的记忆，合并就是**丢数据**。
#   所以这里**只拦"几乎肯定重复"的**（阈值取 0.85，落在 0.800 与 0.857 之间），
#   同义改写的根因改在 App 侧治（提取时把已有记忆喂给模型，让它别再提取）。
#   宁可漏一条重复，也不能把两条不同的记忆并成一条。
_OB_DEDUP_RATIO = 0.85

# 去重扫描的条数上限（性能护栏）：只扫最近这么多条，不把整个桶读一遍。
_OB_DEDUP_SCAN_MAX = 120

# 判重的最短长度：太短的串（如"用户"）做子串包含判断会误伤。
_OB_DEDUP_MIN_LEN = 4


def _is_duplicate_content(a: str, b: str) -> bool:
    """两条记忆正文是否**几乎肯定是同一条**（去重判据，纯函数、可单测）。

    两个判据，满足其一即判重：
    1. **子串包含** —— 规范化后一条完整包含另一条（且短的那条 ≥ `_OB_DEDUP_MIN_LEN`）。
       例：「熬夜到早上七点才睡」⊂「用户熬夜到早上七点才睡」→ 判重。
       ⚠️ 这是最可靠的判据：包含关系意味着"同一件事，一条比另一条多说了一点"。
    2. **高字面相似** —— SequenceMatcher 比值 ≥ `_OB_DEDUP_RATIO`（见该常量的实测依据）。

    ⚠️ 它**故意保守**：宁可漏一条重复，也不把两条不同的记忆并成一条（合并 = 丢数据）。
    """
    na, nb = _dedup_normalize(a), _dedup_normalize(b)
    if not na or not nb:
        return False
    short, long_ = (na, nb) if len(na) <= len(nb) else (nb, na)
    if len(short) >= _OB_DEDUP_MIN_LEN and short in long_:
        return True
    return difflib.SequenceMatcher(None, na, nb).ratio() >= _OB_DEDUP_RATIO


def _ob_find_similar_bucket(persona_id: str, content: str, domain: str = "") -> dict[str, Any] | None:
    """在同域已有记忆里找与 `content` 高度相似的那条（去重闸，见 `_is_duplicate_content`）。

    返回命中的条目（`{"id", "content", ...}`）或 `None`。**读不到桶时返回 None** ——
    去重是增强，读不到不该挡住写入（宁可多一条，也别因为读失败而写不进去）。
    """
    root = _ob_buckets_root()
    if not root:
        return None
    pdir = os.path.join(root, persona_id)
    if not os.path.isdir(pdir):
        return None
    if not _dedup_normalize(content):
        return None
    scanned = 0
    hit: dict[str, Any] | None = None
    for type_name in sorted(os.listdir(pdir)):
        if type_name.startswith(".") or type_name.startswith("_"):
            continue
        tdir = os.path.join(pdir, type_name)
        if not os.path.isdir(tdir):
            continue
        for domain_name in sorted(os.listdir(tdir)):
            # 同域优先：指定了域就只比同域（不同域的同义句不该合并 —— 语义不同）
            if domain and domain_name != domain:
                continue
            ddir = os.path.join(tdir, domain_name)
            if not os.path.isdir(ddir):
                continue
            for fname in sorted(os.listdir(ddir)):
                if scanned >= _OB_DEDUP_SCAN_MAX or hit is not None:
                    break
                if not fname.endswith(".md"):
                    continue
                parsed = _parse_bucket_file(os.path.join(ddir, fname))
                if not parsed:
                    continue
                scanned += 1
                if _is_duplicate_content(content, str(parsed["body"])):
                    meta = parsed["meta"]
                    hit = {
                        "id": meta.get("id") or fname.rsplit("_", 1)[-1].removesuffix(".md"),
                        "content": _clip_bucket_content(parsed["body"]),
                        "domain": domain_name,
                    }
            if scanned >= _OB_DEDUP_SCAN_MAX or hit is not None:
                break
        if scanned >= _OB_DEDUP_SCAN_MAX or hit is not None:
            break
    return hit


def _dedup_normalize(text: str) -> str:
    """去重比的规范化：去掉空白与常见标点，只留实义字符。

    「用户在上大学」与「用户是一名大学生」的差异在词，不在标点 ——
    但「宿舍不允许使用违规电器（如锅）」与「宿舍不允许使用违规电器，没有锅」
    的括号/逗号必须被抹掉，否则比值被标点拉低。
    """
    return re.sub(r"[\s，。、；：！？（）()\[\]【】\"'“”‘’·,.;:!?~—-]+", "", str(text))

# 列表里单条正文最多返回多少字符（2026-10-06：800 → 4000）。
# 800 会让 App「点开看全文」永远显示半截长记忆；上限本身保留（最坏 300 条 × 4000 字符），
# 防止响应体失控。截断逻辑抽成 `_clip_bucket_content` 以便单测盯住这个数。
_OB_LIST_CONTENT_MAX = 4000


def _clip_bucket_content(body: Any) -> str:
    """列表里单条记忆正文的截断 —— 上限见 `_OB_LIST_CONTENT_MAX`。"""
    return str(body)[:_OB_LIST_CONTENT_MAX]


def _ob_buckets_dir() -> str:
    """OB 的 `buckets_dir`（读 config.yaml，单一真源）。读不到 → 空串。

    ⚠️ 按 mtime 缓存（2026-10-06 性能加固）：原实现**每个请求**都开一次文件逐行扫，纯白读盘。
    """
    try:
        mtime = os.path.getmtime(OB_CONF_PATH)
    except OSError:
        return ""
    if _OB_BUCKETS_CACHE["mtime"] != mtime:
        value = ""
        try:
            with open(OB_CONF_PATH, "r", encoding="utf-8") as fh:
                for line in fh:
                    if line.strip().startswith("buckets_dir:"):
                        value = line.split(":", 1)[1].strip().strip("\"'").replace("\\", "/")
                        break
        except OSError:
            value = ""
        _OB_BUCKETS_CACHE["value"] = value
        _OB_BUCKETS_CACHE["mtime"] = mtime
    return _OB_BUCKETS_CACHE["value"]


def _ob_buckets_root() -> str:
    """OB 的**桶根目录**（各人设是它下面的平级子目录）。

    多桶（C-2）下每个人设的桶是 `data/ob/<personaId>/`（见 OB 的 persona_bucket.py：
    default 的 buckets_dir 是 `.../data/ob/default`，其他人设把末段换成 personaId）。
    故取 `buckets_dir` 的**上一级**。

    ⚠️ 这是与 OB **文件布局的耦合**（不是它的公开 API）。读不到就返回空串，
       调用方给 503 —— 绝不瞎猜路径（猜错会读到别人/别处的目录）。
    """
    bdir = _ob_buckets_dir()
    if not bdir:
        return ""
    parent = os.path.dirname(bdir.rstrip("/"))
    return parent if os.path.isdir(parent) else ""


def _to_int(value: Any) -> int:
    try:
        return int(float(str(value)))
    except (TypeError, ValueError):
        return 0


def _parse_bucket_file(path: str) -> dict[str, Any] | None:
    """解析一个 OB 桶文件（YAML frontmatter + 正文）。认不出 → None。

    只取展示要用的字段；`domain` / `tags` 支持 `key:` 换行 `- item` 与行内 `[a, b]` 两种写法。
    **轻量解析、不引 yaml 依赖**（项目禁新依赖）。
    """
    try:
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
    except OSError:
        return None
    if not text.startswith("---"):
        return None
    end = text.find("\n---", 3)
    if end < 0:
        return None
    head = text[3:end].strip("\n")
    body = text[end + 4:].strip()
    meta: dict[str, Any] = {}
    pending = ""
    for raw in head.split("\n"):
        line = raw.rstrip()
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.startswith("- ") and pending:
            if not isinstance(meta.get(pending), list):
                meta[pending] = []
            meta[pending].append(stripped[2:].strip().strip("'\""))
            continue
        if ":" in line:
            key, value = line.split(":", 1)
            key = key.strip()
            value = value.strip().strip("'\"")
            pending = key
            if value == "":
                meta[key] = []  # 可能是列表（后续 - item）
            elif value.startswith("[") and value.endswith("]"):
                meta[key] = [x.strip().strip("'\"") for x in value[1:-1].split(",") if x.strip()]
            else:
                meta[key] = value
                pending = ""
    return {"meta": meta, "body": body}


def _clean_now_text(text: str) -> str:
    """把引擎生成的「此刻」文本转成**用户向文案**（展示层转换，不改引擎本体）。

    ⚠️ 为什么必须转换：这段文本原本是引擎**对 AI 模型的第二人称播报**——
       里面的「你」指 Ta 自己、「她/他」指用户——直接端给用户看会人称错乱
       （「你有点想她了」），还带内部黑话（xinchao_context、「（涨）」标注）
       和一行给模型的元指令标题。这里做四件事：剥标题 → 人称互换 → 去黑话 → 行标口语化。
       引擎本身仍按原样把原文喂给模型（本函数只影响本服务对外的展示）。
    """
    lines = (text or "").splitlines()
    # 1) 剥离开头的【…】元指令标题行（给模型的，不面向用户）
    if lines and lines[0].strip().startswith("【") and lines[0].strip().endswith("】"):
        lines = lines[1:]
    out = "\n".join(lines).strip()
    if not out:
        return ""
    # 2) 人称互换：你(=Ta) ↔ 她/他(=用户) —— 两段式占位防串替换
    out = out.replace("你", "\x00")
    out = out.replace("她", "你").replace("他", "你")
    out = out.replace("\x00", "Ta")
    # 3) 去内部黑话（整句优先，再兜底单独词位）
    out = out.replace("细的在 xinchao_context", "细节都在记忆里")
    out = out.replace("xinchao_context", "记忆").replace("心潮", "")
    # 4) 驱动强度标注（（涨）/（落）/（平·想念）/（静））
    out = re.sub(r"（(?:涨|落|静|平(?:·[^）]*)?)）", "", out)
    # 5) 行标口语化
    out = out.replace("驱力：", "心里：").replace("情绪：", "心情：")
    return out.strip()


def _persona_view(now: Any, state: Any) -> dict[str, Any]:
    """用户侧状态卡用的精简视图：从 /v1/now + /v1/state 挑字段。

    只为「展示用」而裁剪（此刻文本 / 情绪 / 驱力 / 最近的梦 / 最近浮现）——
    全量状态面不直接暴露给浏览器；需要排查时走管理端点。
    """
    now = now if isinstance(now, dict) else {}
    state = state if isinstance(state, dict) else {}
    emotion = state.get("emotion") if isinstance(state.get("emotion"), dict) else {}
    drives = state.get("drives") if isinstance(state.get("drives"), dict) else {}
    dreams = state.get("recentDreams") if isinstance(state.get("recentDreams"), list) else []
    surfaced = state.get("surfacedBuckets") if isinstance(state.get("surfacedBuckets"), dict) else {}
    surfaced_recent = sorted(surfaced.items(), key=lambda kv: str(kv[1]), reverse=True)[:6]
    return {
        "text": _clean_now_text(str(now.get("text") or "")),
        "revision": now.get("revision", state.get("revision")),
        "generatedAt": now.get("generatedAt") or state.get("lastSettledAt"),
        "consciousness": state.get("consciousness"),
        "emotion": {
            "label": emotion.get("label"),
            "valence": emotion.get("valence"),
            "arousal": emotion.get("arousal"),
        },
        "drives": drives,
        "recentDreams": [
            {
                "createdAt": d.get("createdAt"),
                "dream": str(d.get("dream") or "")[:160],
                "sourceOmbreBucketIds": d.get("sourceOmbreBucketIds") or [],
            }
            for d in dreams[-2:][::-1] if isinstance(d, dict)
        ],
        "surfacedBuckets": [{"bucketId": k, "at": v} for k, v in surfaced_recent],
        "lastConversationAt": state.get("lastConversationAt"),
        "lastSettledAt": state.get("lastSettledAt"),
    }


@app.get("/xinchao/health")
def xinchao_health() -> Any:
    """心潮连通性（公开）。只回最小信息，不泄漏状态内容。"""
    status, body, _ = _xinchao_request("GET", "/health", timeout=5)
    try:
        info = json.loads(body.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        info = None
    if status != 200 or not isinstance(info, dict) or not info.get("ok"):
        return JSONResponse({"ok": False, "upstream": status}, status_code=502)
    return {"ok": True, "xinchao": info}


@app.get(f"{API_PREFIX}/admin/xinchao/state")
def admin_xinchao_state(actor: str = Depends(require_admin)) -> Any:
    """后台「心潮」页签的数据源：连通状态 +「此刻」+ 状态快照（只读）。"""

    def _load(raw: bytes) -> Any:
        try:
            return json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None

    h_code, h_body, _ = _xinchao_request("GET", "/health", timeout=5)
    result: dict[str, Any] = {"health": _load(h_body) if h_code == 200 else None}
    if h_code == 200:
        s_code, s_body, _ = _xinchao_request("GET", "/v1/state")
        n_code, n_body, _ = _xinchao_request("GET", "/v1/now")
        result["state"] = _load(s_body) if s_code == 200 else None
        now_obj = _load(n_body) if n_code == 200 else None
        # 「此刻」文本同样走用户向清洗（后台是运营者看的，也不该出现内部黑话）
        if isinstance(now_obj, dict) and isinstance(now_obj.get("text"), str):
            now_obj["text"] = _clean_now_text(now_obj["text"])
        result["now"] = now_obj
    return result


@app.post(f"{API_PREFIX}/admin/xinchao/restart")
def admin_xinchao_restart(actor: str = Depends(require_admin)) -> Any:
    """重启心潮 + 记忆大脑（走计划任务 XinchaoServices：按端口杀旧、重新拉起）。

    ⚠️ 重启期间相关接口短暂不可达；本接口同步等到心潮就绪（最多 ~45 秒）再返回。
    """
    try:
        subprocess.run(["schtasks", "/run", "/tn", "XinchaoServices"],
                       capture_output=True, timeout=15)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=f"计划任务调用失败：{exc}")
    deadline = time.time() + 45
    ok_xc = False
    while time.time() < deadline:
        code, _, _ = _xinchao_request("GET", "/health", timeout=4)
        if code == 200:
            ok_xc = True
            break
        time.sleep(2)
    audit(actor, "xinchao_restart", f"ok={ok_xc}")
    return {"ok": ok_xc, "message": "已重启，心潮就绪" if ok_xc else "已触发重启，但 45 秒内未见心潮就绪"}


@app.post(f"{API_PREFIX}/admin/xinchao/settle")
def admin_xinchao_settle(actor: str = Depends(require_admin)) -> Any:
    """「使用」入口：让心潮立即结算一次（推进她的状态到此刻；正常心跳的手动版）。"""
    code, body, _ = _xinchao_request("POST", "/v1/settle", body=b"{}", timeout=120)
    if code != 200:
        raise HTTPException(status_code=502, detail="结算失败（心潮不可达或繁忙）")
    try:
        result = json.loads(body.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        result = {"ok": True}
    audit(actor, "xinchao_settle", f"revision={result.get('revision')}")
    return result


@app.get("/xinchao/personas/{persona_id}/state")
def xinchao_persona_state(persona_id: str, user_id: str = Depends(require_user)) -> Any:
    """某人设的「此刻」（心潮 /v1/state + /v1/now 合并）。

    多租户（2026-10-06）：先过归属校验（只允许本人），再带 `X-Persona-Id` 转发到
    该人设自己的数据桶；未登记 / 属于他人 → 404（不区分，防探测）。
    """
    if not _valid_persona_id(persona_id):
        raise HTTPException(status_code=400, detail="人设 id 格式不对")
    _persona_owner_guard(persona_id, user_id, register=False)
    s_code, state_body, _ = _xinchao_request("GET", "/v1/state", persona_id=persona_id)
    n_code, now_body, _ = _xinchao_request("GET", "/v1/now", persona_id=persona_id)
    if s_code != 200:
        raise HTTPException(status_code=502, detail="心潮状态读取失败")
    return {
        "personaId": persona_id,
        "owner": user_id,
        "state": _json_or_none(state_body),
        "now": _json_or_none(now_body) if n_code == 200 else None,
    }


@app.get("/xinchao/personas/{persona_id}/context")
def xinchao_persona_context(
    persona_id: str,
    session_id: str = "",
    user_id: str = Depends(require_user),
) -> Any:
    """某人设的**上下文信封**（心潮 `/v1/context` 代理，v0.61.53）。

    ## 为什么带 `mode=inspect`
    心潮的信封是**投递式**的（`handoffOnceHours=12`：同一个 session 只交付一次，之后返回空）。
    实测（2026-10-06）：`mode=inspect` **可以连续重复调用、每次都返回完整 sections**
    —— 这正是"App 想随时拿一次当前信封"需要的模式；而默认的 `session_start` 只能拿一次。

    ## 返回什么
    心潮原样的 `{sections:[{id, source, ttl, content}], sessionId, mode, expiresAt, ...}`。
    ⚠️ `sections[].content` 是**给模型看的**（第二人称播报 / 内部字段名），
        **不要直接端给用户**；App 侧把它拼进**对话附录**。
    """
    if not _valid_persona_id(persona_id):
        raise HTTPException(status_code=400, detail="人设 id 格式不对")
    _persona_owner_guard(persona_id, user_id, register=False)
    sid = (session_id or "default")[:120]
    code, body, _ = _xinchao_request(
        "GET",
        f"/v1/context?session_id={urllib.parse.quote(sid, safe='')}&mode=inspect",
        persona_id=persona_id,
    )
    if code != 200:
        raise HTTPException(status_code=502, detail="心潮上下文信封读取失败")
    return _json_or_none(body) or {}


@app.get("/xinchao/personas/{persona_id}/intent")
def xinchao_persona_intent(persona_id: str, user_id: str = Depends(require_user)) -> Any:
    """某人设的**当前意图**（心潮 `/v1/intent` 代理）。

    返回 `{"intent": {"key","value","label"}, "topDrives": [...]}`。
    ⚠️ `label` 是心潮给的**描述性长句**（如「想她、想黏着她、想占有与靠近」）——
        App 侧必须过词表换成短词再给用户看（与 `/xinchao/personas` 的 view 同一套口径）。
    """
    if not _valid_persona_id(persona_id):
        raise HTTPException(status_code=400, detail="人设 id 格式不对")
    _persona_owner_guard(persona_id, user_id, register=False)
    code, body, _ = _xinchao_request("GET", "/v1/intent", persona_id=persona_id)
    if code != 200:
        raise HTTPException(status_code=502, detail="心潮意图读取失败")
    return _json_or_none(body) or {}


@app.get("/xinchao/personas/{persona_id}/breath")
def xinchao_persona_breath(persona_id: str, user_id: str = Depends(require_user)) -> Any:
    """某人设的**梦境余韵（紧凑）**（心潮 `/v1/breath-context` 代理）。

    返回 `{"available": bool, "dreams": [{"id","createdAt","summary","residue"}]}`。
    ⚠️ App 侧只给用户看 `residue`（"醒来时指尖还留着一点奶油似的黏"那种**醒来感受**），
        `summary` 是元叙述（"几乎没意识到在做梦"）—— 那是给模型看的，别端给用户。
    """
    if not _valid_persona_id(persona_id):
        raise HTTPException(status_code=400, detail="人设 id 格式不对")
    _persona_owner_guard(persona_id, user_id, register=False)
    code, body, _ = _xinchao_request("GET", "/v1/breath-context", persona_id=persona_id)
    if code != 200:
        raise HTTPException(status_code=502, detail="心潮梦境读取失败")
    return _json_or_none(body) or {}


@app.post("/xinchao/personas/{persona_id}/heartbeat")
async def xinchao_persona_heartbeat(
    persona_id: str,
    request: Request,
    user_id: str = Depends(require_user),
) -> Any:
    """**在场时间**上报（心潮 `/v1/heartbeat` 代理）。

    App 在「启动 / 回到前台」时打一次 —— 心跳**只刷新在场时间，不上传聊天正文**
    （心潮 README 原文）。意义：用户开着 App 但没说话时，心潮也能知道"他在"，
    不会把这段静默误判成"长期不在"从而触发主动消息。
    """
    if not _valid_persona_id(persona_id):
        raise HTTPException(status_code=400, detail="人设 id 格式不对")
    raw = await request.body()
    if len(raw) > 4 * 1024:
        raise HTTPException(status_code=413, detail="心跳体过大")
    _persona_owner_guard(persona_id, user_id, register=False)
    code, body, _ = _xinchao_request(
        "POST", "/v1/heartbeat", body=raw or b"{}", persona_id=persona_id
    )
    if code != 200:
        raise HTTPException(status_code=502, detail="心潮在场时间上报失败")
    return _json_or_none(body) or {"ok": True}


@app.get("/xinchao/personas")
def xinchao_persona_list(user_id: str = Depends(require_user)) -> Any:
    """当前账号下已接入心潮的人设列表（含「此刻」精简视图，供用户侧网页渲染状态卡）。

    ⚠️ 每人设 2 次上游调用（/v1/now + /v1/state）。单用户人设数以十计，串行足够；
       将来「上百人设」的全局规模下再考虑并发/缓存（先简单，别过早优化）。
    """
    rows = query(
        "SELECT persona_id, persona_name, created_at, last_seen_at FROM persona_owners "
        "WHERE user_id = ? ORDER BY last_seen_at DESC",
        (user_id,),
    )
    def _fetch_one(row: Any) -> dict[str, Any]:
        pid = row["persona_id"]
        n_code, now_body, _ = _xinchao_request("GET", "/v1/now", timeout=8, persona_id=pid)
        s_code, state_body, _ = _xinchao_request("GET", "/v1/state", timeout=8, persona_id=pid)
        now = _json_or_none(now_body) if n_code == 200 else None
        state = _json_or_none(state_body) if s_code == 200 else None
        return {
            "personaId": pid,
            "name": row["persona_name"] or "",
            "createdAt": row["created_at"],
            "lastSeenAt": row["last_seen_at"],
            "online": bool(n_code == 200 or s_code == 200),
            "view": _persona_view(now, state),
        }

    # ⚠️ 2026-10-06 性能加固：每人设 2 次上游，**串行**时 N 个人设就是 2N 次往返
    #    （审查结论：单用户数十人设即到秒级~分钟级）。并发度**固定 4**：墙钟时间约压到 1/4，
    #    又不至于把心潮/OB 打爆 —— 它们也是同机单实例，打得越猛这边越慢。
    if rows:
        with ThreadPoolExecutor(max_workers=4) as pool:
            items: list[dict[str, Any]] = list(pool.map(_fetch_one, rows))
    else:
        items = []
    return {"personas": items}


@app.post("/xinchao/personas/{persona_id}/event")
async def xinchao_persona_event(
    persona_id: str,
    request: Request,
    user_id: str = Depends(require_user),
) -> Any:
    """转发一条对话事件（App 侧上报入口）。

    多租户（2026-10-06）：首次上报即登记归属（persona_id→user_id）；此后只有本人可读写。
    事件带 `X-Persona-Id` 转发到该人设自己的数据桶。
    """
    if not _valid_persona_id(persona_id):
        raise HTTPException(status_code=400, detail="人设 id 格式不对")
    raw = await request.body()
    if len(raw) > 64 * 1024:
        raise HTTPException(status_code=413, detail="事件体过大")
    _persona_owner_guard(persona_id, user_id, register=True)
    status, body, _ = await _run_blocking(
        _xinchao_request,
        "POST", "/v1/conversation-event", body=raw or b"{}", persona_id=persona_id,
    )
    if status != 200:
        return JSONResponse({"ok": False, "upstream": status}, status_code=502)
    return _json_or_none(body) or {"ok": True}


@app.post("/xinchao/personas/{persona_id}/register")
async def xinchao_persona_register(
    persona_id: str,
    request: Request,
    user_id: str = Depends(require_user),
) -> Any:
    """把人设登记到当前账号名下（App 打开「接入心潮」开关时调用；可重复，用于补名字）。

    - 首次登记：新建记录（归属从这一刻锁定）；
    - 本人重复登记：刷新 last_seen_at，可更新 persona_name（改名场景）；
    - 他人已登记：404（同所有路径，不给探测空间）。
    body（可选 JSON）：{"name": "展示名"} —— 明文，服务器可见（App 注册时上报）。
    """
    if not _valid_persona_id(persona_id):
        raise HTTPException(status_code=400, detail="人设 id 格式不对")
    raw = await request.body()
    if len(raw) > 4096:
        raise HTTPException(status_code=413, detail="请求体过大")
    payload = _json_or_none(raw) or {}
    name = str(payload.get("name") or "").strip()[:64]
    _persona_owner_guard(persona_id, user_id, register=True)
    if name:
        execute(
            "UPDATE persona_owners SET persona_name = ? WHERE persona_id = ?",
            (name, persona_id),
        )
    row = query_one(
        "SELECT persona_id, persona_name, created_at, last_seen_at FROM persona_owners "
        "WHERE persona_id = ?",
        (persona_id,),
    )
    return {"ok": True, "persona": dict(row) if row is not None else None}


@app.post("/xinchao/personas/{persona_id}/memory")
async def xinchao_persona_memory_write(
    persona_id: str,
    request: Request,
    user_id: str = Depends(require_user),
) -> Any:
    """把一条记忆写进「记忆大脑」该人设的桶（App 云同步的上行入口）。

    - 归属：首次写即登记（同 event/register 的 register=True 语义）；非本人 404。
    - 参数（JSON）：content（必填，逐字保存）、title、category（→ 记忆域）、importance(1-10)。
    - **去重（2026-10-06）**：写前先在同域已有条目里找相似度 ≥ `_OB_DEDUP_RATIO` 的，
      命中则不新建、直接返回已存在那条的桶 id（`merged: true`）。
      修的是用户报的「云端记忆会重复记很多相同记忆」——OB 的 hold 只会新建，
      没有跨条目合并，于是「用户在上大学 / 用户是一名大学生 / 用户是大学生」会各存一条。
    """
    if not _valid_persona_id(persona_id):
        raise HTTPException(status_code=400, detail="人设 id 格式不对")
    raw = await request.body()
    if len(raw) > 16 * 1024:
        raise HTTPException(status_code=413, detail="记忆体过大")
    payload = _json_or_none(raw) or {}
    content = str(payload.get("content") or "").strip()
    if not content:
        raise HTTPException(status_code=400, detail="记忆内容不能为空")
    _persona_owner_guard(persona_id, user_id, register=True)
    args: dict[str, Any] = {"content": content[:2000]}
    title = str(payload.get("title") or "").strip()
    if title:
        args["title"] = title[:80]
    domain = str(payload.get("category") or "").strip()
    if domain:
        args["domain"] = domain[:16]
    importance = payload.get("importance")
    if isinstance(importance, (int, float)):
        args["importance"] = max(1, min(10, int(importance)))

    # ── 去重闸（2026-10-06）：同域内已有近似条目 → 不新建，回已存在的那条 ──
    existing = _ob_find_similar_bucket(persona_id, content, domain)
    if existing:
        return {
            "ok": True,
            "personaId": persona_id,
            "bucketId": existing["id"],
            "merged": True,
            "result": "已存在近似记忆，未新建",
        }

    ok, text = await _run_blocking(_ob_mcp_call, "hold", args, persona_id=persona_id)
    if not ok:
        return JSONResponse({"ok": False, "upstream": text[:200]}, status_code=502)
    # 把 OB 分配的桶 id 解析出来回给 App（v0.61.38）：App 存下它，将来删除这条记忆时要按 id 删。
    m = re.search(r"→\s*([0-9a-zA-Z]{6,})", text)
    return {
        "ok": True,
        "personaId": persona_id,
        "bucketId": m.group(1) if m else None,
        "merged": False,
        "result": text[:300],
    }


@app.get("/xinchao/personas/{persona_id}/memory")
def xinchao_persona_memory_read(
    persona_id: str,
    q: str = "",
    user_id: str = Depends(require_user),
) -> Any:
    """读该人设「记忆大脑」里的记忆（App 云端记忆页的数据源）。

    带 `q` → 语义/关键词检索（breath_search）；不带 → 当下权重最高的记忆（breath）。
    非本人 / 未登记 → 404（与所有 persona 路径一致，防探测）。
    """
    if not _valid_persona_id(persona_id):
        raise HTTPException(status_code=400, detail="人设 id 格式不对")
    _persona_owner_guard(persona_id, user_id, register=False)
    query = (q or "").strip()
    if query:
        ok, text = _ob_mcp_call("breath_search", {"query": query[:80]}, persona_id=persona_id)
    else:
        ok, text = _ob_mcp_call("breath", {}, persona_id=persona_id)
    if not ok:
        raise HTTPException(status_code=502, detail="记忆大脑读取失败")
    return {"personaId": persona_id, "owner": user_id, "text": text}


@app.get("/xinchao/personas/{persona_id}/memory/list")
def xinchao_persona_memory_list(persona_id: str, user_id: str = Depends(require_user)) -> Any:
    """列出该人设「记忆大脑」里的记忆（**结构化**，供 App 云端记忆页按域分组渲染）。

    数据来源 = OB 的桶文件（同机可读，含 frontmatter：域/标题/重要性/时间）。非本人 → 404。
    ⚠️ 与 OB 文件布局耦合（见 `_ob_buckets_root`），读不到时给 503 而不是瞎猜路径。
    """
    if not _valid_persona_id(persona_id):
        raise HTTPException(status_code=400, detail="人设 id 格式不对")
    _persona_owner_guard(persona_id, user_id, register=False)
    root = _ob_buckets_root()
    if not root:
        raise HTTPException(status_code=503, detail="记忆大脑未配置（读不到 buckets_dir）")
    pdir = os.path.join(root, persona_id)
    buckets: list[dict[str, Any]] = []
    if os.path.isdir(pdir):
        for type_name in sorted(os.listdir(pdir)):
            if len(buckets) >= _OB_LIST_MAX:
                break
            if type_name.startswith(".") or type_name.startswith("_"):
                continue
            tdir = os.path.join(pdir, type_name)
            if not os.path.isdir(tdir):
                continue
            for domain_name in sorted(os.listdir(tdir)):
                if len(buckets) >= _OB_LIST_MAX:
                    break
                ddir = os.path.join(tdir, domain_name)
                if not os.path.isdir(ddir):
                    continue
                for fname in sorted(os.listdir(ddir)):
                    if len(buckets) >= _OB_LIST_MAX:
                        break
                    if not fname.endswith(".md"):
                        continue
                    parsed = _parse_bucket_file(os.path.join(ddir, fname))
                    if not parsed:
                        continue
                    meta = parsed["meta"]
                    dom = meta.get("domain")
                    if isinstance(dom, list):
                        dom = dom[0] if dom else domain_name
                    buckets.append({
                        "id": meta.get("id") or fname.rsplit("_", 1)[-1].removesuffix(".md"),
                        "domain": dom or domain_name,
                        "title": meta.get("title") or meta.get("name") or "",
                        "content": _clip_bucket_content(parsed["body"]),
                        "importance": _to_int(meta.get("importance")),
                        "createdAt": meta.get("created"),
                        "lastActiveAt": meta.get("last_active"),
                        "type": meta.get("type") or type_name,
                    })
    buckets.sort(key=lambda b: str(b.get("createdAt") or ""), reverse=True)
    return {"personaId": persona_id, "owner": user_id, "count": len(buckets), "buckets": buckets}


@app.delete("/xinchao/personas/{persona_id}/memory/{bucket_id}")
def xinchao_persona_memory_delete(
    persona_id: str,
    bucket_id: str,
    user_id: str = Depends(require_user),
) -> Any:
    """把云端**某一条**记忆归档（用户在自己的 App 里删掉它时调用）。

    ⚠️ 语义边界（实测过、必须写清）：OB 的设计是「记忆会被遗忘，但**绝不能被抹去**」
       （其 rule.md 第 1/9 条）。实测 `hard_delete` 被拒绝（原文：「拒绝永久删除：普通记忆桶
       不可被 trace 物理删除」），`delete=True` 的语义是**归档** —— 从日常召回里消失、
       原文仍留在服务器磁盘。所以这个端点如实叫「归档」，不是物理抹除。
       要**真正**清掉某个角色的全部记忆，走删人设端点（那里是物理清桶 —— 角色都没了）。
    """
    if not _valid_persona_id(persona_id):
        raise HTTPException(status_code=400, detail="人设 id 格式不对")
    if not re.fullmatch(r"[0-9a-zA-Z_-]{1,64}", bucket_id or ""):
        raise HTTPException(status_code=400, detail="记忆 id 格式不对")
    _persona_owner_guard(persona_id, user_id, register=False)
    ok, text = _ob_mcp_call(
        "trace",
        {
            "bucket_id": bucket_id,
            "delete": True,
            "delete_reason": "用户在自己的 App 里删除了这条记忆",
        },
        persona_id=persona_id,
    )
    if not ok:
        return JSONResponse({"ok": False, "upstream": text[:200]}, status_code=502)
    # 归档之后**再把档案文件抹掉** —— 这才是用户说的"删掉"（2026-10-06 实测得出）：
    #   OB 的 `delete=True` 只是把桶文件**搬进** `<桶>/archive/`：它仍能被 breath_search
    #   以「已删除到档案」命中、也仍占磁盘（OB 的哲学是"会被遗忘但绝不被抹去"）。
    #   实测：把档案文件抹掉后搜索不再命中、OB 无异常、breath 正常。
    #   这样做既走完了 OB 的官方流程（ledger/索引一致），又**不用改 OB 的源码**
    #   —— 省掉了第三方升级时的合并成本。scope 只限该 persona 的 archive 目录。
    purged = 0
    root = _ob_buckets_root()
    if root:
        adir = os.path.join(root, persona_id, "archive")
        if os.path.isdir(adir):
            for dirpath, _dirs, files in os.walk(adir):
                for name in files:
                    if bucket_id in name:
                        try:
                            os.remove(os.path.join(dirpath, name))
                            purged += 1
                        except OSError:
                            pass
    audit(user_id, "xinchao_memory_archived", f"persona={persona_id} bucket={bucket_id} purged={purged}")
    return {"ok": True, "personaId": persona_id, "bucketId": bucket_id, "archived": True, "purged": purged}


@app.delete("/xinchao/personas/{persona_id}")
def xinchao_persona_purge(persona_id: str, user_id: str = Depends(require_user)) -> Any:
    """删除一个角色在云端的**全部痕迹**：物理清掉它的 OB 桶 + 心潮状态目录 + 注销归属行。

    与单条归档不同，这里是**真删** —— 角色在 App 里已经不存在了，整桶是无人再引用的数据；
    留着一个"已经不存在的角色"的记忆既没用、也不符合用户对"删掉"的预期。

    ⚠️ 路径安全三保险：personaId 过白名单（字母数字-_）；`default` 桶拒删；
       目标路径必须是桶根的**直接子目录**（os.path.dirname 比对），桶根本身永不碰。
    """
    if not _valid_persona_id(persona_id):
        raise HTTPException(status_code=400, detail="人设 id 格式不对")
    if persona_id == "default":
        raise HTTPException(status_code=400, detail="default 桶不可删除")
    _persona_owner_guard(persona_id, user_id, register=False)
    removed: list[str] = []
    ob_root = _ob_buckets_root()
    xc_root = os.path.join(os.path.dirname(ob_root), "xinchao") if ob_root else ""
    for root, label in ((ob_root, "ob"), (xc_root, "xinchao")):
        if not root:
            continue
        target = os.path.join(root, persona_id)
        if (
            os.path.isdir(target)
            and os.path.dirname(os.path.abspath(target)) == os.path.abspath(root)
        ):
            shutil.rmtree(target, ignore_errors=True)
            removed.append(label)
    execute("DELETE FROM persona_owners WHERE persona_id = ?", (persona_id,))
    audit(user_id, "xinchao_persona_purged", f"persona={persona_id} removed={','.join(removed)}")
    return {"ok": True, "personaId": persona_id, "removed": removed}


@app.get("/xinchao/personas/{persona_id}/pending")
def xinchao_persona_pending(
    persona_id: str,
    since: str = "",
    user_id: str = Depends(require_user),
) -> Any:
    """**Ta 主动想说的话**（最近几条）—— App 轮询取走，用来弹本地通知。

    ⚠️ 这里**不做任何定时/调度判断**（2026-10-06 与心潮作者口径对齐）：
       该不该说话、什么时候说、说什么，全部由心潮的**情绪状态机**决定
       （情绪变化 / 梦境 / 念头涌现 / 驱力变化）；本服务只负责"把已经发生的那句话取回来"。
       接入侧**绝不能**自己加闹钟 —— 那会把"情绪驱动"降级回"定时唤醒"，是这套设计最忌讳的走歪。

    数据源 = 心潮 `/v1/state` 的 `recentBarkMessages`（引擎自己记的：{at, kind, message}）。
    可选 `?since=<ISO 时间>` —— 只回比它新的（App 记下上次看到的时间即可）。
    """
    if not _valid_persona_id(persona_id):
        raise HTTPException(status_code=400, detail="人设 id 格式不对")
    _persona_owner_guard(persona_id, user_id, register=False)
    code, body, _ = _xinchao_request("GET", "/v1/state", timeout=10, persona_id=persona_id)
    if code != 200:
        raise HTTPException(status_code=502, detail="心潮状态读取失败")
    state = _json_or_none(body) or {}
    raw = state.get("recentBarkMessages")
    msgs = raw if isinstance(raw, list) else []
    if since:
        msgs = [m for m in msgs if isinstance(m, dict) and str(m.get("at") or "") > since]
    # 只回展示需要的字段（不透传整个 state）
    return {
        "personaId": persona_id,
        "owner": user_id,
        "lastAutonomousMessage": state.get("lastAutonomousMessage"),
        "messages": [
            {"at": m.get("at"), "kind": m.get("kind"), "message": m.get("message")}
            for m in msgs[-8:]
            if isinstance(m, dict) and m.get("message")
        ],
    }


@app.get("/dashboard")
def xinchao_dashboard_root() -> Any:
    """无尾斜杠的老习惯 → 补齐（心潮路由都带 /dashboard/ 前缀）。"""
    return RedirectResponse(url="/dashboard/", status_code=307)


@app.api_route("/dashboard/{rest:path}", methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"])
async def xinchao_dashboard_proxy(rest: str, request: Request) -> Any:
    """心潮看板反代（同路径转发）。

    ⚠️ 保持原路径不改写 —— 页面里的绝对路径（/dashboard/api/...）与
       Cookie Path=/dashboard 因此原样可用。口令保护仍在心潮自己那层
       （DASHBOARD_ACCESS_TOKEN + 60 秒 8 次限速）。
    """
    query = request.url.query
    target = "/dashboard/" + rest + (("?" + query) if query else "")
    if request.method in ("GET", "HEAD"):
        status, body, headers = await _run_blocking(_xinchao_request, "GET", target)
    else:
        raw = await request.body()
        status, body, headers = await _run_blocking(_xinchao_request, request.method, target, body=raw)
    return Response(content=body, status_code=status, headers=dict(headers))


if STATIC_DIR.exists():
    @app.get("/")
    def root() -> Any:
        index = STATIC_DIR / "index.html"
        if index.exists():
            return FileResponse(index)
        return JSONResponse({"ok": True, "hint": "后台静态文件缺失，请检查 backend/static/"})

    # ⚠️ `/uploads` 单独挂一次（**必须在根挂载之前** —— Starlette 按注册先后匹配）。
    #    否则 `UPLOAD_DIR` 与"实际被服务的目录"会各说各话：默认两者相同看不出问题，
    #    但测试用 `YUKI_UPLOAD_DIR` 指到临时目录时，图写进去了却取不回来。
    if UPLOAD_DIR.exists():
        app.mount("/uploads", StaticFiles(directory=UPLOAD_DIR), name="uploads")

    app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
else:
    @app.get("/")
    def root_missing() -> Any:
        return JSONResponse({"ok": True, "hint": "后台静态文件缺失，请检查 backend/static/"})
