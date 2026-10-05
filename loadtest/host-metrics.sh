#!/usr/bin/env bash
set -euo pipefail
OUTPUT=${1:?Pass an output directory}
mkdir -p "$OUTPUT"
date -u +%FT%TZ >"$OUTPUT/start-utc.txt"
lscpu >"$OUTPUT/cpu.txt"
free -b >"$OUTPUT/memory.txt"
lsblk -o NAME,SIZE,TYPE,ROTA,MODEL >"$OUTPUT/disks.txt"
iostat -xz -y 5 >"$OUTPUT/iostat.txt" & IO_PID=$!
vmstat -w 5 >"$OUTPUT/vmstat.txt" & VM_PID=$!
trap 'kill "$IO_PID" "$VM_PID" 2>/dev/null || true' EXIT
trap 'exit 0' INT TERM
while true; do
  date -u +%FT%TZ
  for name in memory.current memory.events cpu.stat; do
    echo "$name"
    cat "/sys/fs/cgroup/minecraft.slice/$name"
  done
  df -Pk /var/lib/docker
  sleep 5
done >"$OUTPUT/cgroup.txt"
