#!/bin/bash
# deploy_xz.sh —— 编译 + 保数据部署（-r 在某些版本不换 dex，所以必须卸载重装后回填）
set -e
ADB=$HOME/bin/platform-tools/adb
cd "$HOME/Desktop/中移T70L/核心资产/源码/07-小智App"

echo "==> 编译"
bash app/build_apk.sh > /tmp/xz_build.log 2>&1 || { tail -20 /tmp/xz_build.log; exit 1; }
tail -1 /tmp/xz_build.log

echo "==> 备份"
$ADB shell 'su 0 cp -r /data/data/com.lm.xiaozhi/files/. /data/local/tmp/xzbak/files/; su 0 cp -r /data/data/com.lm.xiaozhi/shared_prefs/. /data/local/tmp/xzbak/shared_prefs/; echo ok'

echo "==> 停止 + 卸载 + 安装"
$ADB shell 'am force-stop com.lm.xiaozhi'
$ADB uninstall com.lm.xiaozhi >/dev/null 2>&1 || true
$ADB install app/xiaozhi.apk | tail -1

U=$($ADB shell 'dumpsys package com.lm.xiaozhi' | grep -o "userId=[0-9]*" | head -1 | cut -d= -f2 | tr -d '\r')
echo "==> 新 UID = $U"

echo "==> 回填唤醒词模板与配置"
$ADB shell "su 0 mkdir -p /data/data/com.lm.xiaozhi/files /data/data/com.lm.xiaozhi/shared_prefs; su 0 cp -r /data/local/tmp/xzbak/files/. /data/data/com.lm.xiaozhi/files/; su 0 cp -r /data/local/tmp/xzbak/shared_prefs/. /data/data/com.lm.xiaozhi/shared_prefs/; su 0 chown $U:$U /data/data/com.lm.xiaozhi /data/data/com.lm.xiaozhi/files /data/data/com.lm.xiaozhi/files/kws_tpl.dat /data/data/com.lm.xiaozhi/files/xz.log /data/data/com.lm.xiaozhi/shared_prefs /data/data/com.lm.xiaozhi/shared_prefs/xiaozhi.xml; su 0 chmod 700 /data/data/com.lm.xiaozhi /data/data/com.lm.xiaozhi/files /data/data/com.lm.xiaozhi/shared_prefs; su 0 chmod 600 /data/data/com.lm.xiaozhi/files/kws_tpl.dat /data/data/com.lm.xiaozhi/files/xz.log /data/data/com.lm.xiaozhi/shared_prefs/xiaozhi.xml; su 0 ls -l /data/data/com.lm.xiaozhi/files"

echo "==> 启动"
# ★★★ 2026-11-10 19:47 回填之后必须再 force-stop 一次 ★★★
#   uninstall 会删掉整个 data 目录，install 完到回填之间有两三秒空窗。
#   这期间如果服务被自启机制拉起（日志实证过「服务已在运行」），
#   它就会在**没有模板**的状态下把 Kws 初始化完，之后 am startservice
#   只是"已在运行"、根本不会重建，于是本地唤醒永久假死，
#   而表现和"代码没改对"完全一样 —— 今天为此白排查了一轮。
#   所以：回填完先杀干净，再拉起来。
$ADB shell 'am force-stop com.lm.xiaozhi'
sleep 1
# 回填把旧日志也拷回来了，不清空的话"校验模板"会匹配到上一次的记录
$ADB shell 'su 0 sh -c ": > /data/data/com.lm.xiaozhi/files/xz.log"' 2>/dev/null
$ADB shell 'am startservice -n com.lm.xiaozhi/.XzService'
sleep 5
$ADB shell 'dumpsys activity services com.lm.xiaozhi' | grep -E "app=|isForeground" | head -2

echo "==> 校验模板"
$ADB shell 'su 0 cat /data/data/com.lm.xiaozhi/files/xz.log' 2>/dev/null | grep -a "本地唤醒" | tail -2
echo "==> 完成"
