#!/system/bin/sh
# 守护一键起停（带 pid 文件，杜绝重复实例）
# 用法: sh guards.sh start|stop|status
D=/data/local/tmp
BB=/sbin/busybox
SENT=02-disk_sentinel.sh
GUARD=01-disk_guard.sh
LAMP=lamp_icon_guard.sh

stopone() {
    n=$1
    [ -f "$D/$n.pid" ] && kill -9 "$(cat $D/$n.pid)" 2>/dev/null
    rm -f "$D/$n.pid"
}
startone() {
    n=$1; f=$2
    stopone "$n"
    $BB setsid /system/bin/sh "$D/$f" </dev/null >/dev/null 2>&1 &
    echo $! > "$D/$n.pid"
}
alive() {
    n=$1
    [ -f "$D/$n.pid" ] || { echo "  $n: 未运行"; return; }
    p=$(cat "$D/$n.pid")
    if kill -0 "$p" 2>/dev/null; then echo "  $n: 运行中 pid=$p"; else echo "  $n: 已死（pid $p）"; fi
}
case "$1" in
start)
    startone sentinel "$SENT"
    startone guard "$GUARD"
    startone lamp "$LAMP"
    sleep 3
    $0 status ;;
stop)
    stopone sentinel; stopone guard; stopone lamp
    echo "已全部停止" ;;
status)
    alive sentinel; alive guard; alive lamp
    $BB df /data | $BB tail -1 ;;
*)
    echo "用法: sh $0 start|stop|status" ;;
esac
