"""Download only hash-pinned files. Build a portable client .mrpack without redistributing jars."""
import argparse,json,hashlib,urllib.request,pathlib,concurrent.futures,zipfile,shutil
ROOT=pathlib.Path(__file__).parent
p=argparse.ArgumentParser();p.add_argument('--assets',required=True);p.add_argument('--client-output');p.add_argument('--include-client',action='store_true');args=p.parse_args()
lock=json.loads((ROOT/'mods.lock.json').read_text());dest=pathlib.Path(args.assets)/'fabric-1.20.1';dest.mkdir(parents=True,exist_ok=True)
def fetch(f):
 path=dest/f['filename']
 if not path.exists() or hashlib.sha512(path.read_bytes()).hexdigest()!=f['sha512']:
  req=urllib.request.Request(f['url'],headers={'User-Agent':'KiwyClub/1.0 (github.com/Sp0ge/Minecraft-Servers)'})
  data=urllib.request.urlopen(req,timeout=180).read()
  if hashlib.sha512(data).hexdigest()!=f['sha512']:raise RuntimeError('Checksum mismatch: '+f['filename'])
  tmp=path.with_suffix('.partial');tmp.write_bytes(data);tmp.replace(path)
 return f['slug']
# Only server artifacts are needed to run Docker; client launchers resolve .mrpack downloads themselves.
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
 for slug in pool.map(fetch,[f for f in lock['files'] if 'server' in f['sides'] or args.include_client]):print(slug,'verified')
shutil.copyfile(ROOT/'mods.lock.json',dest/'mods.lock.json')
if args.client_output:
 out=pathlib.Path(args.client_output);out.parent.mkdir(parents=True,exist_ok=True)
 index={'formatVersion':1,'game':'minecraft','versionId':'1.0.0','name':'KiwyClub Survival','summary':'KiwyClub — приключения, исследования, голосовой чат и безопасные PvP-дуэли.','dependencies':{'minecraft':lock['minecraft'],'fabric-loader':lock['fabric_loader']},'files':[]}
 for f in lock['files']:
  if 'client' in f['sides']:index['files'].append({'path':'mods/'+f['filename'],'hashes':{'sha1':f['sha1'],'sha512':f['sha512']},'env':{'client':'required','server':'unsupported'},'downloads':[f['url']],'fileSize':f['size']})
 with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
  z.writestr('modrinth.index.json',json.dumps(index,ensure_ascii=False,indent=2));z.writestr('overrides/options.txt','lang:ru_ru\nrenderDistance:8\nsimulationDistance:4\n')
  z.writestr('overrides/KiwyClub.md','Импортируйте эту сборку в Modrinth App или Prism Launcher. Вход только с официальной учётной записью Minecraft. Survival и PvP требуют Minecraft 1.20.1 и эту сборку. Другие режимы остаются доступны через Velocity.\n')
 print('Client pack:',out)
