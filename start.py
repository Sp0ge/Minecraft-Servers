"""Pinned Fabric runtime shared by Survival and PvP. Never opens a newer Paper world."""
import os,pathlib,json,hashlib,shutil,secrets,datetime
root=pathlib.Path('/data');root.mkdir(exist_ok=True);os.chdir(root)
if (root/'.wipe-in-progress').exists():raise RuntimeError('Interrupted wipe; restore manually')
role=os.getenv('ROLE','survival')
marker=root/'kiwy-runtime.json'
if (root/'world/level.dat').exists() and (not marker.exists() or json.loads(marker.read_text()).get('minecraft')!='1.20.1'):raise RuntimeError('Refusing downgrade/import of unmarked world. Use separate Fabric data directory.')
marker.write_text(json.dumps({'minecraft':'1.20.1','loader':'fabric'}))
season=root/'season.json'
if not (root/'world/level.dat').exists():
 old=json.loads(season.read_text()).get('seed') if season.exists() else None;seed=secrets.randbits(63)
 while seed==old:seed=secrets.randbits(63)
 season.write_text(json.dumps({'id':secrets.token_hex(16),'seed':seed,'created_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}))
 (root/'kiwy-settled.json').unlink(missing_ok=True)
lock=json.loads(pathlib.Path('/assets/fabric-1.20.1/mods.lock.json').read_text());mods=root/'mods';mods.mkdir(exist_ok=True)
expected={f['filename'] for f in lock['files'] if 'server' in f['sides']}|{'kiwy-fabric.jar'}
# Only prune artifacts previously managed by this installer; preserve administrator additions.
managed=mods/'.kiwy-managed.json'
for old in json.loads(managed.read_text()) if managed.exists() else []:
 if old not in expected:(mods/old).unlink(missing_ok=True)
for f in lock['files']:
 if 'server' not in f['sides']:continue
 source=pathlib.Path('/assets/fabric-1.20.1')/f['filename']
 if hashlib.sha512(source.read_bytes()).hexdigest()!=f['sha512']:raise RuntimeError('Checksum mismatch: '+f['filename'])
 shutil.copyfile(source,mods/f['filename'])
shutil.copyfile('/shared/kiwy-fabric.jar',mods/'kiwy-fabric.jar');managed.write_text(json.dumps(sorted(expected)))
config=root/'config';config.mkdir(exist_ok=True)
secret=pathlib.Path('/secrets/forwarding.secret').read_text().strip()
(config/'FabricProxy-Lite.toml').write_text('hackOnlineMode = true\nhackEarlySend = true\nhackMessageChain = true\nsecret = '+json.dumps(secret)+'\n')
voice=config/'voicechat';voice.mkdir(exist_ok=True)
(voice/'voicechat-server.properties').write_text('port='+os.getenv('VOICE_PORT','24454')+'\nbind_address=0.0.0.0\nvoice_host='+os.getenv('VOICE_PUBLIC_HOST','')+'\nmax_voice_distance=128.0\n')
# Cap native and worldgen concurrency within the shared 12-thread network budget.
(config/'c2me.toml').write_text('version = 3\nglobalExecutorParallelism = 2\n[threadedWorldGen]\nenabled = false\n')
chunky=config/'chunky';chunky.mkdir(exist_ok=True)
cfg_path=chunky/'config.json';cfg=json.loads(cfg_path.read_text()) if cfg_path.exists() else {'version':1,'language':'ru','silent':False}
cfg.update({'continueOnRestart':False,'forceLoadExistingChunks':False,'updateInterval':60});cfg_path.write_text(json.dumps(cfg,ensure_ascii=False,indent=2))
chat=config/'styled-chat.json'
if chat.exists():
 chat_data=json.loads(chat.read_text());formats=chat_data.get('default',{}).get('message_formats',{})
 for key in ['joined_the_game','joined_after_name_change','joined_for_first_time']:formats[key]='<green>${player} зашёл на '+('Survival' if role=='survival' else 'PvP')+'.</green>'
 chat.write_text(json.dumps(chat_data,ensure_ascii=False,indent=2))
styled=config/'styledplayerlist';(styled/'styles').mkdir(parents=True,exist_ok=True)
# Proxy owns global TAB counts/names; backend style is shown only once on entry.
sc=styled/'config.json'
sc_data=json.loads(sc.read_text()) if sc.exists() else {'config_version':2,'player':{'modify_name':False,'passthrough':True},'messages':{}}
sc_data['default_style']='kiwy';sc.write_text(json.dumps(sc_data,ensure_ascii=False,indent=2))
(styled/'styles/kiwy.json').write_text(json.dumps({'style_name':'KiwyClub','update_tick_time':2147483647,'list_header':['','<gr:#73DB9A:#EDD58A><bold>KiwyClub</bold></gr>','<gray>Сейчас: '+role,''],'list_footer':['<green>/server <gray>— режимы  •  <green>/help <gray>— справка'],'hidden_in_commands':True},ensure_ascii=False,indent=2))
properties={'server-port':'25565','online-mode':'false','enforce-secure-profile':'false','level-name':'world','level-seed':str(json.loads(season.read_text())['seed']),'spawn-protection':'0','allow-flight':'true','max-players':os.getenv('MAX_PLAYERS','64'),'view-distance':'6' if role=='survival' else '3','simulation-distance':'4' if role=='survival' else '3','gamemode':'survival','difficulty':'normal','enable-rcon':'true','rcon.port':'25575','rcon.password':pathlib.Path('/secrets/api-token').read_text().strip(),'motd':'KiwyClub - '+role,'max-tick-time':'120000','sync-chunk-writes':'true','enable-command-block':'false'}
if role=='pvp':properties.update({'level-type':'minecraft:flat','generator-settings':json.dumps({'biome':'minecraft:plains','layers':[],'structure_overrides':[]},separators=(',',':'))})
(root/'server.properties').write_text('\n'.join(k+'='+v for k,v in properties.items())+'\n');(root/'eula.txt').write_text('eula=true\n')
os.execvp('java',['java','-XX:ActiveProcessorCount='+os.getenv('JAVA_PROCESSORS','3'),'-Xms256M','-Xmx'+os.getenv('MEMORY','4G'),'-jar','/assets/fabric-server-1.20.1.jar','nogui'])
