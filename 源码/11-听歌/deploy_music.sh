#!/bin/bash
# deploy_music.sh —— 一键装「听歌」到 T70L
#
# 用法：
#   bash deploy_music.sh            # 自动找设备、装包、启动
#   bash deploy_music.sh 192.168.1.151   # 指定 IP
#
# 设备 adb 卡死（显示 offline）时，这个脚本救不了，得先重启盒子：
#   adb 卡死 → 用 shell 通道 18889 执行 stop adbd; start adbd
#   两个都卡死 → 拔电重启（本脚本会提示）

set -u
export PATH=$HOME/re-tools/platform-tools:$PATH
P="$(cd "$(dirname "$0")" && pwd)"
APK="$P/music.apk"

if [ ! -f "$APK" ]; then
    echo "!! 没有 $APK，先跑 bash build_music.sh"
    exit 1
fi

IP="${1:-}"

if [ -z "$IP" ]; then
    echo "==> 扫描局域网找 T70L…"
    for i in $(seq 100 200); do
        ( ping -c1 -W200 192.168.1.$i >/dev/null 2>&1 && echo "192.168.1.$i" >> /tmp/_t70l_scan ) &
    done
    wait
    # android-xxxx.lan 的 arp 名更准
    CAND=$(arp -a 2>/dev/null | grep -i "android-" | grep -oE "192\.168\.8\.[0-9]+" | head -1)
    [ -z "$CAND" ] && CAND=$(head -1 /tmp/_t70l_scan 2>/dev/null)
    rm -f /tmp/_t70l_scan
    IP="$CAND"
fi

if [ -z "$IP" ]; then
    echo "!! 没找到设备。确认盒子和 Mac 在同一个 WiFi 下。"
    exit 1
fi

echo "==> 设备 $IP"
adb connect "$IP:5555" >/dev/null 2>&1
sleep 2

ST=$(adb -s "$IP:5555" get-state 2>/dev/null)
if [ "$ST" != "device" ]; then
    echo "!! adb 状态是「$ST」，不是 device。"
    echo "   这是 4.4 上 adbd 卡死的老毛病，装机前必须救活："
    echo "   1) 首选：拔电重启盒子，等 40 秒，再跑一次本脚本"
    echo "   2) 次选：用 shell 通道执行  stop adbd; start adbd"
    echo "      printf 'AUTH <token>\\nstop adbd; start adbd\\nQUIT\\n' | nc $IP 18889"
    echo "      token 在 /data/local/tmp/shell_token"
    exit 2
fi

echo "==> 安装"
adb -s "$IP:5555" install -r "$APK" | tail -2

echo "==> 启动"
adb -s "$IP:5555" shell am start -n com.lm.music/.MainActivity >/dev/null 2>&1
sleep 2

echo "==> 看日志（Ctrl+C 退出）"
adb -s "$IP:5555" logcat -c
adb -s "$IP:5555" logcat -s MUSIC:*
