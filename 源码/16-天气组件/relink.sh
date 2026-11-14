#!/bin/bash
# 天气组件「安装 + 重新挂载到桌面槽位」一键脚本
# 说明：每次 adb install -r 之后，桌面会重建 item_info 里的天气行并丢掉绑定，
#       所以必须跑一遍这个脚本重新绑定并写回 appWidgetId。
set -e
export PATH=$HOME/re-tools/platform-tools:$PATH
D=${D:-192.168.1.151:5555}
PKG=com.nemo.weather
PROV=$PKG/$PKG.WeatherWidget
DB=/data/data/com.xylink.launcher/databases/_setting.db

echo "=== 1) 安装 APK ==="
adb -s $D install -r $HOME/re-tools/t70l/wxwidget/nemowx.apk | tail -1
sleep 2

echo "=== 2) 当前天气行 ==="
adb -s $D shell "sqlite3 $DB 'select _id,title,appWidgetId from item_info where title=\"天气\"'" | tr -d '\r'

echo "=== 3) 以 root 身份绑定组件 ==="
OUT=$(adb -s $D shell "CLASSPATH=/data/local/tmp/binder.jar app_process /system/bin com.nemo.tool.WidgetBinder $PKG $PKG.WeatherWidget 1024" | tr -d '\r')
echo "$OUT"
ID=$(echo "$OUT" | grep '^ALLOCATED=' | cut -d= -f2)
if [ -z "$ID" ]; then echo "绑定失败，中止"; exit 1; fi

echo "=== 4) 停桌面 → 写回 id → 启桌面 ==="
PID=$(adb -s $D shell "ps" | grep com.xylink.launcher | awk '{print $2}')
adb -s $D shell "kill -9 $PID" >/dev/null 2>&1 || true
sleep 2
adb -s $D shell "sqlite3 $DB \"update item_info set appWidgetProvider='$PROV', appWidgetId=$ID where title='天气'; select _id,title,appWidgetId from item_info where title='天气';\"; chown 10024:10024 $DB; sync" | tr -d '\r'
adb -s $D shell "am start -n com.xylink.launcher/.app.LauncherActivity" >/dev/null 2>&1
sleep 8

echo "=== 5) 触发一次刷新 ==="
adb -s $D shell "am broadcast -a com.nemo.weather.REFRESH -n $PROV -f 32" | tail -1
sleep 6
echo "DONE  widgetId=$ID"
