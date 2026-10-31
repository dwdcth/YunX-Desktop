package com.yunx.app.util

import java.awt.EventQueue
import javax.swing.JPopupMenu
import javax.swing.JMenuItem
import javax.swing.JSeparator
import javax.swing.UIManager

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

    /** Swing 菜单：无 invoker 窗口时直接按屏幕坐标显示；选中后回调并自动消失 */
    private fun showSwing(items: List<Pair<String, Int>>, x: Int, y: Int, onCommand: (Int) -> Unit) {
        runCatching {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
            val menu = JPopupMenu()
            for ((label, id) in items) {
                if (label == "-") {
                    menu.add(JSeparator())
                } else {
                    menu.add(JMenuItem(label).apply {
                        addActionListener {
                            runCatching { menu.setVisible(false) }
                            onCommand(id)
                        }
                    })
                }
            }
            menu.isFocusable = true
            // invoker 为 null 时按屏幕坐标显示；提前 setLocation 保证多显示器下不越界
            menu.setLocation(x, y)
            menu.show(null, x, y)
            // 无 invoker 的 JPopupMenu 不会自动抢焦点，手动请求以支持键盘导航/点击外部关闭
            menu.requestFocusInWindow()
        }.onFailure { Log.w("YunX-Tray", "TrayMenu show failed: ${it.message}") }
    }
}
