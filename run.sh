#!/usr/bin/env bash
# YunX-Desktop Linux/macOS 构建/运行脚本
# 用法: ./run.sh [run|build|package]   （默认 run）
#   run       编译并启动桌面应用
#   build     仅编译
#   package   打 Linux 包（AppImage；首次会下载 linuxdeploy 工具链到 Gradle 缓存）
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
    *)
        echo "未知命令: $CMD（支持 run / build / package）" >&2
        exit 1
        ;;
esac
