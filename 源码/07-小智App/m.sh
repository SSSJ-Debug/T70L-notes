#!/bin/bash
# m.sh —— 多轮对话实测（唱一句 → 等它答完 → 再唱下一句）
# 用法: bash m.sh "小智陪我聊聊" "今天天气怎么样" "不聊了"
export PATH=$HOME/re-tools/platform-tools:$PATH
osascript -e 'set volume output volume 100' >/dev/null 2>&1
adb shell "su 0 rm -f /data/data/com.lm.xiaozhi/files/xz.log" 2>/dev/null
sleep 1

for line in "$@"; do
    say -v Tingting -o /tmp/m_t.wav --data-format=LEI16@16000 "$line" 2>/dev/null
    echo ">>> 说: $line"
    afplay /tmp/m_t.wav
    sleep 13
done

adb shell "su 0 cat /data/data/com.lm.xiaozhi/files/xz.log 2>/dev/null" > /tmp/m.log 2>&1
echo "======== 日志 ========"
grep -E "开口|收尾|识别|回答|本轮结束|畅聊|会话|结束|没喊|不理" /tmp/m.log | tail -30
