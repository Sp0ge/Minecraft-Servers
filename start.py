import os, pathlib, shutil, zipfile, json, secrets, datetime, re
root=pathlib.Path('/data');root.mkdir(exist_ok=True);os.chdir(root)
if (root/'.wipe-in-progress').exists():raise RuntimeError('Interrupted wipe; restore or finish manually before startup')
role=os.getenv('ROLE','survival');version=os.getenv('VERSION','1.21.10')
if role=='pillars' and not (root/'world/level.dat').exists():
 archive=pathlib.Path('/assets/pillars.zip')
 if not archive.exists():raise RuntimeError('Pillars map missing')
 world=root/'world';world.mkdir(exist_ok=True)
 with zipfile.ZipFile(archive) as z:
  names=z.namelist(); levels=[n for n in names if n.endswith('level.dat')]
  if len(levels)!=1:raise RuntimeError('Map must contain exactly one level.dat')
  prefix=levels[0][:-len('level.dat')]
  for name in names:
   if not name.startswith(prefix):continue
   rel=pathlib.PurePosixPath(name[len(prefix):])
   if rel.is_absolute() or '..' in rel.parts:raise RuntimeError('Unsafe archive path')
   if not rel.parts or rel.parts[0] in ('playerdata','stats','advancements') or rel.name in ('session.lock','level.dat_old'):continue
   dest=world.joinpath(*rel.parts)
   if name.endswith('/'):dest.mkdir(parents=True,exist_ok=True)
   else:dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(z.read(name))
(root/'plugins').mkdir(exist_ok=True);shutil.copy('/shared/backend.jar',root/'plugins/NetworkBackend.jar')
if role=='lobby':
 shutil.copy('/assets/AuthMe.jar',root/'plugins/AuthMe.jar')
 config=root/'plugins/AuthMe/config.yml';config.parent.mkdir(exist_ok=True)
 if not config.exists():config.write_text('settings:\n    registration:\n        dialog:\n            preJoin:\n                enable: false\n            postJoin:\n                enable: false\n')
 else:
  text=config.read_text()
  text=re.sub(r'(preJoin:\s*\n(?:[ \t]*#[^\n]*\n)*[ \t]*enable:) true',r'\1 false',text)
  text=re.sub(r'(postJoin:\s*\n(?:[ \t]*#[^\n]*\n)*[ \t]*enable:) true',r'\1 false',text)
  config.write_text(text)
seed=''
if role=='survival':
 from season import prepare_season
 seed=prepare_season(root)
secret=pathlib.Path('/secrets/forwarding.secret').read_text().strip()
(root/'config').mkdir(exist_ok=True)
(root/'config/paper-global.yml').write_text('''_version: 31
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: "'''+secret+'''"
block-updates:
  disable-noteblock-updates: false
''')
(root/'config/paper-world-defaults.yml').write_text('''_version: 31
misc:
  update-pathfinding-on-block-update: true
unsupported-settings:
  fix-invulnerable-end-crystal-exploit: false
''')
(root/'spigot.yml').write_text('''settings:
  bungeecord: false
world-settings:
  default:
    view-distance: default
    simulation-distance: default
    mob-spawn-range: 4
''')
bukkit='settings:\n  allow-end: '+('true' if role=='survival' else 'false')+'\n'
if role=='lobby':bukkit+='worlds:\n  world:\n    generator: NetworkBackend\n'
(root/'bukkit.yml').write_text(bukkit)
(root/'server.properties').write_text('''server-port=25565
online-mode=false
enforce-secure-profile=false
level-name=world
level-seed='''+seed+'''
spawn-protection=0
enable-command-block=true
allow-flight=true
max-players='''+os.getenv('MAX_PLAYERS','30')+'''
view-distance='''+('6' if role=='survival' else '4')+'''
simulation-distance=4
gamemode='''+('survival' if role=='survival' else 'adventure')+'''
difficulty=normal
enable-rcon=true
rcon.port=25575
rcon.password='''+pathlib.Path('/secrets/api-token').read_text().strip()+'''
motd=KiwyClub - '''+role+'''
max-tick-time=120000
sync-chunk-writes=true
''')
(root/'eula.txt').write_text('eula=true\n')
os.execvp('java',['java','-Xms256M','-Xmx'+os.getenv('MEMORY','2G'),'-jar','/assets/paper-'+version+'.jar','--nogui'])
