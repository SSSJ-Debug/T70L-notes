#!/system/bin/sh
# cam_guard.sh v2 —— 看家要相机时，让原厂 App（vulture.app.home）让出摄像头
# 2026-11-08
#
# 背景：Android 4.4 全局只允许一个相机客户端，先到先得。
#       vulture.app.home 开机后就把前摄抱着不放（实测 reboot 后 9 分钟仍在手里），
#       普通应用没有 root（su 返回 1），只能借本脚本（root 身份）让它让出。
#
# v1 的问题（2026-11-08 现场）：
#   ① 2026-11-07 因为"force-stop 会让电话 widget 消失、呼吸灯常亮"被整段撤掉，
#      于是开机后没人踢 vulture → 看家永远卡在「正在接管 / 暂无画面」。
#   ② v1 每 2 秒才轮询一次，看家那边原来 2.5 秒才重试一次，
#      中间有 4~6 秒空窗，vulture 重启回来又把相机抢走了。
#
# v2 的正确姿势（和 mic_owner 同一套已验证逻辑）：
#   ① 请求文件出现 → 立刻 force-stop vulture（有 6 秒冷却，不刷屏）
#   ② 看家每 400ms 重试 Camera.open，1 秒内把相机抢到手里
#   ③ 「确认看家已经拿着相机」之后，才 am startservice 把 PeripheralService 拉回来
#      （电话 widget provider + 电源键呼吸灯在里面；顺序反了会被它把相机又抢走）
#
# 触发：看家写入 /sdcard/HomeGuard/.need_cam
# 用法: sh cam_guard.sh start|stop|status|once

BB=/sbin/busybox
PKG=vulture.app.home
VSVC=$PKG/vulture.service.PeripheralService
GPKG=com.lm.guard
REQ=/sdcard/HomeGuard/.need_cam
SOFF=/sdcard/HomeGuard/.need_screenoff
PIDF=/data/local/tmp/cam_guard.pid
LOG=/data/local/tmp/cam_guard.log
RESTORE=/data/local/tmp/.cam_need_restore
BEAT=/data/local/tmp/cam_guard.beat

# 用系统 date：busybox date 按 UTC 走，日志差 8 小时
log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> $LOG; }

# ★ 2026-11-08 修：不能只 kill -0。
#   重启后 /data/local/tmp/cam_guard.pid 里还是上一轮的旧 pid，
#   而新系统把这个 pid 分给了别的进程（实测 991 = com.ainemo.speech.app）→
#   脚本以为"我还在跑"，于是永远不重启，看家就永远「正在接管」。
#   改用心跳文件：loop 每轮写一次时间戳，超过 20 秒没更新就认为已经死了。
is_running() {
    [ -f "$PIDF" ] || return 1
    P=$($BB cat $PIDF 2>/dev/null)
    [ -n "$P" ] || return 1
    $BB kill -0 $P 2>/dev/null || { $BB rm -f $PIDF $BEAT; return 1; }
    [ -f "$BEAT" ] || return 1
    B=$($BB cat $BEAT 2>/dev/null)
    [ -n "$B" ] || return 1
    N=$(date +%s)
    [ $((N - B)) -le 20 ] || { $BB rm -f $PIDF $BEAT; return 1; }
    return 0
}

# ★★ 这台机器对 SIGPIPE 零容忍（NemoWatchdog：同一条命令 180 秒崩 5 次直接重启）。
#   所以任何 "cmd | grep -q" / "cmd | head -1" 都不能写 —— 消费端提前退出，
#   上游进程写管道时吃 SIGPIPE，就被记成 "grep crash / sh crash / ps crash"。
#   统一姿势：先重定向到文件，再让 awk 读文件（awk 从文件读，不存在提前关管道）。
PSF=/data/local/tmp/.cam_ps.txt
CAMF=/data/local/tmp/.cam_dump.txt

g_pid() { ps > $PSF 2>/dev/null; $BB awk -v k="$GPKG" '$0 ~ k && $0 !~ /awk|grep/ {print $2; exit}' $PSF; }
v_pid() { ps > $PSF 2>/dev/null; $BB awk -v k="$PKG" '$0 ~ k && $0 !~ /awk|grep/ {print $2; exit}' $PSF; }

# 当前相机客户端 pid；空 = 没人占
cam_pid() {
    dumpsys media.camera > $CAMF 2>/dev/null
    $BB awk '/PID:/ {print $NF; exit}' $CAMF
}

# 看家是否真的拿着相机
guard_has_cam() {
    C=$(cam_pid)
    G=$(g_pid)
    [ -n "$C" ] && [ -n "$G" ] && [ "$C" = "$G" ]
}

