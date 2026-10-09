plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "ai.yuki.chuxue"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.yuki.chuxue"
        minSdk = 26
        targetSdk = 35
        versionCode = 143
        versionName = "0.61.57"

        // ── 内置的后端地址（用户不需要填，也不该看到）──
        //
        // ⚠️ 改这个值要同时确认三件事，缺一个 App 就会在启动时报"连不上服务器"：
        //     ① Nginx 里有对应的 server 块（当前是 listen 11445 ssl）
        //     ② 云服务器安全组 + 本机防火墙已放行该端口
        //     ③ 后端确实在跑（backend\start_backend.bat）
        buildConfigField("String", "SERVER_BASE_URL", "\"https://your-server.example.com\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 「关于」页从 BuildConfig 读版本号，避免手写字符串与构建配置漂移
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")

    // 导航与返回栈（开发文档 §46 的路由设计）—— 返回栈交给 NavHost 统一管理，
    // 避免「每个页面都成了根页面、返回键直接退出 App」
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // 生命周期 / ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // ── 启动屏（官方 API，不是第三方库）──
    //
    // 它解决的是一个我们的自绘开屏**做不到**的问题：**冷启动白屏**。
    // 进程还没起来时，系统先显示一个由主题定义的启动窗口；没有它，用户会先看到
    // 一片主题底色（甚至白屏）再看到我们的 Compose 开屏 —— 中间有一次闪烁。
    //
    // 做法参考了 GitHub 上的 `kibotu/androidx-splashscreen-compose`（101⭐）：
    // 它的核心主张是「Works with AndroidX SplashScreen, not against it」——
    // 不另造一套，而是把这层系统窗口**当成开屏动画的第一帧**。
    // 三个关键细节（都照它做）：
    //   ① 背景色必须与 `windowSplashScreenBackground` 完全一致 → 看不出切换
    //   ② 图标位置/尺寸对齐 → 视觉上"图标一直在，文字浮现出来"
    //   ③ 用 `setKeepOnScreenCondition` 让它保持到数据就绪 → 不会露出空列表
    implementation("androidx.core:core-splashscreen:1.0.1")

    // 本地持久化（设置与人设）
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 会话与消息的持久化（开发文档 §13：Room）。
    // 分工：Room 管 sessions / messages 两张表；DataStore 继续管设置与人设。
    // 这样迁移风险可控 —— 设置与人设那两条链路完全不碰。
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // 网络（直连 DeepSeek，无代理层）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 协程（OkHttp 的 suspend 调用、ViewModel 状态流）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // JSON —— 仅用于【解析】响应；请求体的序列化由自研 StableJson 负责。
    // ⚠️ 版本被下方的 resolutionStrategy.force 钉在 1.7.3 —— 原因见那里，改前先读。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 调试工具
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 单元测试（缓存字节一致性的核心断言在此）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

/**
 * ⚠️ 2026-09-28：把 kotlinx-serialization 全局钉在 **1.7.3**。
 *
 * ## 为什么
 * Room 2.8.5 的 room-migration 模块是用 kotlinx-serialization **1.7.x** 编译的
 * （它内部有个带 @Serializable 的 FieldBundle），而 room-compiler-processing
 * 依赖 **1.8.1**。Gradle 按「取最高版本」解析 → KSP 的类加载器里于是出现
 * **接口来自 1.8.1（多了 typeParametersSerializers）、实现来自 1.7.x（没这个方法）**：
 *
 * AbstractMethodError: Receiver class androidx.room.migration.bundle.FieldBundle
 *   的序列化器没有实现 GeneratedSerializer 里的 typeParametersSerializers()
 *
 * 结果是 `kspDebugKotlin` 直接崩 —— 一行自己的代码都编不到。
 *
 * ## 为什么它能潜伏很久（这才是真正值得记的）
 * 这个雷**只在 KSP 真正处理 Room 时才炸**，而增量编译会跳过那一刻。
 * 所以前几轮构建全是绿的，直到这次改了 @Dao 的 SQL（MemoryDao）才突然红。
 * 也就是说：**之前那些「构建成功」都站在增量编译的沙子上。**
 *
 * ## 为什么钉回 1.7.3，而不是升到 1.8.1
 * 升不了 —— room-migration 的字节码已按 1.7.x 编好，改不动。
 * 只能让**接口**回到它编译时的那一版。
 * 本项目自己**没有任何 @Serializable 类**、也没应用 serialization 编译器插件，
 * 只用 runtime 的 Json 解析，所以钉 1.7.3 对我们自己的代码零影响。
 */
configurations.configureEach {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3",
            "org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3",
        )
    }
}

ksp {
    // 把 Room 生成的 schema 导出到版本库：手写的 Migration SQL 要照着它逐字段核对，
    // 否则 Room 启动时 schema 校验失败会直接抛 IllegalStateException。
    arg("room.schemaLocation", "$projectDir/schemas")
}
