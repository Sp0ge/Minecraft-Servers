"""Keep the existing AuthMe settings; set only KiwyClub's declared options."""
def set_value(text, path, value):
 lines=text.splitlines();start=0;end=len(lines)
 for depth,key in enumerate(path):
  indent=' '*4*depth
  index=next((i for i in range(start,end) if lines[i].startswith(indent+key+':') and len(lines[i])-len(lines[i].lstrip())==4*depth),None)
  if depth==len(path)-1:
   line=indent+key+': '+str(value)
   if index is None:lines.insert(end,line)
   else:
    stop=index+1
    while stop<end and (not lines[stop].strip() or lines[stop].lstrip().startswith(('#','-')) or len(lines[stop])-len(lines[stop].lstrip())>4*depth):stop+=1
    lines[index:stop]=[line]
   break
  if index is None:lines.insert(end,indent+key+':');index=end;end+=1
  start=index+1
  end=next((i for i in range(start,end) if lines[i].strip() and not lines[i].lstrip().startswith('#') and len(lines[i])-len(lines[i].lstrip())<=4*depth),end)
 return '\n'.join(lines)+'\n'

def configure(text):
 for key in ('maxRegPerIp','maxLoginPerIp','maxJoinPerIp'):
  text=set_value(text,('settings','restrictions',key),0)
 text=set_value(text,('settings','serverName'),'KiwyClub')
 text=set_value(text,('settings','restrictions','ForceSpawnLocOnJoin','enabled'),'true')
 text=set_value(text,('settings','restrictions','ForceSpawnLocOnJoin','worlds'),'[world]')
 text=set_value(text,('settings','restrictions','teleportUnAuthedToSpawn'),'true')
 text=set_value(text,('settings','restrictions','SaveQuitLocation'),'false')
 text=set_value(text,('settings','restrictions','spawnPriority'),'default')
 text=set_value(text,('settings','restrictions','allowCommands'),'[/login, /register, /help, /server]')
 text=set_value(text,('settings','restrictions','ProtectInventoryBeforeLogIn'),'false')
 text=set_value(text,('Protection','geoIpDatabase','enabled'),'false')
 for stage in ('preJoin','postJoin'):
  text=set_value(text,('settings','registration','dialog',stage,'enable'),'false')
 return text
