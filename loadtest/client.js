'use strict';
const mineflayer=require('mineflayer'),fs=require('fs');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const opt=Object.fromEntries(process.argv.slice(2).map(s=>{const i=s.indexOf('=');if(i<1)throw Error('Use --key=value');return[s.slice(0,i).replace(/^--/,''),s.slice(i+1)];}));
const host=opt.host;if(!host)throw Error('--host is required');
const accounts=JSON.parse(fs.readFileSync(opt.accounts||'accounts.json','utf8'));
const arenas=Number(opt.arenas||5),duration=Number(opt.duration||1800),gap=Number(opt.gap||3500),total=Number(opt.players||64);
const layout=opt.distribution?opt.distribution.split(',').map(Number):opt['per-arena']?Array(arenas).fill(Number(opt['per-arena'])):Array.from({length:arenas},(_,i)=>Math.floor(total/arenas)+(i<total%arenas?1:0));
const required=layout.reduce((a,b)=>a+b,0);
if(!Number.isInteger(arenas)||arenas<1||arenas>5||layout.length!==arenas||layout.some(n=>!Number.isInteger(n)||n<1||n>16)||required>64||accounts.length<required)throw Error('Invalid arena/player count; network maximum is 64');
const bots=[],errors=[];let closing=false;
function event(type,data={}){process.stdout.write(JSON.stringify({time:new Date().toISOString(),type,...data})+'\n');}
async function until(check,label,ms=240000){const start=Date.now();while(Date.now()-start<ms){if(await check())return;await sleep(200);}throw Error(label+' timeout');}
function stop(){closing=true;for(const bot of bots)bot.quit();}
process.on('SIGINT',()=>{stop();process.exitCode=130;});process.on('SIGTERM',()=>{stop();process.exitCode=143;});
async function connect(account,target){
 const started=Date.now(),bot=mineflayer.createBot({host,port:Number(opt.port||25565),username:account.username,auth:'offline',version:'1.21.10',physicsEnabled:opt.workload==='active'});bots.push(bot);
 const write=bot._client.write.bind(bot._client);
 bot._client.write=(name,data)=>{
  if(['position','position_look','look'].includes(name)&&Object.entries(data).some(([k,v])=>['x','y','z','yaw','pitch'].includes(k)&&!Number.isFinite(v)))return;
  const result=write(name,data);if(['position','position_look','look','flying'].includes(name))write('tick_end',{});return result;
 };
 let authenticated=false,ended=false;
 bot.on('messagestr',text=>{if(text.includes('Successful login')||text.includes("already logged in"))authenticated=true;});
 bot.on('error',e=>{if(!closing){errors.push(account.username+':'+e.message);event('error',{username:account.username,message:e.message});}});
 bot.on('kicked',reason=>{if(!closing){errors.push(account.username+':kicked');event('kicked',{username:account.username,reason});}});
 bot.on('end',()=>{ended=true;if(!closing){errors.push(account.username+':disconnected');event('disconnected',{username:account.username});}});
 await new Promise((resolve,reject)=>{bot.once('login',resolve);bot.once('end',()=>reject(Error('Disconnected before lobby login')));setTimeout(()=>reject(Error('lobby login timeout')),60000).unref();});
 bot.chat('/login '+account.password);await until(()=>authenticated||ended,'AuthMe',30000);if(ended)throw Error('Disconnected during login');
 bot.chat('/server '+target);
 // This pinned map's waiting area is at (997.5,-36,997.5); VM-side collector verifies actual membership.
 await until(()=>ended||(bot.entity&&bot.entity.position.y<0&&bot.entity.position.x>900),'arena join');if(ended)throw Error('Disconnected during arena join');
 event('joined',{username:account.username,target,join_ms:Date.now()-started});return bot;
}
async function main(){
 event('start',{host,arenas,distribution:layout,duration_seconds:duration,workload:opt.workload||'idle'});
 // Fill one arena before the next so cold starts cannot queue five JVMs behind one client's timeout.
 let index=0;for(let arena=1;arena<=arenas;arena++)for(let player=0;player<layout[arena-1];player++){
  if(closing)return;await connect(accounts[index++],opt.route==='auto'?'pillars':'pillars_'+arena);await sleep(gap);
 }
 event('steady',{clients:bots.length});const end=Date.now()+duration*1000;let last=Date.now(),cpu=process.cpuUsage();
 while(!closing&&Date.now()<end){
  if(opt.workload==='active')for(const bot of bots){if(!bot.entity)continue;bot.look(Math.random()*Math.PI*2,0,true);bot.swingArm();}
  await sleep(1000);const now=Date.now(),used=process.cpuUsage(cpu);event('generator',{event_loop_lag_ms:Math.max(0,now-last-1000),rss_bytes:process.memoryUsage().rss,cpu_ms:(used.user+used.system)/1000});last=now;cpu=process.cpuUsage();
 }
 event('finished',{clients:bots.length,unexpected_disconnects:errors.length});stop();await sleep(1000);if(errors.length)process.exitCode=1;
}
main().catch(e=>{event('failed',{message:e.message});process.exitCode=1;stop();});
