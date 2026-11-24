#!/system/bin/sh
# boot_init.sh —— T70L 开机自启总入口（2026-11-04 修订）
#
# 由 /system/etc/install-recovery.sh 末尾以「后台」方式调用。
# flash_recovery 阶段 /data 已经挂载（可读脚本），但系统还没起完，
# 所以必须等 sys.boot_completed=1 之后再做任何 am / 蓝牙相关操作。
#
# 只做两件事，别的都不做（1G 内存，非必要不占）：
#   1) 拉起前台应用与守护
#   2) 校正桌面布局（装 APK 等事件会把布局打乱）
#
# 用法: sh boot_init.sh          正常开机流程
#       sh boot_init.sh gw       只起网关
#       sh boot_init.sh layout   只校正桌面
#
# ── 2026-11-04 修订 ───────────────────────────────────────────
# 旧版只等「com.android.bluetooth 进程出现」就去起网关，这是错的：
#   进程出现 ≠ 芯片初始化完成。OffState 时进程活着，但根本不打开 /dev/ttyS1，
#   此时起网关 → HCI 命令全部 -1、adv=0，灯就是"死"的。
# 正确判据：bluedroid 是否持有 /dev/ttyS1 的 fd（= vendor 已下完 hcd、切到 1500000）。
# 另外：改用 `t70gw2.sh start` 而不是 restart —— restart 里的 do_stop 会 CONT
#   bluedroid，这一下 CONT 把刚要用的串口状态搅了；start 直接换新进程更干净。
# ─────────────────────────────────────────────────────────────

BB=/sbin/busybox
LOG=/data/local/tmp/boot_init.log
GW=/data/local/tmp/t70gw2.sh

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

now_ms() { $BB date +%s; }

bt_pid() {
    ps | $BB grep com.android.bluetooth | $BB grep -v grep | $BB awk '{print $2}'
}

# ---------- 等待系统就绪 ----------
wait_boot() {
    n=0
    while [ "$(getprop sys.boot_completed)" != "1" ]; do
        $BB sleep 2
        n=$((n + 1))
        if [ $n -ge 150 ]; then
            log "WARN boot_completed 超时（${n}x2s），继续尝试"
            return 1
        fi
    done
    log "boot_completed 就绪，等待 ${n}x2s"
    return 0
}

# ---------- 等芯片真正初始化好 ----------
wait_chip() {
    n=0
    while [ $n -lt 45 ]; do
        if chip_inited; then
            [ $n -gt 0 ] && log "芯片初始化完成，等待 $((n * 2))s"
            return 0
        fi
        $BB sleep 2
        n=$((n + 1))
    done
    log "WARN 等了 90s 芯片仍未初始化（bluedroid 没持有 ttyS1）"
    return 1
}

# ---------- 兜底：用系统设置页把蓝牙开关打开 ----------
# 只在 wait_chip 失败时调用。开机早期 am 可能还不能用，所以先探一下。
ui_enable_bt() {
    log "尝试 UI 兜底：打开蓝牙开关"
    $BB sleep 5
    am start -a android.settings.BLUETOOTH_SETTINGS >/dev/null 2>&1
    $BB sleep 6
    # 蓝牙开关 bounds [198,144]-[314,180]，中心 (256,162)
    # ⚠ (256,113) 是 WiFi 开关，点错会断网
    input tap 256 162
    $BB sleep 10
    ui_enable_bt_done=1
}

# ---------- 预热 WebView（必须在冻结 bluedroid 之前！） ----------
# 【2026-11-04 13:40 修复「文件夹点进去卡死/无响应」】
#
# 根因（ANR 堆栈实证，/data/anr/traces.txt）：
#   vulture.app.home 主线程
#     → WebView.<init> → Chromium 初始化
#       → AudioManagerAndroid.initBluetooth()
#         → BluetoothAdapter.getState()
#           → IBluetooth.getState()  ← binder 调用，卡死在这里
# 因为 服务进程把 com.android.bluetooth 整个进程 SIGSTOP 了（让出串口），
# 该进程里的 IBluetooth 服务不再应答 → 这个同步 binder 调用永不返回 →
# WebView 建不出来 → 「文件夹」(MyFullScreenWebActivity，本质是 WebView 页) 必 ANR。
# 影响面：只要网关在跑，**任何新建 WebView 的界面都会卡**（时钟/天气/设置不用 WebView，所以正常）。
#
# 解法：Chromium 在每个进程里只初始化一次（BrowserStartupController 只跑一遍），
# 所以只要在「冻结之前」让 vulture.app.home 先建过一次 WebView，之后整个开机周期都不受影响。
# 代价：开机时该页会闪一下（此时屏幕通常还没人看），6 秒后自动 BACK 退出。
warm_webview() {
    log "预热 WebView（防冻结蓝牙后文件夹 ANR）"
    am start -a nemo.itent.action.activity.MyFullScreenWebActivity >/dev/null 2>&1
    $BB sleep 6
    input keyevent 4 >/dev/null 2>&1   # BACK，退出预热页
    $BB sleep 1
    log "WebView 预热完成"
}

