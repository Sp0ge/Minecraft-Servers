"""Obtain a unique internal UDP port from the network controller."""
import json,os,socket,time,urllib.request,urllib.parse
from pathlib import Path

def allocate():
 token=Path('/secrets/api-token').read_text().strip()
 instance=socket.gethostname()
 url='http://controller:8080/voice-port?'+urllib.parse.urlencode({'instance':instance})
 for attempt in range(30):
  try:
   request=urllib.request.Request(url,headers={'Authorization':'Bearer '+token})
   with urllib.request.urlopen(request,timeout=5) as response:port=int(json.load(response)['port'])
   if not 1024<=port<=65535:raise ValueError('Invalid allocated voice port')
   print('Voice Chat internal UDP port:',port,flush=True)
   return port
  except Exception:
   if attempt==29:raise
   time.sleep(1)
