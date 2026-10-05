#!/usr/bin/env bash
set -euo pipefail
PACKAGE_DIR=$(cd -- "${1:?Pass the unpacked release directory}" && pwd)
TARGET=${2:-/opt/minecraft-servers}
[[ ! -e $TARGET ]] || { echo 'Target must not exist' >&2; exit 1; }
(cd "$PACKAGE_DIR" && sha256sum -c SHA256SUMS)
git clone --branch main "$PACKAGE_DIR/repository.bundle" "$TARGET"
cd "$TARGET"
mkdir -p .runtime/source
printf '\n/.runtime/\n' >>.git/info/exclude
while read -r path branch; do
  git worktree add -b "$branch" ".runtime/source/$path" "origin/$branch"
done <<'EOF'
infra infra-controller
proxy proxy-velocity
lobby lobby-1.21.11-paper
survival SV-survival-26.3-paper
pillars SV-pillars-1.21.10-paper
parkour SV-parkour-26.3-paper
pvp SV-pvp-26.3-paper
EOF
tar -xzf "$PACKAGE_DIR/assets.tar.gz" -C .runtime
cp .runtime/source/infra/deploy/ubuntu.env .runtime/ubuntu.env
python3 -c 'from pathlib import Path; p=Path(".runtime/ubuntu.env"); p.write_text(p.read_text().replace("PROJECT_ROOT=/opt/minecraft-servers", "PROJECT_ROOT="+str(Path.cwd())))'
python3 -m venv .runtime/tools-venv
.runtime/tools-venv/bin/pip install docker==7.1.0
export PROJECT_ROOT="$PWD"
docker compose --env-file .runtime/ubuntu.env --profile build build
echo 'Prepared. Restore optional volume backup before starting. Use start.sh when ready.'
