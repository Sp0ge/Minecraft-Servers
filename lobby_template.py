"""Export the supplied Sponge v2 schematic as a fresh, upgradeable lobby world.

No newer Minecraft world is downgraded: block data comes from the original
Minecraft 1.20.4 schematic. Only standard-library Python is required.
"""
import argparse,array,gzip,io,json,math,struct,zlib,zipfile
from pathlib import Path

class NBT:
 def __init__(self,raw):self.stream=io.BytesIO(raw)
 def num(self,fmt):return struct.unpack('>'+fmt,self.stream.read(struct.calcsize('>'+fmt)))[0]
 def string(self):return self.stream.read(self.num('H')).decode('utf-8')
 def value(self,kind):
  if kind in range(1,7):return self.num({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind])
  if kind==7:return self.stream.read(self.num('i'))
  if kind==8:return self.string()
  if kind==9:
   subtype=self.num('b');return [self.value(subtype) for _ in range(self.num('i'))]
  if kind==10:
   result={}
   while True:
    subtype=self.num('b')
    if not subtype:return result
    name=self.string();result[name]=self.value(subtype)
  if kind in (11,12):return [self.num('i' if kind==11 else 'q') for _ in range(self.num('i'))]
  raise ValueError('Unsupported NBT tag: '+str(kind))
 def root(self):kind=self.num('b');self.string();return self.value(kind)

def tag(kind,value):return (kind,value)
def string(value):return tag(8,value)
def integer(value):return tag(3,value)
def long(value):return tag(4,value)
def byte(value):return tag(1,value)
def compound(value):return tag(10,value)
def listing(kind,values):return tag(9,(kind,values))
def encoded(kind,value):
 if kind in range(1,7):return struct.pack('>'+{1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind],value)
 if kind==7:return struct.pack('>i',len(value))+value
 if kind==8:
  data=value.encode('utf-8');return struct.pack('>H',len(data))+data
 if kind==9:
  sub,items=value;return bytes([sub])+struct.pack('>i',len(items))+b''.join(encoded(sub,v) for v in items)
 if kind==10:return b''.join(bytes([k])+encoded(8,n)+encoded(k,v) for n,(k,v) in value.items())+b'\0'
 if kind in (11,12):return struct.pack('>i',len(value))+b''.join(struct.pack('>i' if kind==11 else '>q',v) for v in value)
 raise ValueError(kind)
def root(value):return b'\x0a\0\0'+encoded(10,value)

def plain(value):
 if isinstance(value,str):return string(value)
 if isinstance(value,bytes):return tag(7,value)
 if isinstance(value,int):return integer(value) if -2**31<=value<2**31 else long(value)
 if isinstance(value,float):return tag(6,value)
 if isinstance(value,dict):return compound({k:plain(v) for k,v in value.items()})
 if isinstance(value,list):
  nodes=[plain(v) for v in value]
  if nodes and any(k!=nodes[0][0] for k,v in nodes):raise ValueError('Mixed NBT list')
  return listing(nodes[0][0] if nodes else 10,[v for k,v in nodes])
 raise ValueError(type(value))

def state(name):
 result={'Name':string(name.split('[',1)[0])}
 if '[' in name:
  result['Properties']=compound({k:string(v) for k,v in (p.split('=') for p in name.split('[',1)[1].rstrip(']').split(','))})
 return result

