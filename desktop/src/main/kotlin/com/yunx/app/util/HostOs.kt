package com.yunx.app.util

enum class HostKind { WINDOWS, MAC, LINUX }

/**
 * 桌面端唯一的操作系统判断。测试传入伪造的 os.name / os.arch，
 * 不读当前进程的真实属性。
 */
object HostOs {
    fun kindOf(osName: String): HostKind {
        val name = osName.lowercase()
        return when {
            name.contains("win") -> HostKind.WINDOWS
            name.contains("mac") || name.contains("darwin") -> HostKind.MAC
            else -> HostKind.LINUX
        }
    }

    val kind: HostKind = kindOf(System.getProperty("os.name", ""))
    val isWindows: Boolean get() = kind == HostKind.WINDOWS
    val isMac: Boolean get() = kind == HostKind.MAC
    val isLinux: Boolean get() = kind == HostKind.LINUX

    /** 与 desktop/build.gradle.kts 里的 javafxClassifier 使用同一组字符串。 */
    fun javafxClassifier(osName: String, osArch: String): String = when (kindOf(osName)) {
        HostKind.WINDOWS -> "win"
        HostKind.MAC -> if (osArch == "aarch64") "mac-aarch64" else "mac"
        HostKind.LINUX -> "linux"
    }

    /** Gopeed 官方 web 包名中的操作系统段：windows / macos / linux。 */
    fun gopeedOsToken(osName: String): String = when (kindOf(osName)) {
        HostKind.WINDOWS -> "windows"
        HostKind.MAC -> "macos"
        HostKind.LINUX -> "linux"
    }

    /** Gopeed 官方 web 包名中的架构段：arm64 / amd64。 */
    fun gopeedArchToken(osArch: String): String = when (osArch) {
        "aarch64", "arm64" -> "arm64"
        else -> "amd64"
    }
}
