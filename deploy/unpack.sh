#!/usr/bin/env bash
set -euo pipefail
PACKAGE_DIR=$(cd -- "${1:?Pass the release directory containing KiwyClub-main.zip}" && pwd)
TARGET=${2:-/opt/minecraft-servers}
[[ ! -e $TARGET ]] || { echo 'Target must not exist' >&2; exit 1; }
(cd "$PACKAGE_DIR" && sha256sum -c SHA256SUMS)
python3 - "$PACKAGE_DIR/KiwyClub-main.zip" "$TARGET" <<'PY'
import sys,zipfile
from pathlib import Path
dest=Path(sys.argv[2]);dest.mkdir(parents=True)
with zipfile.ZipFile(sys.argv[1]) as z:
 for member in z.infolist():
  path=Path(member.filename)
  if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0]!='KiwyClub':raise SystemExit('Unsafe package path')
  if not member.is_dir():
   target=dest.joinpath(*path.parts[1:]);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(member))
PY
echo "Prepared: $TARGET. Start with: cd '$TARGET' && bash start.sh"
