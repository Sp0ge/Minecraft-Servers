import datetime as dt, json, os, secrets, threading, time, urllib.request, urllib.parse, uuid, logging, socket, struct
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from concurrent.futures import ThreadPoolExecutor
from zoneinfo import ZoneInfo
import docker
from docker.types import Mount

logging.basicConfig(level=logging.INFO,format='%(asctime)s %(levelname)s %(message)s')
SECRETS=Path(os.getenv('SECRETS_DIR','/secrets')); STATE=Path(os.getenv('STATE_DIR','/state')); SECRETS.mkdir(exist_ok=True); STATE.mkdir(exist_ok=True)
for name in ('api-token','forwarding.secret'):
 p=SECRETS/name
 if not p.exists(): p.write_text(secrets.token_hex(32));p.chmod(0o600)
TOKEN=(SECRETS/'api-token').read_text().strip()
CLIENT=docker.from_env(timeout=10); LOCK=threading.RLock(); VOICE_LOCK=threading.Lock(); WORKER=ThreadPoolExecutor(max_workers=1); MAINTENANCE_WORKER=ThreadPoolExecutor(max_workers=1)
NETWORK='mcservers_internal'; PREFIX='mcservers-'; MAX=int(os.getenv('MAX_ARENAS','5'))
ARENAS={f'pillars_{i}':{'state':'STOPPED','generation':None,'players':[], 'reservations':{},'visited':False} for i in range(1,MAX+1)}
LEGACY_SPARE='pillars_ready'
PUBLIC_ARENAS=tuple(ARENAS)
DATA_HOST=os.getenv('DATA_HOST_DIR','')
TZ=ZoneInfo('Europe/Moscow'); shutting_down=False

def voice_port(instance):
 if not instance or len(instance)>64:raise ValueError('Invalid voice instance')
 container=CLIENT.containers.get(instance)
 if not container.name.startswith(PREFIX) or container.labels.get('mcservers.managed')!='true':raise ValueError('Unmanaged voice instance')
 if container.name not in {PREFIX+n for n in ('lobby','survival','pvp','parkour')} and not container.labels.get('mcservers.arena'):raise ValueError('Not a game server')
 first=int(os.getenv('VOICE_PORT_MIN','24460'));last=int(os.getenv('VOICE_PORT_MAX','24559'))
 if not 1024<=first<=last<=65535 or first<=24454<=last:raise ValueError('Invalid voice port range')
 with VOICE_LOCK:
  path=STATE/'voice-ports.json';leases=json.loads(path.read_text()) if path.exists() else {}
  live={c.id for c in CLIENT.containers.list(all=True,filters={'label':'mcservers.managed=true'})}
  leases={key:port for key,port in leases.items() if key in live and first<=port<=last}
  if container.id not in leases:
   used=set(leases.values());available=next((p for p in range(first,last+1) if p not in used),None)
   if available is None:raise RuntimeError('Voice port range exhausted')
   leases[container.id]=available
  temp=path.with_suffix('.tmp');temp.write_text(json.dumps(leases));temp.replace(path)
  return leases[container.id]

def api(host,path,body=None):
 req=urllib.request.Request('http://'+host+path,data=body.encode() if body is not None else None,
   headers={'Authorization':'Bearer '+TOKEN})
 with urllib.request.urlopen(req,timeout=8) as r:
  b=r.read();return json.loads(b) if b else {}

def voice_route(name):
 if name not in {'lobby','survival','parkour','pvp'}|set(ARENAS):raise ValueError('Unknown game server')
 container=CLIENT.containers.get(PREFIX+name)
 if container.labels.get('mcservers.managed')!='true' or container.status!='running':raise ValueError('Server is not running')
 address=container.attrs['NetworkSettings']['Networks'][NETWORK]['IPAddress']
 with VOICE_LOCK:
  path=STATE/'voice-ports.json';leases=json.loads(path.read_text()) if path.exists() else {}
  port=leases.get(container.id)
 if not port:return {'ready':False,'server':name}
 nonce=uuid.uuid4().bytes;stamp=struct.pack('>q',int(time.time()*1000))
 packet=b'\xff'+uuid.UUID('58bc9ae9-c7a8-45e4-a11c-efbb67199425').bytes+b'\x18'+nonce+stamp
 with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as udp:
  udp.settimeout(1)
  try:
   udp.sendto(packet,(address,port));response,peer=udp.recvfrom(2048)
   ready=response==nonce+stamp and peer==(address,port)
  except OSError:ready=False
 return {'ready':ready,'server':name,'address':address,'port':port,'container':container.id,
         'generation':container.labels.get('mcservers.generation','static')}