def export(source,output):
 schematic=NBT(gzip.decompress(Path(source).read_bytes())).root()
 if schematic.get('Version')!=2:raise ValueError('Sponge v2 schematic required')
 width,height,length=(schematic[k] for k in ('Width','Height','Length'))
 blocks=array.array('I');current=shift=0
 for b in schematic['BlockData']:
  current|=(b&127)<<shift
  if b&128:shift+=7
  else:blocks.append(current);current=shift=0
 if len(blocks)!=width*height*length or shift:raise ValueError('Malformed schematic block data')
 palette={v:k for k,v in schematic['Palette'].items()}
 air=schematic['Palette']['minecraft:air'];floor=-46
 def at(x,y,z):
  if not(0<=x<width and 0<=z<length and floor<=y<floor+height):return air
  return blocks[((y-floor)*length+z)*width+x]
 # Start near the supplied world spawn; choose a solid floor with two air blocks.
 spawn=None
 dangerous=('air','water','lava','cactus','magma_block','fire','scaffolding','trapdoor','fence','wall','leaves','stairs','slab')
 for distance in range(20):
  for dx in range(-distance,distance+1):
   for dz in range(-distance,distance+1):
    if max(abs(dx),abs(dz))!=distance:continue
    x,z=86+dx,116+dz
    for y in range(43,50):
     name=palette[at(x,y-1,z)].split('[',1)[0]
     if not any(n in name for n in dangerous) and at(x,y,z)==air and at(x,y+1,z)==air:
      spawn=[x,y,z];break
    if spawn:break
   if spawn:break
  if spawn:break
 if not spawn:raise ValueError('No safe lobby spawn near supplied spawn')
 version=schematic['DataVersion'];regions={}
 for cz in range(math.ceil(length/16)):
  for cx in range(math.ceil(width/16)):
   sections=[]
   for sy in range(-4,20):
    local=[];ids={};values=[]
    for y in range(sy*16,sy*16+16):
     for z in range(cz*16,cz*16+16):
      for x in range(cx*16,cx*16+16):
       value=at(x,y,z)
       if value not in ids:ids[value]=len(local);local.append(state(palette[value]))
       values.append(ids[value])
    bs={'palette':listing(10,local)}
    if len(local)>1:
     bits=max(4,(len(local)-1).bit_length());per=64//bits;packed=[]
     for start in range(0,4096,per):
      word=sum(v<<(bits*i) for i,v in enumerate(values[start:start+per]));packed.append(word if word<2**63 else word-2**64)
     bs['data']=tag(12,packed)
    sections.append({'Y':byte(sy),'block_states':compound(bs),'biomes':compound({'palette':listing(8,['minecraft:plains'])})})
   chunk={'DataVersion':integer(version),'xPos':integer(cx),'yPos':integer(-4),'zPos':integer(cz),'Status':string('minecraft:full'),
          'LastUpdate':long(0),'InhabitedTime':long(0),'isLightOn':byte(0),'sections':listing(10,sections),
          'block_entities':listing(10,[]),'block_ticks':listing(10,[]),'fluid_ticks':listing(10,[]),
          'Heightmaps':compound({}),'structures':compound({'starts':compound({}),'References':compound({})})}
   # Signs and other block entities can be upgraded from the schematic's original version.
   entities=[]
   for entity in schematic.get('BlockEntities',[]):
    pos=entity.get('Pos')
    if not pos or pos[0]//16!=cx or pos[2]//16!=cz:continue
    data=entity.get('Data',entity);record={'id':string(entity.get('Id',data.get('id','minecraft:sign'))),
       'x':integer(pos[0]),'y':integer(pos[1]+floor),'z':integer(pos[2])}
    # Preserve decorative metadata; command blocks are disabled by lobby startup.
    for key,val in data.items():
     if key in ('Pos','Id','id','x','y','z','Command','Items'):continue
     record[key]=plain(val)
    entities.append(record)
   chunk['block_entities']=listing(10,entities)
   data=zlib.compress(root(chunk),6);body=struct.pack('>I',len(data)+1)+b'\x02'+data
   regions[(cx%32)+(cz%32)*32]=body
   print('chunk',cx,cz,flush=True) if cx==0 else None
 header=bytearray(8192);body=bytearray();sector=2
 for slot,data in sorted(regions.items()):
  count=math.ceil(len(data)/4096);header[slot*4:slot*4+4]=sector.to_bytes(3,'big')+bytes([count])
  body.extend(data+b'\0'*(count*4096-len(data)));sector+=count
 dims={'minecraft:overworld':compound({'type':string('minecraft:overworld'),'generator':compound({'type':string('minecraft:flat'),
   'settings':compound({'biome':string('minecraft:plains'),'features':byte(0),'lakes':byte(0),'layers':listing(10,[]),'structure_overrides':listing(8,[])})})}),
   'minecraft:the_nether':compound({'type':string('minecraft:the_nether'),'generator':compound({'type':string('minecraft:noise'),'settings':string('minecraft:nether'),'biome_source':compound({'type':string('minecraft:multi_noise'),'preset':string('minecraft:nether')})})}),
   'minecraft:the_end':compound({'type':string('minecraft:the_end'),'generator':compound({'type':string('minecraft:noise'),'settings':string('minecraft:end'),'biome_source':compound({'type':string('minecraft:the_end')})})})}
 level={'DataVersion':integer(version),'version':integer(19133),'LevelName':string('KiwyClub Japan Lobby'),'GameType':integer(2),
  'Difficulty':byte(0),'hardcore':byte(0),'allowCommands':byte(0),'initialized':byte(1),'Time':long(0),'DayTime':long(6000),
  'SpawnX':integer(spawn[0]),'SpawnY':integer(spawn[1]),'SpawnZ':integer(spawn[2]),'SpawnAngle':tag(5,0.0),
  'WorldGenSettings':compound({'bonus_chest':byte(0),'seed':long(0),'generate_features':byte(0),'dimensions':compound(dims)}),
  'GameRules':compound({k:string(v) for k,v in {'doDaylightCycle':'false','doWeatherCycle':'false','doMobSpawning':'false','randomTickSpeed':'0','spawnRadius':'0'}.items()})}
 with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as z:
  z.writestr('level.dat',gzip.compress(root({'Data':compound(level)}),mtime=0))
  z.writestr('region/r.0.0.mca',header+body)
  z.writestr('kiwy-map.json',json.dumps({'spawn':spawn,'source':'by-kubj-lobby-japan-free.schem','source_data_version':version,'dimensions':[width,height,length]}))
 print('Exported',output,'spawn',spawn)

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('schematic');p.add_argument('output');a=p.parse_args();export(a.schematic,a.output)
