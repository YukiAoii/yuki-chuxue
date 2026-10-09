# 心潮 + OB 本机部署说明

> 2026-10-05 首建（Yuki 初雪「心潮接入」Wave 1 完成时）。本机 = Windows Server 2022，4GB 内存 / 4 核。
> 本文件是这套服务的维护入口：目录、启动、密钥、改动清单、待办。

## 一、目录结构

```
C:\Xinchao\
  ├─ ombre-brain\            OB 记忆大脑（v3.6.14，官方 main 分支）
  │    ├─ .venv\             Python 3.14 虚拟环境
  │    └─ config.yaml        部署配置（mcp_token / buckets_dir / 鉴权模式）
  ├─ xinchao\                心潮 4.0.0（Node，零依赖）
  │    ├─ xinchao.conf       部署配置（SERVICE_TOKEN / OB 地址与令牌 / 路径）
  │    └─ configs\           interaction-rules.json、attention-rules.json、dream_push_prompt.md
  ├─ data\
  │    ├─ ob\default\        OB 数据（记忆桶 .md / embeddings.db / 日志）
  │    └─ xinchao\default\   心潮状态（state.json / cabin / black-box / journal 等）
  └─ _dl\                    下载缓存、旧版备份（ombre-brain-v363-backup）、验证脚本
```

注意：配置文件**不叫 `.env`**（名字命中本机安全策略），统一用 `xinchao.conf` / `config.yaml`。
心潮启动时必须用 `--env-file=xinchao.conf`，它不会自动读取。

## 二、启动方式（两个服务都要跑）

> **快捷方式（推荐）**：直接运行 `start_ob.bat` / `start_xinchao.bat`（2026-10-05 已生成、含免费通道 key），
> 不必手敲下面的命令；下面的命令用于手工排查。

### OB（端口 18001，绑定 127.0.0.1）

```bat
cd C:\Xinchao\ombre-brain
set OMBRE_BIND_HOST=127.0.0.1
.venv\Scripts\python.exe src\server.py
```

### 心潮（端口 18110，绑定 127.0.0.1）

```bat
cd C:\Xinchao\xinchao
node --env-file=xinchao.conf src\server.js
```

⚠️ 不能用 `npm start`（它不读配置文件）。

## 三、密钥与令牌（均为本机生成，勿外泄；此文件只是索引，值在各配置里）

| 令牌 | 位置 | 用途 |
|---|---|---|
| OB `mcp_token` | `ombre-brain/config.yaml` | 心潮调 OB MCP 的 Bearer |
| OB `hooks.token` | `ombre-brain/config.yaml` | OB 的 /breath-hook 等 |
| 心潮 `SERVICE_TOKEN` | `xinchao/xinchao.conf` | 心潮全部 /v1 接口的 Bearer |
| 心潮 `DASHBOARD_ACCESS_TOKEN` | `xinchao/xinchao.conf` | 看板口令（Wave 3 将改造为 Yuki 账号登录） |

**对应关系**：`xinchao.conf` 的 `OMBRE_MCP_TOKEN` 必须等于 `config.yaml` 的 `mcp_token`（改一个要同步另一个）。

## 四、AI 模型通道（2026-10-05 已接通）

已从 YukiServer 的「免费组」配置（settings 表 `free_group`，名称「Yuki初雪Pro」）取 key 接入
（key 不落在本文件，落在 `start_ob.bat` 与 `xinchao.conf` 里）：

- **OB 压缩通道**：`start_ob.bat` 的 `OMBRE_COMPRESS_*`（base_url=https://api.your-llm-provider.example.com:18443/v1，model=deepseek-flash）
- **心潮模型通道**：`xinchao.conf` 的 `MODEL_*`（同一通道，MODEL_ENABLED=true）

实测：hold 写记忆 ✓（LLM 压缩出新桶）、dream 生成（LLM）✓、梦写回 OB ✓。

**仍缺（可选）**：`OMBRE_EMBED_API_KEY`——免费通道无 embeddings 端点（实测 404），
语义检索维持 standby、关键词/BM25 降级可用。

**未来方向（用户自带 key，待多租户改造时实现）**：每位用户用自己的 key（App 侧上传到服务器）、
没填的用户走免费通道、免费用户在界面不显示地址与 key。详见项目内 `memory/心潮接入-实施交接.md`。

## 五、对第三方代码的改动清单（升级时重打/注意）

