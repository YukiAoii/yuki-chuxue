# Yuki 初雪

> ## ⚠️ 本项目由**天枢 harness**（Tianshu-harness）完成开发，**仅供学习使用**。

一个**人机恋（AI 伴侣）**主题的聊天应用，从客户端到服务端全部自托管。

它不是"套壳聊天框"——角色有**对话之外持续变化的内在状态**（驱动、情绪、念头池、梦），
记忆存在自己的记忆库里、按遗忘曲线衰减，在你不说话的时候也可能**主动来找你**。

---

## 这是什么

| 维度 | 说明 |
|---|---|
| **形态** | Android 客户端 + Web 前端 + 自托管服务端 + 可选的心智/记忆后端 |
| **模型接入** | **BYOK**（Bring Your Own Key）—— 用自己的 API Key，支持多家供应商分组 |
| **部署** | 纯自托管，服务端跑在你自己机器上，数据不出你的服务器 |
| **定位** | 个人自用 / 学习研究 |

---

## 功能

### 聊天

- 与多个人设（角色）分别保持独立会话，**流式输出**逐字打字机效果
- 「分段气泡」——长回复按句拆成多枚气泡，像真人连发
- 思考过程可折叠展示、可回看历史版本并切换
- 长按消息：复制 / 删除 / 重新生成
- 表情包、图片消息、图片预览

### 角色与内在状态

- **人设系统**：角色卡 + 用户自己的资料卡（头像、昵称、性别等）
- **心潮引擎**：给角色一个在对话之外**一直在变**的内在状态——驱力、情绪、念头池、梦、小屋
- **「Ta 主动来找你」**：角色可以主动发消息；退出会话后在消息列表显示未读提示

### 记忆

- 两级记忆：**本地记忆**（Room）与**云端记忆**（Ombre Brain）按开关分轨
- 云端记忆基于**效价/唤醒度**坐标打标，带遗忘曲线与语义检索
- 记忆管理界面：查看、编辑、上传、搬移

### 界面与观感

- **美化设置**：全局自定义背景、气泡不透明度、顶栏不透明度
- 背景遮罩用**真高斯模糊**，浓度即模糊程度
- 浅色"初雪"主题，圆角卡片式设计

### 服务端

- 账号体系（邮箱注册/登录/找回）、设备管理
- 后台管理页：用户、公告、版本发布、反馈处理
- **版本发布与 App 内更新检查**、更新日志下发
- 遥测上报（可关）

### 上下文管理

- **上下文压缩**：触发线公式对齐 deepseek-harness 的 `resolveCompactSpec`（取 min 下界），
  压缩阈值可在 20%–90% 之间调整，界面显示当前触发线对应的 token 数
- **缓存纪律**：已写入的内容不改写，新内容只追加 —— 保住前缀缓存，降低 token 成本

---

## 架构

```
┌─────────────────┐        ┌─────────────────┐
│  Android 客户端  │        │   Web 前端       │
│  (Kotlin/Compose)│        │ (Vite + React)  │
└────────┬────────┘        └────────┬────────┘
         │  HTTPS / SSE              │
         └───────────┬───────────────┘
                     ▼
          ┌──────────────────────┐
          │  backend (FastAPI)   │  ← 账号、会话归档、版本发布
          │  + SQLite            │     后台管理页 /admin
          └───────┬──────────────┘
                  │  HTTP（可选）
        ┌─────────┴──────────┐
        ▼                    ▼
┌───────────────┐   ┌─────────────────┐
│ xinchao        │   │ ombre-brain      │
│ 心潮引擎        │──▶│ 情绪记忆库 (MCP)  │
│ 内在状态/梦/念头 │   │ 打标/衰减/检索    │
└───────────────┘   └─────────────────┘
```

> **后两个是可选的**。不接它们，App 仍然完整可用（只是没有"云端记忆"与"Ta 主动来找你"
> 这类依赖服务端状态的功能）。两者的部署顺序、环境变量与上游出处见各自目录下的 `README.md`。

---

## 技术栈

### Android 客户端（`android-native/`）

| 项 | 版本 / 说明 |
|---|---|
| 语言 | Kotlin **2.0.21** |
| UI | Jetpack Compose（**BOM 2024.10.01**）+ Material 3，单 Activity |
| 路由 | `navigation-compose` 2.8.4 |
| 构建 | Gradle **9.1.0** + AGP 8.11.0 + KSP 2.0.21-1.0.28 + **JDK 17** |
| 本地存储 | **Room 2.7.2**（会话/消息/记忆）+ **DataStore** 1.1.1（设置/人设） |
| 网络 | **OkHttp 4.12.0**，SSE 自行解析 |
| 序列化 | kotlinx-serialization 1.7.3 |
| 协程 | kotlinx-coroutines 1.9.0 |
| 测试 | JUnit 4.13.2 —— **1347 个 JVM 单元测试 / 140 个测试类** |
| SDK | compileSdk 35 · minSdk 26 · targetSdk 35 |

> ⚠️ 本项目**没有**用 Hilt / Paging 3 / Coil / Glide / WorkManager —— 依赖一律手写。
> 当前版本 `versionCode 143` / `versionName 0.61.57`。

### Web 前端（`app/`）

Vite + React 18 + TypeScript + Capacitor 8（可打包成原生壳）。

### 服务端（`backend/`）

