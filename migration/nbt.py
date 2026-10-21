"""Small typed NBT codec used only for offline world migration (standard library)."""
import struct,gzip
from dataclasses import dataclass
@dataclass
class Tag:
 kind:int
 value:object
class Reader:
 def __init__(self,data):self.data=memoryview(data);self.pos=0
 def take(self,n):
  if n<0 or self.pos+n>len(self.data):raise ValueError('Truncated NBT')
  b=self.data[self.pos:self.pos+n];self.pos+=n;return b
 def num(self,fmt):return struct.unpack('>'+fmt,self.take(struct.calcsize(fmt)))[0]
 def string(self):
  raw=bytes(self.take(self.num('H'))).replace(b'\xc0\x80',b'\0')
  return raw.decode('utf-8','surrogatepass').encode('utf-16-be','surrogatepass').decode('utf-16-be','surrogatepass')
 def tag(self,k,depth=0):
  if depth>128:raise ValueError('Too deeply nested NBT')
  if k in range(1,7):v=self.num({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[k])
  elif k in (7,11,12):
   count=self.num('i');v=(count,bytes(self.take(count*{7:1,11:4,12:8}[k])))
  elif k==8:v=self.string()
  elif k==9:
   child=self.num('B');count=self.num('i')
   if count<0 or count>10000000:raise ValueError('Invalid NBT list')
   v=(child,[self.tag(child,depth+1) for _ in range(count)])
  elif k==10:
   v={}
   while True:
    child=self.num('B')
    if child==0:break
    name=self.string();v[name]=self.tag(child,depth+1)
  else:raise ValueError('Invalid NBT type')
  return Tag(k,v)
def string(value):
 units=value.encode('utf-16-be','surrogatepass');out=bytearray()
 for i in range(0,len(units),2):
  n=int.from_bytes(units[i:i+2],'big')
  if n==0:out.extend(b'\xc0\x80')
  elif n<=127:out.append(n)
  elif n<=2047:out.extend((192|(n>>6),128|(n&63)))
  else:out.extend((224|(n>>12),128|((n>>6)&63),128|(n&63)))
 b=bytes(out);return struct.pack('>H',len(b))+b
def payload(t):
 k,v=t.kind,t.value
 if k in range(1,7):return struct.pack('>'+{1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[k],v)
 if k in (7,11,12):return struct.pack('>i',v[0])+v[1]
 if k==8:return string(v)
 if k==9:
  if any(x.kind!=v[0] for x in v[1]):raise ValueError('Mixed NBT list types')
  return bytes([v[0]])+struct.pack('>i',len(v[1]))+b''.join(payload(x) for x in v[1])
 if k==10:return b''.join(bytes([x.kind])+string(name)+payload(x) for name,x in v.items())+b'\0'
 raise ValueError('Invalid NBT type')
def loads(data):
 if data[:2]==b'\x1f\x8b':data=gzip.decompress(data)
 r=Reader(data);k=r.num('B');name=r.string();return name,r.tag(k)
def dumps(name,t):return bytes([t.kind])+string(name)+payload(t)
def text(value):return Tag(8,value)
