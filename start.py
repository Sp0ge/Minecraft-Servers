import os,pathlib,shutil,re
p=pathlib.Path('/data'); (p/'plugins').mkdir(exist_ok=True)
shutil.copy('/opt/network-proxy.jar',p/'plugins/NetworkProxy.jar')
shutil.copy('/opt/server-icon.png',p/'server-icon.png')
for plugin in ('ViaVersion','ViaBackwards'):
 shutil.copy('/assets/'+plugin+'.jar',p/'plugins'/ (plugin+'.jar'))
via=p/'plugins/viaversion/config.yml';via.parent.mkdir(exist_ok=True)
block='velocity-servers:\n  default: 777\n  lobby: 774\n  pillars: 774\n  survival: 777\n  parkour: 777\n  pvp: 777\n'+''.join('  pillars_'+str(i)+': 773\n' for i in range(1,6))
text=via.read_text() if via.exists() else ''
if 'velocity-servers:' in text:text=re.sub(r'(?m)^velocity-servers:\n(?:[ \t]+[^\n]*\n)*',block,text)
else:text+='\n'+block
text=re.sub(r'(?m)^velocity-ping-save:.*$', 'velocity-ping-save: false',text)
if 'velocity-ping-save:' not in text:text+='velocity-ping-save: false\n'
via.write_text(text)
secret=pathlib.Path('/secrets/forwarding.secret').read_text().strip()
(p/'forwarding.secret').write_text(secret)
(p/'velocity.toml').write_text('''config-version = "2.7"
bind = "0.0.0.0:25565"
motd = "<gradient:#73DB9A:#EDD58A><bold>KiwyClub</bold></gradient> <dark_gray>•</dark_gray> <white>Твой клуб приключений</white>\\n<green>Survival</green> <gray>•</gray> <gold>Pillars</gold> <gray>•</gray> <aqua>Parkour</aqua> <gray>•</gray> <red>PvP</red>"
show-max-players = 64
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
announce-forge = false
kick-existing-players = true
ping-passthrough = "DISABLED"
enable-player-address-logging = false
[servers]
lobby = "lobby:25565"
survival = "survival:25565"
parkour = "parkour:25565"
pvp = "pvp:25565"
try = ["lobby"]
[forced-hosts]
[advanced]
compression-threshold = 256
compression-level = -1
login-ratelimit = 0
connection-timeout = 5000
read-timeout = 30000
haproxy-protocol = false
bungee-plugin-message-channel = true
show-ping-requests = false
failover-on-unexpected-server-disconnect = true
[query]
enabled = false
''')
os.execvp('java',['java','-Xms128M','-Xmx'+os.getenv('PROXY_MEMORY','512M'),'-jar','/assets/velocity.jar'])
