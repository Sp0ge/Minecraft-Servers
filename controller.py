import datetime as dt, json, os, secrets, threading, time, urllib.request, urllib.parse, uuid, logging
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
CLIENT=docker.from_env(); LOCK=threading.RLock(); WORKER=ThreadPoolExecutor(max_workers=1); MAINTENANCE_WORKER=ThreadPoolExecutor(max_workers=1)
NETWORK='mcservers_internal'; PREFIX='mcservers-'; MAX=int(os.getenv('MAX_ARENAS','5'))
ARENAS={f'pillars_{i}':{'state':'STOPPED','generation':None,'players':[], 'reservations':{},'visited':False} for i in range(1,MAX+1)}
TZ=ZoneInfo('Europe/Moscow'); shutting_down=False

def api(host,path,body=None):
 req=urllib.request.Request('http://'+host+path,data=body.encode() if body is not None else None,
   headers={'Authorization':'Bearer '+TOKEN})
 with urllib.request.urlopen(req,timeout=8) as r:
  b=r.read();return json.loads(b) if b else {}

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

def spawn(name,reset=False):
 if shutting_down:return
 a=ARENAS[name]
 try:
  generation=uuid.uuid4().hex
  with LOCK:
   if not reset and not resources(): raise RuntimeError('Недостаточно доступного бюджета памяти для новой арены.')
  if reset:
   existing=CLIENT.containers.get(PREFIX+name)
   if existing.status=='running':api(name+':8081','/admission','closed')
   report=api(name+':8081','/status') if existing.status=='running' else {'generation':a['generation'],'count':0}
   with LOCK:
    if report.get('generation')!=a['generation'] or report.get('count',1)!=0 or a['reservations']:
     api(name+':8081','/admission','open');a['state']='STARTING';return
   old=CLIENT.containers.get(PREFIX+name)
   if old.labels.get('mcservers.arena')!=name: raise RuntimeError('Container ownership mismatch')
   old.stop(timeout=60);oldvol=old.labels['mcservers.volume'];old.remove()
   vol=CLIENT.volumes.get(oldvol)
   if vol.attrs.get('Labels',{}).get('mcservers.arena')!=name: raise RuntimeError('Volume ownership mismatch')
   vol.remove()
  with LOCK:
   if not resources():raise RuntimeError('Недостаточно памяти после сброса.')
  if not Path('/assets/pillars.zip').exists() and os.getenv('ALLOW_TEST_MAP')!='true':
   raise RuntimeError('Нужен архив карты /assets/pillars.zip; тестовая карта выключена.')
  volname=PREFIX+name+'-'+generation
  CLIENT.volumes.create(volname,labels={'mcservers.arena':name,'mcservers.managed':'true'})
  env={'ROLE':'pillars','VERSION':'1.21.10','MEMORY':os.getenv('ARENA_HEAP','2G'),
   'MAX_PLAYERS':'16','GENERATION':generation,'ALLOW_TEST_MAP':os.getenv('ALLOW_TEST_MAP','false')}
  mounts=[Mount('/data',volname,type='volume'),Mount('/secrets','mcservers_secrets',type='volume',read_only=True),
   Mount('/shared','mcservers_plugins',type='volume',read_only=True),Mount('/assets',os.environ['ASSETS_HOST_DIR'],type='bind',read_only=True)]
  if shutting_down:raise RuntimeError('Контроллер завершает работу')
  c=CLIENT.containers.create('minecraft-pillars:local',name=PREFIX+name,environment=env,mounts=mounts,
    network=NETWORK,mem_limit=int(float(os.getenv('ARENA_LIMIT_GIB','3'))*1024**3),
    memswap_limit=int(float(os.getenv('ARENA_LIMIT_GIB','3'))*1024**3),nano_cpus=int(float(os.getenv('ARENA_CPUS','1.2'))*1e9),
    labels={'mcservers.managed':'true','mcservers.arena':name,'mcservers.generation':generation,'mcservers.volume':volname},
    restart_policy={'Name':'no'},log_config=docker.types.LogConfig(type='json-file',config={'max-size':'10m','max-file':'3'}))
  # Replace endpoint with a stable DNS alias before starting the JVM.
  net=CLIENT.networks.get(NETWORK);net.disconnect(c);net.connect(c,aliases=[name]);c.start()
  with LOCK:
   a.update(state='STARTING',generation=generation,players=[],visited=False,reservations={},error=None,started_at=time.time());persist()
  logging.info('Created %s generation=%s',name,generation)
 except Exception as e:
  with LOCK:a.update(state='FAILED',error=str(e));persist()
  logging.exception('Arena operation failed for %s',name)

