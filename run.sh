#!/usr/bin/env bash
# YunX-Desktop Linux 构建/运行脚本
# 用法: ./run.sh [run|build|package|install-desktop]   （默认 run）
#   run            编译并启动桌面应用
#   build          仅编译
#   package        打 Linux 包（AppImage；首次会下载 linuxdeploy 工具链到 Gradle 缓存）
#   install-desktop 生成开发模式 .desktop 图标（Exec 直指 app-run.sh，跑构建产物）：
#                   写一份到项目根，并安装到 ~/.local/share/applications/（应用菜单可见）
#
# 需要 JDK 17（编译目标）：优先 JAVA_HOME，否则探测常见安装路径。
# Arch: sudo pacman -S jdk17-openjdk ；Debian/Ubuntu: sudo apt install openjdk-17-jdk

set -e
cd "$(dirname "$0")"

CMD="${1:-run}"

if [ -z "$JAVA_HOME" ]; then
    for j in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-21-openjdk \
             /usr/lib/jvm/temurin-17-jdk /usr/lib/jvm/temurin-21-jdk \
             /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17; do
        if [ -x "$j/bin/java" ]; then
            export JAVA_HOME="$j"
            break
        fi
    done
fi

if [ -n "$JAVA_HOME" ]; then
    echo "[OK] Using JAVA_HOME: $JAVA_HOME"
    "$JAVA_HOME/bin/java" -version 2>&1 | head -1
else
    echo "[WARN] 未找到 JDK 17，交给 Gradle toolchain 处理（若失败请安装 JDK 17 并设 JAVA_HOME）" >&2
fi

case "$CMD" in
    run)
        exec ./gradlew ":desktop:run" --console=plain
        ;;
    build)
        # 用 jar 而非 build：build 会连带 test（见 run.ps1 同款说明）
        exec ./gradlew ":desktop:jar" --console=plain
        ;;
    package)
        exec ./gradlew ":desktop:packageAppImage" --console=plain
        ;;
    install-desktop)
        # 开发模式桌面集成：Exec 直指项目内 app-run.sh（跑 desktop/build 下的构建产物），
        # 改代码后 ./run.sh build 即可，图标无需重装。同步写项目根一份留档。
        REPO="$(pwd)"
        gen_desktop() {
            cat > "$1" <<EOF
[Desktop Entry]
Type=Application
Name=云析 YunX-Desktop
Name[en]=YunX-Desktop
Comment=网盘分享链接解析与高速下载（开发运行版）
Comment[en]=Netdisk share-link parser and high-speed downloader (dev)
Exec=$REPO/app-run.sh
Icon=$REPO/desktop/packaging/YunX-Desktop.png
Path=$REPO
Terminal=false
Categories=Network;FileTransfer;
Keywords=yunx;netdisk;downloader;
StartupWMClass=com-yunx-app-MainKt
EOF
        }
        gen_desktop "$REPO/YunX-Desktop.desktop"
        DEST="$HOME/.local/share/applications"
        mkdir -p "$DEST"
        gen_desktop "$DEST/yunx-desktop.desktop"
        update-desktop-database "$DEST" 2>/dev/null || true
        echo "[OK] 已生成 $REPO/YunX-Desktop.desktop"
        echo "[OK] 已安装 $DEST/yunx-desktop.desktop（应用菜单可直接搜索「云析」）"
        echo "     首次使用请先执行 ./run.sh build 生成构建产物"
        exit 0
        ;;
    *)
        echo "未知命令: $CMD（支持 run / build / package / install-desktop）" >&2
        exit 1
        ;;
esac
