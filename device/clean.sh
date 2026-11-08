#!/system/bin/sh
# 关掉一个普通应用的后台。不碰桌面、系统界面和本应用。
BB=/data/adb/magisk/busybox
DIR=/data/adb/y700-proc
LOG=$DIR/proc.log
pkg=$1
mkdir -p "$DIR"

case "$pkg" in
  ""|*[!a-zA-Z0-9._]*)
    echo '{"ok":false,"error":"bad"}'
    exit 1
    ;;
esac
case "$pkg" in
  com.zui.launcher|com.android.systemui|android|app.y700.proc)
    echo '{"ok":false,"error":"protected"}'
    exit 1
    ;;
esac

uid=$(pm list packages -U --user 0 | "$BB" sed -n "s/^package:${pkg} uid://p")
if [ -z "$uid" ] || [ "$uid" -lt 10000 ]; then
  echo '{"ok":false,"error":"not_app"}'
  exit 1
fi

before=$(awk '/MemAvailable/{print $2; exit}' /proc/meminfo)
am force-stop --user 0 "$pkg"
sleep 1
after=$(awk '/MemAvailable/{print $2; exit}' /proc/meminfo)
stamp=$("$BB" date '+%Y-%m-%d %H:%M:%S')
echo "$stamp clean $pkg uid=$uid avail ${before}KB -> ${after}KB" >> "$LOG"
printf '{"ok":true,"pkg":"%s","before":%s,"after":%s}\n' "$pkg" "${before:-0}" "${after:-0}"
