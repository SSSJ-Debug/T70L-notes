#!/system/bin/sh
# xz_guard.sh —— 小智常驻唤醒守护（2026-11-05 v2）
#
# 做三件事：
#   1. 开机/启动时设一次 DMIC 数字增益（这是 T70L 麦克风唯一有效的增益）。
#      ⚠ 不要改 ADCL/ADCR Mux，也不要轮询 tinymix —— HAL 开流会自动设成 DMIC，
#         轮询 tinymix 会和 HAL 抢 ALSA 控制接口，造成 XRUN/读零。
#   2. 保证 com.lm.xiaozhi/.XzService 活着，挂了就重拉（不放弃）。
#   3. 记录日志到 /data/local/tmp/xz_guard.log。
#
# 重要：不再 force-stop vulture.app.home（会议 App），
#       因为「电源键→HOME 返回桌面」是它提供的，杀了按键就失效。
#
# 用法：
#   sh /data/local/tmp/xz_guard.sh start
#   sh /data/local/tmp/xz_guard.sh stop
#   sh /data/local/tmp/xz_guard.sh status
#   sh /data/local/tmp/xz_guard.sh once     # 只做一次，供开机脚本调用

BB=/sbin/busybox
D=/data/local/tmp
LOG=$D/xz_guard.log
PIDF=$D/xz_guard.pid
ROUND=30

log() { echo "$($BB date +'%m-%d %H:%M:%S') $*" >> $LOG; }

# DMIC 唯一有效的增益是数字增益 AIF1 ADC timeslot volume：
#   - 190 实测最优（远场可唤醒，无自激）
#   - 200 会自激啸叫（麦克风-扬声器正反馈）
# 模拟增益 ADC volume / ADC input gain / MIC boost 对 DMIC 无效，设回默认即可。
fix_route() {
    tinymix 'ADC volume' 176 2>/dev/null
    tinymix 'ADC input gain' 3 2>/dev/null
    tinymix 'MIC1 boost amplifier gain' 4 2>/dev/null
    tinymix 'MIC2 boost amplifier gain' 4 2>/dev/null
    tinymix 'AIF1 ADC timeslot 0 volume' 190 2>/dev/null
    tinymix 'AIF1 ADC timeslot 1 volume' 190 2>/dev/null
}

svc_alive() {
    dumpsys activity services com.lm.xiaozhi 2>/dev/null | $BB grep -q "com.lm.xiaozhi/.XzService"
}

start_svc() {
    am startservice -n com.lm.xiaozhi/.XzService -a com.lm.xiaozhi.WAKE >/dev/null 2>&1
    $BB sleep 1
    if svc_alive; then
        log "WakeService 已启动"
        return 0
    fi
    log "WakeService 启动失败"
    return 1
}

is_guard_alive() {
    [ -f $PIDF ] || return 1
    P=$($BB cat $PIDF 2>/dev/null)
    [ -n "$P" ] && [ -d /proc/$P ] || return 1
    $BB cat /proc/$P/cmdline 2>/dev/null | $BB grep -q xz_guard
}

case "$1" in
    once)
        fix_route
        start_svc
        ;;
    start)
        if is_guard_alive; then
            echo "守护已在运行 pid=$($BB cat $PIDF)"
            exit 0
        fi
        fix_route
        $BB setsid sh $D/xz_guard.sh loop >> $LOG 2>&1 < /dev/null &
        $BB sleep 2
        if is_guard_alive; then
            echo "守护已启动 pid=$($BB cat $PIDF)"
        else
            echo "守护启动失败"
        fi
        ;;
    loop)
        echo $$ > $PIDF
        log "===== xz_guard 启动 pid=$$ ====="
        N=0
        while true; do
            N=$((N + 1))
            if ! svc_alive; then
                log "第 $N 轮：服务不在，重拉"
                fix_route
                start_svc
            else
                # 服务活着但麦克风被别人拿着（vulture 会议 App 被拉回来就会抢）
                OWNER=$($BB cat /proc/asound/card0/pcm0c/sub0/status 2>/dev/null | $BB grep owner_pid | $BB awk '{print $3}')
                if [ -n "$OWNER" ] && [ -d /proc/$OWNER ]; then
                    CL=$($BB cat /proc/$OWNER/cmdline 2>/dev/null | $BB tr '\0' ' ')
                    case "$CL" in
                        *xiaozhi*) : ;;
                        *)
                            log "第 $N 轮：麦克风被 [$CL] 占用，force-stop vulture 并重启小智"
                            am force-stop vulture.app.home >/dev/null 2>&1
                            $BB sleep 1
                            am force-stop com.lm.xiaozhi >/dev/null 2>&1
                            $BB sleep 1
                            fix_route
                            start_svc
                            ;;
                    esac
                fi
            fi
            $BB sleep $ROUND
        done
        ;;
    stop)
        if is_guard_alive; then
            kill $($BB cat $PIDF) 2>/dev/null
        fi
        $BB rm -f $PIDF
        am force-stop com.lm.xiaozhi >/dev/null 2>&1
        echo "已停止"
        ;;
    status)
        if is_guard_alive; then
            echo "守护: running pid=$($BB cat $PIDF)"
        else
            echo "守护: not running"
        fi
        if svc_alive; then echo "服务: running"; else echo "服务: not running"; fi
        if $BB pgrep -f vulture.app.home >/dev/null 2>&1; then
            echo "会议App: 在跑（电源键→HOME 靠它，不能杀）"
        else
            echo "会议App: 未运行"
        fi
        echo "--- 日志尾 ---"
        $BB tail -n 6 $LOG 2>/dev/null
        ;;
    *)
        echo "用法: sh $0 {start|stop|status|once|loop}"
        ;;
esac
