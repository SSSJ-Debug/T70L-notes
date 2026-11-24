#!/system/bin/sh
# launcher_guard.sh —— 桌面布局守护 v2（2026-11-05）
#
# 守护对象：com.xylink.launcher 的 item_info 表
# 触发源：① 每 10 秒巡检  ② 包列表变化（装/卸软件后 vulture 会推平桌面）
#
# v2 相对 v1 的变化（都是针对「装完软件桌面乱」实测踩出来的）：
#   1) 签名加入 intent 特征(kind)：能识别「文件夹被换成原厂 WebView 壳」。
#      v1 只比 title+坐标，文件夹被换成原厂网页壳时签名一致 → 判定正常 → 不修。
#   2) 增加 intent 校正：文件夹/指令执行/录像机/设置 的 intent 逐条对齐模板库。
#   3) 装包事件驱动：pm list packages 指纹一变，等 vulture 重建完（库稳定）后复查。
#   4) 轻量修复连续 2 次不达标 → 整表恢复兜底（180 秒冷却，防拉锯）。
#   5) 开关文件 /data/local/tmp/desk_guard.off：存在则暂停（自己整理桌面时用）。
#
# 安全边界：只写 com.xylink.launcher 数据库，不动 vulture，不重启系统。
# 时序铁律：先写库 → 再杀桌面进程。反了新进程会读到旧库（v1 注释里的老坑）。
#
# 用法: sh launcher_guard.sh start|stop|check|status|loop

BB=/sbin/busybox
S=/system/xbin/sqlite3
DB=/data/data/com.xylink.launcher/databases/_setting.db
TPL=/data/local/tmp/_setting.db.bak-privacy
LOG=/data/local/tmp/launcher_guard.log
PIDF=/data/local/tmp/launcher_guard.pid
OFF=/data/local/tmp/desk_guard.off
COOL=/data/local/tmp/launcher_guard.cooldown
REFRESH_COOL=/data/local/tmp/launcher_guard.refreshcooldown
URI=content://com.xylink.launcher.data/ItemInfo
PKG=com.xylink.launcher

# 标准布局签名（第一屏：2 个 widget + 电话/哔哩哔哩/指令执行；第二屏：文件夹/录像机/设置）
# 2026-11-05 晚：按用户要求删除「日程」「消息中心」两个 widget，哔哩哔哩上到第一屏
# 末位 kind: w=widget files=自研文件夹 bili=哔哩哔哩 rec=录像机 set=设置 x=未知快捷方式
# 2026-11-07 晚：新增「扩展屏」（com.lm.screen，第三屏 (0,0) 挪到第二屏 (0,2)）
STD='听歌:1:1:1:x,哔哩哔哩:0:2:1:bili,天气:0:2:0:w,小智:1:0:1:x,录像机:1:1:0:rec,扩展屏:2:0:0:x,抖音:1:2:1:x,文件夹:1:0:0:files,时间:0:0:0:w,电话:0:0:1:w,看家:1:3:0:x,看电视:0:1:1:tv,设置:1:2:0:set'

SIGSQL="SELECT group_concat(title||':'||screen||':'||cellX||':'||cellY||':'||CASE WHEN itemType=4 THEN 'w' WHEN intent LIKE '%com.lm.files%' THEN 'files' WHEN intent LIKE '%com.lm.bili%' THEN 'bili' WHEN intent LIKE '%com.lm.tv%' THEN 'tv' WHEN intent LIKE '%RECORD%' THEN 'rec' WHEN intent LIKE '%systemsettings%' THEN 'set' WHEN itemType=2 THEN 'x' ELSE '?' END, ',') FROM (SELECT * FROM item_info ORDER BY title);"

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

