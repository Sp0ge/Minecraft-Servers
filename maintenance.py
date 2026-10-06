"""Offline season reset; never touch AuthMe or any volume outside survival."""
import os, shutil, tarfile, hashlib, json, datetime
from pathlib import Path
root=Path(os.getenv('SURVIVAL_PATH','/survival'));backups=Path(os.getenv('BACKUPS_PATH','/backups'));backups.mkdir(exist_ok=True)
if (root/'.wipe-in-progress').exists():raise RuntimeError('Interrupted previous wipe; manual recovery required')
worlds=[p for p in root.iterdir() if p.is_dir() and ((p/'level.dat').exists() or p.name in ('world','world_nether','world_the_end'))]
if not worlds:raise RuntimeError('No worlds found; refusing reset')
if os.getenv('PREVIOUS_SEED'):
 metadata=root/'season.json';season=json.loads(metadata.read_text()) if metadata.exists() else {};season['seed']=int(os.environ['PREVIOUS_SEED']);metadata.write_text(json.dumps(season))
size=sum(p.stat().st_size for world in worlds for p in world.rglob('*') if p.is_file())
if shutil.disk_usage(backups).free<size*1.2+1024**3:raise RuntimeError('Insufficient free space for backup; reset cancelled')
stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
archive=backups/('survival-'+stamp+'.tar.gz');tmp=archive.with_suffix('.partial')
with tarfile.open(tmp,'w:gz') as tar:
 for world in worlds:tar.add(world,arcname=world.name)
 if (root/'season.json').exists():tar.add(root/'season.json',arcname='season.json')
 for name in ['kiwy-settled.json','kiwy-chunky.json','config/chunky/tasks','config/chunky/config.json']:
  if (root/name).exists():tar.add(root/name,arcname=name)
with tarfile.open(tmp,'r:gz') as tar:
 for member in tar:
  if member.isfile():
   f=tar.extractfile(member)
   while f.read(1024*1024):pass
checksum=hashlib.file_digest(tmp.open('rb'),'sha256').hexdigest();tmp.replace(archive)
archive.with_suffix('.sha256').write_text(checksum+'  '+archive.name+'\n')
# Retain a marker so a failed deletion is never silently presented as success.
(root/'.wipe-in-progress').write_text(json.dumps({'backup':archive.name,'worlds':[p.name for p in worlds]}))
for world in worlds:shutil.rmtree(world)
for name in ['kiwy-settled.json','kiwy-chunky.json']:(root/name).unlink(missing_ok=True)
if (root/'config/chunky/tasks').is_dir():shutil.rmtree(root/'config/chunky/tasks')
(root/'.wipe-in-progress').unlink()
keep=int(os.getenv('BACKUP_KEEP','2'))
for old in sorted(backups.glob('survival-*.tar.gz'))[:-keep]:old.unlink();old.with_suffix('.sha256').unlink(missing_ok=True)
print(json.dumps({'backup':archive.name,'sha256':checksum,'reset_worlds':[p.name for p in worlds]}))
