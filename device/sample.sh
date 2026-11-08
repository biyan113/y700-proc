#!/system/bin/sh
# 采样一次 CPU 和内存，输出一行 JSON。参数 loop 时每 2 秒一次。
# 不用 toybox tr。toybox 的 tr '\0' 会空转占满一核。
BB=/data/adb/magisk/busybox
DIR=/data/adb/y700-proc
LOG=$DIR/proc.log
mkdir -p "$DIR"

run_once() {
  "$BB" awk -v bb="$BB" '
function ncpu(   n, line) {
  n = 0
  while ((getline line < "/proc/stat") > 0)
    if (line ~ /^cpu[0-9]/) n++
  close("/proc/stat")
  return n < 1 ? 1 : n
}
function cpu_pair(   line, n, i, s, idle) {
  s = 0
  idle = 0
  while ((getline line < "/proc/stat") > 0) {
    if (line ~ /^cpu /) {
      n = split(line, f, " ")
      for (i = 2; i <= 8 && i <= n; i++) s += f[i]
      idle = f[5] + 0
      if (n >= 6) idle += f[6]
      break
    }
  }
  close("/proc/stat")
  cpu_total = s
  cpu_idle = idle
}
function meminfo(   line, mf) {
  mem_total = 0
  mem_avail = 0
  swap_total = 0
  swap_free = 0
  while ((getline line < "/proc/meminfo") > 0) {
    split(line, mf, /[ :]+/)
    if (mf[1] == "MemTotal") mem_total = mf[2] + 0
    else if (mf[1] == "MemAvailable") mem_avail = mf[2] + 0
    else if (mf[1] == "SwapTotal") swap_total = mf[2] + 0
    else if (mf[1] == "SwapFree") swap_free = mf[2] + 0
  }
  close("/proc/meminfo")
}
function collect(slot,   cmd, line, pid, sl, name, uid, rss, st, m, rest, nf, ticks) {
  cmd = "ls -d /proc/[0-9]*"
  while ((cmd | getline line) > 0) {
    pid = line
    sub(/^.*\//, "", pid)
    name = ""
    uid = -1
    rss = 0
    while ((getline sl < ("/proc/" pid "/status")) > 0) {
      if (sl ~ /^Name:/) {
        name = sl
        sub(/^Name:[ \t]*/, "", name)
      } else if (sl ~ /^Uid:/) {
        split(sl, uf, /[ \t]+/)
        uid = uf[2] + 0
      } else if (sl ~ /^VmRSS:/) {
        split(sl, rf, /[ \t]+/)
        rss = rf[2] + 0
      }
    }
    close("/proc/" pid "/status")
    ticks = 0
    st = ""
    if ((getline st < ("/proc/" pid "/stat")) > 0) {
      m = match(st, /\) /)
      if (m > 0) {
        rest = substr(st, m + RLENGTH)
        nf = split(rest, ff, " ")
        if (nf >= 13) ticks = ff[12] + ff[13]
      }
    }
    close("/proc/" pid "/stat")
    if (slot == 1) {
      ta[pid] = ticks
      ra[pid] = rss
      ua[pid] = uid
      na[pid] = name
    } else {
      tb[pid] = ticks
    }
  }
  close(cmd)
}
function esc(s) {
  gsub(/\\/, "", s)
  gsub(/"/, "", s)
  gsub(/[\n\r\t]/, " ", s)
  return s
}
function emit(   dt, di, busy, one, pid, dlt, pct, uid, rss, name, key) {
  dt = cpu_total - base_total
  di = cpu_idle - base_idle
  if (dt < 1) dt = 1
  busy = (dt - di) * 100 / dt
  if (busy < 0) busy = 0
  if (busy > 100) busy = 100
  one = dt / nc
  if (one < 1) one = 1
  delete seen
  for (pid in tb) {
    dlt = tb[pid] - ta[pid]
    if (dlt < 0) dlt = 0
    pct = dlt * 100 / one
    uid = ua[pid] + 0
    if (ua[pid] == "") continue
    rss = ra[pid] + 0
    name = na[pid]
    if (rss < 20000 && pct < 1) continue
    if (uid >= 10000) key = "u" uid
    else key = "p" pid
    if (!(key in seen)) {
      seen[key] = 1
      krss[key] = 0
      kcpu[key] = 0
      kn[key] = 0
      kuid[key] = uid
      kpid[key] = pid
      kname[key] = name
    }
    krss[key] += rss
    kcpu[key] += pct
    kn[key]++
    if (rss >= krss_top[key]) {
      krss_top[key] = rss
      kname[key] = name
      kpid[key] = pid
    }
  }
  items = ""
  nitem = 0
  top_mem_name = ""
  top_mem = 0
  top_cpu_name = ""
  top_cpu = 0
  for (key in seen) {
    if (nitem >= 80) break
    if (nitem > 0) items = items ","
    nitem++
    name = esc(kname[key])
    items = items sprintf("{\"key\":\"%s\",\"uid\":%d,\"pid\":%d,\"name\":\"%s\",\"rss\":%d,\"cpu\":%.1f,\"n\":%d}", key, kuid[key], kpid[key], name, krss[key], kcpu[key], kn[key])
    if (krss[key] > top_mem) { top_mem = krss[key]; top_mem_name = name }
    if (kcpu[key] > top_cpu) { top_cpu = kcpu[key]; top_cpu_name = name }
  }
  meminfo()
  printf "{\"ok\":true,\"mem_total\":%d,\"mem_avail\":%d,\"swap_used\":%d,\"cpu\":%.1f,\"ncpu\":%d,\"items\":[%s]}\n", mem_total, mem_avail, (swap_total - swap_free), busy, nc, items
  fflush()
  cmd = bb " date +%Y-%m-%dT%H:%M:%S"
  stamp = ""
  if ((cmd | getline stamp) > 0) { }
  close(cmd)
  msg = stamp " avail=" int(mem_avail / 1024) "MB cpu=" sprintf("%.1f", busy) "% top_mem=" top_mem_name " " int(top_mem / 1024) "MB top_cpu=" top_cpu_name " " sprintf("%.1f", top_cpu) "%"
  print msg >> "/data/adb/y700-proc/proc.log"
  close("/data/adb/y700-proc/proc.log")
}
BEGIN {
  nc = ncpu()
  cpu_pair()
  base_total = cpu_total
  base_idle = cpu_idle
  collect(1)
  system("sleep 1")
  cpu_pair()
  collect(2)
  emit()
}
'
  if [ -f "$LOG" ]; then
  size=$("$BB" wc -c < "$LOG")
  else
  size=0
  fi
  if [ "${size:-0}" -gt 200000 ]; then
    "$BB" tail -c 100000 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
  fi
}

if [ "${1:-}" = "once" ]; then
  run_once
else
  while true; do
    run_once
    sleep 1
  done
fi
