"""Keep the existing AuthMe settings; set only KiwyClub's declared options."""
def set_value(text, path, value):
 lines=text.splitlines();start=0;end=len(lines)
 for depth,key in enumerate(path):
  indent=' '*4*depth
  index=next((i for i in range(start,end) if lines[i].startswith(indent+key+':') and len(lines[i])-len(lines[i].lstrip())==4*depth),None)
  if depth==len(path)-1:
   line=indent+key+': '+str(value)
   if index is None:lines.insert(end,line)
   else:lines[index]=line
   break
  if index is None:lines.insert(end,indent+key+':');index=end;end+=1
  start=index+1
  end=next((i for i in range(start,end) if lines[i].strip() and not lines[i].lstrip().startswith('#') and len(lines[i])-len(lines[i].lstrip())<=4*depth),end)
 return '\n'.join(lines)+'\n'

def configure(text):
 for key in ('maxRegPerIp','maxLoginPerIp','maxJoinPerIp'):
  text=set_value(text,('settings','restrictions',key),0)
 text=set_value(text,('settings','serverName'),'KiwyClub')
 for stage in ('preJoin','postJoin'):
  text=set_value(text,('settings','registration','dialog',stage,'enable'),'false')
 return text
