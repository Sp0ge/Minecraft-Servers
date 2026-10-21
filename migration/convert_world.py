"""Copy a stopped 1.20.1 Survival, convert removed mod content, preserve original."""
import argparse,collections,gzip,hashlib,json,pathlib,shutil,struct,time,zlib
from nbt import Tag,text,loads,dumps
from audit_world import audit
ALLOWED={'minecraft','terralith','tectonic','nullscape','lithostitched'}
BLOCKS={'lootr:lootr_chest':'minecraft:chest','lootr:lootr_barrel':'minecraft:barrel','lootr:lootr_trapped_chest':'minecraft:trapped_chest'}
BES={'lootr:special_loot_chest':'minecraft:chest','lootr:special_loot_barrel':'minecraft:barrel','lootr:special_trapped_loot_chest':'minecraft:trapped_chest'}

def from_json(v):
 if isinstance(v,dict):return Tag(10,{k:from_json(x) for k,x in v.items() if not k.startswith(('fabric:','neoforge:'))})
 if isinstance(v,list):
  values=[from_json(x) for x in v]
  if len({x.kind for x in values})>1:
   if all(x.kind in (1,3,6) for x in values):values=[Tag(6,float(x.value)) for x in values]
   else:raise ValueError('Mixed NBT dimension list')
  return Tag(9,(values[0].kind if values else 0,values))
 if isinstance(v,str):return text(v)
 if isinstance(v,bool):return Tag(1,int(v))
 if isinstance(v,int):return Tag(3,v)
 if isinstance(v,float):return Tag(6,v)
 raise ValueError('Unsupported dimension setting')

