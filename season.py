import secrets,json,datetime
from pathlib import Path
def prepare_season(root):
 metadata=root/'season.json'
 if not (root/'world/level.dat').exists():
  old=json.loads(metadata.read_text()).get('seed') if metadata.exists() else None
  fresh=secrets.randbits(63)
  while fresh==old:fresh=secrets.randbits(63)
  metadata.write_text(json.dumps({'seed':fresh,'created_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}))
 elif not metadata.exists():metadata.write_text(json.dumps({'seed':None,'created_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}))
 value=json.loads(metadata.read_text())['seed']
 return str(value) if value is not None else ''