# ★ 2026-11-06 关键修复：签名必须从 ContentProvider 读，不能 sqlite3 直读数据库。
#   实测同一个时刻：content query 看到 12 条（桌面真实状态），
#   sqlite3 直读只有 8 条（桌面进程的事务/缓存还没落盘）。
#   用 SQL 直读 → 每轮都误判"4 条目缺失" → 反复补写 → 界面反复被刷。
#   这就是"桌面老是反弹"的直接推手之一。
sig() {
    content query --uri $URI 2>/dev/null | $BB awk '
    function val(line, key,   p, v) {
        p = index(line, key "=");
        if (p == 0) return "";
        v = substr(line, p + length(key) + 1);
        p = index(v, ", ");
        if (p > 0) v = substr(v, 1, p - 1);
        return v;
    }
    /^Row:/ {
        t = val($0, "title"); s = val($0, "screen"); x = val($0, "cellX");
        y = val($0, "cellY"); it = val($0, "itemType"); itn = val($0, "intent");
        k = "?";
        if (it == "4") k = "w";
                else if (itn ~ /com.lm.files/) k = "files";
        else if (itn ~ /com.lm.bili/) k = "bili";
        else if (itn ~ /com.lm.tv/) k = "tv";
        else if (itn ~ /RECORD/) k = "rec";
        else if (itn ~ /systemsettings/) k = "set";
        else if (it == "2") k = "x";
        if (t != "") print t ":" s ":" x ":" y ":" k;
    }' | $BB sort | $BB tr '\n' ','
}

# ---------- 包含式校验（2026-11-06 关键修复） ----------
# 旧逻辑：签名必须【完全等于】STD，多一条就判定偏离 → 连续 2 次 → 整表恢复 + kill 桌面。
# 而 vulture 重启桌面时会自动塞回「日程」「消息中心」两个 widget，
# 于是形成「系统塞回 → 守护整表恢复 → 杀桌面 → 桌面重启 → 系统再塞回」的死循环。
# 这就是用户说的"桌面反复反弹、装的软件全没了"。
# 新逻辑：只校验我们关心的条目【存在且坐标正确】，多出来的条目不管（由 trigger 拦截）。
sig_ok() {
    C=$(sig)
    [ -z "$C" ] && return 1
    CC=",$C,"
    for it in $(echo "$STD" | $BB tr ',' ' '); do
        case "$CC" in
            *",$it,"*) ;;
            *) return 1 ;;
        esac
    done
    return 0
}

# 数据库层拦截：任何进程（vulture / 桌面自己）想插入这两个 widget 都被 IGNORE 掉。
# 比守护脚本"删了又被加回来"稳得多——从源头插不进来。
# ★ 2026-11-06 撤回 item_info 拦截触发器。
#   原意是拦住系统塞回来的「日程」「消息中心」，但实测加了这个 AFTER 不存在的 RAISE(IGNORE) 之后，
#   桌面每 60 秒左右会把 item_info 里的快捷方式全清一遍（只剩 时间/天气/电话 三个 widget），
#   疑似桌面把"插入被 IGNORE"当成事务失败而回滚重建。
#   而且现在签名校验已经是包含式的，多余条目根本不影响判断，这个触发器纯属多余的风险。
# ---------- 数据库层硬保护（2026-11-06 根本解） ----------
# 协议分析：有进程每隔约 60 秒对 item_info 执行一次【整表 DELETE】
# （审计触发器抓到：同一秒删掉全部 14 条，随后桌面只从 AppWidgetService 恢复了
#   时间/天气/电话 三个 widget，其余快捷方式全没了 → 用户看到"装的软件全消失"）。
# 删完守护再补，补完又被删 —— 这就是反复反弹的死循环。
#
# 所以不再跟它比手速，直接在 SQLite 层锁死：
#   1) trg_protect_items：11 个关键图标【不许删】
#      （云台调节 2026-11-06 并入看家后已从桌面移除，不再需要保护）（DELETE ... WHERE 命中也 IGNORE）
#   2) trg_dedup_insert：同名条目【不许重复插入】（防止重建时出现两个同名图标）
#   3) trg_audit_del   ：删了什么、什么时候删的，全记进 _del_audit，方便以后复盘
# 系统想删「日程」「消息中心」照样随它删，互不干扰。
install_triggers() {
    $S "$DB" "DROP TRIGGER IF EXISTS trg_block_extra;" >/dev/null 2>&1
    $S "$DB" "CREATE TRIGGER IF NOT EXISTS trg_block_privacy2 BEFORE INSERT ON status_info WHEN NEW._id = 0 BEGIN SELECT RAISE(IGNORE); END;" >/dev/null 2>&1
    $S "$DB" "CREATE TABLE IF NOT EXISTS _del_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, title TEXT, screen INTEGER, cellX INTEGER, cellY INTEGER);" >/dev/null 2>&1
    $S "$DB" "CREATE TRIGGER IF NOT EXISTS trg_protect_items BEFORE DELETE ON item_info WHEN OLD.title IN ('时间','天气','电话','看电视','哔哩哔哩','小智','指令执行','看家','文件夹','录像机','设置') BEGIN SELECT RAISE(IGNORE); END;" >/dev/null 2>&1
    $S "$DB" "CREATE TRIGGER IF NOT EXISTS trg_dedup_insert BEFORE INSERT ON item_info WHEN EXISTS(SELECT 1 FROM item_info WHERE title = NEW.title) BEGIN SELECT RAISE(IGNORE); END;" >/dev/null 2>&1
    $S "$DB" "CREATE TRIGGER IF NOT EXISTS trg_audit_del BEFORE DELETE ON item_info BEGIN INSERT INTO _del_audit (ts,title,screen,cellX,cellY) VALUES (datetime('now','localtime'),OLD.title,OLD.screen,OLD.cellX,OLD.cellY); END;" >/dev/null 2>&1
}

