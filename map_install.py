"""Install a versioned lobby template offline; retain old worlds and AuthMe."""
import hashlib,json,shutil,zipfile,datetime
from pathlib import Path

def install(root, archive):
 archive=Path(archive)
 if not archive.is_file():raise RuntimeError('Lobby map missing: '+str(archive))
 revision=hashlib.sha256(archive.read_bytes()).hexdigest()
 marker=root/'lobby-map.json'
 if marker.exists() and json.loads(marker.read_text()).get('sha256')==revision and (root/'world/level.dat').is_file():return
 staging=root/'.lobby-map-staging'
 if staging.exists():shutil.rmtree(staging)
 staging.mkdir()
 with zipfile.ZipFile(archive) as z:
  for entry in z.infolist():
   rel=Path(entry.filename)
   if rel.is_absolute() or '..' in rel.parts or '\\' in entry.filename:raise RuntimeError('Unsafe map path')
   if entry.is_dir():continue
   if not rel.parts or rel.parts[0] in ('playerdata','stats','advancements') or rel.name in ('.DS_Store','session.lock','uid.dat','level.dat_old'):continue
   dest=staging/rel;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(z.read(entry))
 if not (staging/'level.dat').is_file() or not (staging/'region').is_dir():raise RuntimeError('Map must contain level.dat and region at archive root')
 stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
 backup=root/'map-backups'/stamp
 for name in ('world','world_nether','world_the_end'):
  world=root/name
  if world.exists():backup.mkdir(parents=True,exist_ok=True);world.rename(backup/name)
 staging.rename(root/'world')
 marker.write_text(json.dumps({'sha256':revision,'installed_at':stamp,'spawn':json.loads((root/'world/kiwy-map.json').read_text())['spawn']}))
 print('Lobby map installed; previous worlds retained at',backup)