1. **`xinchao/src/server.js`（1 行）**：`server.listen` 增加 `process.env.HOST || '0.0.0.0'` ——
   使裸跑部署能绑定 127.0.0.1（默认行为不变）。位置约 L1574，注释带「Yuki 接入」标记。
2. **`xinchao/src/ombre-client.js`（2 处）**：`storeDream` 的 hold 调用去掉 `auto`/`source`、
   `storeHeldOutput` 的 grow 调用去掉 `source`——这两个参数是 CyberSealNull fork 的定制，
   官方 OB（3.6.14）会 `extra_forbidden` 拒绝；去掉后写入语义不变（`tags:'dream'` 已保留来源信息）。
   两处均带「Yuki 接入」注释标记。
3. **`xinchao/src/persona-context.js`（新文件，2026-10-05 深夜）**：单实例多状态（C-2）——
   按 `X-Persona-Id`（或 `?persona=`）把六个状态组件（state / black-box / transitions / bridge-queue /
   cabin / personality）分到 `data/xinchao/<personaId>/` 数据桶（懒创建 + 进程内缓存 +
   AsyncLocalStorage 请求上下文；personaId 白名单 `^[0-9a-zA-Z_-]{1,64}# 心潮 + OB 本机部署说明

> 2026-10-05 首建（Yuki 初雪「心潮接入」Wave 1 完成时）。本机 = Windows Server 2022，4GB 内存 / 4 核。
> 本文件是这套服务的维护入口：目录、启动、密钥、改动清单、待办。

## 一、目录结构

```
C:\Xinchao\
  ├─ ombre-brain\            OB 记忆大脑（v3.6.14，官方 main 分支）
  │    ├─ .venv\             Python 3.14 虚拟环境
  │    └─ config.yaml        部署配置（mcp_token / buckets_dir / 鉴权模式）
  ├─ xinchao\                心潮 4.0.0（Node，零依赖）
  │    ├─ xinchao.conf       部署配置（SERVICE_TOKEN / OB 地址与令牌 / 路径）
  │    └─ configs\           interaction-rules.json、attention-rules.json、dream_push_prompt.md
  ├─ data\
  │    ├─ ob\default\        OB 数据（记忆桶 .md / embeddings.db / 日志）
  │    └─ xinchao\default\   心潮状态（state.json / cabin / black-box / journal 等）
  └─ _dl\                    下载缓存、旧版备份（ombre-brain-v363-backup）、验证脚本