launcher_pid() { ps | grep "$PKG" | grep -v grep | $BB awk '{print $2}' | $BB head -n 1; }

# ★ 不能用 `ps | grep launcher_guard` 判断守护是否在跑：
#   4.4 的 ps 对 `sh 脚本` 这类进程只显示 "sh"（COMMAND 列被截断），
#   2026-11-05 因此误判「没在跑」→ 重复启动 → 堆进程。统一走 /proc/PID/cmdline。
loop_pids() {
    for d in /proc/[0-9]*; do
        p=${d#/proc/}
        [ -r "$d/cmdline" ] || continue
        c=$($BB tr -d '\0' < "$d/cmdline" 2>/dev/null)
        case "$c" in
            # 必须以 loop 结尾：调 status/check/stop 的临时 shell 也含脚本名，
            # 用 *loop* 会把它们误算成守护（实测把执行 stop 的 shell 自己杀了）
            *launcher_guard.sh*loop) echo "$p" ;;
        esac
    done
}

is_running() { [ -n "$(loop_pids)" ]; }

pkgfp() { pm list packages 2>/dev/null | $BB md5sum | $BB awk '{print $1}'; }

# ---------- 轻量修复：走桌面自己的 ContentProvider，不杀进程、不碰 journal ----------
fix_coords() {
    $S "$TPL" "SELECT title,screen,cellX,cellY FROM item_info;" 2>/dev/null | \
    while IFS='|' read -r T SC CX CY; do
        [ -z "$T" ] && continue
        content update --uri $URI \
            --bind screen:i:$SC --bind cellX:i:$CX --bind cellY:i:$CY \
            --where "title='$T'" >/dev/null 2>&1
    done
}

fix_intents() {
    $S "$TPL" "SELECT title,intent FROM item_info WHERE intent IS NOT NULL AND intent<>'';" 2>/dev/null | \
    while IFS='|' read -r T IN; do
        [ -z "$T" ] && continue
        [ -z "$IN" ] && continue
        CUR=$($S "$DB" "SELECT ifnull(intent,'') FROM item_info WHERE title='$T';" 2>/dev/null | $BB tr -d '\r\n')
        if [ "$CUR" != "$IN" ]; then
            log "intent 不符: $T（现=${CUR%%,*}… 应为=${IN%%,*}…）→ 校正"
            # 注意：值里含 ':' 和 ';'，content 按 split(":",3) 解析，引号内安全
            content update --uri $URI --bind "intent:s:$IN" --where "title='$T'" >/dev/null 2>&1
        fi
    done
}

