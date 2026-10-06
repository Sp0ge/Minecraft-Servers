const mineflayer=require('mineflayer'),fs=require('fs'),crypto=require('crypto'),rcon=require('./rcon');
const token=fs.readFileSync('/secrets/api-token','utf8').trim(),wait=ms=>new Promise(r=>setTimeout(r,ms));let bots=[];
async function api(host,path){const r=await fetch('http://'+host+path,{headers:{Authorization:'Bearer '+token},signal:AbortSignal.timeout(10000)});if(!r.ok)throw Error('API '+r.status);return r.json()}
async function until(check,label,timeout=90000){const start=Date.now();while(Date.now()-start<timeout){if(await check())return;await wait(300)}throw Error('Timeout '+label)}
async function at(b,s){try{return(await api(s+':8081','/status')).players.includes(b._client.uuid)}catch{return false}}
function plain(c){
 if(c==null)return '';if(typeof c==='string')return c;if(Array.isArray(c))return c.map(plain).join('');
 if(c.type&&Object.prototype.hasOwnProperty.call(c,'value'))return plain(c.value);const v=c;if(typeof v==='string')return v;if(Array.isArray(v))return v.map(plain).join('');
 return (v.text?plain(v.text):'')+(v.extra?plain(v.extra):'');
}
function connect(account){
 const b=mineflayer.createBot({host:'proxy',username:account.name,auth:'offline',version:'1.21.10',physicsEnabled:false});bots.push(b);b.lines=[];b.header='';b.helpPackets=[];
 const write=b._client.write.bind(b._client);b._client.write=(n,d)=>{if(['position','position_look','look'].includes(n)&&Object.entries(d).some(([k,v])=>['x','y','z','yaw','pitch'].includes(k)&&!Number.isFinite(v)))return;write(n,d);if(['position','position_look','look','flying','use_item','block_place','held_item_slot','window_click'].includes(n))write('tick_end',{})};
 b.on('messagestr',s=>{b.lines.push(s);fs.appendFileSync('/tests/kiwy-chat.log',s.replaceAll(account.password,'[hidden]')+'\n',{mode:0o600})});b._client.on('playerlist_header',p=>{b.header=JSON.stringify(p);b.headerText=plain(p.header)+plain(p.footer)});b._client.on('system_chat',p=>b.helpPackets.push(p));b._client.on('open_book',()=>b.bookOpened=true);
 b.on('error',e=>console.log('CLIENT_ERROR',e.message));b.on('kicked',reason=>console.log('CLIENT_KICKED',account.name,JSON.stringify(reason)));return b;
}
async function main(){
 await until(async()=>{try{return(await api('lobby:8081','/status')).role==='lobby'}catch{return false}},'lobby');
 const accounts=Array.from({length:2},()=>({name:'KCTest'+crypto.randomBytes(3).toString('hex'),password:crypto.randomBytes(12).toString('hex')}));
 fs.writeFileSync('/tests/kiwy-accounts.json',JSON.stringify(accounts),{mode:0o600});
 const [a,b]=accounts.map(connect);await until(async()=>await at(a,'lobby')&&await at(b,'lobby'),'simultaneous same IP connections');console.log('PASS simultaneous connections from one IP');
 await until(()=>a.inventory.items().some(i=>i.name==='written_book'),'guidebook');const book=a.inventory.items().find(i=>i.name==='written_book');
 const data=book.components.find(c=>c.type==='written_book_content').data;
 if(data.author!=='KiwyClub'||data.rawTitle!=='Команды и режимы'||data.pages.length!==16)throw Error('Guidebook metadata mismatch');
 const content=JSON.stringify(data);if(!content.includes('change_page')||!content.includes('run_command'))throw Error('Book navigation missing');
 fs.writeFileSync('/tests/kiwy-book.json',JSON.stringify(data));a.activateItem();await until(()=>a.bookOpened,'book before authentication');console.log('PASS styled 16-page book, clickable contents and reading before login');
 await wait(1500);a.chat('/register '+accounts[0].password+' '+accounts[0].password);b.chat('/register '+accounts[1].password+' '+accounts[1].password);
 await until(async()=>(await api('lobby:8081','/registered?name='+accounts[0].name)).registered&&(await api('lobby:8081','/registered?name='+accounts[1].name)).registered,'both normal registrations from same IP');
 for(let i=0;i<2;i++)bots[i].chat('/login '+accounts[i].password);
 await until(async()=>(await api('lobby:8081','/auth?uuid='+a._client.uuid)).authenticated&&(await api('lobby:8081','/auth?uuid='+b._client.uuid)).authenticated,'both authenticated');console.log('PASS normal registration and login of two accounts from one IP');
 a.chat('/help');b.chat('/help all');await until(()=>a.lines.some(s=>s.includes('KiwyClub'))&&a.lines.some(s=>s.includes('/help all'))&&b.lines.some(s=>s.includes('/pvpaccept')),'help');
 a.chat('/help pvp');await until(()=>a.lines.some(s=>s.includes('/pvpaccept')),'pvp help');
 const rendered=JSON.stringify(a.helpPackets);if(!rendered.includes('suggest_command')||!rendered.includes('run_command'))throw Error('Help actions missing');console.log('PASS section help, full command reference and clickable actions');
 await until(()=>a.headerText?.includes('KiwyClub'),'TAB');
 // Gradient letters may be separate text nodes, so verify the actual header via recursive text extraction below.
 a.setQuickBarSlot(4);await wait(400);a.activateItem();await until(()=>a.currentWindow,'compass menu');
 if(!JSON.stringify(a.currentWindow.title).includes('KiwyClub'))throw Error('Menu branding mismatch');await a.clickWindow(6,0,0);await until(()=>at(a,'parkour'),'styled menu routes to parkour');
 await until(()=>a.header.includes('parkour')&&a.header.includes('Пинг'),'parkour TAB');fs.writeFileSync('/tests/kiwy-tab.json',a.header);console.log('PASS branded compass menu and dynamic TAB on parkour');
 const course=await api('parkour:8081','/parkour/status');
 for(const i of [70,80]){const step=course.steps[i];await rcon('parkour',token,'tp '+a.username+' '+(step.x+.5)+' '+(step.y+1)+' '+(step.z+.5));await wait(700);a._client.write('position',{x:step.x+.6,y:step.y+1,z:step.z+.5,flags:{onGround:true,hasHorizontalCollision:false}});await wait(700);}
 await until(()=>b.lines.some(s=>s.includes(a.username)&&s.includes('прошёл паркур')),'global parkour winner');
 const renewed=await api('parkour:8081','/parkour/status');if(renewed.seed===course.seed)throw Error('Course not renewed on finish');
 a.chat('/checkpoint');await wait(1000);const position=await api('parkour:8081','/player?uuid='+a._client.uuid);if(Math.abs(position.x-.5)>1)throw Error('Winner checkpoint not reset');console.log('PASS parkour winner global announcement, new seed and reset checkpoint');
 await until(async()=>(await api('controller:8080','/status')).arenas.pillars_ready.state==='WAITING','ready reserve');
 const before=(await api('controller:8080','/status')).arenas.pillars_ready.generation;
 a.chat('/server pillars_2');await until(()=>at(a,'pillars_2'),'promoted reserve',120000);
 const state=await api('controller:8080','/status');if(state.arenas.pillars_2.generation!==before)throw Error('Reserve restarted instead of renamed');
 console.log('PASS ready reserve renamed pillars_2, same generation');await wait(10000);
 a.chat('/server lobby');await until(()=>at(a,'lobby'),'return from pillars');
 await until(async()=>(await api('controller:8080','/status')).arenas.pillars_2.state==='STOPPED','empty arena removed');
 await until(async()=>(await api('controller:8080','/status')).arenas.pillars_ready.state==='WAITING','replacement reserve');console.log('PASS empty arena removed and fresh reserve replenished');
 for(const bot of bots)bot.quit();await wait(1500);
 for(const account of accounts)await rcon('lobby',token,'authme unregister '+account.name);
 fs.unlinkSync('/tests/kiwy-accounts.json');console.log('KIWY_OK');
}
main().then(()=>process.exit(0)).catch(e=>{console.error(e.stack);for(const b of bots)b.quit();process.exit(1)});
