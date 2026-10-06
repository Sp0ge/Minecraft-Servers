"""Read-only network and Voice Chat checks; no player bots or load generation."""
import json, subprocess, sys

def docker(*args):
 result=subprocess.run(['docker',*args],text=True,capture_output=True)
 if result.returncode:raise RuntimeError(result.stderr.strip())
 return result.stdout

def inspect(name):
 return json.loads(docker('inspect',name))[0]

def main():
 failed=False
 names=docker('ps','-a','--filter','label=mcservers.managed=true','--format','{{.Names}}').splitlines()
 if not names:raise RuntimeError('Нет контейнеров KiwyClub')
 probe="""import socket,time,uuid,struct,json,sys
host,port=sys.argv[1],int(sys.argv[2]);nonce=uuid.uuid4().bytes;stamp=struct.pack('>q',int(time.time()*1000))
packet=b'\\xff'+uuid.UUID('58bc9ae9-c7a8-45e4-a11c-efbb67199425').bytes+b'\\x18'+nonce+stamp
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.settimeout(3)
try:
 s.sendto(packet,(host,port));data,peer=s.recvfrom(2048)
 # The proxy wraps its pong with the packet type; backends return raw payload.
 valid=data==nonce+stamp or (len(data)>=18 and data[0]==24 and data[1:17]==nonce)
 print('OK' if valid else 'INVALID RESPONSE')
except OSError:print('NO UDP RESPONSE')
finally:s.close()
"""
 for name in sorted(names):
  info=inspect(name)
  if not info['State']['Running']:
   print(name+': STOPPED (exit '+str(info['State']['ExitCode'])+')');failed=True;continue
  if name=='mcservers-controller':print(name+': RUNNING');continue
  config='plugins/voicechat/voicechat-proxy.properties' if name=='mcservers-proxy' else 'config/voicechat/voicechat-server.properties' if name in ('mcservers-survival','mcservers-pvp') else 'plugins/voicechat/voicechat-server.properties'
  reader="""import pathlib,json,sys
p=pathlib.Path('/data')/sys.argv[1]
props=dict(line.split('=',1) for line in p.read_text().splitlines() if '=' in line and not line.startswith('#'))
print(json.dumps({key:props.get(key,'') for key in ('port','bind_address','voice_host')}))
"""
  try:
   props=json.loads(docker('exec',name,'python3','-c',reader,config));port=int(props['port'])
   host='127.0.0.1' if name=='mcservers-proxy' else info['NetworkSettings']['Networks']['mcservers_internal']['IPAddress']
   result=docker('exec','mcservers-proxy','python3','-c',probe,host,str(port)).strip()
   print(name+': UDP '+str(port)+' '+result+(' | advertised '+props['voice_host'] if name=='mcservers-proxy' else ''))
   if result!='OK':failed=True
  except (RuntimeError,ValueError,KeyError) as error:
   print(name+': CHECK FAILED: '+str(error));failed=True
 print('Проверено: процессы и UDP Voice Chat. Микрофон и соединение клиента требуют проверки в игре.')
 return 1 if failed else 0

if __name__=='__main__':
 try:sys.exit(main())
 except RuntimeError as error:print(error,file=sys.stderr);sys.exit(1)
