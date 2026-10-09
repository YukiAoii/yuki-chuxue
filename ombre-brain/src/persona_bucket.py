"""
========================================
persona_bucket.py — 多状态（C-2）请求上下文与惰性组件代理（OB 侧）
========================================

心潮多桶（2026-10-05）之后，OB 侧跟上：同一进程按 `X-Persona-Id`（或 `?persona=`）
把记忆数据分到 `data/ob/<personaId>/` 数据桶。

做法（与心潮侧同构）：
- **请求上下文**：ASGI 中间件（server_app.PersonaContextMiddleware）在每个请求里
  `enter(pid)`，把该 persona 的组件集放进 contextvar；
- **模块级 `__getattr__`（PEP 562）**：tools/_runtime 与 web/_shared 不再定义组件字段，
  改由模块 __getattr__ 在**读取时**按当前上下文解析真对象——`rt.bucket_mgr.xxx`、
  裸读 `sh.embedding_engine`、判空 / `is` 身份比较全部与旧语义逐字节一致，
  293（tools）+ 211（web）处既有引用一字不改；写（`rt.xxx = v`）仍走模块 dict（旧的直接赋值语义）；
- **懒创建**：`bundle_for(pid)` 首次调用时用 server.py 注入的 builder 组装该桶
  的全套组件（config 浅拷贝 + buckets_dir 覆盖）。

边界（本波不做）：
- `default` 桶 = 现有一套组件（行为与改动前一致；无 persona 的请求全走它）；
- 热重载（web/_shared.replace_embedding_engine）只发布到 default；其他桶在
  各自引擎重建/进程重启时自然用新配置（低频运营操作，已知限制）；
- dashboard 等 web 路由默认操作 default（可显式带 `?persona=` 看别的桶）。

不做：路径解析以外的 IO；不做桶的卸载（LRU 释放留待需要时——先观察内存）。
========================================
"""

from __future__ import annotations

import contextvars
import gc
import os
import re
from collections import OrderedDict
from typing import Any, Callable, Optional
from urllib.parse import parse_qs

# personaId 白名单：与心潮侧一致（字母数字-_，1..64）——防路径穿越。
_PID_RE = re.compile(r"^[0-9A-Za-z_-]{1,64}$")

# 当前请求的组件集（None = 非请求上下文 → 回退 default）。
_current: contextvars.ContextVar = contextvars.ContextVar("omb_persona_components", default=None)

# personaId -> 组件集（'default' 由 server.py 的注入写入；其余懒创建）。
# ⚠️ 有序（LRU）：见 _evict_if_needed —— 桶会**常驻一整套组件**（bucket / embedding / SQLite 句柄），
#    实测每桶约 637KB RSS 且永不释放（2026-10-06 实测：20 个新桶 +12.7MB）。
_bundles: "OrderedDict[str, dict]" = OrderedDict()

# 同时驻留的桶数上限。**默认 0 = 不启用**。
#
# ⚠️ 2026-10-06 实测结论（照实记，别被这段注释骗了）：
#   背景：预计规模上百~上千人设，而 OB 实测**每个新角色常驻约 1.6MB RSS**、
#   线性增长、无平台（三次分批各 100 个新桶，每次 +165MB）。1000 人设 ≈ 1.6GB，
#   在 4GB（空闲约 1.1GB）机器上会越来越卡、最后 OOM —— **这是真问题**。
#   本 LRU 是为此加的，但**实测没治住**：把上限压到 4 再测，100 个新桶**仍然 +167MB**；
#   同一角色重复 200 次请求只 +6MB（说明**不是每请求泄漏**，是每角色常驻）。
#   → 常驻物**不在**这个注册表里（或释放后仍被别的东西引用，例如每桶组件里的后台线程。
#     若如此，`del` 之后下次请求会**重建第二份**，反而更糟）。
#   所以**默认关闭**：留着旋钮，等真正定位到常驻源（下一步）再决定值。
#   可用环境变量 OMBRE_MAX_PERSONA_BUNDLES 显式打开（仅在你确知组件可安全释放时）。
_MAX_BUNDLES = max(0, int(os.environ.get("OMBRE_MAX_PERSONA_BUNDLES", "0") or 0))

# server.py 注入：builder(persona_id) -> {组件名: 实例}（内含路径推导与组装）。
_builder: Optional[Callable[[str], dict]] = None


def init_builder(builder: Callable[[str], dict]) -> None:
    """启动时由 server.py 调用：注入「按 persona 组装一套组件」的工厂。"""
    global _builder
    _builder = builder


def set_default_component(name: str, value: Any) -> None:
    """把某个组件登记到 default 桶（tools/_runtime 与 web/_shared 的 init 穿透调用）。"""
    _bundles.setdefault("default", {})[name] = value


