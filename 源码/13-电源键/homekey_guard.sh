#!/system/bin/sh
# ###########################################################################
# ⚠️⚠️ 危险：这个脚本默认**不要启动**，仅供逆向参考 ⚠️⚠️
#
#   它会和原厂 vulture.app.home 抢 isHome 状态，实测后果：
#     · 按电源键直接重启（不是息屏）
#     · 崩溃循环 → 每 10 秒一条 2.9MB 崩溃转储 → /data 写满 → 卡开机第一屏
#
#   确实要试，务必先起磁盘守护（源码/17-磁盘守护/），并且准备好随时 kill。
#   红线详见 docs/00-总览/红线-磁盘绝不能写满.md
# ###########################################################################
# homekey_guard.sh —— T70L 电源键「在软件里=HOME」状态守护（2026-11-07）
#
# == 这个脚本解决什么问题 ==
# 解析 /system/framework/services.jar 得到厂商改过的 PowerManagerService：
#
#   private boolean isHome = true;          // 默认值就是 true！
#   private void goToSleepFromNative(long t, int r) {
#       if (cntPowerKey != -1) cntPowerKey++;
#       Slog.i(TAG, "gotosleepfromnative " + isHome + " cnt " + cntPowerKey);
#       if (!isHome) {
#           sendOrderedBroadcast(mGoHomeIntent);   // com.nemo.action.GOHOME → 回桌面
#           isHome = true;
#       } else {
#           goToSleepInternal(t, r);               // 真息屏
#       }
#   }
#   // 唯一的更新入口：
#   NemoHomeReceiver.onReceive -> isHome = intent.getBooleanExtra("isHome", true);
#
# 所以「在软件里按电源键回桌面」完全依赖【有谁给系统发
# com.nemo.action.HOME 广播，并在 extra 里带 isHome=false】。
# 原厂这个发送者是 vulture.app.home（会议 App）。
#
# 坑：本机自己的脚本会 force-stop vulture.app.home ——
#   mic_guard.sh  抢麦克风
#   boot_svc.sh   开机抢麦克风
#   cam_guard.sh  看家抢摄像头
# 一旦它被停，isHome 就永远停在 true，于是「在任何软件里按电源键都直接息屏」，
# 而且因为是 force-stop（stopped 状态），它自己也不会再自动起来。
# 这就是用户反复遇到的「电源键今天好使、明天又变息屏」。
#
# == 本脚本的做法 ==
# 监听 ActivityManager 的 START 日志（事件驱动，毫秒级），自己维护 isHome：
#   顶层是桌面（com.xylink.launcher / vulture.app.lanucher）→ 发 isHome=true
#   顶层是任何其它 App                                  → 发 isHome=false
# 只在状态变化时发一次，另有 20 秒兜底对账。
# 完全绕开 vulture.app.home，它被杀多少次都不影响电源键。
#
# 用法: sh homekey_guard.sh start|stop|status|once
#
# 安全边界：
#   - 只发广播，不杀进程、不改系统文件、不注入按键事件。
#   - 广播无权限保护，任何身份可发；本脚本以 root 跑更稳。

BB=/sbin/busybox
D=/data/local/tmp
LOG=$D/homekey_guard.log
PIDF=$D/homekey_guard.pid
STF=$D/homekey_guard.state

HOME_ACTION=com.nemo.action.HOME
LAUNCHERS=" com.xylink.launcher vulture.app.lanucher com.android.launcher "

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

# 取当前顶层包名
top_pkg() {
    dumpsys activity activities 2>/dev/null \
        | $BB grep -m1 'mResumedActivity' \
        | $BB sed -n 's/.*u0 \([^ ]*\)\/.*/\1/p'
}

is_launcher() {
    case "$LAUNCHERS" in
        *" $1 "*) return 0 ;;
    esac
    return 1
}

# 发布状态（只在变化时真的发）
publish() {
    want=$1                      # true / false
    cur=$($BB cat $STF 2>/dev/null)
    [ "$cur" = "$want" ] && return 0
    am broadcast -a $HOME_ACTION --ez isHome $want >/dev/null 2>&1
    echo "$want" > $STF
    log "isHome -> $want  (top=$2)"
}

