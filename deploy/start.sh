#!/usr/bin/env bash
set -euo pipefail
cd -- "${1:-/opt/minecraft-servers}"
export PROJECT_ROOT="$PWD"
test -f .runtime/ubuntu.env
test "$(docker info --format '{{.CgroupDriver}}')" = systemd
systemctl is-active --quiet minecraft.slice
free_kib=$(df -Pk . | awk 'NR==2{print $4}')
[[ $free_kib -ge 10485760 ]] || { echo 'At least 10 GiB free required before start' >&2; exit 1; }
docker compose --env-file .runtime/ubuntu.env config -q
docker compose --env-file .runtime/ubuntu.env up -d
echo 'Started. Keep BIND_ADDRESS on loopback until verification is complete.'
