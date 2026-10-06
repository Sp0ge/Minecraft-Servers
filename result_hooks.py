"""Bridge the original map's result functions to the network announcement."""
from pathlib import Path

def install(world):
 root=Path(world)/'datapacks/spark/data/spark/function'
 results={'player_win':'solo','team_win_red':'red','team_win_blue':'blue','not_win':'none'}
 for name,kind in results.items():
  path=root/(name+'.mcfunction')
  text=path.read_text(encoding='utf-8')
  command='networkbackend:kiwypillarsresult '+kind
  target='function spark:trigger_gameover_active'
  if command in text:continue
  if target not in text:raise RuntimeError('Unexpected Pillars result function: '+name)
  text=text.replace(target,'# KiwyClub result bridge\n'+command+'\n\n'+target,1)
  if kind in ('red','blue'):
   lines=text.splitlines()
   for i,line in enumerate(lines):
    if line.startswith('tellraw @a '):
     import json
     parts=json.loads(line[len('tellraw @a '):]);parts.extend([{'text':'Победители: ','color':'gold'},{'selector':'@a[team='+kind+']','color':kind}])
     lines[i]='tellraw @a '+json.dumps(parts,ensure_ascii=False);break
   text='\n'.join(lines)+'\n'
  temporary=path.with_suffix('.mcfunction.tmp');temporary.write_text(text,encoding='utf-8');temporary.replace(path)
