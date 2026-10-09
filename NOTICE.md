# 第三方致谢与许可标注

Yuki 初雪 在设计与实现上参考了以下开源项目。

> **本仓库不包含这些项目的源代码。** 它们仅以只读快照形式存放在开发者的本地
> 工作目录（`_refs/`）中用于查阅，未随本仓库分发，也不属于本项目。

---

## 参考项目

### Tianshu Harness

- **许可证**：Apache License 2.0
- **版权**：Copyright 2025-2026 Tianshu Contributors
- **借鉴内容**：上下文压缩的触发线公式（`resolveCompactSpec` 的取 min 下界）、
  provider 配置的三态前缀缓存策略、模型预算分档表
- **形式**：原项目为 TypeScript，本项目为 Kotlin —— 属**实现思路的意译**，非代码复制

### DeepSeek Harness

- **许可证**：MIT
- **版权**：Copyright (c) 2026 DeepSeek
- **借鉴内容**：compaction 的区域划分与 token 计量口径、聊天 token 用量的展示格式
- **形式**：同为 TypeScript → Kotlin 的意译参考

### Operit

- **许可证**：GNU Lesser General Public License v3.0
- **借鉴内容**：记忆库的界面组织方式与记忆抽取流程的交互设计
- **⚠️ 注意**：Operit 与本项目的 Android 端**同为 Kotlin/Java**，理论上存在直接移植的可能。
  本项目记忆相关模块的编写以各自独立实现为准；若后续发现确有直接移植的代码片段，
  需在对应文件保留 LGPL-3.0 声明，或改写为独立实现。

### 心潮 · 念（Xinchao · Nian）

- **许可证**：GNU Affero General Public License v3.0
- **使用方式**：Yuki 后端将心潮作为**独立部署的服务**通过 HTTP 接口调用，
  **未**把心潮的源代码并入本仓库，**未**修改后随本仓库分发。
- **⚠️ 注意**：AGPL-3.0 对"通过网络提供服务"有源码开放要求。如果你基于心潮改造了
  自己的服务并对公网提供，AGPL 要求你向使用者提供修改后的源码 —— 这与本仓库的许可相互独立。

### MemMe

- **许可证**：Apache License 2.0
- **借鉴内容**：记忆系统的架构评估参考

### kibotu / androidx-splashscreen-compose

- **许可证**：Apache License 2.0（已核对其仓库 LICENSE 原文）
- **借鉴内容**：启动屏与开屏动画的交互设计

---

## Apache-2.0 / MIT 项目的义务（已履行）

对被参考的 Apache-2.0 与 MIT 项目，本项目：

- 保留其版权声明与许可证名称（见上）
- 未使用其商标
- 未声称任何形式的担保

## 随本仓库一起分发的第三方组件

下面两个组件**包含源代码**（不只是借鉴思路），各自保留原有许可证。

### 心潮 · Xinchao（`xinchao/`）

- **上游**：心潮念（Xinchao · Nian）的 `xinchao/` 模块
- **许可证**：MIT License —— Copyright (c) 2026 派派
- **本仓库对其所做的修改**（为接入本项目的**人设隔离**而适配）：
  - 新增 `src/persona-context.js`（按 personaId 隔离状态与上下文）
  - 修改 `src/server.js`、`src/engine.js`、`src/config.js`、
    `src/context-envelope.js`、`src/ombre-client.js`、`src/bark-client.js`
- 上游 `LICENSE` 原样保留于 `xinchao/LICENSE`。

### Ombre Brain（`ombre-brain/`）

- **上游**：P0luz/Ombre-Brain —— 原始 MIT，Copyright (c) 2026 P0lar1zzZ
- **许可证**：MIT License
- **本仓库对其所做的修改**：记忆写入流程的**异步化**改造
  （`src/tools/hold/core.py`：正文先落盘、打标转后台补回），以适配本项目的云端记忆。
- 上游 `LICENSE`、`NOTICE.md`、`AUTHORS.md` 原样保留于 `ombre-brain/`。

> 依两个上游 `NOTICE` 的请求，此处保留对原作者与开发组的署名，并说明本仓库的改动。
> 二者均为 **MIT**，**允许商用、修改与再分发**，唯一硬性要求是保留版权声明。

---

## 本项目自身的许可

本项目以 **Apache License 2.0** 分发，完整条款见 LICENSE。

```
Copyright 2026 YukiAoii
```

### ⚠️ 一个需要你知晓的前提

采用 Apache-2.0 的前提是：本项目对上述参考项目**只借鉴实现思路，代码为独立编写**。
当前仓库**不含** Operit（LGPL-3.0）与 心潮（AGPL-3.0）的源代码 ——
对前者的引用均在注释层（"参考实现……"），对后者是**跨进程的 HTTP 调用**。

若日后发现确有**直接移植**的代码片段，需按来源调整：

- 来自 **Operit（LGPL-3.0）** 的代码 → 该部分须以 LGPL-3.0 分发（常见做法：把该文件独立成模块并附声明）。
- 来自 **心潮（AGPL-3.0）** 的代码 → 该部分须以 AGPL-3.0 分发，并履行网络服务场景下的源码提供义务。
  心潮在本项目中是**独立部署、经接口调用的服务**，其代码不属于本仓库，无此约束。