```

注意：配置文件**不叫 `.env`**（名字命中本机安全策略），统一用 `xinchao.conf` / `config.yaml`。
心潮启动时必须用 `--env-file=xinchao.conf`，它不会自动读取。

## 二、启动方式（两个服务都要跑）

> **快捷方式（推荐）**：直接运行 `start_ob.bat` / `start_xinchao.bat`（2026-10-05 已生成、含免费通道 key），
> 不必手敲下面的命令；下面的命令用于手工排查。

### OB（端口 18001，绑定 127.0.0.1）

```bat
cd C:\Xinchao\ombre-brain
set OMBRE_BIND_HOST=127.0.0.1
.venv\Scripts\python.exe src\server.py
```

### 心潮（端口 18110，绑定 127.0.0.1）

```bat
cd C:\Xinchao\xinchao
node --env-file=xinchao.conf src\server.js
```

⚠️ 不能用 `npm start`（它不读配置文件）。

## 三、密钥与令牌（均为本机生成，勿外泄；此文件只是索引，值在各配置里）

| 令牌 | 位置 | 用途 |
|---|---|---|
| OB `mcp_token` | `ombre-brain/config.yaml` | 心潮调 OB MCP 的 Bearer |
| OB `hooks.token` | `ombre-brain/config.yaml` | OB 的 /breath-hook 等 |
| 心潮 `SERVICE_TOKEN` | `xinchao/xinchao.conf` | 心潮全部 /v1 接口的 Bearer |
| 心潮 `DASHBOARD_ACCESS_TOKEN` | `xinchao/xinchao.conf` | 看板口令（Wave 3 将改造为 Yuki 账号登录） |

**对应关系**：`xinchao.conf` 的 `OMBRE_MCP_TOKEN` 必须等于 `config.yaml` 的 `mcp_token`（改一个要同步另一个）。

## 四、AI 模型通道（2026-10-05 已接通）

已从 YukiServer 的「免费组」配置（settings 表 `free_group`，名称「Yuki初雪Pro」）取 key 接入
（key 不落在本文件，落在 `start_ob.bat` 与 `xinchao.conf` 里）：

- **OB 压缩通道**：`start_ob.bat` 的 `OMBRE_COMPRESS_*`（base_url=https://api.your-llm-provider.example.com:18443/v1，model=deepseek-flash）
- **心潮模型通道**：`xinchao.conf` 的 `MODEL_*`（同一通道，MODEL_ENABLED=true）

实测：hold 写记忆 ✓（LLM 压缩出新桶）、dream 生成（LLM）✓、梦写回 OB ✓。

**仍缺（可选）**：`OMBRE_EMBED_API_KEY`——免费通道无 embeddings 端点（实测 404），
语义检索维持 standby、关键词/BM25 降级可用。

**未来方向（用户自带 key，待多租户改造时实现）**：每位用户用自己的 key（App 侧上传到服务器）、
没填的用户走免费通道、免费用户在界面不显示地址与 key。详见项目内 `memory/心潮接入-实施交接.md`。

## 五、对第三方代码的改动清单（升级时重打/注意）

1. **`xinchao/src/server.js`（1 行）**：`server.listen` 增加 `process.env.HOST || '0.0.0.0'` ——
   使裸跑部署能绑定 127.0.0.1（默认行为不变）。位置约 L1574，注释带「Yuki 接入」标记。
，非法 → 400）。
   设计见项目内 `memory/心潮接入-Wave3设计.md`；实测见本文档第八节。
4. **`xinchao/src/server.js`（多状态接入，约 15 处；均带「Yuki 接入 · 部署适配改动」注释）**：
   删除 6 个模块级单例（改走 persona-context 的按请求组件集）；请求回调入口解析 persona
   （`enterWith` 上下文，每请求独立——并发隔离经探针实测）；11 个模块级函数各加一行
   `currentBundle()` 解构；`runCycle` 单飞锁改按 persona 分键；定时结算遍历全部已加载桶
   （启动时预加载磁盘上已有桶）。**升级时对照 `_backups\20261005-231538\xinchao-src` 做 diff 重打。**
5. **`ombre-brain/`（多状态（C-2）接入，2026-10-05 深夜；升级时对照 `_backups\20261005-231538\ombre-brain-src` 重打）**：
   - **新文件 `src/persona_bucket.py`**：按 persona 分桶的请求上下文（contextvar + builder 注入 +
     懒创建 + personaId 白名单）；`resolve_component()` 供两个模块的 `__getattr__` 兜底。
   - **`src/tools/_runtime.py`**：组件字段改为**模块级 `__getattr__`**（PEP 562）按请求解析——
     读 `rt.bucket_mgr` = 当前桶真对象（未注入 → None，与旧字段语义逐字节一致）；
     `init()` 的组件键登记到 default 桶（其余全局键照旧）。
   - **`src/web/_shared.py`**：同上（组件集含 deletion_requests / migrate_engine）；
     `init_runtime` 同改；**`replace_embedding_engine` 只发布 default 桶**——
     每个桶的 engine 与自己的 embeddings.db 绑定，其他桶在各自重建/进程重启时用新配置（已知限制）。
   - **`src/server.py`**：组装之后注入 `_build_bucket_components` 工厂
     （default 配置浅拷贝 + buckets_dir 覆盖：末段是 `default` → 平级桶，否则子目录）。
   - **`src/server_app.py`**：新增 `PersonaContextMiddleware`（挂在中间件链最内层；
     `X-Persona-Id` 头 / `?persona=` 解析；非法值 → 400）。
   - **测试适配 2 处**：① `tests/test_release_audit_regressions.py`——热重载测试的注入从
     「模块字段 monkeypatch」改为「default 桶注册 + `set_default_component`」（契约适配，意图不变）；
     ② `tests/conftest.py` 的 `_restore_tool_runtime`——补 **default 注册快照/恢复**
     （`persona_bucket.snapshot_default/restore_default`）与 **web/_shared 快照/恢复**——否则
     跨测试组件泄漏的老病（见 fixture 注释：dream feel 段无声消失）会借「组件搬到注册表」复发。

升级心潮：**对照 `_backups\20261005-231538\xinchao-src\` 与上游新版 diff，重打全部带「Yuki 接入」标记的改动**
（目前：server.listen HOST、ombre-client 2 处、server.js 多状态接入、persona-context.js 整个新文件）；
升级 OB：**对照 `_backups\wave3-完成-20261006-002515\ombre-brain-src\` 与上游新版 diff 重打**
（条目 5 的全部改动 + `tests/conftest.py` 的装配恢复扩展）；`.venv` 重建：
`py -3 -m venv .venv && .venv\Scripts\python.exe -m pip install --require-hashes -r requirements.lock.txt -i https://pypi.tuna.tsinghua.edu.cn/simple`
（跑测试再加 dev 依赖：`-r requirements-dev.lock.txt`，本轮已装）。

