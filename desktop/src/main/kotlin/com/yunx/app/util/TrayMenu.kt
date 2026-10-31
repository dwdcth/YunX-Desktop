package com.yunx.app.util

import java.awt.EventQueue
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.JWindow
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * 托盘右键菜单的跨平台入口。
 *
 * - Windows：[Win32PopupMenu]（原生 TrackPopupMenu，中文正常、暗色跟随系统，见 DarkMode）；
 * - Linux/macOS：Swing JPopupMenu 定位到屏幕坐标（AWT 托盘无原生右键菜单 API）。
 *
 * 菜单项用 (label, id) 表达："-" 为分隔符。
 * 返回选中项 id；取消/失败返回 0。异步平台（Swing）立即返回 0，
 * 选中动作经 [onCommand] 回调（Windows 路径不回调，直接返回值）。
 */
object TrayMenu {

    /**
     * 弹出菜单。
     * @param items 菜单项列表，label 为 "-" 表示分隔符
     * @param x y 屏幕坐标（托盘图标处）
     * @param onCommand 异步平台（Swing 菜单）的选中回调；Windows 原生菜单不会走此回调
     * @return Windows 原生菜单返回选中 id（0 = 取消）；Swing 菜单恒返回 0
     */
    fun show(items: List<Pair<String, Int>>, x: Int, y: Int, onCommand: (Int) -> Unit): Int {
        if (HostOs.isWindows) {
            return Win32PopupMenu.show(items, x, y)
        }
        EventQueue.invokeLater { showSwing(items, x, y, onCommand) }
        return 0
    }

    /**
     * Swing 菜单：JPopupMenu 无 invoker 时在部分 WM（KDE）上不显示、也不能点击外部自动关闭，
     * 这里挂一个 1x1 全透明窗作 invoker，菜单关闭（选中/点外部/Esc）时一并销毁。
     */
    private fun showSwing(items: List<Pair<String, Int>>, x: Int, y: Int, onCommand: (Int) -> Unit) {
        EventQueue.invokeLater {
            runCatching {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
                val menu = JPopupMenu()
                for ((label, id) in items) {
                    if (label == "-") {
                        menu.add(JSeparator())
                    } else {
                        menu.add(JMenuItem(label).apply {
                            addActionListener {
                                runCatching { menu.isVisible = false }
                                onCommand(id)
                            }
                        })
                    }
                }
                val invoker = JWindow().apply {
                    isAlwaysOnTop = true
                    runCatching { opacity = 0f }
                    bounds = java.awt.Rectangle(x, y, 1, 1)
                    isVisible = true
                }
                menu.addPopupMenuListener(object : PopupMenuListener {
                    override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                        EventQueue.invokeLater { runCatching { invoker.dispose() } }
                    }
                    override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {}
                    override fun popupMenuCanceled(e: PopupMenuEvent) {}
                })
                menu.isFocusable = true
                menu.show(invoker, 0, 0)
                menu.requestFocusInWindow()
            }.onFailure { Log.w("YunX-Tray", "TrayMenu show failed: ${it.message}") }
        }
    }
}
