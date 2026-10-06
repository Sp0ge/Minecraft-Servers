"""Download pinned runtime assets; import a user-owned map without publishing it."""
import argparse,hashlib,json,urllib.request,zipfile,shutil
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--assets',default='.runtime/assets');parser.add_argument('--map',required=True);parser.add_argument('--lobby-map',required=True)
args=parser.parse_args();dest=Path(args.assets);dest.mkdir(parents=True,exist_ok=True)
shutil.copyfile(Path(__file__).with_name('voice_ports.py'),dest/'voice_ports.py')
for url,name,digest in json.loads(Path(__file__).with_name('assets.lock.json').read_text()):
 p=dest/name
 if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest()!=digest:
  req=urllib.request.Request(url,headers={'User-Agent':'MinecraftServers/1.0'})
  data=urllib.request.urlopen(req,timeout=120).read()
  if hashlib.sha256(data).hexdigest()!=digest:raise RuntimeError('Checksum mismatch: '+name)
  temp=p.with_suffix('.partial');temp.write_bytes(data);temp.replace(p)
 print(name,'verified')
source=Path(args.map);out=dest/'pillars.zip'
if source.is_dir():
 if not (source/'level.dat').exists():raise RuntimeError('Choose the world folder containing level.dat')
 with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
  for p in source.rglob('*'):
   if p.is_file() and not any(x in p.relative_to(source).parts for x in ['playerdata','stats','advancements']) and p.name not in ('session.lock','.DS_Store','level.dat_old'):
    z.write(p,p.relative_to(source))
elif source.resolve()!=out.resolve():shutil.copyfile(source,out)
with zipfile.ZipFile(out) as z:
 if len([n for n in z.namelist() if n.endswith('level.dat')])!=1:raise RuntimeError('Invalid map')
print('pillars.zip',hashlib.sha256(out.read_bytes()).hexdigest())
source=Path(args.lobby_map);out=dest/'lobby-map.zip'
if source.resolve()!=out.resolve():shutil.copyfile(source,out)
with zipfile.ZipFile(out) as z:
 if 'level.dat' not in z.namelist() or not any(n.startswith('region/') and n.endswith('.mca') for n in z.namelist()):raise RuntimeError('Invalid lobby map')
print('lobby-map.zip',hashlib.sha256(out.read_bytes()).hexdigest())

import subprocess,sys
subprocess.run([sys.executable,str(Path(__file__).parent/"modpack/install.py"),"--assets",str(dest)],check=True)