def convert(source,target):
 source=source.resolve();target=target.resolve()
 if target.exists() or source==target or source in target.parents:raise ValueError('Target must be a new directory outside the source')
 report=audit(source/'world')
 fatal=[e for e in report['errors'] if 'Truncated region header' not in e['error'] or pathlib.Path(e['file']).stat().st_size!=0]
 if fatal:raise ValueError('Source has unreadable NBT/regions; inspect audit first')
 unknown=set(report['non_vanilla']['blocks'])-set(BLOCKS)
 if unknown:raise ValueError('No lossless conversion for blocks: '+str(unknown))
 if report['non_vanilla']['items']:raise ValueError('Modded item stacks require a separate migration review')
 unsupported_entities=set(report['non_vanilla']['entities'])-{'artifacts:mimic','lootr:lootr_minecart'}
 if unsupported_entities:raise ValueError('Unknown modded entities: '+str(unsupported_entities))
 unsupported_tiles=set(report['non_vanilla']['block_entities'])-set(BES)
 if unsupported_tiles:raise ValueError('Unknown modded block entities: '+str(unsupported_tiles))
 if report['player_files']:raise ValueError('Player inventories require a separate item migration review; refusing automatic conversion')
 # Full original directory is retained. Copy only world, player/network state and access lists.
 target.mkdir(parents=True);counts=collections.Counter();changed_files={}
 for name in ['world','season.json','kiwy-settled.json','ops.json','banned-players.json','banned-ips.json','whitelist.json','usercache.json']:
  src=source/name
  if src.is_dir():shutil.copytree(src,target/name)
  elif src.is_file():shutil.copy2(src,target/name)
 def walk(tag,path=''):
  if tag.kind==10:
   d=tag.value
   if path.endswith('/GameRules'):
    for key in list(d):
     if key.startswith('artifacts.'):
      d.pop(key);counts['removed_mod_game_rules']+=1
   if 'Name' in d and d['Name'].kind==8 and d['Name'].value in BLOCKS:
    d['Name']=text(BLOCKS[d['Name'].value]);counts['block_palette_entries']+=1
   if 'id' in d and d['id'].kind==8:
    ident=d['id'].value
    if ident in BES:
     d['id']=text(BES[ident]);counts['block_entities']+=1
     for key in list(d):
      if key.startswith('Lootr') or key in ('tileId','keepPacked'):d.pop(key)
    elif ident=='lootr:lootr_minecart':d['id']=text('minecraft:chest_minecart');counts['minecarts']+=1
   if 'LootTable' in d and d['LootTable'].kind==8 and d['LootTable'].value.split(':')[0] not in ALLOWED:
    d['LootTable']=text('minecraft:chests/simple_dungeon');counts['removed_mod_loot_tables_replaced']+=1
   # Generated buildings remain. Drop only obsolete procedural structure metadata.
   if path.endswith('/structures'):
    for child in ('starts','References'):
     if child in d and d[child].kind==10:
      for key in list(d[child].value):
       if key.split(':')[0] not in ALLOWED:d[child].value.pop(key);counts['obsolete_structure_records']+=1
   for key,val in list(d.items()):walk(val,path+'/'+key)
  elif tag.kind==9:
   kind,values=tag.value;kept=[]
   for val in values:
    d=val.value if val.kind==10 else {}
    ident=d.get('id')
    if path.lower().endswith('/attributes'):
     attribute=d.get('id') or d.get('Name')
     if attribute and attribute.kind==8 and not attribute.value.startswith('minecraft:'):
      counts['removed_mod_attributes']+=1;continue
    if path.endswith('/ScheduledEvents'):
     callback=d.get('Callback');fn=callback.value.get('Name') if callback and callback.kind==10 else None
     if fn and fn.kind==8 and fn.value.split(':')[0] not in ALLOWED:
      counts['removed_mod_schedules']+=1;continue
    if ident and ident.kind==8 and ident.value=='artifacts:mimic':counts['mimics_removed']+=1;continue
    walk(val,path+'/*');kept.append(val)
   tag.value=(kind,kept)
 def changed(raw):
  name,tree=loads(raw);walk(tree);return dumps(name,tree)
 for file in sorted((target/'world').rglob('*')):
  if not file.is_file():continue
  if file.name in ('session.lock','level.dat_old'):file.unlink();continue
  if file.suffix=='.mca':
   raw=file.read_bytes()
   if not raw:counts['empty_region_placeholders_skipped']+=1;continue
   if len(raw)<8192:raise ValueError('Truncated region')
   header=bytearray(raw[:8192]);parts=[];sector=2
   for slot in range(1024):
    loc=int.from_bytes(raw[slot*4:slot*4+4],'big');offset=loc>>8
    if not offset:continue
    at=offset*4096;length=int.from_bytes(raw[at:at+4],'big');kind=raw[at+4]
    if kind&128:raise ValueError('External chunks require manual migration')
    data=raw[at+5:at+4+length]
    if kind==2:data=zlib.decompress(data)
    elif kind==1:data=gzip.decompress(data)
    elif kind!=3:raise ValueError('Unsupported compression')
    data=zlib.compress(changed(data));record=struct.pack('>I',len(data)+1)+b'\2'+data;n=(len(record)+4095)//4096
    if n>255:raise ValueError('Converted chunk too large')
    header[slot*4:slot*4+4]=((sector<<8)|n).to_bytes(4,'big');parts.append(record.ljust(n*4096,b'\0'));sector+=n
   file.write_bytes(header+b''.join(parts));counts['regions_rewritten']+=1
  elif file.suffix=='.dat':
   if file.name=='random_sequences.dat':
    file.unlink();counts['legacy_loot_rng_regenerated']+=1;continue
   if file.name.startswith('fabricRegistry'):file.unlink();counts['old_fabric_registry_snapshots_omitted']+=1;continue
   raw=file.read_bytes();converted=changed(raw)
   if file.name=='level.dat':
    name,tree=loads(converted);d=tree.value['Data'].value
    # Keep same terrain/seed. Old serialized biome lists reference removed Incendium and old registries.
    # Let 26.3 rebuild dimension settings from the current worldgen datapacks using the original seed.
    wg=d.get('WorldGenSettings')
    if wg and wg.kind==10:
     wg.value['dimensions']=from_json(json.loads(pathlib.Path(__file__).with_name('dimensions-26.3.json').read_text()))
    d['DataPacks']=Tag(10,{'Enabled':Tag(9,(8,[text('vanilla'),text('fabric')])),'Disabled':Tag(9,(8,[]))})
    converted=dumps(name,tree)
   file.write_bytes(gzip.compress(converted,mtime=0))
 (target/'kiwy-runtime.json').write_text(json.dumps({'minecraft':'26.3','loader':'fabric','migrated_from':'1.20.1'}))
 result={'source':str(source),'target':str(target),'original_unchanged':True,'counts':dict(counts),'source_level_sha256':hashlib.sha256((source/'world/level.dat').read_bytes()).hexdigest(),'requires_26_3_first_start':True}
 (target/'migration-report.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2));return result
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=pathlib.Path,required=True);p.add_argument('--target',type=pathlib.Path,required=True);p.add_argument('--convert-lootr-remove-mimics',action='store_true',required=True);a=p.parse_args();convert(a.source,a.target)
