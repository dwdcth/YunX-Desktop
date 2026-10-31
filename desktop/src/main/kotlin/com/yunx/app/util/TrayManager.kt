package com.yunx.app.util

import java.awt.Image
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 系统托盘管理器。
 *
 * 右键菜单用 JNA 调 Win32 TrackPopupMenu（CreatePopupMenu + AppendMenuW），
 * 中文正常、暗色跟随系统。不用 Swing、不自绘窗口。
 */
object TrayManager {

    private const val TAG = "YunX-Tray"

    private var trayIcon: TrayIcon? = null

    /** 是否已安装托盘图标 */
    val isInstalled: Boolean
        get() = trayIcon != null

    /**
     * 安装托盘图标。
     * @param image 图标
     * @param onLeftClick 左键单击回调
     * @param onRightClick 右键回调（屏幕坐标 x, y）
     */
    fun install(
        image: BufferedImage,
        onLeftClick: () -> Unit,
        onRightClick: (Int, Int) -> Unit
    ) {
        if (!SystemTray.isSupported()) {
            Log.w(TAG, "SystemTray 不可用，托盘功能关闭")
            return
        }
        if (trayIcon != null) uninstall()

        val icon = TrayIcon(image.getScaledInstance(16, 16, Image.SCALE_SMOOTH), "云析 YunX-Desktop").apply {
            isImageAutoSize = true
            if (HostOs.isWindows) {
                // Windows 必须设（哪怕空的）PopupMenu 才能收到右键事件；
                // Linux(XEmbed) 上设了反而吞掉右键，macOS 无此机制 —— 都不设
                popupMenu = PopupMenu()
            }
            addMouseListener(object : MouseAdapter() {
                // 统一用 mousePressed 分发：Linux(XEmbed/xembedsniproxy) 上 mouseClicked
                // 从不触发（JDK 已知行为），Windows 上按下即响应也更接近原生托盘手感
                override fun mousePressed(e: MouseEvent) {
                    when (e.button) {
                        MouseEvent.BUTTON1 -> onLeftClick()
                        MouseEvent.BUTTON3 -> {
                            e.consume()
                            onRightClick(e.xOnScreen, e.yOnScreen)
                        }
                    }
                }
            })
        }
        runCatching {
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            Log.i(TAG, "托盘图标已安装")
        }.onFailure { Log.w(TAG, "托盘图标安装失败: ${it.message}") }
    }

    /** 移除托盘图标 */
    fun uninstall() {
        trayIcon?.let { icon ->
            runCatching { SystemTray.getSystemTray().remove(icon) }
            trayIcon = null
        }
    }

    /**
     * 显示系统通知。
     *
     * 走 AWT 托盘图标的原生气泡（Windows 10/11 会归入通知中心）。
     * 注意：托盘图标必须处于可见状态，否则 Windows 不会显示。
     * 这里不能用 Compose 的 TrayState —— 托盘图标已改为手写 AWT TrayIcon，没有 TrayState 实例。
     */
    fun showNotification(title: String, message: String) {
        val icon = trayIcon ?: return
        runCatching { icon.displayMessage(title, message, TrayIcon.MessageType.NONE) }
            .onFailure { Log.w("YunX-Tray", "showNotification failed: ${it.message}") }
    }

    /** 从 classpath 加载 icon.png */
    fun loadTrayIcon(): BufferedImage? = runCatching {
        val stream = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("icon.png") ?: return@runCatching null
        ImageIO.read(stream)
    }.getOrNull()
}
