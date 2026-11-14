#!/bin/bash
# t.sh —— 语音延迟实测（Mac 扬声器播放 → 读设备日志算四段耗时）
# 用法: bash t.sh [开灯|关灯] [等待秒数]
# 注意: 坑① 绝不用 T70L 自己播自己测（AEC 会吃掉），必须外放。

export PATH=$HOME/re-tools/platform-tools:$PATH
W=${1:-开灯}
WAIT=${2:-7}

say -v Tingting -o /tmp/xz_t.wav --data-format=LEI16@16000 "小智$W" 2>/dev/null
osascript -e 'set volume output volume 100' >/dev/null 2>&1

adb shell "su 0 rm -f /data/data/com.lm.xiaozhi/files/xz.log" 2>/dev/null
sleep 1
afplay /tmp/xz_t.wav
sleep "$WAIT"

adb shell "su 0 cat /data/data/com.lm.xiaozhi/files/xz.log 2>/dev/null" > /tmp/xz_t.log 2>&1

echo "======== 小智$W ========"
grep -E "开口|收尾|已发完|识别|成功|失败|超时" /tmp/xz_t.log | tail -12
echo "-------- 拆分 --------"
python3 - <<'PY'
import re
L = open('/tmp/xz_t.log', encoding='utf-8', errors='replace').read().splitlines()
def ts(l):
    m = re.match(r'\d+-\d+ (\d+):(\d+):(\d+)\.(\d+)', l)
    if not m: return None
    return int(m.group(1))*3600 + int(m.group(2))*60 + int(m.group(3)) + int(m.group(4))/1000.0
open_t = end_t = sent_t = done_t = None
for l in L:
    t = ts(l)
    if t is None: continue
    if '开口' in l and open_t is None: open_t = t
    if '收尾' in l: end_t = t
    if '已发完' in l: sent_t = t
    if '成功' in l and '｜' in l: done_t = t
def d(a, b):
    return f"{round((b-a)*1000)}ms" if a and b else "-"
print(f"说完→收尾判定 : {d(end_t, end_t) if not end_t else ''}")
print(f"开口→收尾     : {d(open_t, end_t)}")
print(f"收尾→发完     : {d(end_t, sent_t)}   ★瓶颈")
print(f"发完→执行     : {d(sent_t, done_t)}")
print(f"开口→执行总    : {d(open_t, done_t)}")
PY