handle() {
    # ── 息屏请求：看家点「息屏值守」后写这个文件，由这里代按电源键 ──
    # 普通应用没有 DEVICE_POWER 权限，按不了电源键；root 身份可以注入 KEYCODE_POWER
    if [ -f "$SOFF" ]; then
        $BB rm -f "$SOFF"
        PWS=$(dumpsys power 2>/dev/null)
        case "$PWS" in *mScreenOn=true*) PWS_ON=1 ;; *) PWS_ON=0 ;; esac
        if [ "$PWS_ON" = "1" ]; then
            OUT=$(/system/bin/input keyevent 26 2>&1)
            log "screen off -> keyevent 26 rc=$? out=[$OUT]"
            # 兜底：有东西（Toast 的 FLAG_KEEP_SCREEN_ON 之类）挡住时第一次按不掉，
            # 2 秒后还在亮就再按一次
            $BB sleep 2
            PWS=$(dumpsys power 2>/dev/null)
        case "$PWS" in *mScreenOn=true*) PWS_ON=1 ;; *) PWS_ON=0 ;; esac
        if [ "$PWS_ON" = "1" ]; then
                OUT=$(/system/bin/input keyevent 26 2>&1)
                log "screen still on -> retry keyevent 26 rc=$? out=[$OUT]"
            fi
        else
            log "screen off requested but already off"
        fi
    fi

    # ── 相机请求 ──
    if [ -f "$REQ" ]; then
        $BB rm -f "$REQ"
        if guard_has_cam; then
            log "need_cam 但看家已持有相机，跳过"
        else
            NOW=$(date +%s)
            LAST=$($BB cat /data/local/tmp/.cam_last_kill 2>/dev/null)
            [ -z "$LAST" ] && LAST=0
            CP=$(cam_pid)
            if [ -z "$CP" ]; then
                # 相机空着（原厂没抢）→ 谁也别杀，看家下一次重试就能拿到
                log "need_cam 但相机空闲，等看家自己打开"
            elif [ $((NOW - LAST)) -ge 6 ]; then
                V=$(v_pid)
                am force-stop $PKG >/dev/null 2>&1
                echo $NOW > /data/local/tmp/.cam_last_kill
                echo $NOW > $RESTORE
                log "need_cam -> force-stop $PKG (pid=$V, cam=[$CP])"
            else
                log "need_cam 冷却中（$((NOW - LAST))s < 6s），等看家重试"
            fi
        fi
    fi

    # ── 看家已经拿到相机 → 再把原厂 PeripheralService 拉回来 ──
    # 顺序很重要：先让它死透、让看家占住相机，再拉起服务恢复 widget/呼吸灯，
    # 反过来的话它一起来就把相机抢回去（v1 时代的"正在接管"就是这么来的）。
    if [ -f "$RESTORE" ]; then
        RS=$($BB cat $RESTORE 2>/dev/null)
        [ -z "$RS" ] && RS=0
        NOW2=$(date +%s)
        if guard_has_cam; then
            am startservice -n $VSVC -a nemo.intent.peripheralservice.start >/dev/null 2>&1
            $BB rm -f $RESTORE
            log "看家已持相机 -> 恢复 $VSVC"
        elif [ $((NOW2 - RS)) -ge 15 ]; then
            # 兜底：15 秒还没把相机交给看家（比如用户中途退出了），
            # 也必须把原厂外设服务拉回来，不能把电话 widget 和呼吸灯丢在那。
            am startservice -n $VSVC -a nemo.intent.peripheralservice.start >/dev/null 2>&1
            $BB rm -f $RESTORE
            log "WARN 15s 未拿到相机 -> 兜底恢复 $VSVC"
        fi
    fi

}

case "$1" in
    ""|start)
        if is_running; then
            echo "already running pid=$($BB cat $PIDF)"
            exit 0
        fi
        $BB rm -f $PIDF $BEAT
        $BB setsid sh /data/local/tmp/cam_guard.sh loop >/dev/null 2>&1 </dev/null &
        $BB sleep 2
        echo "started pid=$($BB cat $PIDF 2>/dev/null)"
        ;;
    loop)
        echo $$ > $PIDF
        echo $(date +%s) > $BEAT
        log "loop start pid=$$"
        # ★ 2026-11-08 降耗：原来是死循环每 0.4 秒跑一轮，不管有没有事都要
        #   fork 一个 date，还要 fork 一个 wc 去数日志行 —— 1GB 机器上白烧 CPU。
        #   改成分档：
        #     空闲（没人要看家）→ 1 秒一轮，只做 3 个 [ -f ] 内建判断
        #     热态（收到请求后 10 秒内）→ 0.5 秒一轮，保证接管够快
        #   日志裁剪从"每轮"改成"每到 300 行才裁一次"，省掉每轮的 fork。
        IDLE_US=1000000
        HOT_US=500000
        HOT_UNTIL=0
        LINEBUF=0
        while true; do
            # 请求文件出现 → 进入热态
            if [ -f "$REQ" ] || [ -f "$SOFF" ] || [ -f "$RESTORE" ]; then
                HOT_UNTIL=$(( $(date +%s) + 10 ))
            fi
            handle
            NOWT=$(date +%s)
            echo $NOWT > $BEAT
            # 日志裁剪：先看字节数（不用 fork wc），超过 24KB 才真裁
            SZ=$($BB stat -c %s $LOG 2>/dev/null)
            [ -z "$SZ" ] && SZ=0
            if [ "$SZ" -gt 24576 ]; then
                $BB tail -150 $LOG > $LOG.tmp 2>/dev/null
                $BB mv $LOG.tmp $LOG
            fi
            if [ "$NOWT" -le "$HOT_UNTIL" ]; then
                $BB usleep $HOT_US
            else
                $BB usleep $IDLE_US
            fi
        done
        ;;
    stop)
        if is_running; then
            $BB kill $($BB cat $PIDF) 2>/dev/null
            log "stopped"
        fi
        $BB rm -f $PIDF
        echo stopped
        ;;
    check)
        # 只回退出码：给 mic_keeper 保活用，不输出任何东西
        is_running
        ;;
    once)
        handle
        echo ok
        ;;
    status)
        if is_running; then echo "守护   : running pid=$($BB cat $PIDF)"; else echo "守护   : NOT running"; fi
        echo "看家pid: $(g_pid)"
        echo "原厂pid: $(v_pid)"
        echo "相机   : $(cam_pid)"
        guard_has_cam && echo "归属   : 看家持有（正常）" || echo "归属   : 不在看家手里"
        $BB tail -n 6 $LOG 2>/dev/null
        ;;
    *)
        echo "usage: cam_guard.sh {start|stop|status|once}"
        exit 2
        ;;
esac