## 六、版本说明（为什么 OB 不是 v3.6.3）

心潮 4.0.0 调用 OB 时使用 `mode`（调用意图）与 `with_ids`（返回机器可读结果清单）两个参数，
**这两个参数是 OB 官方 3.6.4 才加入的**（v3.6.3 会报 `extra_forbidden` 拒绝调用）。
v3.6.3 之后官方未再发 tag，所以使用官方 **main 分支（VERSION=3.6.14）**。
心潮为 4.0.0（快照复制，未改动核心逻辑）。

## 七、冒烟测试命令

```bash
# OB 活着（401=鉴权正常，200=带 token 正常）
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://127.0.0.1:18001/mcp -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}'
# 心潮活着
curl -s http://127.0.0.1:18110/health
# 心潮「此刻」
curl -s -H "Authorization: Bearer <SERVICE_TOKEN>" http://127.0.0.1:18110/v1/now
```

验证脚本（兼容性 5 例 / 记忆读 / 梦链路）在 `_dl\test_compat.py`、`_dl\verify_memory.py`、`_dl\trigger_dream.py`。

## 八、已实测的关键数据（2026-10-05）

- OB 进程内存 ≈ 114 MB；心潮 ≈ 54 MB；系统空闲 ≈ 1.1 GB / 4 GB
- 心潮 5/5 调用形态在 OB 3.6.14 上全部通过（breath×2、breath_search、breath_advanced×2 含心跳原样参数）
- 端到端：dream 结算实测从 OB 拉到记忆（`sourceOmbreBucketIds: ['testbucket001']`）
- 看板：口令换会话 ✓ / 错误口令 401 ✓ / 无会话 401 ✓
- **写记忆（LLM 压缩）**：hold 实测 ✓ → `dynamic/工作/2026-10-05 22-10-26 心潮接入测试_aa320569bb8f.md`
- **梦写回**：dream 实测 ✓（LLM 生成「少了一颗草莓…」）→ `dynamic/梦境/2026-10-05 22-12-28 少了一颗草莓有人从背后补上_c304e05abb8d.md`；标题含「草莓」= 素材来自测试记忆的实证
- AI 通道：免费通道（your-llm-provider）chat 200 ✓；embeddings 404（维持关键词降级）
- **多状态（C-2，2026-10-05 深夜实测）**：`X-Persona-Id: testp1` 独立桶（revision 0→2→4 独立推进）、
  testp2 独立（0）、**default 不受影响**（15→17→18）；`/v1/settle` 按桶隔离；非法 personaId → 400 ✓；
  心潮自带测试 `npm test` **173/173** ✓；YukiServer 代理链路 `/xinchao/health` 正常 ✓。
  （测试桶 `data/xinchao/testp1|testp2` 保留待后续联调，收尾时清理。）
- **OB 侧多桶（2026-10-05 深夜实测）**：hold 带 `X-Persona-Id: testp1` → 记忆落 `data/ob/testp1/`
  （default 零污染）；breath 精确 token——testp1 命中 / default 不命中 ✓；非法 personaId → 400 ✓；
  **心潮→OB 透传**（探针 `_dl/probe-xinchao-to-ob.mjs`）——persona 上下文经 OmbreClient 调 breath：
  testp1 命中 / default 不命中 ✓；**OB 全量测试 2916 passed / 0 failed** ✓（含 conftest 两处装配泄漏修复，
  详见交接文档「续 5 追加 2」的根因闭环）。

## 九、待办（下一波）

- [x] 开机自启：**已注册**——计划任务 `XinchaoServices`（ONSTART、SYSTEM、管理员注册），
      动作是 `C:\Xinchao\start_all.bat`（幂等：按端口杀旧实例再拉起两个服务；手动 `schtasks /run` 即重启）。
