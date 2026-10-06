#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
ROOT=$PWD
PROFILE=${1:-ubuntu}
[[ $PROFILE == ubuntu || $PROFILE == --local ]] || { echo 'Использование: bash start.sh [--local]' >&2; exit 1; }
if ! command -v python3 >/dev/null; then
 if [[ $(uname -s) == Linux ]] && command -v apt-get >/dev/null; then
  if [[ $EUID == 0 ]]; then apt-get update; apt-get install -y python3; else sudo apt-get update; sudo apt-get install -y python3; fi
 else echo 'Установите Python 3, затем повторите запуск.' >&2; exit 1; fi
fi
export DATA_ROOT=$(python3 -c 'import os; print(os.path.abspath(os.environ.get("DATA_ROOT", "./ServerData")))')
export SOURCE_ROOT="$DATA_ROOT/source"
export NETWORK_REVISION=$(python3 - "$ROOT/network-sources.tar.gz" <<'PYHASH'
import hashlib,sys
from pathlib import Path
print(hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest())
PYHASH
)
mkdir -p "$DATA_ROOT" "$SOURCE_ROOT"
# The archive ships the exact component sources; no Git checkout or branch downloads are required.
python3 - "$ROOT/network-sources.tar.gz" "$SOURCE_ROOT" <<'PY'
import sys,tarfile,hashlib
from pathlib import Path
archive=Path(sys.argv[1]);target=Path(sys.argv[2])
expected='a56677b78e42acf9efd594d4895a9e581ba9a15c522e33b5993a6d51e01dc6da'
if hashlib.sha256(archive.read_bytes()).hexdigest()!=expected:raise SystemExit('Повреждён архив исходников KiwyClub')
with tarfile.open(archive) as t:
 for member in t.getmembers():
  dest=(target/member.name).resolve()
  if target.resolve() not in dest.parents or not (member.isfile() or member.isdir()):raise SystemExit('Недопустимый путь в архиве')
 t.extractall(target)
PY
if ! command -v docker >/dev/null; then
 [[ $(uname -s) == Linux ]] || { echo 'Установите и запустите Docker Desktop, затем повторите запуск.' >&2; exit 1; }
 if [[ $EUID == 0 ]]; then bash "$SOURCE_ROOT/infra/deploy/bootstrap.sh"; else sudo bash "$SOURCE_ROOT/infra/deploy/bootstrap.sh"; fi
fi
DOCKER=(docker)
if ! docker info >/dev/null 2>&1; then
 if [[ $(uname -s) == Linux ]] && sudo docker info >/dev/null 2>&1; then DOCKER=(sudo docker); else echo 'Docker не запущен или недоступен.' >&2; exit 1; fi
fi
"${DOCKER[@]}" compose version >/dev/null
for name in assets arenas plugins secrets state lobby survival proxy parkour pvp duels backups; do mkdir -p "$DATA_ROOT/$name"; done
chmod 700 "$DATA_ROOT/secrets"
# Preserve this project's old named volumes on the first start after upgrading.
for name in plugins secrets state lobby survival proxy parkour pvp duels backups; do
 if [[ -z $(ls -A "$DATA_ROOT/$name") ]] && "${DOCKER[@]}" volume inspect "mcservers_$name" >/dev/null 2>&1; then
  if [[ -n $("${DOCKER[@]}" ps -q --filter "volume=mcservers_$name") ]]; then echo "Остановите старую сеть перед переносом $name" >&2; exit 1; fi
  "${DOCKER[@]}" run --rm --network none --mount "type=volume,src=mcservers_$name,dst=/from,readonly" --mount "type=bind,src=$DATA_ROOT/$name,dst=/to" alpine:3.21 sh -c 'cp -a /from/. /to/'
 fi
done
python3 "$SOURCE_ROOT/infra/prepare.py" --assets "$DATA_ROOT/assets" --map "$SOURCE_ROOT/pillars-map.zip"
ENV_FILE="$DATA_ROOT/settings.env"
if [[ ! -f $ENV_FILE ]]; then
 if [[ $PROFILE == --local ]]; then cp "$SOURCE_ROOT/infra/local.env" "$ENV_FILE"; else
  sed '/^PROJECT_ROOT=/d; /^MC_CGROUP_PARENT=/d' "$SOURCE_ROOT/infra/deploy/ubuntu.env" >"$ENV_FILE"
  printf '\nBIND_ADDRESS=0.0.0.0\n' >>"$ENV_FILE"
 fi
 chmod 600 "$ENV_FILE"
fi
"${DOCKER[@]}" compose --env-file "$ENV_FILE" --profile build config -q
"${DOCKER[@]}" compose --env-file "$ENV_FILE" --profile build build
"${DOCKER[@]}" compose --env-file "$ENV_FILE" up -d
printf '\nKiwyClub запущен. Данные: %s\nMinecraft: localhost:25565 (локально) или IP вашей машины:25565\n' "$DATA_ROOT"
echo 'Первый запуск миров занимает несколько минут. Настройки: ServerData/settings.env'
