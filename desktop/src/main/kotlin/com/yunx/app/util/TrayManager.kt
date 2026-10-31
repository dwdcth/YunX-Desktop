package com.yunx.app.util

import java.awt.Graphics2D
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * 系统托盘管理器。
 *
 * 左键单击激活主窗口；右键弹菜单（显示主窗口 / 退出）。实现按平台选择：
 * - Windows：AWT TrayIcon + Win32 TrackPopupMenu（原生、中文、暗色跟随系统，见 DarkMode）；
 * - Linux：优先 dorkbox SystemTray（原生 StatusNotifierItem/DBus）。JDK 的 AWT TrayIcon
 *   走已淘汰的 XEmbed 协议，新桌面（KDE 6/GNOME）不转发其鼠标事件——图标能显示但
 *   点击永远无反应（JDK-8341144，上游无修复计划），故必须换协议；dorkbox 不可用时
 *   回退 AWT（至少图标可见）；
 * - macOS：AWT（ActionEvent / AWT PopupMenu 均可靠）。
 */
object TrayManager {

    private const val TAG = "YunX-Tray"

    /** SNI 托盘句柄（dorkbox），null = 未启用 SNI 路径 */
    @Volatile
    private var sniTray: Any? = null

    private var trayIcon: TrayIcon? = null

    /** 是否已安装托盘图标 */
    val isInstalled: Boolean
        get() = sniTray != null || trayIcon != null

    /**
     * 安装托盘图标。
     *
     * @param image 图标
     * @param menuItems 托盘菜单项（label to id，"-" 为分隔符）
     * @param onLeftClick 左键单击回调
     * @param onRightClick 右键回调（屏幕坐标 x, y；Windows 用于弹 Win32 原生菜单）
     * @param onMenuCommand 菜单项选中回调（Linux SNI 菜单 / macOS AWT 菜单；Windows 菜单走同步返回值不经此）
     */
    fun install(
        image: BufferedImage,
        menuItems: List<Pair<String, Int>>,
        onLeftClick: () -> Unit,
        onRightClick: (Int, Int) -> Unit,
        onMenuCommand: (Int) -> Unit
    ) {
        if (HostOs.isLinux && installSni(image, menuItems, onLeftClick, onMenuCommand)) return

        if (!SystemTray.isSupported()) {
            Log.w(TAG, "SystemTray 不可用，托盘功能关闭")
            return
        }
        if (trayIcon != null) uninstall()

        val icon = TrayIcon(image.getScaledInstance(16, 16, Image.SCALE_SMOOTH), "云析 YunX-Desktop").apply {
            isImageAutoSize = true
            when {
                HostOs.isWindows -> {
                    // Windows 必须设（哪怕空的）PopupMenu 才能收到右键事件；菜单由 Win32 弹（暗色/原生）
                    popupMenu = PopupMenu()
                }
                else -> {
                    // macOS：AWT 原生 PopupMenu 即右键菜单本体（Linux 走不到这里，已优先 SNI）
                    popupMenu = PopupMenu().apply {
                        for ((label, id) in menuItems) {
                            if (label == "-") {
                                addSeparator()
                            } else {
                                add(MenuItem(label).apply {
                                    addActionListener { onMenuCommand(id) }
                                })
                            }
                        }
                    }
                }
            }
            addActionListener { onLeftClick() }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    when (e.button) {
                        // 兜底通道：事件能到的环境立即响应（Windows 必达；Linux 部分环境可达）
                        java.awt.event.MouseEvent.BUTTON1 -> onLeftClick()
                        java.awt.event.MouseEvent.BUTTON3 -> {
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
            Log.i(TAG, "托盘图标已安装（AWT）")
        }.onFailure { Log.w(TAG, "托盘图标安装失败: ${it.message}") }
    }

    /**
     * Linux：dorkbox SystemTray（原生 StatusNotifierItem）。
     * @return true = SNI 托盘已就绪（调用方无需再装 AWT）
     */
    private fun installSni(
        image: BufferedImage,
        menuItems: List<Pair<String, Int>>,
        onLeftClick: () -> Unit,
        onMenuCommand: (Int) -> Unit
    ): Boolean = runCatching {
        // AppIndicator 即 StatusNotifierItem 协议；AutoDetect 可能选中同为 XEmbed 的
        // GtkStatusIcon（KDE 上同样收不到事件），强制 SNI
        dorkbox.systemTray.SystemTray.FORCE_TRAY_TYPE =
            dorkbox.systemTray.SystemTray.TrayType.AppIndicator
        val tray = dorkbox.systemTray.SystemTray.get() ?: return@runCatching false

        // SNI 图标按面板缩放自适应，给高分辨率原图
        val hiRes = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        (hiRes.createGraphics() as Graphics2D).apply {
            setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC
            )
            drawImage(image, 0, 0, 64, 64, null)
            dispose()
        }
        tray.setImage(hiRes)
        runCatching { tray.setTooltip("云析 YunX-Desktop") } // AppIndicator 不支持 tooltip，忽略

        val menu = tray.getMenu()
        for ((label, id) in menuItems) {
            if (label == "-") {
                menu.add(dorkbox.systemTray.Separator())
            } else {
                menu.add(
                    dorkbox.systemTray.MenuItem(label) {
                        // dorkbox 回调在其分发线程，AWT/Swing 操作转 EDT
                        SwingUtilities.invokeLater { onMenuCommand(id) }
                    }
                )
            }
        }
        sniTray = tray
        Log.i(TAG, "托盘图标已安装（SNI/StatusNotifierItem，KDE/GNOME 原生菜单）")
        true
    }.onFailure {
        Log.w(TAG, "SNI 托盘不可用（${it.message}），回退 AWT")
    }.getOrDefault(false)

    /** 移除托盘图标 */
    fun uninstall() {
        sniTray?.let { tray ->
            runCatching {
                val m = tray.javaClass.getMethod("remove")
                m.invoke(tray)
            }
            sniTray = null
        }
        trayIcon?.let { icon ->
            runCatching { SystemTray.getSystemTray().remove(icon) }
            trayIcon = null
        }
    }

    /**
     * 显示系统通知。
     *
     * Windows 走 AWT 托盘气泡（归入通知中心）；SNI 模式（Linux）没有 AWT 托盘图标，
     * 改用 `notify-send`（org.freedesktop.Notifications，KDE/GNOME 原生通知，发行版标配）。
     */
    fun showNotification(title: String, message: String) {
        if (sniTray != null) {
            runCatching {
                ProcessBuilder("notify-send", "-a", "YunX-Desktop", title, message).start()
            }.onFailure { Log.w(TAG, "notify-send failed: ${it.message}") }
            return
        }
        val icon = trayIcon ?: return
        runCatching { icon.displayMessage(title, message, TrayIcon.MessageType.NONE) }
            .onFailure { Log.w(TAG, "showNotification failed: ${it.message}") }
    }

    /** 从 classpath 加载 icon.png */
    fun loadTrayIcon(): BufferedImage? = runCatching {
        val stream = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("icon.png") ?: return@runCatching null
        ImageIO.read(stream)
    }.getOrNull()
}
