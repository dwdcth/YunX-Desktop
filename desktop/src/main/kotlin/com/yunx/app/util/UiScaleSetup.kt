package com.yunx.app.util

import java.io.File
import kotlin.math.roundToInt

/**
 * Linux（X11/XWayland）HiDPI 补偿。
 *
 * 问题：KDE/GNOME Wayland 的分数缩放（如 1.6）对 XWayland 里的 JVM 不可见——
 * 实测 JDK 17 在 XWayland 下 transform=1.0、screenRes=96（KDE 注入的 Xft.dpi 也不被
 * AWT 采用），Compose/Skiko 按 96 DPI 渲染，高分屏上字特别小。而 JDK 的 X11 后端
 * 只支持**整数**缩放：`-Dsun.java2d.uiScale=2` 生效、`uiScale=1.6` 被忽略。
 *
 * 做法：在**任何 AWT 类初始化之前**（main 的第一行）调用 [applyEarly]：
 * - 环境变量 `YUNXPC_UI_SCALE` 显式指定（支持小数，内部四舍五入取整；off/1 = 不介入）；
 * - 否则读 KDE `~/.config/kdeglobals` 的 `[KScreen] ScaleFactor`（无命令依赖，直接解析 ini）；
 * - 四舍五入到整数，>1 才设置 `sun.java2d.uiScale`。
 *
 * ★ 分数缩放（1.6→2）意味着界面比 KDE 原生应用大 ~25%，但渲染清晰（无插值模糊）；
 *   嫌大可 `YUNXPC_UI_SCALE=1` 关闭。Windows 原生 DPI 一直正常、macOS 由 AWT 处理，均不介入。
 */
object UiScaleSetup {

    private const val TAG = "YunX-UiScale"

    /** 必须在第一个 AWT 类加载 / Toolkit 初始化之前调用，否则属性不生效 */
    fun applyEarly() {
        if (!HostOs.isLinux) return
        runCatching {
            val scale = resolveScale() ?: return
            val rounded = scale.roundToInt().coerceIn(1, 4)
            if (rounded > 1) {
                System.setProperty("sun.java2d.uiScale", rounded.toString())
                // 诊断：验证 AWT 是否接受该值（本处也是进程内首次触发 Toolkit 初始化）
                val awtScale = runCatching {
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
                }.getOrDefault(-1.0)
                Log.i(TAG, "Linux HiDPI 补偿：桌面缩放 $scale → uiScale=$rounded，AWT transform=$awtScale" +
                    "（YUNXPC_UI_SCALE=1 可关闭）")
            }
        }.onFailure { Log.w(TAG, "uiScale 设置失败: ${it.message}") }
    }

    /** 用户显式指定优先；否则读 KDE 桌面缩放；都拿不到返回 null（不介入） */
    private fun resolveScale(): Double? {
        System.getenv("YUNXPC_UI_SCALE")?.takeIf { it.isNotBlank() }?.let { raw ->
            val v = raw.trim().lowercase()
            if (v in setOf("off", "no", "false", "0", "1")) return null
            raw.trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        return kdeScaleFactor() ?: kdeLegacyScaleFactor()
    }

    /**
     * KDE Plasma 6 桌面缩放：`~/.config/kwinoutputconfig.json`。
     * 实测结构：顶层是命名节点数组 `[{"name":"outputs","data":[{...,"scale":1.6},…]},
     * {"name":"setups",…}]`，显示器条目在 name=="outputs" 节点的 data 里；
     * 历史版本顶层直接是输出数组——两种都兼容。enabled 输出里取最大 scale
     * （多屏混合缩放按最大的补，宁可稍大不可字小）。
     */
    private fun kdeScaleFactor(): Double? = runCatching {
        val home = System.getProperty("user.home") ?: return@runCatching null
        val file = File(home, ".config/kwinoutputconfig.json")
        if (!file.isFile) return@runCatching null
        val text = file.readText()
        val arr = runCatching { org.json.JSONArray(text) }.getOrNull()
            ?: org.json.JSONObject(text).optJSONArray("data")
            ?: return@runCatching null

        var best: Double? = null
        fun offer(scale: Double) {
            val cur = best
            if (scale > 1.0 && (cur == null || scale > cur)) best = scale
        }
        for (i in 0 until arr.length()) {
            val node = arr.optJSONObject(i) ?: continue
            if (!node.optBoolean("enabled", true)) continue
            offer(node.optDouble("scale", 1.0)) // 老结构：顶层直接是输出条目
            // Plasma 6：name=="outputs" 节点的 data 才是显示器列表
            if (node.optString("name") == "outputs") {
                val outputs = node.optJSONArray("data") ?: continue
                for (j in 0 until outputs.length()) {
                    val output = outputs.optJSONObject(j) ?: continue
                    if (!output.optBoolean("enabled", true)) continue
                    offer(output.optDouble("scale", 1.0))
                }
            }
        }
        best
    }.getOrNull()

    /** Plasma 5 旧位置：~/.config/kdeglobals 的 [KScreen] ScaleFactor */
    private fun kdeLegacyScaleFactor(): Double? = runCatching {
        val home = System.getProperty("user.home") ?: return@runCatching null
        val file = File(home, ".config/kdeglobals")
        if (!file.isFile) return@runCatching null
        var inSection = false
        for (line in file.readLines()) {
            val t = line.trim()
            if (t.startsWith("[")) {
                inSection = t.equals("[KScreen]", ignoreCase = true)
                continue
            }
            if (inSection && t.startsWith("ScaleFactor=", ignoreCase = true)) {
                return@runCatching t.substringAfter('=').trim().toDoubleOrNull()
            }
        }
        null
    }.getOrNull()
}
