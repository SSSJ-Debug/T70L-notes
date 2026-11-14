#!/system/bin/sh
# boot_svc.sh —— T70L 开机服务启动
# ★ 2026-11-07 12:20 回滚版（用户指令：今天的改动全部撤销，只保留电源键回桌面逻辑）
#
# 本版相比今天早些时候的版本，去掉了三样东西：
#   ① ensure_mic（会在开机时 force-stop vulture.app.home 抢麦克风）
#   ② mic_guard.sh（会周期性 force-stop vulture.app.home）
#   ③ cam_guard.sh（打开看家时 force-stop vulture.app.home）
#      ↑ 2026-11-08 已恢复（v2）：看家用不了相机的根因就是它被撤了，
#        且 v2 会在看家持相机后立刻把 PeripheralService 拉回来，不再丢 widget/灯。
#
# 原因（2026-11-07 查明）：
#   vulture.app.home 不只是"会议 App"，它同时承担三件事：
#     - 桌面「电话」widget 的提供者（vulture.appwidget.TelephoneAppwidget）
#     - com.nemo.action.GOHOME 广播的原厂发送者
#     - 电源键环形呼吸灯的状态维护（BreathLight 状态机，经 driverservice.breath_light）
#   只要它被禁用 / 反复 force-stop，就会出现：
#     电话图标消失、电源键回不了桌面、环形灯常亮不退。
#   所以本版【绝不再动 vulture.app.home】。
#
# 电源键回桌面逻辑仍然保留：homekey_guard.sh + com.lm.gohome 接收器，
# 与 vulture.app.home 死活无关，双保险。

BB=/sbin/busybox
D=/data/local/tmp
LOG=$D/boot_svc.log
GW=$D/t70gw2.sh

log() { echo "$($BB date '+%m-%d %H:%M:%S') $*" >> $LOG; }

uptime_s() { $BB cut -d' ' -f1 /proc/uptime 2>/dev/null; }

wait_boot() {
    n=0
    while [ "$(getprop sys.boot_completed)" != "1" ]; do
        n=$((n + 1))
        if [ $n -ge 120 ]; then
            log "WARN boot_completed 等 360s 超时，继续"
            return 1
        fi
        $BB sleep 3
    done
    log "boot_completed 就绪（${n}x3s）"
    return 0
}

start_daemons() {
    sh $D/gateway_guard.sh start >/dev/null 2>&1
    log "服务守护 pid=$($BB cat $D/gateway_guard.pid 2>/dev/null)"

    # ★ 2026-11-07 12:30 起，homekey_guard 不再启动。
    #   原厂 vulture.app.home 恢复后，它自己就是 com.nemo.action.GOHOME 的发送者，
    #   电源键逻辑完全正常（实测：软件内按一下回桌面不息屏；桌面按一下息屏）。
    #   而 homekey_guard 每 5 秒强制补发同一个广播，与原厂重复，
    #   两者抢同一个 isHome 状态 → 按电源键时设备直接重启（12:20 实测复现）。
    #   结论：谁也别自己发，交给原厂。

    # ★ 麦克风归属：让小智拿到麦，同时把 vulture 的外设服务单独拉起来
    #   （电话 widget 的 provider + 电源键环形呼吸灯都在它的 PeripheralService 里）
    #   顺序不能反：先让 vulture 交出麦，再单独 startservice，它此时抢不到麦。
    sh $D/mic_owner.sh >/dev/null 2>&1
    log "麦克风归属处理完毕：$(sh $D/mic_owner.sh check 2>&1 | $BB tr '\n' ' ')"

    # ★ 2026-11-08 麦克风常驻守护：mic_owner 只在开机抢一次，
    #   之后任何一次失手（小智被杀/vulture 抢回）就是永久聋，必须有常驻补漏。
    sh $D/mic_keeper.sh start >/dev/null 2>&1
    log "麦克风守护 pid=$($BB cat $D/mic_keeper.pid 2>/dev/null)"

    # ★★ 2026-11-08 18:05 小智自身守护 v3（这一层是今天补的，必须有）★★
    #   起因：14:45~17:12 小智进程活着、线程还在，但主循环卡在
    #   AudioRecord.read() 的 futex 上，一个字节拿不到，日志一条不写。
    #   用户喊了一下午"小智放歌"一次没成 —— 而当时的守护只看进程在不在，
    #   所以谁都没发现它聋了。
    #   v3 做两件事：① 每 30 秒把 DMIC 数字增益设回 190（实测会被 HAL 改回 176，
    #   一改远场就喊不动）；② 日志 150 秒不动就判定假死，强杀重拉。
    sh $D/xz_guard.sh start >/dev/null 2>&1
    log "小智守护 pid=$($BB cat $D/xz_guard.pid 2>/dev/null) 增益=$(tinymix 2>/dev/null | $BB awk '/AIF1 ADC timeslot 0 volume/{print $NF; exit}')"

    sh $D/lamp_icon_guard.sh start >/dev/null 2>&1
    log "图标守护 pid=$($BB cat $D/lamp_icon_guard.pid 2>/dev/null)"

    # 桌面条目守护（只走 ContentProvider 补条目，不 kill 桌面）
    sh $D/launcher_guard.sh start >/dev/null 2>&1
    log "桌面守护 pid=$($BB cat $D/launcher_guard.pid 2>/dev/null)"

    sh $D/desk_watchdog.sh start >/dev/null 2>&1
    log "桌面保活 pid=$($BB cat $D/desk_watchdog.pid 2>/dev/null)"

    # ★ 2026-11-08 恢复相机守护（v2）：看家点「开始看家」时，靠它 force-stop
    #   抱着相机的 vulture.app.home，并在看家拿到相机之后再把 PeripheralService
    #   拉回来（电话 widget + 呼吸灯）。
    #   11-07 撤掉它的后果：开机后原厂把前摄抱死，看家永远卡在「正在接管」。
    #   现在由 mic_keeper 每 15 秒顺带保活，脚本自己也不会再被"遗忘"。
    sh $D/cam_guard.sh start >/dev/null 2>&1
    log "相机守护 pid=$($BB cat $D/cam_guard.pid 2>/dev/null)"
}

case "$1" in
    desk)   sh $D/launcher_guard.sh force ;;
    *)
        log "===== boot_svc 开始（uptime=$(uptime_s)s，回滚版）====="
        wait_boot
        start_gateway
        start_daemons
        log "===== boot_svc 完成（uptime=$(uptime_s)s）====="
        ;;
esac
