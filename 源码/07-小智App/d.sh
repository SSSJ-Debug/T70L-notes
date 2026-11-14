#!/bin/bash
# d.sh —— 部署小智 APK（安装 → 重启服务 → 拿回麦），约 20 秒
export PATH=$HOME/re-tools/platform-tools:$PATH
PKG=com.lm.xiaozhi
APK="$HOME/Desktop/中移T70L/小智App/app/xiaozhi.apk"

adb install -r "$APK" 2>&1 | tail -2
adb shell "am force-stop $PKG"; sleep 1
adb shell "am startservice -n $PKG/.XzService"; sleep 5
adb shell "su 0 sh /data/local/tmp/mic_owner.sh" >/dev/null 2>&1
sleep 2
adb shell "su 0 rm -f /data/data/$PKG/files/xz.log"
echo "--- 麦归属 ---"
adb shell "su 0 sh /data/local/tmp/mic_owner.sh check" 2>&1