| 项 | 说明 |
|---|---|
| 框架 | **FastAPI** + uvicorn |
| 存储 | SQLite |
| 依赖 | 只有 2 个 —— 密码哈希用 `hashlib`、邮件用 `smtplib`、令牌用 `secrets`，全走标准库 |
| 鉴权 | 自签令牌（Bearer） |

### 心潮引擎（`xinchao/`）

Node.js 20+，无外部依赖，纯 `node:http` 自写服务。

### Ombre Brain（`ombre-brain/`）

Python，MCP 协议接入，向量检索 + 遗忘曲线。

---

## 目录结构

```
.
├── android-native/          Android 客户端（Kotlin + Compose）
│   └── app/src/main/java/ai/yuki/chuxue/
│       ├── data/            数据层：Room、网络、记忆、上下文压缩
│       ├── service/         前台服务
│       └── ui/              界面层：聊天、人设、设置、记忆管理
├── app/                     Web 前端（Vite + React + Capacitor）
├── backend/                 服务端（FastAPI + SQLite）
│   ├── main.py              全部接口
│   ├── static/              后台管理页 + 官网页
│   └── deploy/              部署脚本
├── xinchao/                 心潮引擎（可选）
├── ombre-brain/             Ombre Brain 记忆库（可选）
├── docs/                    设计文档与界面设计稿
├── tools/                   开发辅助脚本
├── 人机恋app开发文档.md      产品与架构设计总纲
├── NOTICE.md                第三方许可与改动署名
└── LICENSE                  Apache-2.0
```

---

## 快速开始

### 后端

```bash
cd backend
py -3 -m pip install -r requirements.txt
py -3 main.py            # 默认监听 127.0.0.1:11446
```

后台管理页在 `/admin`，首次启动会引导初始化管理员账号。

### Web 前端

```bash
cd app
npm install
npm run dev              # 开发服务器
npm run build            # 产出静态文件
```

### Android 客户端

需要 Android SDK 与 **JDK 17**。先在 `android-native/` 下建 `local.properties` 指向你的 SDK：

```properties
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
```

然后：

```bash
cd android-native
./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # 跑单元测试
```

> ⚠️ 如果仓库路径包含中文，AGP 会拒绝构建 —— 把源码复制到纯 ASCII 路径下再构建。

### 心潮 + Ombre Brain（可选）

见 `xinchao/README-部署.md` 与 `ombre-brain/README.md`。

---

## 需要你自己配的部分

本仓库是**脱敏后的分发版本**，有两处留了占位符：

### 1. 服务器地址

Android 端的默认后端地址在 `android-native/app/build.gradle.kts` 的 `SERVER_BASE_URL`，
当前为 `https://your-server.example.com`。改成你自己的地址即可；
运行时也能在 App 内的「服务器设置」里覆盖，不必重新编译。

### 2. 模型服务凭据

后端的「免费分组」原本内置了一个公共模型中转地址与密钥，分发版已移除，改为环境变量：

```
FREE_GROUP_BASE_URL=https://your-llm-provider.example.com/v1
FREE_GROUP_API_KEY=sk-...
```

不配这两项，免费分组就是"未配置"状态，其余功能不受影响 ——
用户仍可在 App 内填自己的 API Key 使用。

---

## 数据与隐私

- 会话、消息、记忆都存在**你自己的**机器上（SQLite / Room）
- 后端用 SQLite，库文件与日志在 `backend/data/`、`backend/logs/`，
  已被 `.gitignore` 排除 —— **不要**把它们提交进仓库，里面有用户会话与记忆内容
- 云端记忆模式会把记忆写入你自己部署的 Ombre Brain；本地模式则只留在设备上

---

## 致谢与许可

本项目以 **Apache License 2.0** 分发，完整条款见 LICENSE。

**本项目由天枢 harness（Tianshu-harness）完成开发，仅供学习使用。**

在设计与实现中参考了以下开源项目——感谢每一位作者：

| 项目 | 仓库 | 许可证 | 借鉴内容 |
|---|---|---|---|
| **Tianshu Harness** | https://github.com/huiliyi37/Tianshu-Tui | Apache-2.0 | 上下文压缩触发线公式、provider 三态前缀缓存策略、模型预算分档 |
| **DeepSeek Harness** | https://github.com/deepseek-ai/deepseek-harness | MIT | compaction 区域划分与 token 计量口径 |
| **Operit** | https://github.com/AAswordman/Operit | LGPL-3.0 | 记忆库界面组织与抽取流程设计 |
| **Ombre Brain** | https://github.com/P0luz/Ombre-Brain | MIT | 记忆库（本仓库 `ombre-brain/` 即基于它修改） |
| **MemMe** | https://github.com/vibeinging/MemMe | Apache-2.0 | 记忆系统架构评估参考 |
| **androidx-splashscreen-compose** | https://github.com/kibotu/androidx-splashscreen-compose | Apache-2.0 | 启动屏与开屏动画交互 |
| **xinchao-runtime-bridge** | https://github.com/tianyupaipai-cmd/xinchao-runtime-bridge | MIT | 心潮运行时桥接 |
| **心潮 · 念** | https://github.com/tianyupaipai-cmd/xinchao-nian | MIT | 心潮引擎（本仓库 `xinchao/` 基于它修改） |

各项目的版权声明、借鉴范围与本仓库的改动说明见 **NOTICE.md**。

> 其中 `xinchao/` 与 `ombre-brain/` 是**包含源代码**的修改版，
> 各自的上游 `LICENSE` / `NOTICE` / `AUTHORS` 原样保留在对应目录内。
