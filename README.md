<div align="center">

# ❄️ Yuki 初雪

**人机恋 AI 伴侣 · 从客户端到服务端全部自托管**

它不是一个"套壳聊天框"——角色有**对话之外持续变化的内在状态**，
记忆按遗忘曲线衰减，在你不说话的时候也可能**主动来找你**。

![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)
![Platform](https://img.shields.io/badge/Platform-Android%2026%2B-3DDC84.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg)
![Compose](https://img.shields.io/badge/Compose-BOM%202024.10.01-4285F4.svg)
![Tests](https://img.shields.io/badge/Tests-1347%20passed-success.svg)
![Backend](https://img.shields.io/badge/Backend-FastAPI%20%2B%20SQLite-009688.svg)

### ⚠️ 本项目由 **天枢 harness**（Tianshu-harness）完成开发，**仅供学习使用**

</div>

---

## 📖 目录

- [这是什么](#-这是什么)
- [功能](#-功能)
- [架构](#-架构)
- [代码一览](#-代码一览)
- [技术栈](#-技术栈)
- [目录结构](#-目录结构)
- [快速开始](#-快速开始)
- [需要你自己配的部分](#-需要你自己配的部分)
- [数据与隐私](#-数据与隐私)
- [致谢与许可](#-致谢与许可)

---

## 🧭 这是什么

| 维度 | 说明 |
|---|---|
| **形态** | Android 客户端 + Web 前端 + 自托管服务端 + 可选的心智/记忆后端 |
| **模型接入** | **BYOK**（Bring Your Own Key）—— 用自己的 API Key，支持多家供应商分组 |
| **部署** | 纯自托管，服务端跑在你自己机器上，**数据不出你的服务器** |
| **定位** | 个人自用 / 学习研究 |

---

## ✨ 功能

<table>
<tr><td width="50%" valign="top">

**💬 聊天**
- 多个人设各自独立会话，**流式输出**打字机效果
- 「分段气泡」—— 长回复按句拆成多枚，像真人连发
- 思考过程可折叠、历史版本可回看切换
- 长按消息：复制 / 删除 / 重新生成
- 表情包、图片消息与预览

**🎭 角色与内在状态**
- **人设系统**：角色卡 + 用户自己的资料卡
- **心潮引擎**：角色在对话之外**一直在变**的内在状态 —— 驱力、情绪、念头池、梦、小屋
- **「Ta 主动来找你」**：角色可主动发消息；退出会话后消息列表显示未读

</td><td width="50%" valign="top">

**🧠 记忆**
- 两级记忆：**本地**（Room）与**云端**（Ombre Brain）按开关分轨
- 云端记忆基于**效价 / 唤醒度**坐标打标，带遗忘曲线与语义检索
- 记忆管理界面：查看 / 编辑 / 上传 / 搬移

**🎨 界面**
- **美化设置**：全局自定义背景、气泡不透明度、顶栏不透明度
- 背景遮罩用**真高斯模糊**，浓度即模糊程度
- 浅色"初雪"主题

**🖥 服务端**
- 账号体系、设备管理、后台管理页
- 版本发布与 App 内更新检查、更新日志下发
- 遥测上报（可关）

</td></tr>
</table>

---

## 🏗 架构

```
┌──────────────────┐        ┌──────────────────┐
│  Android 客户端   │        │   Web 前端        │
│  Kotlin / Compose │        │  Vite + React    │
└────────┬─────────┘        └────────┬─────────┘
         │   HTTPS / SSE              │
         └────────────┬───────────────┘
                      ▼
         ┌────────────────────────┐
         │   backend (FastAPI)    │  账号 / 会话归档 / 版本发布
         │   + SQLite             │  后台管理页 /admin
         └───────────┬────────────┘
                     │ HTTP（可选）
           ┌─────────┴──────────┐
           ▼                    ▼
   ┌───────────────┐   ┌──────────────────┐
   │  xinchao       │──▶│  ombre-brain      │
   │  心潮引擎       │   │  情绪记忆库 (MCP)  │
   │  内在状态/梦    │   │  打标/衰减/检索    │
   └───────────────┘   └──────────────────┘
```

> 后两个是**可选的**。不接它们，App 仍然完整可用（只是没有"云端记忆"与
> "Ta 主动来找你"这类依赖服务端状态的功能）。

---

## 📜 代码一览

### ① 上下文压缩的触发线

压缩何时触发，直接决定 token 成本。这里的公式对齐了 **deepseek-harness** 的
`resolveCompactSpec` —— 关键是**取两个下界的最小值**，而不是只看比例：

```kotlin
// android-native/.../data/ContextCompress.kt
fun triggerLineTokens(
    limitTokens: Int,
    threshold: Float,
    reservedOutputTokens: Int = SUMMARY_OUTPUT_TOKENS,
): Int {
    if (limitTokens <= 0) return 0
    val ratio = threshold.coerceIn(0.1f, 1f)
    val byRatio = (limitTokens * ratio).toInt()          // 窗口 × 比例
    val headroom = (limitTokens * COMPRESS_SAFETY_RATIO).toInt()
    val byCapacity = limitTokens - reservedOutputTokens - headroom  // 窗口 − 输出预留 − 余量
    return minOf(byRatio, byCapacity).coerceAtLeast(1)   // 取小；且至少为 1
}
```

> `coerceAtLeast(1)` 不是装饰：返回 0 会让"已用 ≥ 触发线"**恒为真**，于是每一轮都压缩。

### ② 分段气泡：拆句的边界条件

把长回复拆成多枚气泡是产品需求，但"拆"的地方藏着不少必须挡掉的情况：

```kotlin
// android-native/.../ui/BubbleSplit.kt
fun bubbles(content: String, splitEnabled: Boolean, sendMode: String): List<String> {
    if (!splitEnabled || sendMode != SEND_MODE_STREAM) return listOf(content)
    // 含代码块的回复不拆：拆句按句末标点切，而代码里的 `.` `;` 会被当成句子边界
    if (content.isBlank() || content.contains("```")) return listOf(content)
    val parts = split(content).filter { it.isNotBlank() }
    // 极端情况（整段只有标点）：宁可照原样画一枚，也不要渲染出"零枚气泡"
    return parts.ifEmpty { listOf(content) }
}
```

### ③ 缓存命中率的核算

前缀缓存是这个项目**最贵的一课**：已写入的内容不改写、新内容只追加，
才能让缓存前缀不断裂。下面这段是核算工具：

```kotlin
// android-native/.../data/CacheMath.kt
object CacheMath {
    /** 输入侧理论命中上限（块对齐按 64 token 计） */
    fun expectHitUpperBound(prevInputTokens: Int): Int { /* … */ }

    fun blockCount(tokens: Int): Int = if (tokens <= 0) 0 else tokens / BLOCK

    fun isBlockAligned(tokens: Int): Boolean = tokens > 0 && tokens % BLOCK == 0

    fun hitRatio(hitTokens: Int, missTokens: Int): Double { /* … */ }
}
```

---

## 🛠 技术栈

### Android 客户端 `android-native/`

| 项 | 版本 / 说明 |
|---|---|
| 语言 | Kotlin **2.0.21** |
| UI | Jetpack Compose（**BOM 2024.10.01**）+ Material 3，单 Activity |
| 路由 | `navigation-compose` 2.8.4 |
| 构建 | Gradle **9.1.0** + AGP 8.11.0 + KSP 2.0.21-1.0.28 + **JDK 17** |
| 存储 | **Room 2.7.2**（会话 / 消息 / 记忆）+ **DataStore** 1.1.1（设置 / 人设） |
| 网络 | **OkHttp 4.12.0**，SSE 自行解析 |
| 序列化 | kotlinx-serialization 1.7.3 |
| 协程 | kotlinx-coroutines 1.9.0 |
| 测试 | JUnit 4.13.2 —— **1347 个 JVM 单测 / 140 个测试类** |
| SDK | compileSdk 35 · minSdk 26 · targetSdk 35 |
| 版本 | `versionCode 143` / `versionName 0.61.57` |

> ⚠️ 本项目**没有**用 Hilt / Paging 3 / Coil / Glide / WorkManager —— 依赖一律手写。

### Web 前端 `app/`

Vite + React 18 + TypeScript + Capacitor 8（可打包成原生壳）。

### 服务端 `backend/`

| 项 | 说明 |
|---|---|
| 框架 | **FastAPI** + uvicorn |
| 存储 | SQLite |
| 依赖 | 只有 2 个 —— 密码哈希用 `hashlib`、邮件用 `smtplib`、令牌用 `secrets`，全走标准库 |
| 鉴权 | 自签令牌（Bearer） |

### 心潮引擎 `xinchao/`

Node.js 20+，无外部依赖，纯 `node:http` 自写服务。版本 4.0.0。

### Ombre Brain `ombre-brain/`

Python 3，MCP 协议接入，向量检索 + 遗忘曲线。版本 3.6.14。

---

## 📂 目录结构

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
├── 人机恋app开发文档.md      产品与架构设计总纲（设计意图，非当前实现）
├── NOTICE.md                第三方许可与改动署名
└── LICENSE                  Apache-2.0
```

---

## 🚀 快速开始

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

需要 Android SDK 与 **JDK 17**。先在 `android-native/` 下建 `local.properties`：

```properties
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
```

```bash
cd android-native
./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # 跑单元测试
```

> ⚠️ 如果仓库路径包含中文，AGP 会拒绝构建 —— 把源码复制到纯 ASCII 路径下再构建。

### 心潮 + Ombre Brain（可选）

见 `xinchao/README-部署.md` 与 `ombre-brain/README.md`。

---

## ⚙️ 需要你自己配的部分

本仓库是**脱敏后的分发版本**，有两处留了占位符：

**1. 服务器地址** —— Android 端默认后端地址在
`android-native/app/build.gradle.kts` 的 `SERVER_BASE_URL`，当前为
`https://your-server.example.com`。改成你自己的即可；运行时也能在
App 内的「服务器设置」里覆盖，不必重新编译。

**2. 模型服务凭据** —— 后端的「免费分组」原本内置了公共中转地址与密钥，
分发版已移除，改为环境变量：

```bash
FREE_GROUP_BASE_URL=https://your-llm-provider.example.com/v1
FREE_GROUP_API_KEY=sk-...
```

不配这两项，免费分组就是"未配置"状态，其余功能不受影响 ——
用户仍可在 App 内填自己的 API Key 使用。

---

## 🔒 数据与隐私

- 会话、消息、记忆都存在**你自己的**机器上（SQLite / Room）
- 库文件与日志在 `backend/data/`、`backend/logs/`，已被 `.gitignore` 排除 ——
  **不要**把它们提交进仓库
- 云端记忆模式会把记忆写入你自己部署的 Ombre Brain；本地模式则只留在设备上

---

## 🙏 致谢与许可

本项目以 **Apache License 2.0** 分发，完整条款见 LICENSE。

**本项目由天枢 harness（Tianshu-harness）完成开发，仅供学习使用。**

感谢以下开源项目（地址均已核验可达，许可证与标注一致）：

| 项目 | 仓库 | 许可证 | 借鉴内容 |
|---|---|---|---|
| **Tianshu Harness** | https://github.com/huiliyi37/Tianshu-Tui | Apache-2.0 | 上下文压缩触发线公式、prefixCacheStrategy 三态、模型预算分档 |
| **DeepSeek Harness** | https://github.com/deepseek-ai/deepseek-harness | MIT | compaction 区域划分与 token 计量口径 |
| **Operit** | https://github.com/AAswordman/Operit | LGPL-3.0 | 记忆库界面组织与抽取流程设计 |
| **Ombre Brain** | https://github.com/P0luz/Ombre-Brain | MIT | 记忆库（本仓库 `ombre-brain/` 基于它修改） |
| **MemMe** | https://github.com/vibeinging/MemMe | Apache-2.0 | 记忆系统架构评估参考 |
| **androidx-splashscreen-compose** | https://github.com/kibotu/androidx-splashscreen-compose | Apache-2.0 | 启动屏与开屏动画交互 |
| **心潮 · 念** | https://github.com/tianyupaipai-cmd/xinchao-nian | MIT | 心潮引擎（本仓库 `xinchao/` 基于其 `xinchao/` 子目录修改） |
| **xinchao-runtime-bridge** | https://github.com/tianyupaipai-cmd/xinchao-runtime-bridge | MIT | 心潮运行时桥接 |

各项目的版权声明、借鉴范围与本仓库的改动说明见 **NOTICE.md**。

> `xinchao/` 与 `ombre-brain/` 是**包含源代码**的修改版，
> 各自的上游 `LICENSE` / `NOTICE` / `AUTHORS` 已原样保留在对应目录内。

<div align="center">

---

**❄️ Yuki 初雪** · 由 [天枢 harness](https://github.com/huiliyi37/Tianshu-Tui) 开发 · 仅供学习使用

</div>
