"""Bridge the original map's result functions to the network announcement."""
from pathlib import Path

def install(world):
 root=Path(world)/'datapacks/spark/data/spark/function'
 # Hide the original per-score labels that become line_1..line_14 on old clients.
 # NetworkBackend presents a compatible panel without changing gameplay scores.
 display=root/'scoreboards/set.mcfunction'
 text=display.read_text(encoding='utf-8')
 lines=text.splitlines()
 updated='\n'.join('# KiwyClub panel: '+line if line.startswith('scoreboard objectives setdisplay sidebar') else line for line in lines)+'\n'
 if updated!=text:display.write_text(updated,encoding='utf-8')
 # Evaluate the survivors after deaths/falls, with the draw taking precedence.
 tick=root/'tick.mcfunction';text=tick.read_text(encoding='utf-8')
 marker='# KiwyClub match completion'
 if marker not in text:
  lines=[line for line in text.splitlines() if not any('run function spark:'+name in line for name in ('player_win','team_win_red','team_win_blue','not_win'))]
  lines.extend([marker,
   'execute store result score #player count if entity @a[tag=playing]',
   'execute if score #game_info game matches 1 if score #player count matches 0 run function spark:not_win',
   'execute if score #game_info game matches 1 if score #team game matches 0 if score #player count matches 1 run function spark:player_win',
   'execute if score #game_info game matches 1 if score #team game matches 1 if entity @a[tag=playing,team=red] unless entity @a[tag=playing,team=blue] run function spark:team_win_red',
   'execute if score #game_info game matches 1 if score #team game matches 1 if entity @a[tag=playing,team=blue] unless entity @a[tag=playing,team=red] run function spark:team_win_blue'])
  tick.write_text('\n'.join(lines)+'\n',encoding='utf-8')
 results={'player_win':'solo','team_win_red':'red','team_win_blue':'blue','not_win':'none'}
 for name,kind in results.items():
  path=root/(name+'.mcfunction')
  text=path.read_text(encoding='utf-8')
  legacy='networkbackend:kiwypillarsresult '+kind
  signal='scoreboard players set #result kiwy_result '+str({'solo':1,'red':2,'blue':3,'none':4}[kind])
  capture='tag @a remove kiwy_winner\n'
  if kind!='none':capture+='tag @a['+('tag=playing' if kind=='solo' else 'tag=playing,team='+kind)+'] add kiwy_winner\n'
  command=capture+signal
  target='function spark:trigger_gameover_active'
  if signal in text:continue
  if target not in text:raise RuntimeError('Unexpected Pillars result function: '+name)
  if legacy in text:text=text.replace(legacy,command)
  else:text=text.replace(target,'# KiwyClub result bridge\n'+command+'\n\n'+target,1)
  if kind in ('red','blue'):
   lines=text.splitlines()
   for i,line in enumerate(lines):
    if line.startswith('tellraw @a ') and 'Победители:' not in line:
     import json
     parts=json.loads(line[len('tellraw @a '):]);parts.extend([{'text':'Победители: ','color':'gold'},{'selector':'@a[team='+kind+']','color':kind}])
     lines[i]='tellraw @a '+json.dumps(parts,ensure_ascii=False);break
   text='\n'.join(lines)+'\n'
  temporary=path.with_suffix('.mcfunction.tmp');temporary.write_text(text,encoding='utf-8');temporary.replace(path)
