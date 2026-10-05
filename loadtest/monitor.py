"""VM-side authoritative measurements; API secrets never leave this container."""
import argparse,json,time,urllib.request,concurrent.futures,datetime,sys
from pathlib import Path
import docker
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from rcon import command
p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--phase',choices=['load','reset'],default='load')
p.add_argument('--arenas',type=int,default=5);p.add_argument('--per-arena',type=int);p.add_argument('--players',type=int,default=64);p.add_argument('--distribution');p.add_argument('--seconds',type=int,default=900)
p.add_argument('--warmup',type=int,default=300);p.add_argument('--arrival-timeout',type=int,default=1800);p.add_argument('--reset-timeout',type=int,default=180)
p.add_argument('--start-matches',action='store_true');a=p.parse_args()
layout=[1]*a.arenas if a.phase=='reset' else [int(n) for n in a.distribution.split(',')] if a.distribution else [a.per_arena]*a.arenas if a.per_arena else [a.players//a.arenas+(i<a.players%a.arenas) for i in range(a.arenas)]
if not 1<=a.arenas<=5 or len(layout)!=a.arenas or any(n<1 or n>16 for n in layout) or sum(layout)>64 or a.seconds<1:raise SystemExit('Invalid workload size; network maximum is 64')
out=Path(a.output);out.mkdir(parents=True,exist_ok=True);token=Path('/secrets/api-token').read_text().strip();client=docker.from_env();previous={};samples=[];started=time.monotonic();full_since=None;last_match={};initial=None
def api(host):
 req=urllib.request.Request('http://'+host+'/status',headers={'Authorization':'Bearer '+token})
 with urllib.request.urlopen(req,timeout=5) as r:return json.load(r)
def stats(c):
 s=c.stats(stream=False,one_shot=True);now=time.monotonic();cpu=s['cpu_stats']['cpu_usage']['total_usage'];old=previous.get(c.id);previous[c.id]=(cpu,now)
 return {'name':c.name,'memory_bytes':s['memory_stats'].get('usage',0),'cpu_percent':None if old is None else (cpu-old[0])/(now-old[1])/1e7,
  'throttling':s['cpu_stats'].get('throttling_data',{}),'blkio':s.get('blkio_stats',{}),'oom':c.attrs['State'].get('OOMKilled',False),
  'memory_limit_bytes':c.attrs['HostConfig']['Memory'],'generation':c.labels.get('mcservers.generation')}
def take():
 record={'time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'elapsed':time.monotonic()-started,'backend':{},'errors':{}}
 targets=['lobby','survival','parkour','pvp']+[f'pillars_{i}' for i in range(1,a.arenas+1)]
 with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
  tasks={pool.submit(api,n+':8081'):n for n in targets}
  for task,n in tasks.items():
   try:record['backend'][n]=task.result()
   except Exception as e:record['errors'][n]=str(e)
  tasks=[pool.submit(stats,c) for c in client.containers.list(filters={'label':'mcservers.managed=true'})]
  record['containers']=[t.result() for t in tasks]
 record['lifecycle']=[{'name':c.name,'oom':c.attrs['State'].get('OOMKilled',False),'restarts':c.attrs.get('RestartCount',0)} for c in client.containers.list(all=True,filters={'label':'mcservers.managed=true'})]
 try:record['controller']=api('controller:8080')
 except Exception as e:record['errors']['controller']=str(e)
 record['memory_bytes']=sum(c['memory_bytes'] for c in record['containers'])
 return record
def full(r):return all(r['backend'].get(f'pillars_{i}',{}).get('count')==layout[i-1] for i in range(1,a.arenas+1))
failures=[]
with (out/'samples.jsonl').open('w') as stream:
 try:
  if a.phase=='reset':
   initial=api('controller:8080')['arenas'];initial={f'pillars_{i}':initial[f'pillars_{i}']['generation'] for i in range(1,a.arenas+1)}
   (out/'reset-baseline.json').write_text(json.dumps(initial));print('RESET_READY: disconnect all generator clients now',flush=True)
  while True:
   r=take();now=time.monotonic();r['measured']=False
   if a.phase=='reset':
    arenas=r.get('controller',{}).get('arenas',{})
    if all((arenas.get(n,{}).get('state')=='WAITING' and arenas[n].get('generation')!=g and not arenas[n].get('visited')) if n=='pillars_1' else arenas.get(n,{}).get('state')=='STOPPED' and arenas[n].get('generation') is None and ('mcservers_'+n) not in {c['name'] for c in r['lifecycle']} for n,g in initial.items()):
     r['reset_complete_seconds']=now-started;samples.append(r);stream.write(json.dumps(r)+'\n');break
    if now-started>a.reset_timeout:failures.append('Reset deadline exceeded');break
   else:
    if full(r):
     if full_since is None:full_since=now;print('FULL: all expected players verified on backend servers',flush=True)
     r['measured']=now-full_since>=a.warmup
     if a.start_matches:
      for i in range(1,a.arenas+1):
       n=f'pillars_{i}';b=r['backend'][n]
       if b.get('accepting') and now-last_match.get(n,-1000)>180:
        # Reject human participants before invoking the map's real round function.
        if not b.get('players_names') or not all(x.startswith('MLT') for x in b['players_names']):raise RuntimeError('Non-test player or missing name metrics: '+n)
        command(n,'function spark:start_game with storage barrier:');last_match[n]=now
     if now-full_since>=a.warmup+a.seconds:samples.append(r);stream.write(json.dumps(r)+'\n');break
    elif full_since is not None:failures.append('Player loss or unavailable arena during load');break
    elif now-started>a.arrival_timeout:failures.append('Expected arena population not reached');break
   samples.append(r);stream.write(json.dumps(r)+'\n');stream.flush();time.sleep(5)
 except Exception as e:failures.append(str(e))
measured=[s for s in samples if s.get('measured')];arena_summary={}
for i in range(1,a.arenas+1):
 n=f'pillars_{i}';values=[s['backend'].get(n,{}) for s in measured]
 good=sum(v.get('tps_1m',0)>=19.5 and v.get('mspt_p95',999)<=50 for v in values)
 arena_summary[n]={'measured_samples':len(values),'healthy_fraction':good/len(values) if values else None,
  'worst_p95_ms':max([v.get('mspt_p95',999) for v in values],default=None),'minimum_tps_1m':min([v.get('tps_1m',0) for v in values],default=None)}
 if a.phase=='load' and (not values or good/len(values)<.95):failures.append(n+': performance target not met')
if any(s['memory_bytes']>32*1024**3 for s in samples):failures.append('32 GiB memory budget exceeded')
if any(c['oom'] for s in samples for c in s['containers']):failures.append('OOM detected')
if any(c['oom'] for s in samples for c in s['lifecycle']):failures.append('Stopped OOM container detected')
if samples:
 baseline={c['name']:c['restarts'] for c in samples[0]['lifecycle']}
 if any(c['restarts']>baseline.get(c['name'],c['restarts']) for s in samples for c in s['lifecycle']):failures.append('Unexpected container restart detected')
report={'phase':a.phase,'passed':not failures,'failures':failures,'arenas':arena_summary,
 'peak_container_memory_bytes':max([s['memory_bytes'] for s in samples],default=0),'sample_count':len(samples),
 'reset_seconds':samples[-1].get('reset_complete_seconds') if samples else None,
 'note':'p95 is the recent Paper tick window; this is not a percentile over every tick of the whole run. Thresholds are project acceptance targets.'}
(out/'report.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2));sys.exit(0 if report['passed'] else 1)
