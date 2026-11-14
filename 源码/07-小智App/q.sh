#!/bin/bash
# q.sh —— 快速连喊压测（专门验"连喊不漏"）
# 用法: bash q.sh [轮数] [间隔秒]
#   bash q.sh 3 1.2   → 开/关各 3 次，间隔 1.2 秒，共 6 句
# 判据: 开灯次数 == 关灯次数 == 轮数，且没有"停几拍"
export PATH=$HOME/re-tools/platform-tools:$PATH
N=${1:-3}
GAP=${2:-1.2}
PKG=com.lm.xiaozhi

osascript -e 'set volume output volume 100' >/dev/null 2>&1
say -v Tingting -o /tmp/q_on.wav  --data-format=LEI16@16000 "小智开灯" 2>/dev/null
say -v Tingting -o /tmp/q_off.wav --data-format=LEI16@16000 "小智关灯" 2>/dev/null
adb shell "su 0 rm -f /data/data/$PKG/files/xz.log" 2>/dev/null
sleep 1

echo "=== 连喊 $((N*2)) 句，间隔 ${GAP}s ==="
for i in $(seq 1 $N); do
    echo ">>> [$i] 开灯"; afplay /tmp/q_on.wav;  sleep "$GAP"
    echo ">>> [$i] 关灯"; afplay /tmp/q_off.wav; sleep "$GAP"
done
sleep 4

adb shell "su 0 cat /data/data/$PKG/files/xz.log 2>/dev/null" > /tmp/q.log 2>&1

ON=$(grep -c "开灯 成功" /tmp/q.log)
OFF=$(grep -c "关灯 成功" /tmp/q.log)
REC=$(grep -c "识别:" /tmp/q.log)
echo ""
echo "================ 结果 ================"
echo "应执行   : 开灯 $N 次 / 关灯 $N 次"
echo "实际执行 : 开灯 $ON 次 / 关灯 $OFF 次"
echo "收到识别 : $REC 条"
if [ "$ON" = "$N" ] && [ "$OFF" = "$N" ]; then
    echo "判定     : ✓ 全中，无漏"
else
    echo "判定     : ✗ 有漏（丢 $((N-ON)) 次开灯 / $((N-OFF)) 次关灯）"
fi
echo ""
echo "--- 每条的「说完到执行」 ---"
grep -E "开灯 成功|关灯 成功" /tmp/q.log | sed 's/.*I\/Xz: //'
echo ""
echo "--- 漏掉的位置（识别到了但没执行）---"
grep -E "识别:" /tmp/q.log | sed 's/.*I\/Xz: //'
