#!/system/bin/sh
# =============================================================================
# 02-disk_sentinel.sh —— T70L 磁盘写入总闸（2026-11-23 建立）
#
# 背景：11-23 /data 被写满 0 块导致开不了机。已查明两大写盘来源：
#   ① init.rc 的 nemodlogger / nemorlogger：logcat -r10240 -n 30
#      = 单服务最多 300MB，两个近 600MB，且永不停止
#   ② 各种脚本日志无上限追加（boot_svc.log / relay.log / xz.log ...）
#
# 本脚本每 60 秒做一次"总闸"动作，全程只用 busybox，绝不用会 SIGPIPE 的管道：
#   1. setprop ctl.stop 关掉两个 logcat 文件记录服务（init 不会再拉起）
#   2. 删 /data/log/logcat_full.log*
#   3. kernel.log 超 2MB 就只留最后 200 行
#   4. 白名单内所有 *.log 超 256KB 就只留最后 100 行
#   5. 调 01-disk_guard.sh --once 做水位兜底
#
# 用法: sh 02-disk_sentinel.sh         常驻（60s 一轮）
#       sh 02-disk_sentinel.sh --once  只跑一轮
# =============================================================================
BB=/sbin/busybox
[ -x "$BB" ] || BB=busybox
D=/data/local/tmp
LOG=$D/disk_sentinel.log
LOG_MAX=131072          # 自身日志 128KB 封顶
CAP=262144              # 普通日志 256KB 封顶
KCAP=2097152            # kernel.log 2MB 封顶

slog() {
    echo "$($BB date '+%m-%d %H:%M:%S') $*" >> "$LOG"
}

cap_file() {
    # $1=文件 $2=上限字节 $3=保留行数
    [ -f "$1" ] || return 0
    sz=$($BB wc -c < "$1" 2>/dev/null)
    [ -n "$sz" ] || return 0
    if [ "$sz" -gt "$2" ]; then
        $BB tail -n "$3" "$1" > "$1.n" 2>/dev/null
        $BB mv -f "$1.n" "$1" 2>/dev/null
        slog "截断 ${1##*/} 原 ${sz}B -> 保留${3}行"
    fi
}

once() {
    # 1) 掐掉 logcat 落盘服务
    svc=$(/system/bin/getprop init.svc.nemorlogger)
    if [ "$svc" = "running" ]; then
        setprop ctl.stop nemorlogger
        slog "已停 nemorlogger"
    fi
    svc=$(/system/bin/getprop init.svc.nemodlogger)
    if [ "$svc" = "running" ]; then
        setprop ctl.stop nemodlogger
        slog "已停 nemodlogger"
    fi

    # 2) 删 logcat 落盘文件
    $BB rm -f /data/log/logcat_full.log /data/log/logcat_full.log.1 \
        /data/log/logcat_full.log.2 /data/log/logcat_full.log.3 2>/dev/null

    # 3) kernel.log
    cap_file /data/log/kernel/kernel.log "$KCAP" 200

    # 4) 白名单日志逐个封顶
    cap_file $D/boot_svc.log "$CAP" 100
    cap_file $D/relay.log "$CAP" 100
    cap_file $D/gw.log "$CAP" 100
    cap_file $D/t70gw.log "$CAP" 100
    cap_file $D/mic_owner.log "$CAP" 100
    cap_file $D/mic_keeper.log "$CAP" 100
    cap_file $D/xz_guard.log "$CAP" 100
    cap_file $D/cam_guard.log "$CAP" 100
    cap_file $D/desk_watchdog.log "$CAP" 100
    cap_file $D/launcher_guard.log "$CAP" 100
    cap_file $D/lamp_icon_guard.log "$CAP" 100
    cap_file $D/gateway_guard.log "$CAP" 100
    cap_file $D/disk_guard.log "$CAP" 100
    cap_file /data/data/com.lm.xiaozhi/files/xz.log "$CAP" 100

    # 5) 水位兜底
    [ -f $D/01-disk_guard.sh ] && sh $D/01-disk_guard.sh --once >/dev/null 2>&1

    cap_file "$LOG" "$LOG_MAX" 100
}

case "${1:-}" in
    --once)
        slog "===== disk_sentinel 单轮检查 ====="
        once
        ;;
    *)
        slog "===== disk_sentinel 启动 (PID $$) ====="
        once
        while true; do
            $BB sleep 60
            once
        done
        ;;
esac