def persist():
 p=STATE/'arenas.json'; temp=p.with_suffix('.tmp');temp.write_text(json.dumps(ARENAS));temp.replace(p)

def resources():
 # Check VM capacity, not the physical host. Allocation caps are additional to Compose caps.
 running=CLIENT.containers.list(filters={'label':'mcservers.managed=true'})
 usage=sum(c.attrs['HostConfig'].get('Memory',0) for c in running)
 cap=int(float(os.getenv('TOTAL_MEMORY_GIB','29'))*1024**3)
 need=int(float(os.getenv('ARENA_LIMIT_GIB','3'))*1024**3)
 import shutil
 if shutil.disk_usage(STATE).free<2*1024**3:return False
 return usage+need<=cap and usage+need<=CLIENT.info()['MemTotal']-1024**3

def occupied_slots():
 # Include failed/stopped containers awaiting cleanup so errors cannot exceed five arenas.
 actual={c.name.removeprefix(PREFIX) for c in CLIENT.containers.list(all=True,filters={'label':'mcservers.arena'}) if c.name.startswith(PREFIX)}
 planned={name for name,a in ARENAS.items() if a['state'] not in ('STOPPED','FAILED')}
 return len((actual|planned)&set(ARENAS))

def remove_empty(name,arena=None):
 a=arena if arena is not None else ARENAS[name];existing=CLIENT.containers.get(PREFIX+name)
 if existing.status=='running':api(name+':8081','/admission','closed')
 report=api(name+':8081','/status') if existing.status=='running' else {'generation':a['generation'],'count':0}
 with LOCK:
  if report.get('generation')!=a['generation'] or report.get('count',1)!=0 or a['reservations']:
   if existing.status=='running':api(name+':8081','/admission','open')
   a['state']='STARTING';return False
 if existing.labels.get('mcservers.arena')!=a.get('origin',name):raise RuntimeError('Container ownership mismatch')
 existing.stop(timeout=60);oldvol=existing.labels['mcservers.volume'];existing.remove()
 vol=CLIENT.volumes.get(oldvol)
 if vol.attrs.get('Labels',{}).get('mcservers.arena')!=a.get('origin',name):raise RuntimeError('Volume ownership mismatch')
 vol.remove()
 if DATA_HOST:
  import shutil
  folder=Path('/arena-data')/oldvol
  if folder.parent!=Path('/arena-data') or not oldvol.startswith(PREFIX):raise RuntimeError('Invalid arena data path')
  if folder.is_dir():shutil.rmtree(folder)
 return True

def spawn(name,reset=False):
 if shutting_down:return
 a=ARENAS[name]
 try:
  generation=uuid.uuid4().hex
  with LOCK:
   if not reset and not resources(): raise RuntimeError('Недостаточно доступного бюджета памяти для новой арены.')
  if reset:
   if not remove_empty(name):return
  with LOCK:
   if not resources():raise RuntimeError('Недостаточно памяти после сброса.')
  if not Path('/assets/pillars.zip').exists() and os.getenv('ALLOW_TEST_MAP')!='true':
   raise RuntimeError('Нужен архив карты /assets/pillars.zip; тестовая карта выключена.')
  volname=PREFIX+name+'-'+generation
  opts={}
  if DATA_HOST:
   (Path('/arena-data')/volname).mkdir(parents=True,exist_ok=False)
   opts={'type':'none','o':'bind','device':DATA_HOST+'/arenas/'+volname}
  CLIENT.volumes.create(volname,driver='local',driver_opts=opts,labels={'mcservers.arena':name,'mcservers.managed':'true'})
  env={'ROLE':'pillars','VERSION':'1.21.10','MEMORY':os.getenv('ARENA_HEAP','2G'),
   'MAX_PLAYERS':'16','GENERATION':generation,'NETWORK_REVISION':os.getenv('NETWORK_REVISION','initial'),'ALLOW_TEST_MAP':os.getenv('ALLOW_TEST_MAP','false')}
  mounts=[Mount('/data',volname,type='volume'),data_mount('/secrets','secrets',True),
   data_mount('/shared','plugins',True),Mount('/assets',os.environ['ASSETS_HOST_DIR'],type='bind',read_only=True)]
  if shutting_down:raise RuntimeError('Контроллер завершает работу')
  c=CLIENT.containers.create('minecraft-pillars:local',name=PREFIX+name,environment=env,mounts=mounts,
    network=NETWORK,mem_limit=int(float(os.getenv('ARENA_LIMIT_GIB','3'))*1024**3),
    memswap_limit=int(float(os.getenv('ARENA_LIMIT_GIB','3'))*1024**3),nano_cpus=int(float(os.getenv('ARENA_CPUS','1.2'))*1e9),
    labels={'mcservers.managed':'true','mcservers.arena':name,'mcservers.generation':generation,'mcservers.volume':volname,'mcservers.revision':os.getenv('NETWORK_REVISION','initial')},
    cgroup_parent=os.getenv('MC_CGROUP_PARENT') or None,
    restart_policy={'Name':'no'},log_config=docker.types.LogConfig(type='json-file',config={'max-size':'10m','max-file':'3'}))
  # Replace endpoint with a stable DNS alias before starting the JVM.
  net=CLIENT.networks.get(NETWORK);net.disconnect(c);net.connect(c,aliases=[name]);c.start()
  with LOCK:
   a.update(state='STARTING',origin=name,generation=generation,players=[],visited=False,reservations={},error=None,started_at=time.time(),ready_at=None);persist()
  logging.info('Created %s generation=%s',name,generation)
 except Exception as e:
  with LOCK:a.update(state='FAILED',error=str(e),failed_at=time.time());persist()
  logging.exception('Arena operation failed for %s',name)

