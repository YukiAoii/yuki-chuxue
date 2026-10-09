# 源码核验报告 · MemMe（v1.2）

> **为什么有这份文档**：v1.1 里我对 MemMe 的判断全部来自 README 和 GitHub 元数据——**那是外部声称，不是证据**。本报告是读过真实源码后的核验结果，逐条标注 file:line。
>
> **核验方法**：`git clone` 被环境重写到国内镜像后失败（该仓库在镜像中不存在），改用 GitHub tarball 端点直取源码（4.6MB，http=200）。解压后共 **264 个文件，代码在 `_refs\MemMe\`**。读取了 `storage\mod.rs`（schema）、`memory\sync.rs`、`memory\lifecycle.rs`、`rerank.rs`、`webhook.rs`。
>
> **边界**：`memory\search.rs`（797 行）、`compact_ops.rs`（1024 行）、`meditation_ops.rs`（441 行）**尚未逐行读**，相关判断标为"未核验"。

---

## 一、核验结论：我 v1.1 的判断，哪些对、哪些错

| v1.1 里的说法 | 来源 | 核验结果 |
|---|---|---|
| 记忆按 (owner × agent) 隔离 | README 声称 | ✅ **证实，但需修正**——见下方§1.1 |
| MemMe 是纯本地库，没有云端同步 | README 原文 "Stay on the device" | ❌ **被证伪**——有完整增量同步协议，见§1.2 |
| 记忆衰减/遗忘机制要自己做 | 我的推断 | ❌ **它已经有了**——见§1.3 |
| 主动消息的记忆支撑要自己设计 | 我的推断 | ❌ **它已经有专门的接口**——见§1.4 |

**四条里有三条被证伪。** 我 v1.1 的推荐建立在"MemMe 只是个本地小库"的认知上——这是错的。它比我以为的成熟得多。

### 1.1 隔离机制：证实，但有个必须注意的细节

Schema 实测（`crates\memme-core\src\storage\mod.rs:350`）：

```sql
CREATE TABLE IF NOT EXISTS memories (
    id TEXT PRIMARY KEY,
    content TEXT NOT NULL,
    embedding {emb_type},
    user_id TEXT NOT NULL,          ← 必填
    agent_id TEXT,                  ← 可空 ⚠️
    ...
```

`user_id` 是 `NOT NULL`，但 **`agent_id` 是可空的**。

**这很重要**：如果你直接用默认配置，所有角色会共享同一个用户的记忆池——Alice 记住的事会串到 Yuki 身上。**用的时候必须强制 `agent_id` 必填**，否则"每个角色是独立的关系"这条就破了。这是集成时的第一个坑。

### 1.2 同步能力：README 说"只留设备上"，代码说"能增量同步"

README 的原话是 *"Stay on the device"*。但代码里有（`memory\sync.rs`）：

| 行号 | 能力 |
|---|---|
| `sync.rs:141` | `export_changes_since(since_version, device_id) -> SyncDelta` ——**增量同步**，只传变更部分 |
| `sync.rs:159` | `current_sync_version()` —— 同步版本号 |
| `sync.rs:64` | `export_with_privacy(user_id, include_local)` —— **隐私分级导出** |
| `sync.rs:75` | `full_export()` —— 全量导出（含 memories/sessions/events/episodes/entities/relations/identity/sources 八层） |
| `sync.rs:85` | `full_import()` —— 全量导入，**按依赖顺序**插入 |

配合 schema 里的字段（`storage\mod.rs` 的 memories 表）：

```sql
    privacy TEXT DEFAULT 'syncable',   ← 记忆可标记为不可同步
    sync_version INTEGER DEFAULT 0,
    device_id TEXT,
    sync_status TEXT DEFAULT 'pending',
```

`SyncDelta` 的结构就是一套同步协议的原语：

```rust
SyncDelta { device_id, from_version, to_version, changes, exported_at }
```

**结论**：它是一个**为同步而设计**的本地库。"留在设备上"指的是默认运行模式，不是能力上限。

### 1.3 遗忘机制：它已经实现了

`memory\lifecycle.rs`：

| 行号 | 方法 | 作用 |
|---|---|---|
| `lifecycle.rs:15` | `consolidate(user_id, decay_rate, min_importance, delete_below)` | 按时间衰减重要性，不常被访问的记忆掉重要性 |
| `lifecycle.rs:39` | `cleanup_expired()` | 清理过期记忆 |
| `lifecycle.rs:45` | `prune(user_id, strategy, count)` | 修剪 |
| `lifecycle.rs:130` | `pin_trace(memory_id, pinned)` | 钉住记忆 |

`consolidate` 的文档注释原文（`lifecycle.rs:16-20`）：

> *"Apply time-based importance decay to all memories for a user. Memories that haven't been accessed recently lose importance."*

并且当配置项 `enable_forgetting_curve` 打开时，会调用 `consolidate_forgetting_curve(user_id, prune_retention_threshold, 30.0)`（`lifecycle.rs:31`）——**这是艾宾浩斯遗忘曲线的实现**。

`meditations` 表里还有个计数器字段 `memories_decayed`（`storage\mod.rs:509` 区块）——记忆巩固过程中衰减了多少条，**是有记账的**。

**为什么这件事关键**：长期记忆产品最大的隐性风险是记忆库无限膨胀——聊一年后，几万条记忆，检索越来越慢、越来越不准、成本越来越高。**遗忘曲线是让"长期"真正可持续的机制**，它已经写好了。

### 1.4 主动消息：它有一个专门为此设计的接口

`lifecycle.rs:147-149` 的原文注释：

> *"Recall old memories for nostalgia / proactive bubbles ('还记得那天...'). Returns random memories older than `min_age_days` with importance >= `min_importance`."*

方法名 `recall_nostalgia(user_id, min_age_days, min_importance, limit)`（`lifecycle.rs:149`）。

**这正是"她会主动找我"需要的能力**——不是随便发一句"在吗"，而是捞出一段有意义的旧记忆，说"还记得那天……"。而且注释里 `'还记得那天...'` 是用法示例，说明这个用途是作者**明确设计过的**。

配合 `pin_trace` 的注释（`lifecycle.rs:129`）：

> *"Pinned memories are exempt from forgetting curve decay and are prioritized in HOT-tier queries."*

**HOT-tier（热层）**——它的查询是分层级的。这和我 v1.1 里提的"记忆冷热分层"思路同源（虽然我说的 prompt 分层，它说的是查询分层）。

---

## 二、我没预料到的能力

### 2.1 十四张表的认知记忆分层

`storage\mod.rs` 实测的表清单：

| 表 | 行号 | 是什么 |
|---|---|---|
| `memories` | 350 | 基础记忆（38 个字段） |
| `history` | 390 | 记忆变更审计（old/new/event） |
| `entities_*` / `relationships_*` | 400/409 | 实体和关系，**按 collection 动态建表**（多租户隔离） |
| `memory_entities` | 424 | 记忆↔实体关联 |
| `sessions` | 432 | 会话（含 structured_notes） |
| `sources` | 445 | 数据来源 |
| `events` | 454 | 原始事件（含 purified_content "净化后内容"） |
| `episodes` | 476 | **情节记忆**（significance / outcome / recall_count / last_meditated_at） |
| `identity` | 497 | **身份特征**（trait_type / confidence / evidence_ids）——"她是谁" |
| `meditations` | 509 | **记忆巩固过程**（含 conflicts_found 冲突检测） |
| `procedures` | 528 | **程序性记忆**（trigger_pattern / usage_count） |
| `associations` | 541 | **跨层关联**（from_layer/to_layer） |
| `recalls` | 552 | 检索记录 + **feedback 反馈** |
| `memme_config` | 563 | 配置 |

这不是"存个对话记录"，是**按认知科学分的记忆类型**：情节记忆、身份认知、程序性记忆、跨层关联。作者显然懂这块。

`SCHEMA_VERSION = "6"`——迭代过 6 个版本。

### 2.2 记忆的强度模型（防膨胀的核心）

`memories` 表里这一组字段：

```sql
    importance REAL DEFAULT 0.5,
    access_count INTEGER DEFAULT 0,
    stability REAL DEFAULT 1.0,
    storage_strength REAL DEFAULT 1.0,
    retrieval_strength REAL DEFAULT 1.0,
    confidence REAL DEFAULT 0.8,
    superseded_by TEXT,
    valid_from TEXT,
    valid_until TEXT,
    evidence TEXT,
```

**storage_strength vs retrieval_strength** 的这一对（存储强度 / 检索强度），是认知心理学里记忆模型的标准分法——记忆**存着**跟能不能**想起来**是两回事。

**`superseded_by` + `valid_from`/`valid_until`** = **时态记忆**。"你换了工作"这件事，让旧记忆被新记忆取代但不删除（保留历史，能回溯"她以前以为我是做什么的"）。

**`evidence` + `confidence`** = 记忆有**证据链和可信度**——防止她把闲聊里的玩笑话当成事实记住。

### 2.3 ⚠️ LLM 重排序——直接影响用户账单

`rerank.rs:35` 的注释：

> *"Asks the LLM to score each result's relevance to the query on a 0-10 scale."*

它的 system prompt（`rerank.rs:66-68`）：

```
You are a relevance scorer. Given a query and a list of documents,
score each document's relevance to the query on a scale of 0-10
(10 = most relevant). Return JSON: {"scores": [score0, score1, ...]}
```

流程（`rerank.rs:102-115`）：召回结果 → 让大模型逐个打分 → 按分排序 → 截断 top_k。

**这是两阶段检索**（召回 + 精排），效果好，但**每次检索要额外调一次大模型** → **用户的 API 花费增加**。

**你必须知道这个 trade-off**：它是可选的独立层（不在主检索路径上），但如果你打开它，等于每次对话多一次 API 调用。而你整个产品的卖点是"帮用户省钱"——**这两个目标在这里直接冲突**，需要实测权衡。

### 2.4 隐私分级与不可变记忆

- `privacy TEXT DEFAULT 'syncable'`（`storage\mod.rs`）：记忆可标记为 `local_only`，"这条不同步到云端"
- `export_with_privacy(user_id, include_local)`（`sync.rs:64`）：导出时可以选择是否包含本地私有记忆
- `immutable INTEGER DEFAULT 0` + `MemoryError::ImmutableMemory`（`lifecycle.rs:53-105` 中反复出现）：标记为不可变的记忆，**批量更新/删除时会跳过而不是报错**

对一个"她会记住你的私事"的产品，**隐私分级是必需的**——用户得能说"这条别存"。

### 2.5 多语言绑定

`crates\` 下有：`memme-node`（含 `win32-x64-msvc` 预编译二进制，**你的 Windows 能直接用**）、`memme-python`、`cu-memme`、`memme-dora`。Rust 核心 + Node/Python 绑定。

---

## 三、关键缺口（诚实说清）

### 3.1 没有"客户端 ↔ 云端"的同步传输实现

`webhook.rs` 是唯一的网络出站代码，但它是**事件通知**：

```rust
pub enum WebhookEvent { MemoryAdd, MemoryUpdate, MemoryDelete }
// "Webhook manager that fires HTTP POST requests on memory events."
```

**它只往外推事件，不提供"和云端记忆服务器双向同步"的实现。**

**所以分工是**：MemMe 给了你同步的**数据协议**（delta、版本号、隐私过滤、依赖顺序导入），同步的**传输与服务端**要你自己搭。

### 3.2 没有 Dart 绑定 —— 这直接影响你的技术选型

绑定只有 Node / Python。你 v1.0 定的是 **Flutter（Dart）**。所以：

- ❌ App 端直接嵌入 MemMe 核心 → 需要自己写 Dart FFI，成本高
- ✅ **把 MemMe 跑在你的云端记忆服务上**（用 Python 或 Node 绑定），App 通过 API 访问

**这反而和方案 B 天然吻合**：记忆服务本来就在云端，App 只是个客户端。选型不用改。

---

## 四、对架构的影响：一条重要建议的更新

**v1.1 我说"每块都有现成的，但需要拼"。核验后我要把话说得更重：**

> 🎯 **记忆层不要自己重造，直接用 MemMe。**

理由：你产品最难、最容易被低估的部分——**记忆的认知分层、衰减与遗忘、强度模型、多角色隔离、隐私分级、增量同步协议**——它全都实现了，而且实现得比你临时设计要细（14 张表，6 个 schema 版本）。Apache-2.0 可商用。

**你真正该自己做的，缩小到三件事**：
1. **手机 App 外壳**（Flutter）——BYOK 多模型对话
2. **缓存友好的 prompt 组装器**（v1.1 §4 的冷热分层）——**这是你的护城河**
3. **记忆同步的传输层 + 云端服务**——把 MemMe 包成服务（用它的 Python/Node 绑定）

第 2 件仍然是完全空白的领域：MemMe 只解决"记得住"，不解决"调用时便宜"。**两者合起来才是你的完整价值主张。**

---

## 五、修正后的落地顺序

| 阶段 | 做什么 | 用现成的 | 自己写 |
|---|---|---|---|
| 一 | App 壳 + BYOK 对话 | 参考 `oriveo` 架构 | Flutter 壳、多模型适配 |
| 二 | 接入记忆 | **MemMe**（Python/Node 绑定做服务端） | 记忆服务 API 封装 |
| 三 | 缓存优化 | 无现成 | **冷热分层组装器 + 实测账单** |
| 四 | 主动消息 | `recall_nostalgia` | 调度器 + 推送通道 |

**注意阶段三的位置**：它必须等阶段二稳定后才做，因为缓存优化建立在固定的 prompt 结构上——而 prompt 结构由记忆层的输出格式决定。

---

## 六、尚未核验的（下一轮该做）

| 事项 | 为什么重要 |
|---|---|
| `memory\search.rs`（797 行） | 检索**召回**的核心算法（第一阶段），决定"能不能想起来" |
| `compact_ops.rs`（1024 行） | 记忆压缩——长期运行时的成本控制 |
| `meditation_ops.rs`（441 行） | 记忆巩固——**从对话到记忆的加工过程**，是你产品"她真的记住了"的关键 |
| `oriveo` 源码 | 未下载（20MB，按当前网速约 6-7 分钟）。壳的实现细节 |
| **缓存实测** | 最大空白。所有模型商的缓存折扣都**必须实测**，不能引用文档数字 |
