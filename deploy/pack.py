"""Package portable main files without Git operations, runtime data or secrets."""
import argparse,hashlib,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--root',default='.');a=p.parse_args()
root=Path(a.root).resolve();dest=Path(a.output).resolve()
if dest.exists():raise SystemExit('Output must not exist')
files=['.gitignore','Readme.md','docker-compose.yml','start.sh','network-sources.tar.gz']
if not all((root/name).is_file() for name in files):raise SystemExit('Choose the main project root containing start.sh and network-sources.tar.gz')
dest.mkdir(parents=True);archive=dest/'KiwyClub-main.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for name in files:z.write(root/name,'KiwyClub/'+name)
(dest/'SHA256SUMS').write_text(hashlib.sha256(archive.read_bytes()).hexdigest()+'  '+archive.name+'\n')
print('Release:',archive)