def select(target,player,now=None):
 now=now or time.time()
 with LOCK:
  if shutting_down:return {'status':'failed','message':'Сеть завершает работу.'}
  for a in ARENAS.values():a['reservations']={p:t for p,t in a['reservations'].items() if t>now}
  choices=list(ARENAS) if target=='pillars' else [target] if target in ARENAS else []
  if not choices:return {'status':'failed','message':'Неизвестная арена.'}
  for n in choices:
   a=ARENAS[n]
   if (a['state']=='WAITING' or (target!='pillars' and a['state']=='IN_GAME')) and (player in a['players'] or len(set(a['players'])|set(a['reservations']))<16):
    a['reservations'][player]=now+30;return {'status':'ready','server':n,'generation':a['generation']}
  if any(ARENAS[n]['state'] in ('STARTING','RESETTING','QUEUED') for n in choices):return {'status':'waiting'}
  for n in choices:
   if ARENAS[n]['state']=='STOPPED':
    if not resources():return {'status':'failed','message':'Недостаточно ресурсов для запуска арены.'}
    ARENAS[n]['state']='QUEUED';persist();WORKER.submit(spawn,n);return {'status':'waiting'}
  failures=[ARENAS[n].get('error','Ошибка запуска') for n in choices if ARENAS[n]['state']=='FAILED']
  return {'status':'failed' if failures else 'full','message':failures[0] if failures else 'Все подходящие арены заняты или матч уже идёт.'}

def recover():
 for c in CLIENT.containers.list(all=True,filters={'label':'mcservers.arena'}):
  n=c.labels.get('mcservers.arena')
  if n in ARENAS:
   ARENAS[n].update(state='STARTING' if c.status=='running' else 'RESETTING',generation=c.labels.get('mcservers.generation'),started_at=time.time())
   if c.status!='running':WORKER.submit(spawn,n,True)

class Handler(BaseHTTPRequestHandler):
 def do_GET(self):
  if self.path=='/health':self.reply(200,{'healthy':True});return
  if self.headers.get('Authorization')!='Bearer '+TOKEN:self.reply(403,{});return
  u=urllib.parse.urlparse(self.path)
  if u.path=='/status':
   with LOCK:self.reply(200,{'arenas':ARENAS,'test_map':os.getenv('ALLOW_TEST_MAP')=='true','maintenance':maintenance_state})
  elif u.path=='/select':
   try:
    q=urllib.parse.parse_qs(u.query);player=str(uuid.UUID(q['uuid'][0]));self.reply(200,select(q['target'][0],player))
   except (KeyError,ValueError):self.reply(400,{})
  else:self.reply(404,{})
 def reply(self,code,data):
  b=json.dumps(data).encode();self.send_response(code);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(b)
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
 try:
  api('proxy:8080','/drain?survival','')
  status=api('survival:8081','/status')
  if status.get('count',1)!=0:raise RuntimeError('Survival still has players; operation cancelled')
  c=CLIENT.containers.get('mcservers-survival');c.stop(timeout=90)
  if wipe:
   helper=CLIENT.containers.run('minecraft-controller:local',command=['python','/app/maintenance.py'],
    environment={'BACKUP_KEEP':os.getenv('BACKUP_KEEP','2'),'PREVIOUS_SEED':str(status['seed'])},network_disabled=True,
    mounts=[Mount('/survival','mcservers_survival',type='volume'),Mount('/backups','mcservers_backups',type='volume')],
    labels={'mcservers.managed':'true'},mem_limit='512m',nano_cpus=250000000,detach=True)
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
 finally:maintenance_busy=False

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

def loop():
 while not shutting_down:
  for n in ARENAS:
   with LOCK:state=ARENAS[n]['state'];generation=ARENAS[n]['generation']
   if state not in ('STARTING','WAITING','IN_GAME'):continue
   try:
    report=api(n+':8081','/status')
    if report.get('generation')!=generation:continue
    with LOCK:
     a=ARENAS[n];now=time.time()
     a['players']=report['players'];a['visited']=report['visited']
     a['reservations']={p:t for p,t in a['reservations'].items() if t>now and p not in report['players']}
     a['state']='WAITING' if report['accepting'] else 'IN_GAME';a['last_seen']=now
     if a['visited'] and not a['players'] and not a['reservations']:
      a['state']='RESETTING';persist();WORKER.submit(spawn,n,True)
   except Exception:
    with LOCK:
     a=ARENAS[n]
     if a.get('last_seen') and time.time()-a['last_seen']>15:a['state']='STARTING'
     if time.time()-a.get('started_at',time.time())>180 and not a.get('last_seen'):
      a['state']='FAILED';a['error']='Истекло время запуска арены';persist()
  schedule(dt.datetime.now(TZ));time.sleep(2)

def terminate(signum,frame):
 global shutting_down
 shutting_down=True
 MAINTENANCE_WORKER.shutdown(wait=True,cancel_futures=True)
 WORKER.shutdown(wait=True,cancel_futures=True)
 for c in CLIENT.containers.list(filters={'label':'mcservers.arena'}):
  if c.name.startswith(PREFIX) and c.labels.get('mcservers.arena') in ARENAS:
   try:c.stop(timeout=60)
   except Exception:logging.exception('Could not stop managed arena')
 raise SystemExit(0)

if __name__=='__main__':
 import signal
 signal.signal(signal.SIGTERM,terminate);signal.signal(signal.SIGINT,terminate)
 recover();maintenance_state.update(load_schedule())
 threading.Thread(target=loop,daemon=True).start()
 ThreadingHTTPServer(('0.0.0.0',8080),Handler).serve_forever()
