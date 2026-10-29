pluginManagement {
    repositories {
        // 构建环境加速（Aliyun 镜像优先）：直连 Maven Central 在受限网络下会 429/超时。
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
        // 真 PTY 终端（对齐 DSHA）：Termux 拆出的 terminal-view / terminal-emulator
        // （com.termux.termux-app，Apache 2.0）发布在 JitPack。
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "dsh-android"
include(":app")
