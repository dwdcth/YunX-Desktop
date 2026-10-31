# Linux 移植说明

> 本文档面向两类读者：**Linux 用户**（怎么构建/运行/已知限制）与**维护者**
> （改了什么、如何同步上游 Windows 版更新而不丢失 Linux 支持）。

## 状态概览

| 能力 | 状态 |
| --- | --- |
| 编译 / 单元测试 | ✅（JDK 17，Arch / CachyOS 实测） |
| 主界面 / 解析 / 下载 / 数据库 | ✅ |
| JCEF 内嵌浏览器登录、抖音解析 | ✅（`jcef-natives-linux-amd64`，KDE Wayland 实测一次初始化成功） |
| 凭证加密（普通档） | ✅（机器指纹 = `/etc/machine-id`） |
| ffplay 视频预览 | ✅ 独立窗口播放（不嵌入应用窗口，嵌入仅 Windows） |
| 浏览器 Cookie 一键导入 | ⚠️ 仅 Firefox（Chromium 系 cookie 走 keyring 加密，暂不支持） |
| 系统托盘 | ✅ dorkbox SystemTray（原生 StatusNotifierItem/DBus，菜单原生渲染；通知走 notify-send） |
| 系统代理（system 模式） | ✅ 读 `https_proxy` / `all_proxy` / `http_proxy` 环境变量（SOCKS 值跳过并记日志） |
| 高分屏（HiDPI） | ✅ 自动读 KDE 缩放并补偿（见下文） |
| 下载防休眠 / 通知中心进度条 | ❌ Windows 专属，Linux 下 no-op（不影响下载本身） |
| 打包 | ✅ AppImage（`./run.sh package`，需要 `fuse2`） |

## 构建与运行