- [x] 多租户改造（单实例多状态）——**已完成 2026-10-05 深夜**：心潮/OB 双侧多桶 + YukiServer 归属校验（见交接文档「续 5 / 续 6」）
- [x] 用户侧看板（玻璃拟态）——**已完成 2026-10-06**：`https://sy.example.com:11445/me/`（见第十节）
- [ ] App 内人设状态页 + 人设开关（Wave 4；服务端接口已全部就绪）

### 向量化（Embedding）已配置 ✅ —— 2026-10-06 深夜

- **引擎**：硅基流动 **BAAI/bge-m3**（1024 维，免费额度）。key 在 `start_ob.bat` 的
  `set OMBRE_EMBED_API_KEY=...`；端点/模型在 `config.yaml` 的 `embedding.model/base_url`
  （`https://api.siliconflow.cn/v1`）。
- **实测证据**：新记忆写入 → 自动索引（pending 清零）；语义查询「家里的宠物小动物」命中
  「养了一只叫团子的猫」（字面无重合）；不活跃桶（testp1）积压在恢复活动后自动补索引（0→3）。
  ⚠️ **Google Gemini 端点从本机不可达（被墙）——不要把配置切回默认 Gemini。**
- **重启服务的正确姿势（本轮踩坑）**：两个引擎由计划任务 `XinchaoServices`（**SYSTEM** 身份）拉起——
  从普通 shell 直接跑 `start_all.bat` 重启**可能杀不掉旧进程**（taskkill 静默被拒、新进程端口冲突
  起不来，表面像"重启了"其实是旧进程继续跑）。**官方姿势**：`schtasks /run /tn "XinchaoServices"`
  （Git Bash 里前面加 `MSYS_NO_PATHCONV=1`）。
  ⚠️ **日志「两个位置」**：SYSTEM 链的 OB 日志在 `C:\Windows\Temp\ombre_logs\server.log`；
  用户环境启动的在 `%TEMP%\ombre_logs\server.log`——排查时两边都要看。
- **GitHub 备份**：用户 2026-10-06 决定**先不开**（接受单点风险）。github.com 本机可达，
  想开随时配（需私有仓库 + `OMBRE_GITHUB_TOKEN`）。

## 十、外网访问与后台管理（2026-10-06 更新）

外网入口（均走 nginx，复用同一张 sy.example.com 证书）：

| 用途 | 地址 | 鉴权 |
|---|---|---|
| 运营后台（含「心潮」页签） | `https://sy.example.com:11445/admin` | 管理员登录（原有） |
| **用户侧看板**（玻璃拟态） | `https://sy.example.com:11445/me/` | **软件账号**（UID+密码 = App 账号） |
| **OB 记忆大脑看板**（运营者维护用） | `https://sy.example.com:11447/` | **OB 独立密码**（2026-10-06 设，值由运营者保管） |
| 心潮连通检查 | `https://sy.example.com:11445/xinchao/health` | 公开（只回运行信息） |
| 某人设状态（程序调用） | `https://sy.example.com:11445/xinchao/personas/{id}/state` | 用户登录令牌（App Wave 4 用） |
| 对话事件上报（程序调用） | `POST https://sy.example.com:11445/xinchao/personas/{id}/event` | 用户登录令牌 |

后台「心潮」页签提供：在线状态、「她此刻」、11 项驱力、最近的梦、**立即结算一次**、**重启心潮服务**（含记忆大脑，约 10~30 秒）。

⚠️ 心潮本体没有自带网页（它的可视化在第三方平台 xinchaomind.uk）；OB（记忆大脑）**自带**看板（11447），
可查看记忆桶 / 记忆网络 / 日志 / 设置 —— **运营者维护工具，不要发给用户**。

### nginx 配置重载（改 `C:\nginx\conf\wgbh.conf` 后）

nginx 以 SYSTEM 身份由计划任务 `Yuki-Nginx` 开机拉起，**普通管理员权限发不了 reload 信号**（Access denied）。
正确姿势：运行计划任务 `Yuki-Nginx-Reload`（SYSTEM、手动触发，动作 = `C:\nginx\reload.bat`）：

```
schtasks /run /tn "Yuki-Nginx-Reload"     # Git Bash 里前面加 MSYS_NO_PATHCONV=1
```