# ---------- 网关守护 ----------
# 开机只拉一次网关是不够的：进程可能被 OOM 杀掉，蓝牙链路也可能中途彻底坏掉。
# gateway_guard.sh 每 20 秒检查一次（PING 要回 PONG + advAgeMs ≤ 120 秒），
# 连续 2 轮不健康就 t70gw2.sh start（内部换新 bluedroid），仍不行再走 UI 兜底。
# 实测：kill -9 网关 41 秒自愈；BTOFF 让出串口 2 分 45 秒自愈。
start_guard() {
    # 【2026-11-04 06:35 修的坑】重启后 /data 上的 pid 文件不会消失，
    # 而 pid 会重新分配。前台 App 恰好拿到老 pid 时，`kill -0` 成功 →
    # 守护误判"已在运行"直接退出，**重启后守护根本没起来**，但灯照样能控
    # （网关是 boot_init 直接起的），症状极其隐蔽。
    # 所以开机早期直接清掉这个文件，双保险（gateway_guard.sh 里也加了 cmdline 校验）。
    # 只在 uptime < 120 秒时清 —— boot_init 也可能被手动跑，那时不能删正在用的 pid 文件。
    UP=$(cat /proc/uptime | $BB awk '{print int($1)}')
    if [ "$UP" -lt 120 ]; then
        rm -f /data/local/tmp/gateway_guard.pid
    fi
    R=$(sh /data/local/tmp/gateway_guard.sh start 2>&1)
    log "网关守护: $R"
}

# ---------- 桌面布局 ----------
fix_layout() {
    R=$(sh /data/local/tmp/launcher_guard.sh check 2>&1)
    log "布局检查: $R"

    sh /data/local/tmp/launcher_guard.sh start >/dev/null 2>&1
    log "桌面守护已启动（pid=$(cat /data/local/tmp/launcher_guard.pid 2>/dev/null)）"

    # WebView 预热守护：vulture.app.home 被 ANR/OOM 杀掉重启后自动重新预热。
    # 不启这个的话，home 进程一换号，预热就作废 → 点文件夹必黑屏 ANR。
    sh /data/local/tmp/webview_guard.sh start >/dev/null 2>&1
    log "WebView 守护已启动（pid=$(cat /data/local/tmp/webview_guard.pid 2>/dev/null)）"

    # 图标守护：launcher 每次重建 item_info 都会把指定 icon 清空，这里兜底写回
    sh /data/local/tmp/lamp_icon_guard.sh start >/dev/null 2>&1
    # 看家相机守护：看家需要相机时让原厂会议 App 让出（先到先得）
    sh /data/local/tmp/cam_guard.sh start >/dev/null 2>&1
    log "图标守护已启动（pid=$(cat /data/local/tmp/lamp_icon_guard.pid 2>/dev/null)）"
}

# ---------- 主流程 ----------
case "$1" in
    gw)
        start_gateway
        start_guard
        ;;
    layout)
        fix_layout
        ;;
    wake)
        # 2026-11-06 起改用「小智」App（com.lm.xiaozhi），旧的 wake_guard 保留以便回滚
        sh /data/local/tmp/xz_guard.sh once
        sh /data/local/tmp/xz_guard.sh start
        ;;
    *)
        log "===== boot_init 开始（uptime=$(cat /proc/uptime | $BB awk '{print int($1)}')s）====="
        wait_boot

        start_gateway
        start_guard
        $BB sleep 3
        fix_layout

        # 小智：先释放麦克风再拉服务与守护（旧的 wake_guard 保留，回滚只需改回这两行）
        sh /data/local/tmp/xz_guard.sh once >/dev/null 2>&1
        sh /data/local/tmp/xz_guard.sh start >/dev/null 2>&1
        log "小智守护已启动（pid=$(cat /data/local/tmp/xz_guard.pid 2>/dev/null)）"

        log "===== boot_init 完成，MemFree=$(cat /proc/meminfo | $BB grep MemFree | $BB awk '{print $2}')kB ====="
        ;;
esac
