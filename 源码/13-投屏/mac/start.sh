#!/bin/bash
# 启动扩展屏服务端（Mac 端）。双击或在终端运行都行。
set -e
cd "$(dirname "$0")"

if [ ! -x ./mirrord ]; then
    echo "首次运行，先编译…"
    ./build.sh
fi

if pgrep -x mirrord >/dev/null 2>&1; then
    echo "已经有一个 mirrord 在跑了。先执行： pkill -x mirrord"
    exit 1
fi

exec ./mirrord
