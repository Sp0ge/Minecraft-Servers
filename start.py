"""Pinned Fabric 26.3 runtime. Generates a new world on first launch."""
import os,pathlib,json,hashlib,shutil,sys
root=pathlib.Path('/data');root.mkdir(exist_ok=True);os.chdir(root)
if (root/'.wipe-in-progress').exists():raise RuntimeError('Interrupted wipe; restore manually')
role=os.getenv('ROLE','survival');mc='26.3';marker=root/'kiwy-runtime.json'
if (root/'world/level.dat').exists() and (not marker.exists() or json.loads(marker.read_text()).get('minecraft')!=mc):raise RuntimeError('Data folder belongs to another Minecraft version; use a fresh Fabric 26.3 data directory')
if not marker.exists():marker.write_text(json.dumps({'minecraft':mc,'loader':'fabric'}))
from season import prepare_season
prepare_season(root)
lock=json.loads(pathlib.Path('/assets/fabric-26.3/mods.lock.json').read_text());mods=root/'mods';mods.mkdir(exist_ok=True)
expected={f['filename'] for f in lock['files'] if 'server' in f['sides']}|{'kiwy-fabric.jar'}
managed=mods/'.kiwy-managed.json'
for old in json.loads(managed.read_text()) if managed.exists() else []:
 if old not in expected:(mods/old).unlink(missing_ok=True)
for f in lock['files']:
 if 'server' not in f['sides']:continue
 source=pathlib.Path('/assets/fabric-26.3')/f['filename']
 if hashlib.sha512(source.read_bytes()).hexdigest()!=f['sha512']:raise RuntimeError('Checksum mismatch: '+f['filename'])
 shutil.copyfile(source,mods/f['filename'])
shutil.copyfile('/shared/kiwy-fabric.jar',mods/'kiwy-fabric.jar');managed.write_text(json.dumps(sorted(expected)))
config=root/'config';config.mkdir(exist_ok=True)
secret=pathlib.Path('/secrets/forwarding.secret').read_text().strip()
(config/'FabricProxy-Lite.toml').write_text('hackOnlineMode = true\nhackEarlySend = true\nhackMessageChain = true\nsecret = '+json.dumps(secret)+'\n')
# Pair ordinary doors only; avoid recursive scans across large door constructions.
door_path=config/'serilum/doubledoors.json';door_path.parent.mkdir(exist_ok=True)
doors=json.loads(door_path.read_text()) if door_path.exists() else {}
for key,value in {'Enable Double Doors':True,'enableRecursiveOpening':False,'enableDoors':True,'enableFenceGates':False,'enableTrapdoors':False,'enableModIncompatibilityCheck':True}.items():
 entry=doors.get(key)
 if not isinstance(entry,dict):entry={}
 entry['value']=value;doors[key]=entry
door_path.write_text(json.dumps(doors,indent=2))
voice=config/'voicechat';voice.mkdir(exist_ok=True)
sys.path.insert(0,'/assets');from voice_ports import allocate
port=allocate();(voice/'voicechat-server.properties').write_text('port='+str(port)+'\nbind_address=0.0.0.0\nvoice_host=\nmax_voice_distance=128.0\nallow_pings=true\n')
# Bound generation concurrency on the 12-thread VM; preserve normal vanilla simulation.
(config/'c2me.toml').write_text('version = 3\nglobalExecutorParallelism = 2\n')
chunky=config/'chunky';chunky.mkdir(exist_ok=True)
p=chunky/'config.json';cfg=json.loads(p.read_text()) if p.exists() else {'version':1,'language':'ru','silent':False};cfg.update({'continueOnRestart':False,'forceLoadExistingChunks':False,'updateInterval':60});p.write_text(json.dumps(cfg,indent=2))
# Vanilla's enchantment storage supports levels up to 255. Remove gameplay caps/conflicts,
# but leave villager trades and XP consumption enabled.
enchant={'staticCost':False,'useXpPerEnchantLevel':True,'xpPerEnchantLevel':1,'maxLevelCost':2147483647,'useCustomAnvilCap':True,'useGlobalAnvilCap':True,'globalAnvilCap':255,'modifyVillagers':False,'damageConflicts':True,'protectionConflicts':True,'bowConflicts':True,'bootConflicts':True,'tridentConflicts':True,'crossbowConflicts':True,'toolConflicts':True,'itemEnchantConflicts':False,'colorCodedRenaming':False,'lowRenamingCost':True,'showActionbarMessage':False,'showTooltipMessage':True}
(config/'enchantmentsunbound.json').write_text(json.dumps(enchant,indent=2))
season=json.loads((root/'season.json').read_text())
properties={'server-port':'25565','online-mode':'false','enforce-secure-profile':'false','level-name':'world','level-seed':str(season['seed']),'spawn-protection':'0','allow-flight':'true','max-players':os.getenv('MAX_PLAYERS','64'),'view-distance':'6' if role=='survival' else '3','simulation-distance':'4' if role=='survival' else '3','gamemode':'survival','difficulty':'normal','enable-rcon':'true','rcon.port':'25575','rcon.password':pathlib.Path('/secrets/api-token').read_text().strip(),'motd':'KiwyClub - '+role,'max-tick-time':'120000','sync-chunk-writes':'true','enable-command-block':'false','pause-when-empty-seconds':'-1','entity-broadcast-range-percentage':'100'}
if role=='pvp':properties.update({'level-type':'minecraft:flat','generator-settings':json.dumps({'biome':'minecraft:plains','layers':[],'structure_overrides':[]},separators=(',',':'))})
(root/'server.properties').write_text('\n'.join(k+'='+v for k,v in properties.items())+'\n');(root/'eula.txt').write_text('eula=true\n')
os.execvp('java',['java','-XX:ActiveProcessorCount='+os.getenv('JAVA_PROCESSORS','3'),'-Xms256M','-Xmx'+os.getenv('MEMORY','4G'),'-jar','/assets/fabric-server-26.3.jar','nogui'])
