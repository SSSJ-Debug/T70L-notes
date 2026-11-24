#!/sbin/busybox sh
# =============================================================================
#  T70L 磁盘守护 disk_guard.sh —— 最后一道防线（2026-11-23 事故后新增）
#
#  为什么需要它：
#    2026-11-23 设备因 /data 被写满到 0 空闲块而彻底卡死，只能格式化救回。
#    元凶是小智 App 的采集开关残留 + 无上限追加写。
#    App 层已加了三道闸，但"只靠 App 自觉"不够 —— 万一编译没生效、或者别的
#    程序乱写，还得有人在旁边盯着。这个脚本就是那个人。
#
#  三级水位（单位 KB，df 的 1K blocks）：
#    WARN   < 512MB  : 清缓存、截断日志、记一笔
#    CRIT   < 300MB  : 删采集文件 + 删所有采集开关
#    EMERG  < 150MB  : 再删 /data 下所有 >32MB 的可疑大文件
#
#  自身日志同样限长，绝不变成新的写盘元凶。
#
#  用法：
#    disk_guard.sh            # 常驻循环（60 秒一轮）
#    disk_guard.sh --check    # 只查一次，打印状态就退出
#    disk_guard.sh --once     # 跑一轮处置逻辑后退出
# =============================================================================

BB=/sbin/busybox
[ -x "$BB" ] || BB=busybox

DATA=/data
TMP=/data/local/tmp
LOG=$TMP/disk_guard.log
LOG_MAX=262144          # 256KB，超了就滚动

WARN_KB=524288          # 512MB
CRIT_KB=307200          # 300MB
EMERG_KB=153600         # 150MB
BIG_FILE_KB=32768       # 32MB，EMERG 时清理的大文件阈值

XZ_FILES_DIR=/data/data/com.lm.xiaozhi/files

# ---------------------------------------------------------------- 日志（自限）
log() {
    $BB echo "$($BB date '+%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null
    # 日志自限：超过上限就只留最后 200 行
    local sz=$($BB stat -c %s "$LOG" 2>/dev/null || $BB wc -c < "$LOG" 2>/dev/null || echo 0)
    if [ "${sz:-0}" -gt "$LOG_MAX" ]; then
        $BB tail -n 200 "$LOG" > "$LOG.tmp" 2>/dev/null && $BB mv "$LOG.tmp" "$LOG"
    fi
}

free_kb() {
    $BB df "$DATA" 2>/dev/null | $BB tail -n 1 | $BB awk '{print $4}'
}

human() {
    # KB -> 可读
    local k=${1:-0}
    if [ "$k" -ge 1048576 ]; then
        $BB echo "$(($k / 1048576))GB"
    else
        $BB echo "$(($k / 1024))MB"
    fi
}

# ---------------------------------------------------------------- 各档处置
do_warn() {
    log "WARN 剩余 $(human $1)，开始清缓存"
    # 清应用缓存（不动数据）
    $BB rm -rf /data/data/*/cache/* 2>/dev/null
    $BB rm -rf /data/local/tmp/*.log.* 2>/dev/null
    $BB rm -rf /cache/* 2>/dev/null
    log "WARN 清理完成，剩余 $(human $(free_kb))"
}

do_crit() {
    log "CRIT 剩余 $(human $1)，删除采集文件与开关"
    # 采集产出的音频文件（元凶）
    $BB rm -f "$XZ_FILES_DIR"/kws_in.pcm  2>/dev/null
    $BB rm -f "$XZ_FILES_DIR"/kws_dump.pcm 2>/dev/null
    $BB rm -f "$XZ_FILES_DIR"/xiaoai_rec.pcm 2>/dev/null
    $BB rm -f "$XZ_FILES_DIR"/xiaoai_raw.pcm 2>/dev/null
    # 所有采集开关：删掉就永久停采
    for f in kws_in.on kws_dump.on collect_only.on; do
        [ -f "$XZ_FILES_DIR/$f" ] && {
            $BB rm -f "$XZ_FILES_DIR/$f"
            log "CRIT 已删除开关 $f"
        }
    done
    # 顺手清一次缓存和大日志
    $BB rm -rf /data/data/*/cache/* 2>/dev/null
    $BB rm -f /data/log/logcat_full.log 2>/dev/null
    log "CRIT 处置完成，剩余 $(human $(free_kb))"
}

do_emerg() {
    log "★★ EMERG 剩余 $(human $1)，清理 /data 下所有 >$((BIG_FILE_KB/1024))MB 大文件"
    $BB find /data -xdev -type f -size +${BIG_FILE_KB}k 2>/dev/null | while read -r f; do
        case "$f" in
            */databases/*|*/shared_prefs/*|*.apk|*.odex|*.dex|*.so|*.onnx)
                continue ;;   # 这些不能删
        esac
        log "EMERG 删除 $f ($($BB du -k "$f" 2>/dev/null | $BB awk '{print $1}')KB)"
        $BB rm -f "$f" 2>/dev/null
    done
    $BB rm -rf /data/data/*/cache/* 2>/dev/null
    log "EMERG 处置完成，剩余 $(human $(free_kb))"
}

# ---------------------------------------------------------------- 单轮检查
check_once() {
    local f
    f=$(free_kb)
    [ -z "$f" ] && { log "读不到 /data 剩余空间，跳过"; return 1; }

    if [ "$f" -lt "$EMERG_KB" ]; then
        do_emerg "$f"
    elif [ "$f" -lt "$CRIT_KB" ]; then
        do_crit "$f"
    elif [ "$f" -lt "$WARN_KB" ]; then
        do_warn "$f"
    fi
    return 0
}

# ---------------------------------------------------------------- 入口
case "${1:-}" in
    --check)
        f=$(free_kb)
        $BB echo "/data 剩余: $(human $f)  (${f}KB)"
        $BB echo "水位: WARN<$(human $WARN_KB)  CRIT<$(human $CRIT_KB)  EMERG<$(human $EMERG_KB)"
        [ "$f" -lt "$WARN_KB" ] && $BB echo "状态: ⚠ 已低于警戒线" || $BB echo "状态: ✅ 健康"
        ;;
    --once)
        check_once
        ;;
    *)
        log "===== disk_guard 启动（PID $$）====="
        log "初始剩余 $(human $(free_kb))"
        while true; do
            check_once
            $BB sleep 60
        done
        ;;
esac
