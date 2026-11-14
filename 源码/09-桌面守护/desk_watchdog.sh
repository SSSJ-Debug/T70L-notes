#!/system/bin/sh
# desk_watchdog.sh —— 桌面布局守护的保活（2026-11-06）
#
# 为什么需要它（2026-11-06 网线拔插事故的根因）：
#   launcher_guard.sh 本身健壮（轻量修复 + 整表兜底 + 冷却），但它【没有外部保活】。
#   网线拔掉 → vulture.network_service 断连 → vulture.app.home 退出 →
#   vulture.watchdog 触发 nemo.intent.update.RESTART → com.xylink.launcher 重置成默认布局。
#   本该由 launcher_guard 10 秒内修回来，但它自己已经被 vulture 的拉锯战搞死（或低内存被杀），
#   于是桌面就一直停在默认布局（电话/日程/消息中心…），用户以为"软件全没了"。
#   其实软件都在（pm list packages -3 一条没少），只是桌面没显示。
#
# 本脚本职责单一：每 30 秒确认 launcher_guard.sh 还活着，死了就重启它。
# 顺带兜底：发现布局偏离时，直接调 launcher_guard check 立即修（不等下一轮）。
#
# 安全边界（沿用血泪教训）：
#   ⛔ 绝不 kill com.xylink.launcher（HOME 应用，杀了必被秒拉起→重建→循环→安全模式）
#   ⛔ 本脚本不直接改库，一切修复动作都委托 launcher_guard.sh
#
# 用法: sh desk_watchdog.sh start|stop|status|loop

BB=/sbin/busybox
D=/data/local/tmp
LG=$D/launcher_guard.sh
LOG=$D/desk_watchdog.log
PIDF=$D/desk_watchdog.pid

log() { echo "$($BB date '+%m-%d %H:%M:%S') $*" >> $LOG; }

# 和 launcher_guard.sh 里 loop_pids 同款逻辑：靠 /proc/PID/cmdline 判断，不用 ps grep（4.4 截断坑）
loop_pids() {
    for d in /proc/[0-9]*; do
        p=${d#/proc/}
        [ -r "$d/cmdline" ] || continue
        c=$($BB tr -d '\0' < "$d/cmdline" 2>/dev/null)
        case "$c" in
            *launcher_guard.sh*loop) echo "$p" ;;
        esac
    done
}

lg_alive() { [ -n "$(loop_pids)" ]; }

restart_lg() {
    log "launcher_guard 不在，重启它"
    sh $LG stop >/dev/null 2>&1
    $BB sleep 1
    sh $LG start >/dev/null 2>&1
    $BB sleep 3
    if lg_alive; then
        log "launcher_guard 已拉起 pid=$(loop_pids | $BB tr '\n' ' ')"
        return 0
    else
        log "WARN launcher_guard 拉起失败，下轮再试"
        return 1
    fi
}

do_loop() {
    log "desk_watchdog 启动 pid=$$"
    cnt=0
    while true; do
        # ① 保活：launcher_guard 死了就拉起
        if ! lg_alive; then
            restart_lg
        fi

        # ② 兜底：每 3 轮（约 90 秒）主动做一次布局体检，偏离立即修
        cnt=$((cnt + 1))
        if [ $cnt -ge 3 ]; then
            cnt=0
            R=$(sh $LG check 2>&1)
            case "$R" in
                OK*) : ;;
                FIXED*) log "布局体检：$R" ;;
                *) log "布局体检异常：$R" ;;
            esac
        fi

        $BB sleep 30
    done
}

case "$1" in
    loop)
        echo $$ > $PIDF
        do_loop
        ;;
    stop)
        for p in $(for d in /proc/[0-9]*; do p=${d#/proc/}; [ -r "$d/cmdline" ] || continue; c=$($BB tr -d '\0' < "$d/cmdline" 2>/dev/null); case "$c" in *desk_watchdog.sh*loop) echo "$p";; esac; done); do
            kill -9 $p 2>/dev/null
        done
        rm -f $PIDF
        echo "stopped"
        ;;
    status)
        P=$(cat $PIDF 2>/dev/null)
        if [ -n "$P" ] && $BB kill -0 $P 2>/dev/null; then
            echo "running pid=$P"
        else
            echo "NOT running"
        fi
        echo "launcher_guard: $(if lg_alive; then echo alive; else echo DEAD; fi)"
        ;;
    ""|start)
        # 先确认自己没重复跑
        for d in /proc/[0-9]*; do
            p=${d#/proc/}; [ -r "$d/cmdline" ] || continue
            c=$($BB tr -d '\0' < "$d/cmdline" 2>/dev/null)
            case "$c" in
                *desk_watchdog.sh*loop) echo "already running pid=$p"; exit 0 ;;
            esac
        done
        rm -f $PIDF
        $BB setsid sh $D/desk_watchdog.sh loop >/dev/null 2>&1 </dev/null &
        $BB sleep 2
        echo "started pid=$(cat $PIDF 2>/dev/null)"
        ;;
    *)
        echo "usage: $0 start|stop|status|loop"
        exit 2
        ;;
esac