要求 JDK 17 与 ffmpeg（预览/合轨），Gradle 由 wrapper 自动拉取（腾讯镜像）。详见 [BUILD.md](BUILD.md#linux-x86_64)。

```bash
./run.sh run        # 编译并启动
./run.sh build      # 仅编译
./run.sh package    # 打 AppImage
```

### 开发模式桌面图标（改代码即生效，无需安装）

`./run.sh install-desktop` 会生成 `.desktop` 并装进应用菜单（同时留一份在项目根
`YunX-Desktop.desktop`）。它的 `Exec` 指向项目内的 `app-run.sh`，后者直接运行
`desktop/build/` 下的构建产物（classpath 由 `:desktop:jar` 构建自动写入
`desktop/build/dev-classpath.txt`）。因此工作流是：

```bash
./run.sh install-desktop   # 一次性安装
# 以后每次改完代码：
./run.sh build             # 图标下次点击即运行新代码
```

### HiDPI 说明

Linux 的 JVM 跑在 XWayland 下只能拿到**整数**缩放（KDE/GNOME 的 1.5/1.6 等分数缩放不可见），
不处理的话 Compose 按 96 DPI 渲染、高分屏上字特别小。应用启动时（`main` 第一行，
`util/UiScaleSetup.kt`）会读 KDE 桌面缩放并四舍五入后设置 `sun.java2d.uiScale` 补偿：

- 1.6 → 2 倍渲染：**清晰**（真实 2x 光栅化，无插值模糊），但比 KDE 原生应用大 ~25%；
- 不满意可设环境变量 `YUNXPC_UI_SCALE`（支持小数如 `1.5`；`1` / `off` 关闭补偿）；
- 诊断日志：启动日志搜 `YunX-UiScale`，会打印检测到的桌面缩放与最终 AWT transform。

### 托盘说明（为什么 Linux 不用 AWT）

JDK 的 AWT TrayIcon 走**已淘汰的 XEmbed 协议**，新桌面（KDE 6 / GNOME）不再向其转发
鼠标事件——图标能显示但点击永远无反应（[JDK-8341144](https://bugs.openjdk.org/browse/JDK-8341144)，
上游无修复计划）。Linux 版因此引入 [dorkbox SystemTray](https://github.com/dorkbox/SystemTray)
（Apache 2.0，仅 Linux 构建打入，Windows 继续用 AWT + Win32 暗色菜单），走原生
**StatusNotifierItem / D-Bus**：图标与菜单由桌面环境原生渲染，交互是 D-Bus 方法调用
（不经 X 事件转发），KDE/GNOME 下可靠。dorkbox 初始化失败时自动回退 AWT（仅图标可见）。

使用注意：

- 新图标默认在 KDE 托盘**折叠区**（面板 `^` 展开），右键图标可设为「始终显示」；
- **右键菜单是主要交互**（首项即「显示主窗口」）；SNI 的左键单击（Activate 信号）
  支持有限，没反应属协议特性；
- 系统通知（如「已最小化到托盘」）走 `notify-send`（org.freedesktop.Notifications），
  依赖发行版自带该命令（KDE/GNOME 标配）；
- 升级 dorkbox 时注意 4.x 系列 API 变化，`TrayManager.installSni` 集中封装了全部调用。

### Linux 专属环境变量

| 变量 | 作用 |
| --- | --- |
| `YUNXPC_UI_SCALE` | 手动指定 UI 缩放（如 `2` / `1.5` / `1`=关闭） |
| `YUNX_DESKTOP_DATA_DIR` | 覆盖数据目录（`~/.yunx-desktop`） |
| `YUNXPC_RENDER_API` | 同 Windows（SOFTWARE / OPENGL 强制指定） |

---

## 维护者指南：改了什么 & 如何同步上游

### 设计原则

1. **Windows 行为路径与上游保持逐行一致**——所有平台差异以"加守卫 / 加分支"实现，
   不删改 Windows 逻辑。这样上游 diff 读起来干净，合并冲突解决原则唯一：*Windows 分支
   以上游为准，Linux 分支保留*。
2. **平台判断统一走 `util/HostOs.kt`**（`HostKind.WINDOWS / MAC / LINUX`），不散落
   `os.name` 字符串比较；Gradle 脚本侧用同组字符串的 `hostOsName` / `javafxClassifier`
   （与 `HostOs` 保持一致，见 `desktop/build.gradle.kts` 顶部注释）。
3. 该思路与上游 PR #31（macOS 移植）同构；若上游将来合并该 PR，相关文件可直接采用上游版本。

### 改动清单（按类别）

**新增文件（合并零冲突）**

| 文件 | 作用 |
| --- | --- |
| `util/HostOs.kt` | 统一平台判断 + JavaFX classifier / Gopeed 包名 token |
| `util/TrayMenu.kt` | 托盘右键菜单跨平台入口（Windows→Win32 原生菜单，其余→Swing JPopupMenu；Linux SNI 模式下作为 AWT 回退路径保留） |
| `util/UiScaleSetup.kt` | Linux HiDPI 补偿（读 KDE 缩放→`sun.java2d.uiScale`，main 第一行调用） |
| `gradlew` / `gradlew.bat` | 仓库原先缺失的 wrapper 脚本 |
| `run.sh` | Linux 构建入口（run/build/package） |
| `gradle/gradle-daemon-jvm.properties` | daemon 固定 JDK 17（开发机默认 JVM 过新时自举） |
| `desktop/packaging/YunX-Desktop.png` | Linux jpackage 图标（512x512 PNG） |
| `HostOsTest.kt` / `GopeedAssetNameTest.kt` | 平台相关单测 |

**构建脚本**

- `desktop/build.gradle.kts`：按主机 OS 选择 sqlite（win 裁剪包 / 其余官方坐标）、
  JavaFX classifier（`win`/`mac`/`mac-aarch64`/`linux`）、JCEF natives
  （win→windows-amd64，linux→linux-amd64，mac 不带）；darkmode.dll 编译 `onlyIf { isWindowsHost }`；
  打包格式 win→Exe / mac→Dmg / linux→AppImage；`printRuntimeClasspath` 分隔符用 `File.pathSeparator`。
- `settings.gradle.kts`：加 foojay toolchain 插件（无本机 JDK 17 时自动下载，对齐 Windows 版 run.ps1 的 Ensure-Jdk）。
- `gradle.properties`：`compose.desktop.packaging.checkJdkVendor=false`（发行版 OpenJDK 打包需要）。
- `gradle/wrapper/gradle-wrapper.properties`：Gradle 发行版改腾讯镜像。
- 依赖：`com.dorkbox:SystemTray:4.4`（仅 Linux 构建打入，原生 SNI 托盘，见「托盘说明」）。

**运行时守卫 / 平台分支（上游活跃文件，见下方冲突指南）**

| 文件 | 改动要点 |
| --- | --- |
| `Main.kt` | 托盘安装走 `TrayManager`（Linux 优先 SNI，见下）；JCEF/抖音预热加 `!HostOs.isMac` 守卫；main 首行调 `UiScaleSetup.applyEarly()` |
| `TrayManager.kt` | 托盘按平台分路：Linux 优先 dorkbox SystemTray（原生 SNI/DBus，`installSni`），失败回退 AWT；Windows 保持 AWT+Win32；macOS 用 AWT 菜单。SNI 模式通知走 `notify-send` |
| `WindowsKeepAwake.kt` | `acquire/release` 非 Windows 直接 return |
| `DbKeyStore.kt` | 机器指纹：Windows 读注册表 MachineGuid，Linux 读 `/etc/machine-id`；STRICT 档（DPAPI）非 Windows `isSupported=false` |
| `SystemProxy.kt` | Linux 读 `*_proxy` 环境变量（SOCKS 跳过） |
| `BrowserCookieImporter.kt` | `discoverBrowsers` 拆分：Windows 原逻辑不变；Linux/macOS 扫 Firefox profile（含 Flatpak），Chromium 系跳过 |
| `DesktopActions.kt` | `revealFile`/`openDirectory` 非 Windows 走 xdg-open（选中文件优先 D-Bus FileManager1）；`restartApp` POSIX 版；`launcherExe` 按平台取名 |
| `FfplayManager.kt` | 可执行名平台化（`ffplay` vs `ffplay.exe`）；`locate` 全平台；自动下载仅 Windows（Linux 提示包管理器安装）；解包补执行位 |
| `FfplayWindowFx.kt` / `FfplayPlayer.kt` | 未改（原有 isWindows 守卫已完整，Linux 走"独立窗口"降级路径） |
| `GopeedKernelProvisioner.kt` | 包名拼接近平台 token（`assetName()`，与 PR #31 一致） |
| `GopeedEngine.kt` | 内核二进制名平台化（`gopeed`）；解包后 `setExecutable(true)` |
| `JcefHolder.kt` | macOS 无原生库直接跳过；残留进程清理的路径分隔符比较修正为跨平台 |
| `JcefNativesInstaller.kt` | tar 解包按条目 POSIX mode 恢复执行位（Linux 上 `jcef_helper` 必须带 x） |
| `UpdateDialog.kt` | 更新包下载入口按平台选资产（win→exe/zip，linux→AppImage，mac→dmg） |
| `DownloadEngineScreen.kt` / `SettingsRepository.kt` / `AboutScreen.kt` | 平台相关文案 |

### 同步上游更新的操作流程

```bash
git remote add upstream https://github.com/tidain/YunX-Desktop   # 首次
git fetch upstream
git merge upstream/main
# 有冲突时按下表原则解决后：gradlew :desktop:test && ./run.sh run 双平台冒烟
```

**冲突解决原则：Windows 分支以上游为准，Linux 分支原样保留。** 预期冲突点与处理：

| 冲突文件 | 预期场景 | 处理 |
| --- | --- | --- |
| `desktop/build.gradle.kts` | 上游在依赖块新增/升级依赖 | 把上游新依赖**原样**放进对应位置；只有涉及平台原生库（jcef/javafx/sqlite）时才需要包进 `if (isWindowsHost)` 分支 |
| `Main.kt` | 上游改 JCEF 初始化 / 托盘菜单 / 启动流程 | 保持 `UiScaleSetup.applyEarly()` 在 main 首行；JCEF 块保留 `!HostOs.isMac`；托盘块保留 `TrayMenu.show` 调用 |
| `FfplayManager.kt` | 上游改下载源 / 命令行组装 | `EXE_NAME`/`FFMPEG_EXE` 保持平台化 val；`download()` 的非 Windows 提前返回保留；命令行逻辑按上游 |
| `GopeedKernelProvisioner.kt` | 上游改版本/镜像逻辑（或合并 PR #31） | 若上游已含 `assetName()`（PR #31 同款），直接用上游版本，删除我方重复实现 |
| `DbKeyStore.kt` / `SystemProxy.kt` 等守卫类 | 上游改 Windows 实现 | Windows 代码块跟随上游；`else`/Linux 分支保留 |
| `version.txt` / Releases.md | 每次发版 | 直接取上游 |

**升级依赖时注意**：JCEF 的 Windows 与 Linux natives 版本号目前共用同一串
（`jcef-1770317+cef-132.3.1+…`）；将来升级时需确认
[me.friwi:jcef-natives-linux-amd64](https://repo1.maven.org/maven2/me/friwi/jcef-natives-linux-amd64/)
有对应版本（历史版本齐全，通常同步发布）。

### Linux 待办（按优先级）

1. 便携版打包脚本（对应 `portable-package.ps1` 的 bash 版：runtime + app + jcef-natives.tar.xz）
2. Chromium Cookie 导入（需 gnome-keyring/libsecret 解密 v11 cookie）
3. 下载防休眠：org.freedesktop.ScreenSaver D-Bus inhibitor
4. SNI 左键单击激活（Activate/SecondaryActivate 信号，依赖 dorkbox 暴露回调；当前用右键菜单）
5. ffplay X11 窗口嵌入（XReparentWindow，Wayland 下不可行，优先级低）
6. 单实例唤醒已有窗口（Linux 无跨进程窗口枚举，可考虑 D-Bus 自有服务）
