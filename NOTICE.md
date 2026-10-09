# 第三方致谢与许可

本项目在设计与实现中参考了以下开源项目。这些项目以只读快照形式存放在开发者本地
（`_refs/`），未随本仓库分发。

## 参考项目

### Tianshu Harness

- 仓库：https://github.com/huiliyi37/Tianshu-Tui
- 许可证：Apache License 2.0
- 版权：Copyright 2025-2026 Tianshu Contributors
- 借鉴内容：上下文压缩的触发线公式、provider 配置的三态前缀缓存策略、模型预算分档
- 原项目为 TypeScript，本项目为 Kotlin，属实现思路的意译

### DeepSeek Harness

- 仓库：https://github.com/deepseek-ai/deepseek-harness
- 许可证：MIT
- 版权：Copyright (c) 2026 DeepSeek
- 借鉴内容：compaction 的区域划分与 token 计量口径、聊天 token 用量的展示格式

### Operit

- 仓库：https://github.com/AAswordman/Operit
- 许可证：GNU Lesser General Public License v3.0
- 借鉴内容：记忆库的界面组织方式与记忆抽取流程的交互设计
- 注意：Operit 与本项目的 Android 端同为 Kotlin/Java。本项目记忆相关模块以独立实现为准；
  若发现确有直接移植的代码片段，需在对应文件保留 LGPL-3.0 声明或改写为独立实现

### 心潮 · 念（Xinchao · Nian）

- 仓库：https://github.com/tianyupaipai-cmd/xinchao-nian
- 使用方式：作为独立部署的服务，通过 HTTP 接口调用，源代码未并入本仓库

### MemMe

- 仓库：https://github.com/vibeinging/MemMe
- 许可证：Apache License 2.0
- 借鉴内容：记忆系统的架构评估参考

### kibotu / androidx-splashscreen-compose

- 仓库：https://github.com/kibotu/androidx-splashscreen-compose
- 许可证：Apache License 2.0
- 借鉴内容：启动屏与开屏动画的交互设计

---

## 随本仓库分发的第三方组件

以下两个组件包含源代码，各自保留原有许可证。

### 心潮引擎（`xinchao/`）

- 上游：https://github.com/tianyupaipai-cmd/xinchao-nian 的 `xinchao/` 子目录
- 许可证：MIT License，Copyright (c) 2026 派派
- 改动：为适配人设隔离，新增 `src/persona-context.js`，并修改
  `src/server.js`、`src/engine.js`、`src/config.js`、
  `src/context-envelope.js`、`src/ombre-client.js`、`src/bark-client.js`
- 上游 `LICENSE` 保留于 `xinchao/LICENSE`

> `xinchao-nian` 是混合许可仓库：`xinchao/` 为 MIT，`ombre-brain/` 为含非商业约束的衍生版。
> 本仓库只用了其中的 `xinchao/` 子目录。

### Ombre Brain（`ombre-brain/`）

- 上游：https://github.com/P0luz/Ombre-Brain
- 许可证：MIT License，Copyright (c) 2026 P0lar1zzZ
- 改动：记忆写入流程的异步化（`src/tools/hold/core.py`：正文先落盘、打标转后台补回）
- 上游 `LICENSE`、`NOTICE.md`、`AUTHORS.md` 保留于 `ombre-brain/`

> 本仓库的 `ombre-brain/` 取自 P0luz 原版（纯 MIT，允许商用），
> 不是 `xinchao-nian` 内的衍生版本。

---

## 本项目自身的许可

Apache License 2.0，完整条款见 LICENSE。

```
Copyright 2026 YukiAoii
```

### 许可范围

本项目对上述参考项目只借鉴实现思路，代码为独立编写，仓库内不含
Operit（LGPL-3.0）或心潮（AGPL-3.0）的源代码 ——
对前者的引用都在注释层，对后者是跨进程的 HTTP 调用。

如果日后发现确有直接移植的代码片段，按来源处理：

- 来自 Operit（LGPL-3.0）的 → 该部分以 LGPL-3.0 分发
- 来自心潮（AGPL-3.0）的 → 该部分以 AGPL-3.0 分发，并履行网络服务场景下的源码提供义务
