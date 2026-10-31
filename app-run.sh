#!/usr/bin/env bash
# YunX-Desktop 开发模式启动器（供 .desktop 桌面图标 / 命令行使用）。
#
# 直接运行 Gradle 构建产物（desktop/build/libs/desktop.jar + 运行时依赖，classpath 由
# 构建自动写入 desktop/build/dev-classpath.txt）。改代码后执行 ./run.sh build 即可，
# 桌面图标下次点击就是新代码 —— 不复制文件、不做安装。
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

CP_FILE="desktop/build/dev-classpath.txt"
if [ ! -f "$CP_FILE" ]; then
    echo "缺少 $CP_FILE —— 请先执行 ./run.sh build" >&2
    exit 1
fi

# 定位 JDK 17：JAVA_HOME 优先（须为 17），其次常见安装路径与 mise
find_java() {
    local cands=("${JAVA_HOME:-}" /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/temurin-17-jdk
                 "$HOME"/.local/share/mise/installs/java/temurin-17*)
    local j
    for j in "${cands[@]}"; do
        [ -n "$j" ] && [ -x "$j/bin/java" ] || continue
        if "$j/bin/java" -version 2>&1 | grep -q '"17'; then
            echo "$j"; return 0
        fi
    done
    return 1
}
JAVA_HOME="$(find_java)" || {
    echo "未找到 JDK 17（JAVA_HOME / 系统路径 / mise），请先安装" >&2
    exit 1
}

exec "$JAVA_HOME/bin/java" \
    -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 \
    -Xmx2g \
    -cp "$(cat "$CP_FILE")" \
    com.yunx.app.MainKt "$@"