# 从标准模板补齐单个条目：条目被删 → 整行插回；条目在但图标为空 → 只补 icon
# vulture 重建桌面时写的是 SQL NULL（不是长度 0 的 blob），必须用 ifnull(length(icon),-1) 区分
ensure_from_tpl() {
    T=$1
    # 存在性判断同样必须走 Provider：SQL 直读会漏掉桌面还没来得及落盘的条目，
    # 漏判 → 重复 INSERT → 桌面出现两个同名图标。
    ROW=$(content query --uri $URI --where "title='$T'" 2>/dev/null | $BB grep "^Row:")
    if [ -z "$ROW" ]; then
        ST="0/x"
    else
        case "$ROW" in
            *"icon=NULL"*) ST="1/0" ;;
            *"icon=BLOB"*) ST="1/1" ;;
            *) ST="1/1" ;;
        esac
    fi
    case "$ST" in
      0/*)
        log "$T 条目缺失，从模板补回"
        $S "$DB" "ATTACH '$TPL' AS bak; INSERT INTO item_info (title,intent,screen,cellX,cellY,spanX,spanY,itemType,appWidgetId,iconType,icon,modified) SELECT title,intent,screen,cellX,cellY,spanX,spanY,itemType,appWidgetId,iconType,icon,modified FROM bak.item_info WHERE title='$T'; DETACH bak;" >/dev/null 2>&1
        ;;
      1/0|1/-1)
        log "$T 图标为空，从模板补图标"
        $S "$DB" "ATTACH '$TPL' AS bak; UPDATE item_info SET icon=(SELECT icon FROM bak.item_info WHERE title='$T') WHERE title='$T'; DETACH bak;" >/dev/null 2>&1
        ;;
    esac
}

# ---------- 图标自愈（2026-11-08 新增） ----------
# 病根（这次挖到底了）：
#   桌面每次收到「装包/换包」广播，会把第三方应用的条目整行重建成
#   「拿 APK 里的 launcher 图标重新合成」的版本 —— 合出来的图是满铺 220x280、
#   内层图形只占 54~56%，肉眼就是"新软件的图标又比原厂大一圈"（而且颜色/构图
#   还和桌面库里那版不一致，看着像"卡片里又套了一张卡片"）。
#   所以只修数据库治不住：下一次 pm install 又被覆盖。必须由守护按字节比对后复位。
sync_icons() {
    LIST=""
    for _it in $(echo "$STD" | $BB tr ',' ' '); do
        _t=${_it%%:*}
        case "$_t" in 时间|天气) continue ;; esac
        LIST="$LIST'$_t',"
    done
    LIST=${LIST%,}
    BAD=$($S "$DB" "ATTACH '$TPL' AS bak; SELECT COUNT(*) FROM item_info i JOIN bak.item_info b ON b.title=i.title WHERE i.title IN ($LIST) AND i.icon IS NOT NULL AND length(i.icon)>0 AND b.icon IS NOT NULL AND i.icon != b.icon; DETACH bak;" 2>/dev/null | $BB tr -d ' \r\n')
    case "$BAD" in
      ''|*[!0-9]*) return 0 ;;
      0) return 0 ;;
    esac
    log "图标偏离模板 $BAD 个 → 按模板复位（不是尺寸问题，是被桌面按 APK 重画过）"
    $S "$DB" "ATTACH '$TPL' AS bak; UPDATE item_info SET icon=(SELECT icon FROM bak.item_info b WHERE b.title=item_info.title) WHERE title IN ($LIST) AND icon IS NOT NULL AND length(icon)>0 AND (SELECT icon FROM bak.item_info b WHERE b.title=item_info.title) IS NOT NULL AND icon != (SELECT icon FROM bak.item_info b WHERE b.title=item_info.title); DETACH bak;" >/dev/null 2>&1
    touch_all
}

# 节流包装：装包后立刻查一次，平时每 60 秒查一次（比对 14 条约 100KB 的 blob，不贵）
ICONCHK=/data/local/tmp/launcher_guard.iconchk
check_icons() {
    NOW=$($BB date +%s)
    LAST=$(cat $ICONCHK 2>/dev/null)
    [ -z "$LAST" ] && LAST=0
    if [ "$1" != "force" ] && [ $((NOW - LAST)) -lt 60 ]; then
        return 0
    fi
    echo $NOW > $ICONCHK
    sync_icons
}

restore_layout() {
    install_triggers
    # ★ 2026-11-06 修复：以前这里是硬编码的 5 个条目（看电视/哔哩哔哩/云台调节/看家/小智），
    #   漏了「录像机」「文件夹」「设置」。而桌面进程一旦重启（被 LMK 杀、我们 kill、系统重启），
    #   这三条就会被桌面自己丢掉 —— 用户看到的就是"装的软件全没了"，而且守护永远修不回来
    #   （因为它压根不在修复清单里），最终反复整表恢复 + 杀桌面，越修越乱。
    #   现在改成遍历标准签名里的全部条目，以后加新应用自动纳入，不会再漏。
    for _it in $(echo "$STD" | $BB tr ',' ' '); do
        _t=${_it%%:*}
        case "$_t" in
            时间|天气) continue ;;       # 系统 widget，由桌面自己维护，不硬补
        esac
        ensure_from_tpl "$_t"
    done
    fix_coords
    fix_intents
    sync_icons
    # 直接写 SQL 补的条目不会经过 ContentProvider，桌面界面收不到 notifyChange，
    # 会出现"数据库对、屏幕还是旧样子"的假死。这里逐个 touch 一下触发刷新。
    touch_all
}

touch_all() {
    for _it in $(echo "$STD" | $BB tr ',' ' '); do
        _t=${_it%%:*}
        case "$_t" in
            时间|天气) continue ;;
        esac
        content update --uri $URI --bind "modified:i:$($BB date +%s)" --where "title='$_t'" >/dev/null 2>&1
    done
}

# ---------- 整表兜底：轻量修复压不住时才动，带冷却 ----------
full_restore() {
    NOW=$($BB date +%s)
    LAST=$(cat $COOL 2>/dev/null)
    [ -z "$LAST" ] && LAST=0
    if [ $((NOW - LAST)) -lt 180 ]; then
        log "整表恢复冷却中（$((NOW - LAST))s < 180s），本轮跳过"
        return 1
    fi
    # ★ 2026-11-06 停用整表恢复：
    #   full_restore 会 DELETE FROM item_info 再从模板灌回去，还会 kill 桌面。
    #   而桌面进程自己也有一份内存表并在持续写库 —— 两边抢库的结果就是
    #   "条目时有时无、界面反复重排、用户看到桌面在跳"。
    #   轻量修复（补缺失 + 纠坐标 + 纠 intent）已经够用，且只动差异部分，不打全表。
        log "=== 整表恢复已停用（避免与桌面抢库），改为逐条修补 ==="
    restore_layout
    return 0
}

# ---------- 温和刷新桌面（绝不 force-stop / kill HOME） ----------
# 2026-11-06 关键结论（静态分析 分析 vulture.app.lanucher 确认）：
#   这台设备有两个 HOME：com.xylink.launcher（真桌面 UI）和 vulture.app.lanucher（原厂引导器）。
#   lanucher 的 LauncherActivity.onResume() 无条件发 nemo.intent.update.RESTART 把真桌面顶起来。
#   一旦我们 force-stop com.xylink.launcher（HOME），会惊动 lanucher 再发 RESTART，
#   形成"杀桌面 → lanucher 顶回 → 再杀"的横跳循环 —— 这就是 2026-11-06 反复横跳的推手。
# 所以这里【绝不】force-stop / kill HOME。改用最温和的方式：
#   通过 content update 走 Provider（触发 notifyChange），再 am start 拉起桌面 onResume 重载。
# 不杀进程，界面刷新靠 Provider 通知 + onResume，宁可慢一点也不冒 bootloop / 横跳的风险。
# ---------- 整表恢复后重载桌面（一次性 kill -9 + 冷却，绝不循环） ----------
# 2026-11-06 三次复盘，最终确认的正确结论：
#   1. 数据库 item_info 是权威数据源；launcher 界面是内存态，只在进程重启时才重读数据库。
#      只写库、不重启 launcher，界面永远停在旧布局（"数据对、界面错"的假死）。
#   2. am force-stop com.xylink.launcher 杀不掉它（HOME 应用被系统保活，实测 pid 不变）。
#   3. 真正能让它重读数据库的只有 kill -9 直接杀进程，系统 1 秒内自动拉起新进程。
#   4. 横跳的【唯一】推手是 am start HOME（会把 vulture.app.lanucher 顶起来，
#      lanucher.onResume 再发 RESTART 顶回真桌面）。kill -9 launcher 本身不触发横跳
#      （实测 kill 后 30 秒零 RESTART/HOME 广播，pid 稳定）。
#   5. 所以正确做法：整表恢复成功后 kill -9 launcher 一次，带独立冷却 180 秒，
#      绝不连续杀（严守铁律：循环里禁止连续 kill HOME）。
refresh_launcher_once() {
    NOW=$($BB date +%s)
    LAST=$(cat $REFRESH_COOL 2>/dev/null)
    [ -z "$LAST" ] && LAST=0
    if [ $((NOW - LAST)) -lt 180 ]; then
        return 0
    fi
    if ! sig_ok; then
        log "重载前库仍非标准，跳过"
        return 0
    fi
    LP=$(ps | $BB grep "$PKG" | $BB grep -v grep | $BB awk '{print $2}' | $BB head -n 1)
    if [ -n "$LP" ]; then
        # ★ 2026-11-06 起不再 kill 桌面：杀它只会触发一次全量重载，
        #   期间条目短暂消失、界面重排，用户看到的就是"桌面又在跳"。
        log "重载桌面已停用（避免杀 HOME 引起界面重排）"
        echo $NOW > $REFRESH_COOL
    fi
}

# ---------- 等库稳定（vulture 重建过程中库会残缺） ----------
wait_stable() {
    i=0
    a=""
    while [ $i -lt 8 ]; do
        b=$(sig)
        if [ -n "$b" ] && [ "$b" = "$a" ]; then return 0; fi
        a=$b
        i=$((i + 1))
        $BB sleep 4
    done
    return 1
}

do_check() {
    [ -f "$OFF" ] && { echo "PAUSED 守护已暂停（$OFF）"; return 0; }
    CUR=$(sig)
    if [ -z "$CUR" ]; then
        log "读取布局失败，跳过本次"
        return 0
    fi
    if sig_ok; then return 0; fi
    log "布局/变更偏离：$CUR"
    restore_layout
    return 1
}

# ---------- 时钟组件守护 ----------
guard_clock() {
    if ps | grep com.xylink.timewidget | grep -v grep | $BB grep -q .; then
        return 0
    fi
    log "时钟服务不在，重新拉起"
    am startservice -n com.xylink.timewidget/.TimeService >/dev/null 2>&1
}

do_loop() {
    bad=0
    pkg0=$(pkgfp)
    install_triggers
    log "守护启动 v3 pid=$$ pkgfp=${pkg0}（包含式校验 + 触发器拦截）"
    while true; do
        if [ -f "$OFF" ]; then
            pkg0=$(pkgfp)
            $BB sleep 10
            continue
        fi

        pkg1=$(pkgfp)
        if [ -n "$pkg1" ] && [ "$pkg1" != "$pkg0" ]; then
            log "检测到包变化（$pkg0 → $pkg1），等 vulture 重建"
            pkg0=$pkg1
            $BB sleep 20
            pkg0=$(pkgfp)
            wait_stable || log "库 32 秒未稳定，仍然尝试修复"
            bad=1   # 装完软件这类场景，允许一次直接兜底
            # ★ 2026-11-08：装/换包必定触发桌面用 APK 图标重画条目 → 立刻按模板复位
            check_icons force
        fi

        CUR=$(sig)
        if [ -z "$CUR" ]; then
            $BB sleep 5
            continue
        fi

        if sig_ok; then
            bad=0
        else
            # ★ 桌面刚重启时会有几秒钟只加载出 3~5 条（中间态）。
            #   如果这时候立刻判定"缺失"并补写，就会和桌面的加载过程打架，
            #   表现为图标忽隐忽现、桌面反复重排。先等 8 秒再看一次。
            log "疑似偏离，等待二次确认：$CUR"
            # 装/卸软件后桌面会全量重建，实测从"只剩 3 条"到"12 条齐"要 12~15 秒。
            # 等太短会撞在重建中间态上，补写反而捣乱；这里等 15 秒再看。
            $BB sleep 15
            if sig_ok; then
                log "二次确认通过（刚才是桌面加载中间态），不修"
                bad=0
                guard_clock
                $BB sleep 10
                continue
            fi
            log "二次确认仍偏离，开始修补"
            restore_layout
            if sig_ok; then
                log "轻量修复成功"
                bad=0
            else
                bad=$((bad + 1))
                log "轻量修复后仍偏离($bad)：$CUR"
                if [ $bad -ge 2 ]; then
                    # 只做逐条修补，绝不整表重建、绝不杀桌面进程
                    full_restore
                    bad=0
                fi
            fi
        fi

        guard_clock
        # 常规节流巡检：桌面若在后台把某个图标重画过，60 秒内复位
        check_icons

        # ── 自动收录器联动 ──
        # com.lm.deskadd 插完条目只能写文件（它没权限杀 HOME），
        # 界面要重读数据库才看得到新图标，所以这里帮它刷新一次。
        #
        # ★★ 2026-11-07 血泪修正（用户报"桌面全空了"）★★
        #   旧代码是 kill -9 桌面进程让界面重读库。后果：
        #   ① 杀的那一刻桌面一片空白，用户看到的就是"什么软件都没有了"；
        #   ② 若撞上收录器正在 delete+insert（审计表有 4 条删除记录），
        #      重读到的库是残缺的，界面可能一直空着不恢复；
        #   ③ 连杀还会和 vulture.app.lanucher 的 RESTART 横跳。
        #   新做法：只用 content update 碰一下 modified 触发 notifyChange，
        #   桌面自己决定什么时候重读；界面最坏是"暂时没显示新图标"，
        #   绝不会变成空白。宁可晚一点，也绝不空屏。
        RELOAD_FLAG=/data/data/com.lm.deskadd/files/need_reload
        if [ -f "$RELOAD_FLAG" ]; then
            RNOW=$($BB date +%s)
            RLAST=$(cat $REFRESH_COOL 2>/dev/null)
            [ -z "$RLAST" ] && RLAST=0
            if [ $((RNOW - RLAST)) -ge 5 ]; then
                touch_all
                log "收录器新增了图标 → 已用 Provider 通知刷新（不杀桌面）"
                echo $RNOW > $REFRESH_COOL
            fi
            rm -f "$RELOAD_FLAG"
        fi

        $BB sleep 10
    done
}

case "$1" in
  loop|_loop)
    echo $$ > $PIDF
    log "loop 进入 pid=$$"
    do_loop
    ;;
  stop)
    for c in $(loop_pids); do
        kill -9 $c 2>/dev/null
    done
    rm -f $PIDF
    echo "stopped"
    ;;
  check)
    if do_check; then echo "OK 布局与标准一致"; else echo "FIXED 已恢复 sig=$(sig)"; fi
    ;;
  force)
    # 强制整表恢复 + 重载桌面一次（开机固化专用，boot_svc 开机时调一次）。
    # 与运行时循环里的 full_restore 不同：这里无视冷却、无条件执行，
    # 因为开机场景只调一次，不存在"连续杀"风险。
    log "=== force：开机固化，整表恢复 + 重载桌面 ==="
    install_triggers
    $S "$DB" "ATTACH '$TPL' AS bak; DELETE FROM item_info; INSERT INTO item_info SELECT * FROM bak.item_info; DETACH bak;" >/dev/null 2>&1
    $S "$DB" "DELETE FROM status_info;" >/dev/null 2>&1
    LP=$(ps | $BB grep "$PKG" | $BB grep -v grep | $BB awk '{print $2}' | $BB head -n 1)
    if [ -n "$LP" ]; then
        log "force 重载桌面 pid=$LP"
        kill -9 $LP >/dev/null 2>&1
    fi
    echo "FORCED 整表恢复完成 sig=$(sig)"
    ;;
  status)
    if is_running; then
        echo "running pid=$(loop_pids | $BB tr '\n' ' ')"
    else
        echo "NOT running"
    fi
    echo "sig=$(sig)"
    echo "--- 最近日志 ---"
    $BB tail -n 10 $LOG 2>/dev/null
    ;;
  *)
    # ★ 参数白名单：2026-11-05 踩过 fork 炸弹——
    #   start 里传 _loop，而 case 只写了 loop，_loop 落到这个 * 分支
    #   → 又 setsid 一个 _loop → 无限递归，一次启动繁殖出 80+ 进程。
    #   所以这里只认空参数和 start，其他一律报错退出，绝不再 fork。
    case "$1" in
      ""|start) ;;
      *)
        echo "usage: $0 start|stop|check|status"
        exit 2
        ;;
    esac
    P=$(cat $PIDF 2>/dev/null)
    if is_running; then
        echo "already running pid=$(loop_pids | $BB tr '\n' ' ')"
        exit 0
    fi
    rm -f $PIDF
    $BB setsid sh /data/local/tmp/launcher_guard.sh loop >/dev/null 2>&1 </dev/null &
    $BB sleep 3
    echo "started pid=$(cat $PIDF 2>/dev/null)"
    ;;
esac
