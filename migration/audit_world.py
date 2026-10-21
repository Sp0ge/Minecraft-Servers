"""Read-only NBT/Anvil registry audit before removing mods or upgrading a world."""
import argparse,collections,gzip,json,pathlib,struct,zlib

class Reader:
 def __init__(self,data):self.data=memoryview(data);self.pos=0
 def take(self,n):
  if n<0 or self.pos+n>len(self.data):raise ValueError('Truncated/invalid NBT')
  r=self.data[self.pos:self.pos+n];self.pos+=n;return r
 def number(self,fmt):return struct.unpack('>'+fmt,self.take(struct.calcsize(fmt)))[0]
 def string(self):return bytes(self.take(self.number('H'))).decode('utf-8',errors='replace')
 def tag(self,t,depth=0):
  if depth>128:raise ValueError('NBT nesting too deep')
  if t in (1,2,3,4,5,6):return self.number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[t])
  if t in (7,11,12):
   n=self.number('i');return self.take(n*{7:1,11:4,12:8}[t])
  if t==8:return self.string()
  if t==9:
   kind=self.number('B');n=self.number('i')
   if n<0 or n>10000000:raise ValueError('Invalid NBT list')
   return [self.tag(kind,depth+1) for _ in range(n)]
  if t==10:
   result={}
   while True:
    kind=self.number('B')
    if kind==0:return result
    name=self.string();result[name]=self.tag(kind,depth+1)
  raise ValueError('Unknown NBT tag '+str(t))
 def root(self):
  kind=self.number('B');self.string();return self.tag(kind)

def load(data):
 if data[:2]==b'\x1f\x8b':data=gzip.decompress(data)
 return Reader(data).root()

def audit(world):
 counters={k:collections.Counter() for k in ('blocks','biomes','entities','items','block_entities','worldgen','other_ids')};examples={};errors=[];chunks=0;files=0;versions=collections.Counter();packs=[]
 def remember(category,value,source):
  if not isinstance(value,str) or ':' not in value:return
  counters[category][value]+=1
  if not value.startswith('minecraft:'):examples.setdefault(category+':'+value,source)
 def walk(node,path,source):
  if isinstance(node,dict):
   for key,value in node.items():
    here=path+'/'+key
    if key in ('DataVersion','data_version') and isinstance(value,int):versions[value]+=1
    if key in ('id','Name') and isinstance(value,str):
     category='blocks' if key=='Name' and ('block_states' in path or '/Palette/' in path) else 'block_entities' if 'block_entities' in path else 'entities' if any(path.rsplit('/',1)[0].endswith('/'+name) for name in ('entities','Entities')) else 'items' if any(s in path for s in ('Inventory','inventory','Items','items','equipment','HandItems','ArmorItems')) else 'other_ids'
     remember(category,value,source)
    elif isinstance(value,str) and any(s in here for s in ('WorldGenSettings','dimensions','generator')):remember('worldgen',value,source)
    walk(value,here,source)
  elif isinstance(node,list):
   for i,value in enumerate(node):
    if isinstance(value,str) and '/biomes/palette' in path:remember('biomes',value,source)
    walk(value,path+'/'+str(i),source)
 level=world/'level.dat'
 if not level.is_file():raise ValueError('Choose world containing level.dat')
 tree=load(level.read_bytes());packs=tree.get('Data',{}).get('DataPacks',{});walk(tree,'',str(level))
 for file in sorted(world.rglob('*')):
  if not file.is_file():continue
  try:
   if file.suffix=='.dat' and file!=level:
    if file.name in ('session.lock','level.dat_old'):continue
    walk(load(file.read_bytes()),'',str(file));files+=1
   elif file.suffix=='.mca':
    with file.open('rb') as stream:
     header=stream.read(8192)
     if len(header)==0:continue
     if len(header)!=8192:raise ValueError('Truncated region header')
     for slot in range(1024):
      entry=int.from_bytes(header[slot*4:slot*4+4],'big');offset=entry>>8
      if not offset:continue
      stream.seek(offset*4096);length=struct.unpack('>I',stream.read(4))[0];kind=stream.read(1)[0]
      if length<1 or length>64*1024*1024:raise ValueError('Invalid chunk length')
      if kind&128:
       rx,rz=map(int,file.stem.split('.')[1:]);raw=(file.parent/f'c.{rx*32+slot%32}.{rz*32+slot//32}.mcc').read_bytes()
      else:raw=stream.read(length-1)
      kind&=127
      if kind==1:raw=gzip.decompress(raw)
      elif kind==2:raw=zlib.decompress(raw)
      elif kind!=3:raise ValueError('Unsupported chunk compression '+str(kind))
      walk(load(raw),'',str(file)+'#'+str(slot));chunks+=1
    files+=1
  except Exception as e:errors.append({'file':str(file),'error':str(e)})
 return {'world':str(world.resolve()),'read_only':True,'files':files,'chunks_including_entities_poi':chunks,'player_files':len(list((world/'playerdata').glob('*.dat'))),'data_versions':dict(versions),'datapacks':packs,'registries':{k:dict(sorted(v.items())) for k,v in counters.items()},'non_vanilla':{k:{x:n for x,n in v.items() if not x.startswith('minecraft:')} for k,v in counters.items()},'examples':examples,'errors':errors}

if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('world',type=pathlib.Path);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args();report=audit(a.world);a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k not in ('registries','examples')},ensure_ascii=False,indent=2))
 if report['errors']:raise SystemExit(2)
