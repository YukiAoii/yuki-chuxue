// Yuki初雪 · 原生 Android 工程
//
// 仓库镜像说明：本机位于国内网络，Google 官方 Maven 源速度极慢（实测 Gradle 发行版
// 30 分钟仅 8MB）。因此 pluginManagement 与 dependencyResolutionManagement 都把
// 阿里云镜像放在首位，官方源作为兜底。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "YukiChuxue"
include(":app")
