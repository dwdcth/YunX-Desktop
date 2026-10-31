pluginManagement {
    repositories {
        // 国内镜像优先（本机无法直连 dl.google.com）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    // 无本机 JDK 17 时由 Gradle toolchain 自动下载一份到 ~/.gradle/jdks（对齐 Windows 版
    // run.ps1 的 Ensure-Jdk 便携版下载，不写系统目录）。daemon JVM 版本见
    // gradle/gradle-daemon-jvm.properties —— 本机默认 JVM 过新（如 JDK 27）时也能自举。
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
        google()
    }
}

rootProject.name = "YunX-Desktop"
include(":desktop")