`reload.bat` 先 `cd /d C:\nginx` —— nginx -s 找 `logs\nginx.pid` 依赖工作目录；计划任务默认工作目录是
system32，**直接跑 `nginx -s reload` 会静默失败**（2026-10-06 踩过：任务「成功」但配置没重载）。
改配置流程：**改 wgbh.conf → `nginx -t` 验证 → `schtasks /run /tn "Yuki-Nginx-Reload"` → 端口验证**。
备份习惯：改前 `cp wgbh.conf wgbh.conf.bak-<日期>-<用途>`。

### 端口与上游映射（2026-10-06 补记）

本机 nginx 监听：`80/443`（主站）、`11445`（YukiServer）、`11447`（OB 看板）、`11448`（OB 本机测试口，仅 127.0.0.1）。
**外网可达性 = 本机监听 × 上游端口映射**（公网 <你的服务器IP> → <内网IP> 的映射在网关侧，服务器上改不到）：
开新端口时**两层都要做**，漏了上游那层就是「本机通、外网不通」（11447 首测即此症状，用户在上游开映射后即通）。
Windows 防火墙目前三档全关（不是拦截因素）。


---

## 十、2026-10-06 深夜追加：OB 侧改动 + 「每角色 1.6MB」诊断结论

> 本节记录本轮对**记忆大脑 OB 源码**的改动（用户已授权改 OB），以及关于「1000 人设会不会崩」的诊断。

### 改动清单（OB 侧）

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `ombre-brain/src/persona_bucket.py` | 桶注册表改 `OrderedDict` + 新增 `_evict_if_needed()`（LRU）+ 环境变量 `OMBRE_MAX_PERSONA_BUNDLES`（**默认 `0`=不启用**） | 为"1000 人设内存爆炸"加的可控释放旋钮；**实测证明它不减少内存**，故默认关（见下） |
| `ombre-brain/src/web/dashboard.py` | 新增两个**本机诊断端点**：`GET /api/persona/debug`（桶数/GC 对象数/按类型 Top25）、`GET /api/persona/trace?action=start\|reset\|diff\|purge`（tracemalloc 按代码行的内存增量 + 清空注册表判别） | 定位"每角色常驻内存"用的仪器；**只回计数、不回 persona id / 路径 / 内容**，服务只绑 127.0.0.1 |

⚠️ 两个诊断端点是**临时仪器**（定位完可撤）；默认保留以便下一轮继续追查。

### 诊断结论（全部实测，不是推断）

1. **每新建一个角色，OB 常驻内存 +≈1.7MB，线性增长、无平台**（三批各 100 个新桶，每批 +165MB；另一批 50 个 +82MB）。
2. **不是"用久了泄漏"**：同一角色重复 200 次请求只 +6MB → 是**每角色一次性**成本。
3. **不是线程**：建 50 个角色，进程线程数 **+0**（wmic ThreadCount）。
4. **释放逻辑确实在跑**：把上限设 4，建 50 个角色后注册表里确实只有 4 个桶。
5. **但清空整个注册表 + GC，内存一点不降**（`purge` 清掉 51 个桶 → RSS 216.8→216.8MB）。
6. **`tracemalloc` 只见约 4MB Python 分配** → 涨的**不是 Python 可追踪对象**。

→ **结论：这 ~1.7MB/角色 是 C 层资源（最可能是每桶 SQLite 连接页缓存/语句缓存）或分配器高水位，进程内无法回收**。
→ **下一轮要做的事**：找出持有 C 层资源的东西并**显式关闭**（`conn.close()` / 客户端 close）；若不可行，则**按 personaId 分片到多个 OB 实例**（每实例承载 ~200 角色 ≈ 350MB），用进程边界兜住内存，重启单片即可回收。

### 现状与风险

- 当前默认**不做任何回收**（`OMBRE_MAX_PERSONA_BUNDLES=0`）——因为实测"释放"既不省内存、还会让下次请求重建（更费）。
- **几十个角色的规模没事**；**到几百个角色会开始明显吃内存**（≈N×1.7MB）；**1000 角色 ≈ 1.7GB → 4GB 机器上会先卡后崩**。
- 缓解（临时）：`schtasks /run /tn "XinchaoServices"` 重启可把内存清零（实测重启后回到 ~15–135MB）——适合夜里做，不适合按角色数增长来兜。


