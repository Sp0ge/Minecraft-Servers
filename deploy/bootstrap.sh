#!/usr/bin/env bash
set -euo pipefail
if [[ ${EUID} -ne 0 ]]; then echo 'Run with sudo on Ubuntu.' >&2; exit 1; fi
. /etc/os-release
[[ $ID == ubuntu ]] || { echo 'Ubuntu required' >&2; exit 1; }
[[ $(uname -m) == x86_64 ]] || { echo 'x86_64 VM required' >&2; exit 1; }
if ! command -v docker >/dev/null; then
  apt-get update
  apt-get install -y ca-certificates curl
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  cat >/etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
apt-get install -y python3 python3-venv sysstat
systemctl enable --now docker.service
docker compose version
[[ $(docker info --format '{{.CgroupDriver}}') == systemd ]] || { echo 'Docker systemd cgroup driver required; see deployment guide.' >&2; exit 1; }
[[ $(docker info --format '{{.CgroupVersion}}') == 2 ]] || { echo 'cgroup v2 required' >&2; exit 1; }
SCRIPT_DIR=$(cd -- "$(dirname -- "$0")" && pwd)
install -m 0644 "$SCRIPT_DIR/minecraft.slice" /etc/systemd/system/minecraft.slice
systemctl daemon-reload
systemctl start minecraft.slice
echo 'Docker and Minecraft resource slice prepared. No firewall rules were changed.'
