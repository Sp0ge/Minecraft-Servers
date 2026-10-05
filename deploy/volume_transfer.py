"""Offline export/import of persistent Minecraft volumes; never overwrite data."""
import argparse,subprocess,json,hashlib,os
from pathlib import Path
NAMES=['lobby','survival','state','secrets','proxy','backups','parkour','pvp','duels']
def digest(path):
 h=hashlib.sha256()
 with path.open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
def run(*args):return subprocess.check_output(['docker',*args],text=True).strip()
def stopped():
 for name in NAMES:
  if run('ps','-q','--filter','volume=mcservers_'+name):raise SystemExit('Stop network before transferring volumes: '+name)
def helper(name,folder,code,mode='ro'):
 subprocess.run(['docker','run','--rm','--network','none','--memory','512m','--cpus','.25',
  '--label','mcservers.transfer=true','-v',f'mcservers_{name}:/source:{mode}',
  '-v',str(folder)+':/transfer','minecraft-controller:local','python','-c',code,name],check=True)
p=argparse.ArgumentParser();p.add_argument('action',choices=['export','import']);p.add_argument('--directory',required=True);a=p.parse_args()
folder=Path(a.directory).resolve();stopped()
if a.action=='export':
 if folder.exists():raise SystemExit('Choose a new export directory')
 folder.mkdir(parents=True,mode=0o700);manifest={}
 existing=set(run('volume','ls','--format','{{.Name}}').splitlines())
 for name in NAMES:
  if 'mcservers_'+name not in existing:
   if name=='backups':continue
   raise SystemExit('Missing source volume: '+name)
  helper(name,folder,"import tarfile,sys,os; p='/transfer/'+sys.argv[1]+'.tar.gz'; t=tarfile.open(p,'w:gz'); t.add('/source',arcname='.'); t.close(); os.chmod(p,0o600)")
  f=folder/(name+'.tar.gz');manifest[f.name]=digest(f)
 (folder/'volumes.json').write_text(json.dumps(manifest,indent=2));os.chmod(folder/'volumes.json',0o600)
else:
 import tarfile
 manifest=json.loads((folder/'volumes.json').read_text())
 for filename,expected_digest in manifest.items():
  if filename not in [n+'.tar.gz' for n in NAMES]:raise SystemExit('Unexpected archive name')
  f=folder/filename
  if digest(f)!=expected_digest:raise SystemExit('Checksum mismatch: '+filename)
  with tarfile.open(f) as t:
   for m in t:
    if m.name.startswith('/') or '..' in Path(m.name).parts or m.issym() or m.islnk() or m.isdev():raise SystemExit('Unsafe archive entry: '+m.name)
 # Check every destination before importing any archive.
 for filename in manifest:
  name=filename.removesuffix('.tar.gz')
  run('volume','create','--label','com.docker.compose.project=mcservers','--label','com.docker.compose.volume='+name,'mcservers_'+name)
  helper(name,folder,"from pathlib import Path; assert not any(Path('/source').iterdir()), 'Target volume not empty'")
 for filename in manifest:
  name=filename.removesuffix('.tar.gz')
  helper(name,folder,"import tarfile,sys; t=tarfile.open('/transfer/'+sys.argv[1]+'.tar.gz'); t.extractall('/source',filter='data'); t.close()",'rw')
print('Offline volume transfer completed. Archives contain private data; keep directory restricted.')
