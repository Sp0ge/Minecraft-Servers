import json,urllib.request
from pathlib import Path
req=urllib.request.Request('http://localhost:8080/status',headers={'Authorization':'Bearer '+Path('/secrets/api-token').read_text().strip()})
with urllib.request.urlopen(req,timeout=5) as r:print(json.dumps(json.load(r),indent=2,ensure_ascii=False))