sync_by_dumpsys() {
    P=$(top_pkg)
    [ -z "$P" ] && return 1
    if is_launcher "$P"; then publish true "$P"; else publish false "$P"; fi
    return 0
}

# 从 events 缓冲区一行里抠包名
#   I/am_resume_activity(  471): [0,1109642664,2,com.xylink.launcher/.app.LauncherActivity]
# 取最后一段（组件全名），去掉结尾 ]，再去掉 /Activity
parse_cmp() {
    x=${1##*,}
    x=${x%]}
    x=${x%%/*}
    case "$x" in
        com.*) echo "$x" ;;
        *) return 1 ;;
    esac
}

run_loop() {
    echo $$ > $PIDF
    log "===== homekey_guard 启动 pid=$$ ====="
    sync_by_dumpsys

    last=$($BB date +%s)
    while true; do
        # ★ 关键：先把 events 缓冲区清空再跟流。
        #   4.4 的 logcat 不带 -d 时会先把整个历史缓冲区重放一遍，
        #   重放会让状态疯狂翻转（bili→launcher→douyin→launcher…），
        #   最后停在一条过期的记录上。清空后再用 dumpsys 对齐一次即可。
        logcat -b events -c >/dev/null 2>&1
        sync_by_dumpsys
        # ★ 必须用 events 缓冲区：4.4 的 logcat 一旦带 "-b main" 就会把过滤全部吞掉
        #   （实测 logcat -d -b main -s ActivityManager:I 输出 0 行 START）。
        #   events 缓冲区里的 am_resume_activity 每次 Activity 切前台都打一行，
        #   体积极小，正好当事件源。
        logcat -b events -s am_resume_activity 2>/dev/null | while read -r line; do
            case "$line" in
                *am_resume_activity*)
                    P=$(parse_cmp "$line")
                    [ -z "$P" ] && continue
                    if is_launcher "$P"; then publish true "$P"; else publish false "$P"; fi
                    now=$($BB date +%s)
                    if [ $((now - last)) -ge 20 ]; then
                        last=$now
                        sync_by_dumpsys
                    fi
                    ;;
            esac
        done
        # logcat 掉了就重来（低内存被杀、缓冲区切换等）
        log "logcat 退出，2 秒后重启"
        $BB sleep 2
        sync_by_dumpsys
        last=$($BB date +%s)
    done
}

# 扫 /proc 找所有实例（4.4 的 ps 只显示 comm="sh"，grep 抓不到，必须读 cmdline）
all_pids() {
    for d in /proc/[0-9]*; do
        p=${d#/proc/}
        [ -r "$d/cmdline" ] || continue
        c=$($BB tr -d '\0' < "$d/cmdline" 2>/dev/null)
        case "$c" in
            *homekey_guard.sh*loop) echo "$p" ;;
        esac
    done
}

is_running() { [ -n "$(all_pids)" ]; }

case "$1" in
    start)
        if is_running; then
            echo "already running pid=$(all_pids | $BB tr '\n' ' ')"
            exit 0
        fi
        rm -f $PIDF $STF
        $BB setsid sh $D/homekey_guard.sh loop >/dev/null 2>&1 </dev/null &
        $BB sleep 2
        if is_running; then echo "started pid=$(all_pids | $BB tr '\n' ' ')"; else echo "start failed"; fi
        ;;
    stop)
        # 全杀，避免历史遗留多实例同时跟 logcat、重复发广播
        for p in $(all_pids); do kill -9 $p 2>/dev/null; done
        rm -f $PIDF
        echo "stopped"
        ;;
    once)
        sync_by_dumpsys && echo "synced -> $($BB cat $STF 2>/dev/null)" || echo "sync failed"
        ;;
    status)
        if is_running; then
            echo "守护: running pid=$(all_pids | $BB tr '\n' ' ')"
        else
            echo "守护: NOT running"
        fi
        echo "顶层: $(top_pkg)"
        echo "isHome 缓存: $($BB cat $STF 2>/dev/null)"
        $BB tail -n 8 $LOG 2>/dev/null
        ;;
    loop)  run_loop ;;
    *) echo "usage: sh $0 start|stop|status|once|loop" ;;
esac
