#!/system/bin/sh
# vulture_nice.sh —— 压住 vulture 系进程的 CPU 抢占（2026-11-07 19:20）
#
# 背景（本轮实测定位）：
#   mic_owner.sh 会 force-stop vulture.app.home 抢麦，然后只单独拉起
#   PeripheralService。但这个进程起来之后，里面的 CallSdk 三条流水线全都醒着：
#     - AudioRecord openInput() 失败重试死循环（麦已经被小智占了）
#     - 摄像头移动侦测 OMX 编码，稳定 29fps 常驻
#   top 里看着只有 2%，但它的相关线程 nice=-16（音频优先级），
#   和我们的 Opus 编码线程（xz-sender）抢 CPU 时永远是它赢，
#   结果就是编码被拖到 76ms/帧，发送队列积压，收尾白等 1.5 秒。
#
#   试过的错路（别重复）：
#     - pm disable / force-stop ：电话 widget + 环形灯 + GOHOME 全丢，只能恢复出厂
#   正确的路：不动它，只把它的调度优先级压到最低。它照样能跑，
#   但 CPU 紧张时永远是最后被调度的那个。
#
# 用法:
#   sh vulture_nice.sh apply     压一次（立刻生效）
#   sh vulture_nice.sh start     压 + 起守护（每 5 秒复查，PID 变了自动重压）
#   sh vulture_nice.sh stop      还原 nice 并停守护
#   sh vulture_nice.sh status    看当前优先级分布

BB=/sbin/busybox
D=/data/local/tmp
LOG=$D/vulture_nice.log
PIDF=$D/vulture_nice.pid
ROUND=5

VNICE=19        # vulture 系 → 最低
XNICE=-10       # 小智 → 抬高（root 才有权限设负值）

log() { echo "$($BB date '+%m-%d %H:%M:%S') $*" >> $LOG; _caplog; }

# ★ 写盘红线：日志必须封顶。2026-11-23 事故：守护日志无上限 + 崩溃转储 = /data 写满卡开机
_capn=0
_caplog() {
    _capn=$((_capn + 1))
    [ $((_capn % 50)) -ne 0 ] && return 0
    _s=$($BB wc -c < "$LOG" 2>/dev/null)
    [ -z "$_s" ] && return 0
    [ "$_s" -gt 262144 ] || return 0
    $BB tail -c 131072 "$LOG" > "$LOG.t" 2>/dev/null && $BB mv -f "$LOG.t" "$LOG"
    return 0
}

v_pids() { ps | $BB grep "vulture\." | $BB grep -v grep | $BB awk '{print $2}'; }
x_pid()  { ps | $BB grep "com.lm.xiaozhi" | $BB grep -v grep | $BB awk '{print $2}' | $BB head -n 1; }

# nice 是 per-thread 的：renice 一个进程只会改主线程，
# 那些 -16 的音频线程必须逐个 task 改。
nice_tasks() {
    P=$1; N=$2
    for T in $($BB ls /proc/$P/task 2>/dev/null); do
        $BB renice $N -p $T >/dev/null 2>&1
    done
}

nice_of() {  # $1=pid $2=tid → 当前 nice
    # ⚠ 不能直接 awk '{print $19}'：线程名里带空格时（"Signal Catcher"）
    #   括号会把字段数整体错开一位，读出来是 priority(39) 而不是 nice(19)。
    #   正确做法是先跳过 ")" 再数，nice 是跳过后的第 17 个字段。
    $BB cat /proc/$1/task/$2/stat 2>/dev/null | $BB awk '{
        if (match($0, /\) /)) { s = substr($0, RSTART + 2); split(s, a, " "); print a[17] }
    }'
}

apply() {
    CNT=0
    for P in $(v_pids); do
        nice_tasks $P $VNICE
        CNT=$((CNT + 1))
    done
    X=$(x_pid)
    [ -n "$X" ] && nice_tasks $X $XNICE
    echo $CNT
}

status() {
    echo "--- vulture 系 ---"
    for P in $(v_pids); do
        NM=$(ps | $BB grep " $P " | $BB grep -v grep | $BB awk '{print $NF}' | $BB head -n 1)
        [ -z "$NM" ] && NM="pid $P"
        H=""
        for T in $($BB ls /proc/$P/task 2>/dev/null); do
            H="$H$(nice_of $P $T) "
        done
        echo "$NM ($P): nice = $($BB echo $H | $BB tr ' ' '\n' | $BB sort -un | $BB tr '\n' ' ')"
    done
    echo "--- 小智 ---"
    X=$(x_pid)
    if [ -n "$X" ]; then
        H=""
        for T in $($BB ls /proc/$X/task 2>/dev/null); do
            H="$H$(nice_of $X $T) "
        done
        echo "com.lm.xiaozhi ($X): nice = $($BB echo $H | $BB tr ' ' '\n' | $BB sort -un | $BB tr '\n' ' ')"
    else
        echo "com.lm.xiaozhi: 未运行"
    fi
}

alive() {
    [ -f $PIDF ] || return 1
    P=$($BB cat $PIDF 2>/dev/null)
    [ -n "$P" ] && [ -d /proc/$P ] || return 1
    $BB cat /proc/$P/cmdline 2>/dev/null | $BB grep -q vulture_nice
}

case "$1" in
    apply)
        N=$(apply)
        echo "已压：vulture 进程 $N 个 → nice $VNICE，小智 → nice $XNICE"
        ;;
    start)
        if alive; then
            echo "守护已在运行 pid=$($BB cat $PIDF)"
            exit 0
        fi
        apply >/dev/null
        $BB setsid sh $D/vulture_nice.sh loop >> $LOG 2>&1 < /dev/null &
        $BB sleep 1
        echo "已压 + 守护启动 pid=$($BB cat $PIDF 2>/dev/null)"
        ;;
    loop)
        echo $$ > $PIDF
        log "===== vulture_nice 启动 pid=$$ ====="
        while true; do
            # vulture 被重启会换 PID 并恢复默认 nice，所以每轮无脑重压一遍。
            # 已经是 19 的再 renice 是幂等的，开销可忽略。
            apply >/dev/null
            $BB sleep $ROUND
        done
        ;;
    stop)
        alive && kill $($BB cat $PIDF) 2>/dev/null
        $BB rm -f $PIDF
        for P in $(v_pids); do nice_tasks $P 0; done
        X=$(x_pid); [ -n "$X" ] && nice_tasks $X 0
        echo "已还原 nice=0 并停守护"
        ;;
    status)
        status
        if alive; then echo "守护: running pid=$($BB cat $PIDF)"; else echo "守护: not running"; fi
        ;;
    *)
        echo "用法: sh $0 {apply|start|stop|status}"
        ;;
esac
