"""Provision disposable load-test accounts through the AuthMe console."""
import argparse,json,secrets,re,sys,time,urllib.request
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from rcon import command
def registered(name):
 token=Path('/secrets/api-token').read_text().strip()
 req=urllib.request.Request('http://lobby:8081/registered?name='+name,headers={'Authorization':'Bearer '+token})
 with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)['registered']
def wait_registration(name,value):
 for _ in range(30):
  if registered(name)==value:return
  time.sleep(.5)
 raise SystemExit('AuthMe registration state did not change: '+name)
p=argparse.ArgumentParser();p.add_argument('action',choices=['create','remove']);p.add_argument('--file',required=True);p.add_argument('--count',type=int,default=64);a=p.parse_args();path=Path(a.file)
if a.action=='create':
 if path.exists():raise SystemExit('Accounts file exists; reuse it or remove its accounts first')
 if not 1<=a.count<=64:raise SystemExit('Count must be 1..64')
 prefix='MLT'+secrets.token_hex(2)
 accounts=[{'username':prefix+f'{i:03d}','password':secrets.token_hex(12)} for i in range(a.count)]
 path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(accounts,indent=2));path.chmod(0o600)
 # Save the complete credential set before registration, so interrupted runs can be cleaned up.
 for item in accounts:
  command('lobby','authme register '+item['username']+' '+item['password'])
  wait_registration(item['username'],True)
 print('Created',len(accounts),'accounts. Credentials saved privately.')
else:
 accounts=json.loads(path.read_text())
 for item in accounts:
  if not re.fullmatch(r'MLT[0-9a-f]{4}[0-9]{3}',item['username']):raise SystemExit('Account ownership prefix invalid')
 for item in accounts:
  if registered(item['username']):command('lobby','authme unregister '+item['username']);wait_registration(item['username'],False)
 print('Removed disposable accounts. Delete the credential file on generator and VM.')
