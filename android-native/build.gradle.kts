// Yuki初雪 · 根构建脚本
//
// 版本组合（与同机另一个项目对齐 Gradle 9.1.0）：
//   Gradle 9.1.0 + AGP 8.11.0 —— AGP 8.7 只支持到 Gradle 8.9，升 Gradle 必须同步升 AGP。
//   Kotlin 保持 2.0.21（AGP 8.11 兼容 Kotlin 2.0+，减少同时变更的变量）。
//   Kotlin 2.0 起 Compose 编译器为独立插件 org.jetbrains.kotlin.plugin.compose。
plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // KSP —— Room 的注解处理器。
    // 版本必须与 Kotlin **精确配对**（2.0.21 → 2.0.21-1.0.28）；
    // 这一条是从 Google Maven 元数据查出来的，不是凭记忆写的。
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
