import secrets,json,datetime
from pathlib import Path
def prepare_season(root):
 metadata=root/'season.json'
 if not (root/'world/level.dat').exists():
  old=json.loads(metadata.read_text()).get('seed') if metadata.exists() else None
  fresh=secrets.randbits(63)
  while fresh==old:fresh=secrets.randbits(63)
  metadata.write_text(json.dumps({'id':secrets.token_hex(16),'seed':fresh,'created_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}))
  (root/'kiwy-settled.json').unlink(missing_ok=True)
 elif not metadata.exists():raise RuntimeError('Existing Fabric world has no season metadata; restore season.json')
 value=json.loads(metadata.read_text())['seed']
 return str(value) if value is not None else ''
