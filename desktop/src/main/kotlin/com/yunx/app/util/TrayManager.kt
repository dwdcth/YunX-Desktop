package com.yunx.app.util

import java.awt.Image
import java.awt.MenuItem
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
     *
     * 各平台可靠的事件通道不同（多通道并存，动作均幂等，重复触发无害）：
     * - Windows：MouseListener（press）左键激活、右键弹 Win32 原生菜单（[onRightClick]）；
     * - Linux(XEmbed)：MouseListener 经 xembedsniproxy 转发**不可靠**（click 从不触发、
     *   press/released 视环境而定），官方通道是 [TrayIcon.addActionListener]（左键单击即
     *   ActionEvent）+ **AWT PopupMenu**（右键由 peer 层直接弹出，不走 MouseListener）；
     * - macOS：ActionEvent 可靠。
     *
     * @param image 图标
     * @param menuItems 托盘菜单项（label to id，"-" 为分隔符）；Linux/macOS 构建为 AWT 原生菜单
     * @param onLeftClick 左键单击回调
     * @param onRightClick 右键回调（屏幕坐标 x, y；Windows 用于弹 Win32 原生菜单）
     * @param onMenuCommand 菜单项选中回调（Linux/macOS 的 AWT 菜单；Windows 菜单走同步返回值，不经此）
     */
    fun install(
        image: BufferedImage,
        menuItems: List<Pair<String, Int>>,
        onLeftClick: () -> Unit,
        onRightClick: (Int, Int) -> Unit,
        onMenuCommand: (Int) -> Unit
    ) {
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
                    // Linux/macOS：AWT 原生 PopupMenu 就是右键菜单本体（X11TrayIconPeer 在
                    // 右键时直接 show 该菜单，不经过 MouseListener）
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
            // 左键：ActionEvent 是 Linux/macOS 的可靠通道（X11 单击即触发；Windows 双击触发，
            // 与 mouseClicked 单击激活叠加亦幂等）
            addActionListener { onLeftClick() }
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    when (e.button) {
                        // 兜底通道：事件能到的环境立即响应（Windows 必达；Linux 部分环境可达）
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
