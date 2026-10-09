# `src/` 扁平模块渐进迁移计划

## 目标

把 `src/` 根目录的业务实现逐文件迁入 `src/ombrebrain/` 的明确领域包，同时保持：

- `python src/server.py`、Docker、Render、热更新与自动回滚入口不变；
- 现有顶层导入路径在过渡期继续可用；
- MCP、Web、OAuth、CLI 与数据格式行为不变；
- 每次只迁移一个文件，每一步都能独立回滚和验证。

这是一项包结构迁移，不借机重写业务逻辑。功能重构、符号改名和行为改变必须另开变更。

## 不变量

1. `src/server.py` 在最后阶段之前保持原路径。
2. 每个旧模块先变为兼容层，再经过至少一个发布周期后才考虑删除；首批兼容层已在 2.7.8—2.7.10 完成三个正式版本的观察期，并于 2.8.2 审计后退役。
3. 新代码只导入新的 canonical package 路径；旧路径只服务外部兼容。
4. 兼容层必须显式转发公开符号，避免产生第二份模块状态。
5. 含可变模块状态、单例、锁、缓存或 monkeypatch 契约的模块，迁移前必须单独设计状态同一性方案。
6. 每个文件单独提交或保持为可独立审阅的一组变更；绝不在迁移步骤中 push。

## 单文件迁移流程

1. 记录调用方、反向依赖、测试导入与 monkeypatch 路径。
2. 在 `ombrebrain/` 下建立目标模块并原样移动实现。
3. 将旧模块改为无状态兼容层。
4. 将仓库内生产调用方切换到 canonical package 路径。
5. 增加新旧导入兼容测试；有状态模块还要验证对象同一性。
6. 运行该模块定向测试、受影响领域测试和 import/compile 检查。
7. 每批完成后运行完整 pytest；入口相关变更额外跑 Docker/MCP/Web 冒烟测试。

## 分阶段顺序

### 阶段 A：纯函数与小型无状态模块

建议逐个迁移：

1. `memory_messages.py` → `ombrebrain/domain/memory_messages.py`
2. `plan_history.py` → `ombrebrain/domain/plan_history.py`
3. `provider_detect.py` → `ombrebrain/integrations/provider_detect.py`
4. `public_origin.py` → `ombrebrain/security/public_origin.py`
5. `bucket_scoring.py` → `ombrebrain/retrieval/bucket_scoring.py`

### 阶段 B：边界服务与独立基础设施

候选包括 `media_store.py`、`vault_health.py`、`backup_archive.py`、
`embedding_outbox.py`、`projection_*`、`ledger_*`。这些模块需逐个确认文件系统、
数据库、锁和后台任务状态不会因双重导入而复制。

当前逐文件顺序：

- [x] `media_store.py` → `ombrebrain/storage/media_store.py`
- [x] `vault_health.py` → `ombrebrain/storage/vault_health.py`
- [x] `backup_archive.py` → `ombrebrain/storage/backup_archive.py`（旧路径使用模块别名以保留 monkeypatch 语义）
- [x] `embedding_outbox.py` → `ombrebrain/storage/embedding_outbox.py`
- [x] `deployment_profile.py` → `ombrebrain/security/deployment_profile.py`
- [x] `projection_*` 与 `ledger_*`（每个文件仍单独迁移）
  - [x] `ledger_property.py` → `ombrebrain/eventsourcing/ledger_property.py`
  - [x] `ledger_replay.py` → `ombrebrain/eventsourcing/ledger_replay.py`
  - [x] `ledger_mirror.py` → `ombrebrain/eventsourcing/ledger_mirror.py`
  - [x] `projection_mirror.py` → `ombrebrain/projection/projection_mirror.py`
  - [x] `projection_sqlite.py` → `ombrebrain/projection/projection_sqlite.py`
  - [x] `projection_vector.py` → `ombrebrain/projection/projection_vector.py`

### 阶段 C：引擎与大型有状态模块

依次评估 `embedding_engine.py`、`decay_engine.py`、`github_sync.py`、
`import_memory.py`、`migration_engine.py`、`migrate_engine.py`、`dehydrator.py`。
这阶段每个模块都需要领域级回归和失败/取消路径验证。

### 阶段 D：核心装配

最后处理 `utils.py`、`bucket_manager.py`、`server_app.py`。`server.py` 保留稳定脚本入口，
只在内部装配已经全部 package 化后，才考虑将实现移至 `ombrebrain/app/` 并留下三至五行启动壳。

### 阶段 E：CLI 与旧兼容层退役

将 `write_memory.py`、`reclassify_api.py` 等 CLI 的实现迁入 `ombrebrain/cli/`，旧脚本保留入口壳。
只有在仓库内无旧路径引用、文档已更新、完整回归通过并经过兼容观察期后，才逐个删除旧兼容层。首批 16 个兼容层已在 2.8.2 完成该流程；仓库测试也改用 canonical package 路径，防止旧路径被内部引用重新引入。

## 每批停止条件

出现以下任一情况立即停止扩大迁移范围：

- 新旧路径产生两个不同的模块级单例或缓存；
- 测试必须依赖大量临时 alias 才能通过；
- 出现循环导入、启动时副作用顺序变化或配置加载两次；
- Docker、热更新、OAuth/MCP 或数据写入行为发生变化；
- 无法为该文件建立明确、可逆的兼容边界。

## 当前进度

- [x] 建立迁移原则、顺序和验证门槛。
- [x] 阶段 A.1：迁移 `memory_messages.py`。
- [x] 阶段 A.2：迁移 `plan_history.py`。
- [x] 阶段 A.3：迁移 `provider_detect.py`。
- [x] 阶段 A.4：迁移 `public_origin.py`。
- [x] 阶段 A.5：迁移 `bucket_scoring.py`。
- [x] 阶段 B：完成 storage、deployment、ledger 与 projection 模块迁移。
- [x] 兼容观察期：`2.7.8`、`2.7.9`、`2.7.10` 三个正式版本均保留旧路径。
- [x] 退役前引用审计：生产代码、测试、活动文档与部署入口均不再导入旧路径。
- [x] 退役首批 16 个顶层兼容壳，测试统一改用 `ombrebrain.*` canonical package。
