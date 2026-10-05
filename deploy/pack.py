"""Package committed branches and verified assets, without player data or secrets."""
import argparse,subprocess,tarfile,hashlib,json,shutil
from pathlib import Path
BRANCHES=['main','infra-controller','proxy-velocity','lobby-1.21.11-paper','SV-survival-26.3-paper','SV-pillars-1.21.10-paper','SV-parkour-26.3-paper','SV-pvp-26.3-paper']
p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--root',default='.');a=p.parse_args()
root=Path(a.root).resolve();dest=Path(a.output).resolve()
if dest.exists():raise SystemExit('Output must not exist')
for branch in BRANCHES:subprocess.run(['git','rev-parse','--verify',branch],cwd=root,check=True,stdout=subprocess.DEVNULL)
for folder in [root]+list((root/'.runtime/source').iterdir()):
 if subprocess.check_output(['git','status','--porcelain'],cwd=folder).strip():raise SystemExit('Commit source changes first: '+str(folder))
assets=root/'.runtime/assets';lock=json.loads((root/'.runtime/source/infra/assets.lock.json').read_text())
files=[name for _,name,_ in lock]+['pillars.zip']
for _,name,digest in lock:
 if hashlib.sha256((assets/name).read_bytes()).hexdigest()!=digest:raise SystemExit('Asset checksum mismatch: '+name)
dest.mkdir(parents=True)
subprocess.run(['git','bundle','create',str(dest/'repository.bundle'),*BRANCHES],cwd=root,check=True)
with tarfile.open(dest/'assets.tar.gz','w:gz') as t:
 for name in files:t.add(assets/name,arcname='assets/'+name)
deploy=root/'.runtime/source/infra/deploy'
for name in ['bootstrap.sh','unpack.sh','minecraft.slice','Ubuntu.md']:shutil.copyfile(deploy/name,dest/name)
manifest={branch:subprocess.check_output(['git','rev-parse',branch],cwd=root,text=True).strip() for branch in BRANCHES}
(dest/'release.json').write_text(json.dumps(manifest,indent=2)+'\n')
lines=[]
for f in sorted(dest.iterdir()):lines.append(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name)
(dest/'SHA256SUMS').write_text('\n'.join(lines)+'\n')
print('Release:',dest)
