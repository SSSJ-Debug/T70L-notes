#!/system/bin/sh
# mic_owner.sh —— 让"小智"拿到麦克风，同时保住电话 widget 和电源键环形灯
# 2026-11-07 12:40
#
# 背景（血泪）：
#   Android 4.4 全局只允许一个录音客户端，先到先得。
#   vulture.app.home 开机时先起，一启动就开 AudioCapture → 麦被它占死，
#   小智这边 AudioRecord.start() 直接 status -38，日志刷
#   "麦克风 2.5 秒没数据，重开录音"，喊它毫无反应。
#
#   以前的解法是 pm disable / 反复 force-stop vulture.app.home，结果：
#     - 桌面「电话」widget 消失（它就是这个 widget 的 provider）
#     - 电源键环形呼吸灯常亮（BreathLight 状态机在它的 PeripheralService 里）
#     - 恢复出厂才能好
#   2026-11-07 用户报的三个问题全部由此而来。
#
# 本脚本的正确姿势：
#   ① 只 force-stop 一次，目的是让 vulture 交出麦克风；
#   ② 等小智真正开始读数据（不是"进程在"就行，要看 AudioRecord 没报错）；
#   ③ 立刻用 am startservice 单独拉起 PeripheralService，
#      进程活了 → widget provider 和呼吸灯都恢复，
#      但它此时抢不到麦（小智已占），会议录音功能失效，其余全正常。
#
# 用法: sh mic_owner.sh          跑一遍
#       sh mic_owner.sh check    只看状态不动作

BB=/sbin/busybox
D=/data/local/tmp
LOG=$D/mic_owner.log
VPKG=vulture.app.home
XPKG=com.lm.xiaozhi
VSVC=$VPKG/vulture.service.PeripheralService

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

# ★ 麦克风增益（2026-11-07 13:10 实测确定）
#   这是一台"增益窗口极窄"的设备，实测三档：
#     176/3 : 待命 rms=11，说话 rms<900，根本唤不醒（太弱）
#     220/4 : 说话 rms 恒定 25000~30000，静音段不掉，全帧削波，
#             VAD 判不出句尾，每句必录满 2.4 秒（太强）
#     190/3 : 待命 174、开口 1701、静音 1300，动态范围正常，
#             VAD 3 帧收尾，实测开口→发送仅 938ms   ← 用这档
fix_mic_gain() {
    tinymix 9 190  2>/dev/null   # ADC volume
    tinymix 13 3   2>/dev/null   # ADC input gain
    tinymix 0 176  2>/dev/null   # AIF1 ADC timeslot 0 volume
    tinymix 1 176  2>/dev/null   # AIF1 ADC timeslot 1 volume
    tinymix 14 4   2>/dev/null   # MIC1 boost amplifier gain
    tinymix 15 4   2>/dev/null   # MIC2 boost amplifier gain
    log "麦克风增益已设为 190/3（实测最佳档）"
}

# 当前持有 Input track 的 pid 列表
mic_pids() {
    dumpsys media.audio_flinger 2>/dev/null | $BB awk '
        /^Input thread/ { s = ($0 ~ /tracks/); h = 0; next }
        s && /^Client Fmt/ { h = 1; next }
        s && h && /^ +[0-9]+ / { print $1; next }
    ' | $BB sort -u
}

x_pid() { ps | $BB grep "$XPKG" | $BB grep -v grep | $BB awk '{print $2}' | $BB head -n 1; }
v_pid() { ps | $BB grep "$VPKG" | $BB grep -v grep | $BB awk '{print $2}' | $BB head -n 1; }

# 小智是否真的在录音（有 track 且近期没有 AudioRecord 错误）
x_recording() {
    X=$(x_pid)
    [ -z "$X" ] && return 1
    mic_pids | $BB grep -q "^$X$" || return 1
    # 最近 3 秒内有 AudioRecord 报错就算没抢到
    ERR=$(logcat -d -t 400 2>/dev/null | $BB grep -c "AudioRecord.*$X")
    [ "$ERR" -gt 3 ] && return 1
    return 0
}

show() {
    log "小智=$(x_pid)  vulture=$(v_pid)  录音客户端=[$(mic_pids | $BB tr '\n' ' ')]"
}

case "$1" in
    check)
        show
        x_recording && echo "小智：正在录音" || echo "小智：没拿到麦"
        ;;
    *)
        log "===== mic_owner 开始 ====="
        fix_mic_gain
        # 先等 vulture 真正起来（它开机自启，但比 boot_completed 晚几秒）
        n=0
        while [ -z "$(v_pid)" ] && [ $n -lt 10 ]; do
            $BB sleep 2
            n=$((n + 1))
        done
        [ -n "$(v_pid)" ] && log "vulture 已启动（$(v_pid)），等 $((n * 2))s"

        # 小智没跑就先拉起（它是语音助手，靠 XzService 常驻）
        if [ -z "$(x_pid)" ]; then
            log "小智没运行 → 拉起 XzService"
            am startservice -n $XPKG/.XzService -a $XPKG.WAKE >/dev/null 2>&1
            $BB sleep 4
        fi
        if x_recording; then
            log "小智已经在录音，只需确认 vulture 外设服务在跑"
        else
            log "小智没麦 → force-stop $VPKG 让它交出录音"
            am force-stop $VPKG >/dev/null 2>&1
            $BB sleep 3
            n=0
            while [ $n -lt 10 ]; do
                if x_recording; then
                    log "第 $((n + 1)) 次确认：小智已拿到麦"
                    break
                fi
                $BB sleep 2
                n=$((n + 1))
            done
            [ $n -ge 10 ] && log "WARN 20 秒内小智仍没麦，仍继续拉起外设服务"
        fi

        # 拉起外设服务：恢复电话 widget provider + 呼吸灯状态机
        if [ -z "$(v_pid)" ]; then
            log "拉起 PeripheralService"
            am startservice -n $VSVC >/dev/null 2>&1
            $BB sleep 4
        else
            log "vulture 进程已在（$(v_pid)），不重复拉起"
        fi

        # 兜底：如果还起不来，用 monkey 拉主界面再退回桌面
        if [ -z "$(v_pid)" ]; then
            log "WARN PeripheralService 未起来，用 monkey 兜底"
            monkey -p $VPKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
            $BB sleep 3
            am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
        fi

        show
        log "===== mic_owner 结束 ====="
        ;;
esac
