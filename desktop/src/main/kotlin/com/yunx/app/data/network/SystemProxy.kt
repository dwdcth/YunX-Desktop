package com.yunx.app.data.network

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.yunx.app.util.HostOs
import com.yunx.app.util.Log

/**
 * Windows 系统代理解析：读取系统「Internet 选项」里配置的代理。
 *
 * 注册表位置：HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Internet Settings
 * - ProxyEnable(DWORD)：1=启用，0=未启用（缺失视为未启用）
 * - ProxyServer(字符串)：`host:port` 或分协议形式 `http=1.2.3.4:8080;https=1.2.3.4:8080;socks=...`
 * - ProxyOverride(字符串)：绕过代理的地址列表（本实现只读取、不应用）
 * - AutoConfigURL(字符串)：PAC 自动配置脚本地址，本实现不支持
 *
 * 说明：
 * - 非 Windows 平台一律视为不可用，调用方按直连处理。
 * - 绕过列表（ProxyOverride）与 PAC 均不生效；PAC 会明确写日志，不静默降级。
 * - 本解析只在应用启动 / 用户修改代理设置时执行一次；运行中系统代理发生变化不会自动跟随。
 */
object SystemProxy {

    private const val TAG = "YunX-Proxy"
    private const val REG_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

    private const val DEFAULT_HTTP_PORT = 80
    private const val DEFAULT_HTTPS_PORT = 443
    private const val DEFAULT_SOCKS_PORT = 1080

    /** 解析结果（供设置页展示「系统代理实际生效值」） */
    sealed interface Inspect {
        /** 解析出可用代理 */
        data class Proxy(val host: String, val port: Int) : Inspect

        /** 未启用系统代理 / 未配置代理服务器 → 直连 */
        data object Direct : Inspect

        /** 检测到 PAC 自动配置脚本，本实现不支持 → 直连 */
        data object PacUnsupported : Inspect

        /** 非 Windows 平台或读取注册表失败 → 直连 */
        data object Unavailable : Inspect
    }

    private val isWindows: Boolean
        get() = System.getProperty("os.name", "").lowercase().contains("win")

    /**
     * 读取本机系统代理并解析。返回 [Inspect.Proxy] 表示可用，其余一律按直连处理。
     * 调用方据此装配全局代理；Windows 读一次注册表，Linux 读一次环境变量。
     */
    fun inspect(): Inspect {
        if (!isWindows) {
            if (HostOs.isLinux) return inspectLinuxEnv()
            Log.i(TAG, "非 Windows/Linux 平台，不支持系统代理，按直连处理")
            return Inspect.Unavailable
        }
        val enable = readInt("ProxyEnable") ?: 0
        val server = readString("ProxyServer")
        val pac = readString("AutoConfigURL")
        val override = readString("ProxyOverride")
        return parse(enable, server, pac, override)
    }

    /**
     * Linux：读 shell/桌面会话注入的代理环境变量（GNOME「网络代理」设为手动时也会注入）。
     * 优先 https_proxy（客户端流量以 https 为主），其次 all_proxy / http_proxy。
     * socks:// 前缀由 [parseHostPort] 的 scheme 剥离逻辑处理。
     */
    private fun inspectLinuxEnv(): Inspect {
        val env = System.getenv()
        val raw = listOf("https_proxy", "HTTPS_PROXY", "all_proxy", "ALL_PROXY", "http_proxy", "HTTP_PROXY")
            .firstNotNullOfOrNull { name -> env[name]?.takeIf { it.isNotBlank() } }
        if (raw == null) {
            Log.i(TAG, "Linux 未配置代理环境变量，按直连处理")
            return Inspect.Direct
        }
        // OkHttp 装配的是 HTTP 代理；socks:// 值语义不符，跳过（记日志，不静默）
        if (raw.substringBefore("://").equals("socks", ignoreCase = true) ||
            raw.substringBefore("://").equals("socks5", ignoreCase = true)
        ) {
            Log.w(TAG, "代理环境变量是 SOCKS 代理（$raw），本应用仅支持 HTTP 代理，按直连处理")
            return Inspect.Direct
        }
        val parsed = parseServer(raw)
        if (parsed == null) {
            Log.w(TAG, "代理环境变量无法解析（$raw），按直连处理")
            return Inspect.Direct
        }
        Log.i(TAG, "Linux 系统代理（环境变量）：${parsed.host}:${parsed.port}")
        return parsed
    }