def select(target,player,now=None):
 now=now or time.time()
 with LOCK:
  if shutting_down:return {'status':'failed','message':'Сеть завершает работу.'}
  for a in ARENAS.values():a['reservations']={p:t for p,t in a['reservations'].items() if t>now}
  choices=list(PUBLIC_ARENAS) if target=='pillars' else [target] if target in PUBLIC_ARENAS else []
  if not choices:return {'status':'failed','message':'Неизвестная арена.'}
  for n in choices:
   a=ARENAS[n]
   if (a['state']=='WAITING' or (target!='pillars' and a['state']=='IN_GAME')) and (player in a['players'] or len(set(a['players'])|set(a['reservations']))<16):
    a['reservations'][player]=now+30;return {'status':'ready','server':n,'generation':a['generation']}
  if any(ARENAS[n]['state'] in ('STARTING','RESETTING','QUEUED') for n in choices):return {'status':'waiting'}
  for n in choices:
   if ARENAS[n]['state']=='STOPPED':
    if occupied_slots()>=MAX:return {'status':'full','message':'Достигнут предел пяти арен.'}
    if not resources():return {'status':'failed','message':'Недостаточно ресурсов для запуска арены.'}
    ARENAS[n]['state']='QUEUED';persist();WORKER.submit(spawn,n);return {'status':'waiting'}
  failures=[ARENAS[n].get('error','Ошибка запуска') for n in choices if ARENAS[n]['state']=='FAILED']
  return {'status':'failed' if failures else 'full','message':failures[0] if failures else 'Все подходящие арены заняты или матч уже идёт.'}

def data_mount(target,name,read_only=False):
 return Mount(target,DATA_HOST+'/'+name,type='bind',read_only=read_only) if DATA_HOST else Mount(target,'mcservers_'+name,type='volume',read_only=read_only)

def cleanup_legacy_reserve(container):
 try:
  arena={'generation':container.labels.get('mcservers.generation'),
         'origin':container.labels.get('mcservers.arena'),'reservations':{}}
  if remove_empty(LEGACY_SPARE,arena):logging.info('Removed obsolete hidden reserve')
 except Exception:logging.exception('Could not remove obsolete hidden reserve')

def retry_arena(name):
 try:
  container=CLIENT.containers.get(PREFIX+name)
  if container.status!='running':container.start()
  with LOCK:
   ARENAS[name].update(state='STARTING',started_at=time.time(),last_seen=None,error=None)
 except docker.errors.NotFound:spawn(name)
 except Exception as e:
  with LOCK:ARENAS[name].update(state='FAILED',failed_at=time.time(),error=str(e))
  logging.exception('Could not recover arena %s',name)

def recover():
 for c in CLIENT.containers.list(all=True,filters={'label':'mcservers.arena'}):
  n=c.name.removeprefix(PREFIX)
  if n==LEGACY_SPARE:
   WORKER.submit(cleanup_legacy_reserve,c);continue
  if n in ARENAS:
   ARENAS[n].update(state='STARTING',origin=c.labels.get('mcservers.arena'),generation=c.labels.get('mcservers.generation'),started_at=time.time())
   if c.status!='running':
    try:c.start()
    except Exception as e:
     ARENAS[n].update(state='FAILED',failed_at=time.time(),error=str(e));logging.exception('Could not start existing arena %s',n)
 for n in PUBLIC_ARENAS:
  if ARENAS[n]['state']=='STOPPED':ARENAS[n]['state']='QUEUED';WORKER.submit(spawn,n)

