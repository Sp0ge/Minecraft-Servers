"""Maintainer-only resolver. Normal startup uses committed version/hash lock files."""
import json,urllib.request,urllib.parse,pathlib,concurrent.futures,zipfile
ROOT=pathlib.Path(__file__).parent; MC='1.20.1'
REQUESTED='terralith tectonic incendium nullscape explorify towns-and-towers dungeons-and-taverns friends-and-foes more-mob-variants better-combat artifacts lootr simple-voice-chat emotecraft styledplayerlist styled-chat luckperms ledger open-parties-and-claims chunky xaeros-minimap xaeros-world-map xaeroplus jade sound-physics-remastered presence-footsteps ambientsounds fallingleaves effective visuality not-enough-animations lithium ferrite-core modernfix krypton alternate-current noisium servercore c2me-fabric vmp-fabric spark yungs-better-dungeons yungs-better-mineshafts yungs-better-strongholds yungs-better-desert-temples yungs-better-jungle-temples yungs-better-ocean-monuments yungs-better-nether-fortresses yungs-better-witch-huts yungs-better-end-island yungs-bridges yungs-extras'.split()
EXTRA=['lithostitched','fabric-api','fabricproxy-lite','crossstitch']
cache={}
def get(path):
 if path not in cache:
  req=urllib.request.Request('https://api.modrinth.com/v2/'+path,headers={'User-Agent':'KiwyClub/1.0 (github.com/Sp0ge/Minecraft-Servers)'})
  cache[path]=json.load(urllib.request.urlopen(req,timeout=120))
 return cache[path]
projects={};selected={};explicit=set();queue=[]
def add(pid,exact=None,requested=False):
 p=get('project/'+pid);pid=p['id'];projects[pid]=p
 if exact and p['slug']=='fabric-api' and pid in selected:return pid
 if exact:
  v=get('version/'+exact)
  if pid in explicit and selected[pid]['id']!=v['id']:raise RuntimeError('Conflicting exact dependencies: '+p['slug'])
  explicit.add(pid)
 elif pid in selected:return pid
 else:
  candidates=get('project/'+pid+'/version?'+urllib.parse.urlencode({'game_versions':json.dumps([MC]),'loaders':json.dumps(['fabric'])}))
  if not candidates:raise RuntimeError('No Fabric '+MC+' release: '+p['slug'])
  # Prefer production releases, while retaining alpha/beta projects without stable builds.
  v=next((r for r in candidates if r['version_type']=='release'),candidates[0])
 if MC not in v['game_versions'] or 'fabric' not in v['loaders']:raise RuntimeError('Incompatible dependency: '+p['slug']+' '+v['version_number'])
 selected[pid]=v;queue.append(pid);return pid
for slug in REQUESTED+EXTRA:add(slug)
seen=set()
while queue:
 pid=queue.pop(0);v=selected[pid]
 if v['id'] in seen:continue
 seen.add(v['id'])
 for d in v['dependencies']:
  if d['dependency_type']=='required':
   target=d.get('project_id') or get('version/'+d['version_id'])['project_id'];add(target,d.get('version_id'))
# Determine installed sides from project declarations; dependency sides inherit parent need.
envs={pid:{s for s in ['client','server'] if projects[pid][s+'_side']!='unsupported'} for pid in selected}
for pid,p in projects.items():
 if p['slug'] in EXTRA and p['slug']!='fabric-api':envs[pid]={'server'}
# Lithostitched declares environment=* in its Fabric JAR; Tectonic needs it on the client too.
for pid,p in projects.items():
 if p['slug']=='lithostitched':envs[pid]={'client','server'}
changed=True
while changed:
 changed=False
 for pid,v in selected.items():
  for d in v['dependencies']:
   if d['dependency_type']!='required':continue
   target=d.get('project_id') or get('version/'+d['version_id'])['project_id']
   for side in envs[pid]:
    if projects[target][side+'_side']=='unsupported':raise RuntimeError('Unsupported dependency side: '+projects[target]['slug']+' '+side)
    if side not in envs[target]:envs[target].add(side);changed=True
files=[]
for pid in sorted(selected,key=lambda x:projects[x]['slug']):
 p=projects[pid];v=selected[pid];f=next((f for f in v['files'] if f['primary']),v['files'][0])
 files.append({'slug':p['slug'],'name':p['title'],'project_id':pid,'version_id':v['id'],'version':v['version_number'],'release_type':v['version_type'],'requested':p['slug'] in REQUESTED,'sides':sorted(envs[pid]),'filename':f['filename'],'url':f['url'],'sha512':f['hashes']['sha512'],'sha1':f['hashes']['sha1'],'size':f['size'],'source':'https://modrinth.com/mod/'+p['slug'],'dependencies':v['dependencies']})
# Modrinth's official Fabric loader metadata is independent of mod resolution.
req=urllib.request.Request('https://meta.fabricmc.net/v2/versions/loader/'+MC,headers={'User-Agent':'KiwyClub/1.0'})
loader=next(v['loader']['version'] for v in json.load(urllib.request.urlopen(req)) if v['loader']['stable'])
lock={'minecraft':MC,'loader':'fabric','fabric_loader':loader,'requested_count':len(REQUESTED),'files':files}
(ROOT/'mods.lock.json').write_text(json.dumps(lock,ensure_ascii=False,indent=2)+'\n')
print('Pinned',len(files),'files; requested',len(REQUESTED),'Fabric loader',loader)
for f in files:print(f['slug'],f['version'],','.join(f['sides']),f['release_type'])
