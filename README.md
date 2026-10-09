# Yuki 初雪

一个 AI 伴侣（人机恋）聊天应用。仓库含三端源码：

| 目录 | 内容 | 技术栈 |
|---|---|---|
| `android-native/` | Android 客户端 | Kotlin + Jetpack Compose（compileSdk 35 / minSdk 26 / Java 17） |
| `app/` | Web 前端 | Vite + React 18 + TypeScript + Capacitor 8 |
| `backend/` | 服务端与后台管理页 | FastAPI + uvicorn + SQLite |

## ⚠️ 关于这份源码

这是**脱敏后的分发版本**，与开发者本机运行的环境有两处差异，跑起来之前需要自己补：

1. **服务器地址是占位符**
   Android 端的默认后端地址在 `android-native/app/build.gradle.kts` 的 `SERVER_BASE_URL`，
   当前值为 `https://your-server.example.com`。改成你自己的后端地址即可。
   运行时也可以在 App 内的「服务器设置」里覆盖，不必重新编译。

2. **不含默认的模型服务凭据**
   后端的「免费分组」功能原本内置了一个公共模型中转地址与密钥，分发版已移除，
   改为从环境变量读取：

   ```
   FREE_GROUP_BASE_URL=https://your-llm-provider.example.com/v1
   FREE_GROUP_API_KEY=sk-...
   ```

   不配这两个变量，免费分组就是未配置状态，其余功能不受影响 ——
   用户仍可在 App 内填自己的 API Key 使用。

## 跑起来

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

需要 Android SDK 与 JDK 17。先在 `android-native/` 下建 `local.properties` 指向你的 SDK：

```properties
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
```

然后：

```bash
cd android-native
./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # 跑单元测试
```

## 数据

后端用 SQLite，库文件与日志都在 `backend/data/`、`backend/logs/`，
已被 `.gitignore` 排除。**不要**把这两个目录提交进仓库 —— 里面有用户会话与记忆内容。

## 致谢与许可

本项目以 **Apache License 2.0** 分发，完整条款见 LICENSE。

在设计与实现中参考了若干开源项目：

| 项目 | 许可证 |
|---|---|
| Tianshu Harness | Apache-2.0 |
| DeepSeek Harness | MIT |
| Operit | LGPL-3.0 |
| 心潮 · 念 | AGPL-3.0 |
| MemMe | Apache-2.0 |
| kibotu / androidx-splashscreen-compose | Apache-2.0 |

各项目的版权声明、借鉴范围与合规注意事项见 **NOTICE.md**。