class Handler(BaseHTTPRequestHandler):
 def do_GET(self):
  if self.path=='/health':self.reply(200,{'healthy':True});return
  if self.headers.get('Authorization')!='Bearer '+TOKEN:self.reply(403,{});return
  u=urllib.parse.urlparse(self.path)
  if u.path=='/status':
   with LOCK:self.reply(200,{'arenas':ARENAS,'test_map':os.getenv('ALLOW_TEST_MAP')=='true','maintenance':maintenance_state})
  elif u.path=='/voice-port':
   try:code,data=200,{'port':voice_port(urllib.parse.parse_qs(u.query)['instance'][0])}
   except (KeyError,ValueError,docker.errors.NotFound):code,data=400,{'error':'Invalid voice instance'}
   except Exception:logging.exception('Voice port allocation failed');code,data=503,{'error':'Voice port unavailable'}
   self.reply(code,data)
  elif u.path=='/voice-route':
   try:code,data=200,voice_route(urllib.parse.parse_qs(u.query)['server'][0])
   except (KeyError,ValueError,docker.errors.NotFound):code,data=200,{'ready':False}
   except Exception:logging.exception('Voice route lookup failed');code,data=503,{'ready':False}
   self.reply(code,data)
  elif u.path=='/select':
   try:
    q=urllib.parse.parse_qs(u.query);player=str(uuid.UUID(q['uuid'][0]));self.reply(200,select(q['target'][0],player))
   except (KeyError,ValueError):self.reply(400,{})
  else:self.reply(404,{})
 def reply(self,code,data):
  b=json.dumps(data).encode()
  try:
   self.send_response(code);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
  except (BrokenPipeError,ConnectionResetError):
   # A disconnected caller is not an allocation failure; never send a second response.
   self.close_connection=True
 def log_message(self,*args):pass

maintenance_state={}; maintenance_busy=False

def add_months(date,months):
 import calendar
 month=date.month-1+months;year=date.year+month//12;month=month%12+1
 return date.replace(year=year,month=month,day=min(date.day,calendar.monthrange(year,month)[1]))

def load_schedule():
 p=STATE/'schedule.json'
 if p.exists():return json.loads(p.read_text())
 today=dt.datetime.now(TZ).date();s={'next_wipe':add_months(today,3).isoformat(),'last_restart':None,'warnings':[]}
 p.write_text(json.dumps(s));return s

def save_schedule():
 p=STATE/'schedule.json';t=p.with_suffix('.tmp');t.write_text(json.dumps(maintenance_state));t.replace(p)

def maintain(wipe=False):
 global maintenance_busy
 c=None;restart_policy=None
 try:
  api('proxy:8080','/drain?survival','')
  status=api('survival:8081','/status')
  if status.get('count',1)!=0:raise RuntimeError('Survival still has players; operation cancelled')
  c=CLIENT.containers.get('mcservers-survival')
  restart_policy=c.attrs['HostConfig']['RestartPolicy']
  c.update(restart_policy={'Name':'no'})
  from rcon import command
  # Let Minecraft close LuckPerms/H2 before JVM shutdown hooks are invoked.
  command('survival','stop')
  c.wait(timeout=300)
  c.reload()
  if c.status=='running':raise RuntimeError('Survival did not stop cleanly; maintenance cancelled')
  if wipe:
   helper=CLIENT.containers.run('minecraft-controller:local',command=['python','/app/maintenance.py'],
    environment={'BACKUP_KEEP':os.getenv('BACKUP_KEEP','2'),'PREVIOUS_SEED':str(status['seed'])},network_disabled=True,
    mounts=[data_mount('/survival',os.getenv('SURVIVAL_DATA_SUBDIR','survival')),data_mount('/backups','backups')],
    labels={'mcservers.managed':'true'},cgroup_parent=os.getenv('MC_CGROUP_PARENT') or None,
    mem_limit='512m',nano_cpus=250000000,detach=True)
   try:
    result=helper.wait(timeout=600);logging.info('Wipe: %s',helper.logs().decode())
    if result['StatusCode']!=0:raise RuntimeError('Backup/wipe helper failed; survival remains stopped for review')
   finally:helper.remove()
   maintenance_state['next_wipe']=add_months(dt.datetime.now(TZ).date(),3).isoformat();save_schedule()
  c.start()
  for _ in range(120):
   try:
    if api('survival:8081','/status').get('role')=='survival':break
   except Exception:pass
   time.sleep(2)
  else:raise RuntimeError('Survival readiness timed out')
  api('proxy:8080','/undrain?survival','')
  maintenance_state['last_restart']=dt.datetime.now(TZ).date().isoformat();maintenance_state.pop('error',None);save_schedule()
 except Exception as e:
  maintenance_state['error']=str(e);save_schedule();logging.exception('Maintenance failed')
 finally:
  if c is not None and restart_policy is not None:
   try:c.update(restart_policy=restart_policy)
   except Exception:logging.exception('Could not restore Survival restart policy')
  maintenance_busy=False

