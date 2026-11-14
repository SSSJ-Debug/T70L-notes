#!/bin/bash
# deploy_douyin.sh —— 把抖音 App 推到 T70L 并启动
# 用法: bash deploy_douyin.sh [设备IP]
set -e
export PATH=$HOME/re-tools/platform-tools:$PATH
P="$(cd "$(dirname "$0")" && pwd)"
APK=$P/douyin.apk
[ -f "$APK" ] || { echo "先跑 build_douyin.sh"; exit 1; }

IP=${1:-}
if [ -z "$IP" ]; then
    echo "==> 扫描设备…"
    for i in 100 101 102 103 110 120 130 140 150 151 152 153 160 170 180 190 200; do
        ( nc -z -G 1 192.168.1.$i 5555 2>/dev/null && echo "192.168.1.$i" ) &
    done | head -1
    IP=$(for i in 100 101 102 103 110 120 130 140 150 151 152 153 160 170 180 190 200; do
        nc -z -G 1 192.168.1.$i 5555 2>/dev/null && echo "192.168.1.$i" && break
    done | head -1)
fi
[ -z "$IP" ] && { echo "没找到设备，检查网络"; exit 1; }
echo "==> 设备 $IP"
adb disconnect >/dev/null 2>&1 || true
adb connect $IP:5555 >/dev/null 2>&1
sleep 4
STATE=$(adb -s $IP:5555 get-state 2>/dev/null || echo unknown)
echo "==> adb 状态: $STATE"
[ "$STATE" != "device" ] && { echo "adb 不是 device 状态（4.4 上常见 adbd 卡死），请拔电重启盒子"; exit 2; }

echo "==> 安装"
adb -s $IP:5555 install -r -d "$APK"
echo "==> 启动"
adb -s $IP:5555 shell am start -n com.lm.douyin/.MainActivity
sleep 5
echo "==> 日志"
adb -s $IP:5555 logcat -d -s Douyin:* chromium:* | tail -30