def default_components() -> dict:
    return _bundles.get("default", {})


def normalize_persona_id(raw: Any) -> Optional[str]:
    """校验 personaId；空 / 非法 → None（调用方决定回退 default 还是 400）。"""
    s = str(raw or "").strip()
    if not s:
        return None
    if not _PID_RE.match(s):
        return None
    return s


def bundle_for(persona_id: str) -> dict:
    """取（或懒创建）某个 persona 的组件集。

    ⚠️ default 未注册时返回**空集**（而不是报错）：组件访问随即抛 AttributeError，
    与旧语义「字段是 None（未注入）」一致——测试 fixture 常在此态下 hasattr/判空。
    ⚠️ 命中会**刷新 LRU 位置**；新建后若超过上限就释放最久未用的桶（default 除外）。
    """
    pid = persona_id or "default"
    comps = _bundles.get(pid)
    if comps is not None:
        _bundles.move_to_end(pid)
        return comps
    if pid == "default":
        comps = {}   # 空集（后续 register/init 会往同一 dict 里填）
    else:
        if _builder is None:
            raise RuntimeError("persona_bucket 未初始化：先调用 init_builder(builder)")
        comps = _builder(pid)
        if comps is None:
            comps = {}
    _bundles[pid] = comps
    _evict_if_needed()
    return comps


def _evict_if_needed() -> None:
    """超过上限时按 **LRU** 释放最久未用的桶（`default` **永不**释放）。

    ⚠️ 为什么要有它（2026-10-06 实测）：每个桶常驻一整套组件（bucket / embedding / SQLite 句柄），
       实测**每桶约 637KB RSS，且本身不会释放**（新建 20 个桶 → OB 内存 +12.7MB）。
       预计规模是上千人设 → 600MB+，在 4GB（空闲约 1.1GB）的机器上会越来越卡、最后 OOM。
    ⚠️ 句柄的释放靠引用计数归零（SQLite 连接随之关闭）→ 释放后 `gc.collect()` 兜一层，
       防环状引用把连接拖住（实测：被删角色的目录会因句柄未释放而删不干净）。
    ⚠️ 正在服务该桶的请求不受影响：请求手里（contextvar）还拿着引用，这里只是把它移出注册表。
    """
    evicted = False
    if _MAX_BUNDLES <= 0:
        return  # 未启用（见 _MAX_BUNDLES 的实测注释）
    while len(_bundles) > _MAX_BUNDLES:
        victim = next((k for k in _bundles if k != "default"), None)
        if victim is None:
            break
        del _bundles[victim]
        evicted = True
    if evicted:
        gc.collect()


def current() -> dict:
    """当前请求的组件集（非请求上下文——启动/后台线程——回退 default）。"""
    ctx = _current.get()
    if ctx is not None:
        return ctx
    return bundle_for("default")


def enter(persona_id: str) -> None:
    """进入某个 persona 的上下文（中间件用）。"""
    _current.set(bundle_for(persona_id))


def extract_persona_id(scope: dict) -> Optional[str]:
    """从 ASGI scope 取原始 persona 值（`X-Persona-Id` 头优先，`?persona=` 次之）。未校验。"""
    for key, value in scope.get("headers") or ():
        if key == b"x-persona-id":
            try:
                return value.decode("latin-1").strip()
            except Exception:
                return None
    query = scope.get("query_string") or b""
    if query:
        try:
            params = parse_qs(query.decode("latin-1"), keep_blank_values=False)
            values = params.get("persona") or []
            if values:
                return str(values[0]).strip()
        except Exception:
            return None
    return None


def resolve_component(name: str) -> Any:
    """按当前请求上下文解析组件（tools/_runtime 与 web/_shared 的模块级 `__getattr__` 兜底）。

    - 已注入 → 真对象；
    - 未注入 → **None**（与旧「模块字段 = None」逐字节一致：判空 / hasattr 语义不变）。
    """
    return current().get(name)


def snapshot_default() -> dict:
    """给测试 fixture 用：快照 default 桶的组件表（浅拷贝）。

    背景：conftest 的 `_restore_tool_runtime` 用 `vars(rt)` 快照/恢复全局装配——
    多状态（C-2）后组件移到了本模块的 default 注册，不在 `vars(rt)` 里，
    没有这两个函数，跨测试的组件泄漏（fixture 注释里描述的那个病）会借新载体复发。
    """
    return dict(_bundles.get("default", {}))


def restore_default(snapshot: dict) -> None:
    """恢复 default 桶的组件表（替换为快照的副本；值对象身份保留）。"""
    _bundles["default"] = dict(snapshot)
