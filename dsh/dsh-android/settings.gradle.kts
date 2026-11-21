pluginManagement {
    repositories {
        // 国内镜像优先（失败自动回退官方源）
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/jitpack") }
        google()
        mavenCentral()
        // 真 PTY 终端（对齐 DSHA）：Termux 拆出的 terminal-view / terminal-emulator
        // （com.termux.termux-app，Apache 2.0）发布在 JitPack。
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "dsh-android"
include(":app")
