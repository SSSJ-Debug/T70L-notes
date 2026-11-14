#!/bin/bash
# deploy.sh —— 编译 + 安装 + 重启小智（自动清 dalvik 缓存，避免 4.4 上的 ClassNotFound）
set -e
export PATH=$HOME/re-tools/platform-tools:$PATH
P="$(cd "$(dirname "$0")" && pwd)/app"
PKG=com.lm.xiaozhi

echo "==> 编译"
bash "$P/build_apk.sh" >/tmp/xz_build.log 2>&1 || { tail -20 /tmp/xz_build.log; exit 1; }
tail -2 /tmp/xz_build.log

echo "==> 安装（先卸再装：T70L 的 Android 4.4 上 -r 覆盖安装有时不换 dex）"
adb uninstall $PKG >/dev/null 2>&1 || true
adb install "$P/xiaozhi.apk" | tail -1

echo "==> 重启服务（清 dex 缓存）"
adb shell "am force-stop $PKG"
adb shell "rm -f /data/dalvik-cache/data@app@$PKG-1.apk@classes.dex" 2>/dev/null || true 2>/dev/null || true
sleep 1
adb shell "am startservice -n $PKG/.XzService"
sleep 4

echo "==> 状态"
adb shell "ps | grep xiaozhi | grep -v grep" || echo "(进程没起来)"
adb shell "cat /proc/asound/card0/pcm0c/sub0/status 2>/dev/null | /sbin/busybox head -2"