    /**
     * 纯解析逻辑（不读注册表，便于用例验证）：
     * - 设置了 [autoConfigUrl]（PAC）→ [Inspect.PacUnsupported]（写日志，不静默）；
     * - [proxyEnable] 为 0（或缺失）→ [Inspect.Direct]；
     * - [proxyServer] 解析成功 → [Inspect.Proxy]，否则 [Inspect.Direct]。
     */
    fun parse(
        proxyEnable: Int,
        proxyServer: String?,
        autoConfigUrl: String?,
        proxyOverride: String? = null
    ): Inspect {
        // PAC 优先判断：即便 ProxyEnable=1，PAC 场景下也无法用固定 host/port 代理，明确按直连并记录
        if (!autoConfigUrl.isNullOrBlank()) {
            Log.w(
                TAG,
                "检测到 PAC 自动配置脚本（AutoConfigURL=$autoConfigUrl），本应用暂不支持 PAC，" +
                    "已按直连处理"
            )
            return Inspect.PacUnsupported
        }
        if (proxyEnable == 0) {
            Log.i(TAG, "系统代理未启用（ProxyEnable=0），按直连处理")
            return Inspect.Direct
        }
        val parsed = parseServer(proxyServer)
        if (parsed == null) {
            Log.w(
                TAG,
                "系统代理已启用但 ProxyServer 无法解析（ProxyServer=${proxyServer ?: "空"}），按直连处理"
            )
            return Inspect.Direct
        }
        Log.i(
            TAG,
            "系统代理已启用：${parsed.host}:${parsed.port}（ProxyServer=$proxyServer，" +
                "ProxyOverride=${proxyOverride ?: "无"}；绕过列表本实现不应用）"
        )
        return parsed
    }

    /**
     * 解析 ProxyServer 字符串：
     * - 单一值 `host:port`（或仅 `host`）直接使用，缺端口默认 80；
     * - 分协议形式按 `;` 切分，优先取 `http`、其次 `https`，最后回退 `socks`；
     *   缺端口按协议给默认（http 80 / https 443 / socks 1080）。
     * 无法解析返回 null。
     */
    fun parseServer(proxyServer: String?): Inspect.Proxy? {
        val raw = proxyServer?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // 单一值：host:port
        if (!raw.contains('=')) return parseHostPort(raw, DEFAULT_HTTP_PORT)
        // 分协议形式：http=...;https=...;socks=...
        val parts = raw.split(';').mapNotNull { seg ->
            val i = seg.indexOf('=')
            if (i <= 0) null else seg.substring(0, i).trim().lowercase() to seg.substring(i + 1).trim()
        }
        for (proto in listOf("http", "https")) {
            val value = parts.firstOrNull { it.first == proto }?.second ?: continue
            val defaultPort = if (proto == "https") DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
            parseHostPort(value, defaultPort)?.let { return it }
        }
        val socks = parts.firstOrNull { it.first.startsWith("socks") }?.second
        if (socks != null) parseHostPort(socks, DEFAULT_SOCKS_PORT)?.let { return it }
        return null
    }

    /** 解析 `host[:port]`（兼容 `scheme://host:port/path` 与 `[IPv6]:port`）；主机为空返回 null */
    private fun parseHostPort(value: String, defaultPort: Int): Inspect.Proxy? {
        var v = value.trim()
        // 去掉可能的协议前缀与路径
        val schemeIdx = v.indexOf("://")
        if (schemeIdx > 0) v = v.substring(schemeIdx + 3)
        v = v.substringBefore('/').trim()
        if (v.isEmpty()) return null
        if (v.startsWith("[")) {
            // IPv6 字面量：[::1]:8080
            val end = v.indexOf(']')
            if (end <= 1) return null
            val host = v.substring(1, end)
            val rest = v.substring(end + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: defaultPort else defaultPort
            return Inspect.Proxy(host, port.coerceIn(1, 65535))
        }
        val lastColon = v.lastIndexOf(':')
        return if (lastColon > 0 && v.indexOf(':') == lastColon) {
            val host = v.substring(0, lastColon).trim()
            val port = v.substring(lastColon + 1).trim().toIntOrNull() ?: defaultPort
            if (host.isEmpty()) null else Inspect.Proxy(host, port.coerceIn(1, 65535))
        } else {
            // 无端口（或裸 IPv6）：用协议默认端口
            Inspect.Proxy(v, defaultPort)
        }
    }

    private fun readInt(name: String): Int? =
        runCatching { Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, REG_KEY, name) }.getOrNull()

    private fun readString(name: String): String? =
        runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, REG_KEY, name) }.getOrNull()
}