## OB 诊断续（2026-10-06 凌晨 · 持有者排查）—— 未修复，但把"谁钉住了"做实

**新增诊断动作**（同上两个只读端点，另加）：`?action=who`（数各组件类存活数 + 引用者含 frame 行号）、`?action=referrers`（数 sqlite 连接/游标 + 缓存函数名/maxsize/currsize + 采样引用者 repr）。

**本轮实测（artifact-free 的那部分是结论）**：
- 建 N 个角色 → **sqlite 连接数 = N+1**（正好每角色一个）。
- `?action=purge` 清空多桶注册表（`bundles: 0`）之后 —— **11 类组件（EmbeddingOutbox/BucketManager/EmbeddingEngine/DecayEngine/ImportEngine/MigrateEngine/Dehydrator/DeletionRequestStore/SourceStore/YouService/ThemService）存活数一个都没降**（3 角色 → 各 4 个，purge 后仍是各 4 个），连接同理。
  → **结论：组件图被 `_bundles` 之外的引用钉住**（清空注册表无用）。
- 引用者里出现：**`RuntimeLifecycle`**、**`EmbeddingOutbox._run` / `._wait` 的协程对象**、以及若干 dict（含 `__main__` 模块字典）。
  ⚠️ 采样有噪声（我自己的临时 list/协程也出现在引用者里），**"具体是哪一个持有者"尚未确证**。

**下一轮打法（按成本排序）**：
1. 在 `purge` 里**逐桶调 `outbox.stop()` / lifecycle 释放**（`EmbeddingOutbox` 有 `stop()`；`start()` 用 `asyncio.create_task(self._run())`）→ 若组件数随之归零，即确证"后台任务/生命周期钉住组件图"。
2. 若仍不降：在 `?action=who` 里对**单个** Dehydrator 做一次**完整引用链回溯**（逐层 get_referrers，打印每层的 `__qualname__`/文件名:行号），直接指名。
3. 修好后的验收标准：`purge` 后 11 类组件存活数回到 **default 的 1 个**、连接数回 1。

**风险提示**：诊断端点是**本机只读**（只回计数/类名/行号，不回 persona id/路径/内容），但确实新增了表面（surface）；定位完建议撤。

---

## 十一、2026-10-06 凌晨追加：主动消息「只记不发」+ 取件端点

**背景（心潮作者原话，接入准绳）**：*「装了心潮就不需要闹钟唤醒了 叫醒他的只有情绪变化 梦境 念头涌现 驱力变化 … 真正的情绪驱动 而不是闹钟唤醒」*
→ 所以**接入层不加任何调度**：何时说/说什么由心潮的情绪状态机决定，我们只负责取回来送到用户手机。

### 心潮侧改动（均带「Yuki 接入 · 部署适配改动（非上游代码）」注释）
| 文件 | 改动 |
|---|---|
| `xinchao/src/config.js` | 新增 `bark.captureEnabled`（env `BARK_CAPTURE_ENABLED`） |
| `xinchao/src/bark-client.js` | `send()` 在 capture 模式返回 `{sent:true,captured:true}` —— **不发 HTTP**，但让调用方照常 `recordBark` |
| `xinchao/src/server.js` | 新增 `barkActive = enabled \|\| captureEnabled`，替换 4 处生成阀门（dream_pending / dream push / autonomous / daytime） |
| `xinchao.conf` | `BARK_CAPTURE_ENABLED=true`（常开）；防打扰门槛**保持默认** `BARK_MIN_CONTACT_IDLE_HOURS=12` / `BARK_MIN_DRIVE=0.42` |

### YukiServer 改动
`GET /xinchao/personas/{id}/pending?since=` —— 归属校验 → 读心潮 `/v1/state` 的 `recentBarkMessages`（引擎自记）→ 只回 `{at,kind,message}`。
⚠️ 该端点**不做任何调度判断**（"多久查一次"是 App 的取件心跳，不参与"要不要说"）。

### 实测
- 强制结算 → `barkSent:true`，`recentBarkMessages` 0→1：*「团子是不是又趴在键盘上了。深夜写代码的人，美式凉了才想起来喝。」*（引用早前写进 OB 的记忆 → 念头涌现）
- `pending` → 200 / 越权 404 / 无 token 401 / `since=未来` → 0 条

### 未做
App 侧投递（开关 / 轮询取件 / 本地通知 / 按 at 去重）——下一轮。