def schedule(now):
 global maintenance_busy
 if os.getenv('MAINTENANCE_ENABLED','true')!='true':return
 date=now.date().isoformat()
 for minute,label in [(280,20),(290,10),(295,5)]:
  mark=date+':'+str(label)
  if now.hour*60+now.minute==minute and mark not in maintenance_state['warnings']:
   try:api('survival:8081','/announce',f'Перезапуск survival через {label} минут.');maintenance_state['warnings'].append(mark);maintenance_state['warnings']=maintenance_state['warnings'][-6:];save_schedule()
   except Exception:pass
 if now.hour==5 and now.minute==0 and maintenance_state.get('last_restart')!=date and not maintenance_busy and not maintenance_state.get('error'):
  maintenance_busy=True;MAINTENANCE_WORKER.submit(maintain,date>=maintenance_state['next_wipe'])

def empty_action(name,arena,now):
 if arena["players"] or arena["reservations"]:return None
 if arena["visited"]:return "reset"
 return None

def loop():
 while not shutting_down:
  for n in ARENAS:
   with LOCK:state=ARENAS[n]['state'];generation=ARENAS[n]['generation']
   if state not in ('STARTING','WAITING','IN_GAME'):continue
   try:
    report=api(n+':8081','/status')
    if report.get('generation')!=generation:continue
    container=CLIENT.containers.get(PREFIX+n)
    stale=container.labels.get('mcservers.revision')!=os.getenv('NETWORK_REVISION','initial')
    with LOCK:
     a=ARENAS[n];now=time.time()
     a['players']=report['players'];a['visited']=report['visited']
     a['reservations']={p:t for p,t in a['reservations'].items() if t>now and p not in report['players']}
     a['state']='WAITING' if report['accepting'] else 'IN_GAME';a['last_seen']=now
     if not a.get('ready_at'):a['ready_at']=now
     action='reset' if stale and not a['players'] and not a['reservations'] else empty_action(n,a,now)
     if action:
      a['state']='RESETTING';persist()
      WORKER.submit(spawn,n,True)
   except Exception:
    with LOCK:
     a=ARENAS[n]
     if a.get('last_seen') and time.time()-a['last_seen']>15:a['state']='STARTING'
     if time.time()-a.get('started_at',time.time())>600 and not a.get('last_seen'):
      a['state']='FAILED';a['failed_at']=time.time();a['error']='Истекло время запуска арены';persist()
  with LOCK:
   for name in PUBLIC_ARENAS:
    arena=ARENAS[name]
    if arena['state']=='STOPPED' and occupied_slots()<MAX and resources():
     arena['state']='QUEUED';WORKER.submit(spawn,name)
    elif arena['state']=='FAILED' and time.time()-arena.get('failed_at',0)>30:
     arena['state']='QUEUED';WORKER.submit(retry_arena,name)
  schedule(dt.datetime.now(TZ));time.sleep(2)

def terminate(signum,frame):
 global shutting_down
 shutting_down=True
 MAINTENANCE_WORKER.shutdown(wait=True,cancel_futures=True)
 WORKER.shutdown(wait=True,cancel_futures=True)
 for c in CLIENT.containers.list(filters={'label':'mcservers.arena'}):
  if c.name.startswith(PREFIX) and c.name.removeprefix(PREFIX) in ARENAS:
   try:c.stop(timeout=60)
   except Exception:logging.exception('Could not stop managed arena')
 raise SystemExit(0)

if __name__=='__main__':
 import signal
 signal.signal(signal.SIGTERM,terminate);signal.signal(signal.SIGINT,terminate)
 recover();maintenance_state.update(load_schedule())
 threading.Thread(target=loop,daemon=True).start()
 ThreadingHTTPServer(('0.0.0.0',8080),Handler).serve_forever()
