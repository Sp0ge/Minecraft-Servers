import json,pathlib,urllib.request,urllib.parse,hashlib,concurrent.futures,zipfile,io
root=pathlib.Path(__file__).parent
MC='26.3'
requested='appleskin mouse-tweaks shulkerboxtooltip double-doors lithium ferrite-core krypton alternate-current servercore c2me-fabric vmp-fabric spark chunky terralith tectonic nullscape simple-voice-chat sound-physics-remastered luckperms enchantments-unbound controlify'.split()
extra=['fabric-api','fabricproxy-lite']
cache={}
def get(path):
 if path not in cache:cache[path]=json.load(urllib.request.urlopen(urllib.request.Request('https://api.modrinth.com/v2/'+path,headers={'User-Agent':'KiwyClub/2.0'}),timeout=90))
 return cache[path]
selected={};projects={};sides={};queue=[]
clientonly={'controlify','sound-physics-remastered','yacl','cloth-config','modmenu','mouse-tweaks','shulkerboxtooltip'}
def add(slug,exact=None,required_sides=None):
 p=get('project/'+slug);pid=p['id'];projects[pid]=p
 if pid in selected:
  if exact and selected[pid]['id']!=exact:raise RuntimeError('Conflicting exact dependency '+p['slug'])
  if required_sides:sides[pid]|=required_sides
  return pid
 vs=get('project/'+pid+'/version?'+urllib.parse.urlencode({'game_versions':json.dumps([MC]),'loaders':json.dumps(['fabric'])}))
 v=get('version/'+exact) if exact else next((x for x in vs if x['version_type']=='release'),vs[0] if vs else None)
 if not v or MC not in v['game_versions'] or 'fabric' not in v['loaders']:raise RuntimeError('Unavailable '+p['slug'])
 selected[pid]=v;sides[pid]=required_sides or ({'client'} if p['slug'] in clientonly else {'client','server'} if p['slug'] in ('simple-voice-chat','fabric-api','appleskin') else {'server'});queue.append(pid);return pid
for slug in requested+extra:add(slug)
while queue:
 pid=queue.pop(0)
 for dep in selected[pid]['dependencies']:
  if dep['dependency_type']=='required':add(dep.get('project_id') or get('version/'+dep['version_id'])['project_id'],dep.get('version_id'),sides[pid].copy())
files=[]
for pid,v in selected.items():
 p=projects[pid];f=next((f for f in v['files'] if f['primary']),v['files'][0]);files.append({'slug':p['slug'],'name':p['title'],'project_id':pid,'version_id':v['id'],'version':v['version_number'],'release_type':v['version_type'],'requested':p['slug'] in requested,'sides':sorted(sides[pid]),'filename':f['filename'],'url':f['url'],'sha512':f['hashes']['sha512'],'sha1':f['hashes']['sha1'],'size':f['size'],'source':'https://modrinth.com/mod/'+p['slug'],'dependencies':v['dependencies']})
files.sort(key=lambda f:f['slug']);lock={'minecraft':MC,'loader':'fabric','fabric_loader':'0.19.5','requested_count':len(requested),'files':files}
(root/'mods.lock.json').write_text(json.dumps(lock,ensure_ascii=False,indent=2)+'\n')

print("Pinned", len(files), "files for", MC)
