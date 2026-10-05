import socket,struct,argparse
from pathlib import Path

def command(host,text):
 password=Path('/secrets/api-token').read_text().strip()
 def packet(i,t,s):
  payload=struct.pack('<ii',i,t)+s.encode()+b'\0\0';return struct.pack('<i',len(payload))+payload
 with socket.create_connection((host,25575),timeout=10) as sock:
  def read(n):
   data=b''
   while len(data)<n:
    chunk=sock.recv(n-len(data))
    if not chunk:raise RuntimeError('RCON connection closed')
    data+=chunk
   return data
  def receive():
   length=struct.unpack('<i',read(4))[0]
   if length<10 or length>4*1024*1024:raise RuntimeError('Invalid RCON response')
   data=read(length);i,t=struct.unpack('<ii',data[:8]);return i,t,data[8:-2].decode(errors='replace')
  sock.sendall(packet(1,3,password))
  while True:
   i,t,s=receive()
   if i==-1:raise RuntimeError('RCON authentication failed')
   if t==2:break
  sock.sendall(packet(2,2,text));i,t,s=receive();return s
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('server',choices=['lobby','survival']+[f'pillars_{i}' for i in range(1,6)]);parser.add_argument('command');args=parser.parse_args();print(command(args.server,args.command))
