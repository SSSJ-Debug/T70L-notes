#!/system/bin/sh
# xz_guard.sh —— 小智守护 v4（2026-11-08 17:35，教训版）
#
# ★★★ 血泪教训（v3 就是这么把整机搞重启的）★★★
#   v3 每 30 秒执行一次 tinymix 读增益，结果 17:30:39 整机重启，账本写着：
#       REBOOT_REASON : Sun Nov 08 17:30:39 2026 , tinymix crash more than 5 times in 180s
#   这台机的 NemoWatchdog 把"短命进程"一律记成 crash（历史上 ps crash / grep crash /
#   ls crash / sh crash 都是这么来的），180 秒内 5 次就 rebootSystem。
#   所以从 v4 起立一条铁律：
#   **本脚本绝不轮询任何外部命令**（tinymix / ps / grep / ls / date 全不碰），
#   只在**开机跑一次**（once），之后一概不管。
#
#   那"小智假死"谁管？—— App 内部自己管（XzService 里已经做了）：
#     · 主循环心跳 6 秒不动 → 拆录音+断连接强制唤醒
#     · 12 秒还不动 → 直接退出进程，由 mic_keeper.sh（15 秒巡检，已稳定运行两天）拉起
#   外部只保留"开机设一次增益 + 拉起服务"这一件事，越简单越不会害人。
#
# 用法：
#   sh /data/local/tmp/xz_guard.sh once      # 开机调一次（boot_svc.sh 用这个）
#   sh /data/local/tmp/xz_guard.sh status    # 手动看状态（人手执行，无所谓）

BB=/sbin/busybox
D=/data/local/tmp
LOG=$D/xz_guard.log

log() { echo "$($BB date +'%m-%d %H:%M:%S') $*" >> $LOG; }

# DMIC 唯一有效的增益是数字增益 AIF1 ADC timeslot volume：
#   - 190 实测最优（远场可唤醒，无自激）
#   - 200 会自激啸叫（麦克风-扬声器正反馈）
# 模拟增益 ADC volume / ADC input gain / MIC boost 对 DMIC 无效，设回默认即可。
# ★ 只在开机执行一次，绝不放进任何循环。
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

case "$1" in
    once)
        fix_route
        start_svc
        ;;
    start)
        # 兼容老调用：等价于 once（不再起常驻 loop，原因见文件头）
        fix_route
        start_svc
        echo "已执行一次（v4 不再常驻轮询）"
        ;;
    loop)
        echo "v4 已取消常驻轮询：轮询会触发看门狗重启（tinymix crash）"
        echo "假死恢复由 App 内部心跳自愈 + mic_keeper.sh 负责"
        ;;
    stop)
        am force-stop com.lm.xiaozhi >/dev/null 2>&1
        echo "已停止小智服务"
        ;;
    status)
        if svc_alive; then echo "服务: running"; else echo "服务: not running"; fi
        echo "DMIC 增益: $(tinymix 2>/dev/null | $BB awk '$0 ~ /AIF1 ADC timeslot 0 volume/ {print $NF; exit}')（应为 190）"
        if $BB pgrep -f vulture.app.home >/dev/null 2>&1; then
            echo "会议App: 在跑（电源键→HOME 靠它，不能杀）"
        else
            echo "会议App: 未运行"
        fi
        echo "--- 日志尾 ---"
        $BB tail -n 6 $LOG 2>/dev/null
        ;;
    *)
        echo "用法: sh $0 {once|status|stop}"
        ;;
esac
