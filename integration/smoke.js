const mineflayer = require('mineflayer');
const fs = require('fs'); const crypto = require('crypto');
const token = fs.readFileSync('/secrets/api-token','utf8').trim();
const wait = ms => new Promise(r=>setTimeout(r,ms));
async function api(host,path){const r=await fetch('http://'+host+path,{headers:{Authorization:'Bearer '+token},signal:AbortSignal.timeout(5000)});if(!r.ok)throw Error('API '+r.status);return r.json();}
async function until(check,label,timeout=120000){const start=Date.now();while(Date.now()-start<timeout){if(await check())return;await wait(1000);}throw Error('Timeout '+label);}
async function at(bot,host){try{return (await api(host+':8081','/status')).players.includes(bot._client.uuid);}catch{return false;}}
async function main(){
 await until(async()=>{try{return (await api('lobby:8081','/status')).role==='lobby'}catch{return false}},'lobby readiness');
 const bot=mineflayer.createBot({host:'proxy',port:25565,username:'MCNetTestA',auth:'offline',version:'1.21.10',physicsEnabled:false});
 const write=bot._client.write.bind(bot._client);
 bot._client.write=(name,data)=>{if(['position','position_look','look'].includes(name)&&Object.entries(data).some(([k,v])=>['x','y','z','yaw','pitch'].includes(k)&&!Number.isFinite(v))){console.log('TEST_CLIENT_INVALID_MOVEMENT_SKIPPED');return;}return write(name,data);};
 bot.on('error',e=>console.log('BOT_ERROR',e.message));bot.on('kicked',r=>console.log('KICKED',JSON.stringify(r)));
 bot.on('messagestr',s=>{console.log('CHAT',s.replace(/\/[a-z]+ [0-9a-f]{24}/g,'[redacted]'));});
 await new Promise((resolve,reject)=>{bot.once('login',resolve);setTimeout(()=>reject(Error('No client login')),60000)});console.log('PASS lobby client login',bot._client.uuid);
 bot.chat('/server survival');await wait(3000);if(await at(bot,'survival'))throw Error('Unauthorized player escaped lobby');console.log('PASS auth gate');
 let password; if(fs.existsSync('/tests/password'))password=fs.readFileSync('/tests/password','utf8');else{password=crypto.randomBytes(12).toString('hex');fs.writeFileSync('/tests/password',password,{mode:0o600});}
 bot.chat('/register '+password+' '+password);await wait(2000);bot.chat('/login '+password);
 await until(async()=> (await api('lobby:8081','/auth?uuid='+bot._client.uuid)).authenticated,'AuthMe registration');console.log('PASS AuthMe');
 bot.chat('/server pillars');await until(()=>at(bot,'pillars_1'),'pillars join',180000);console.log('PASS automatic arena startup and join');
 const generation=(await api('controller:8080','/status')).arenas.pillars_1.generation;
 bot.chat('/server survival');await until(()=>at(bot,'survival'),'survival cross-version join');console.log('PASS survival cross-version join');
 await wait(5000);if(!await at(bot,'survival'))throw Error('Survival connection did not persist');console.log('PASS survival stable connection');
 bot.chat('/home');await wait(1000);
 await until(async()=>{const a=(await api('controller:8080','/status')).arenas.pillars_1;return a.state==='WAITING'&&a.generation!==generation;},'empty arena recreated',180000);console.log('PASS arena recreation after last player leaves');
 bot.chat('/server pillars_1');await until(()=>at(bot,'pillars_1'),'explicit arena join');console.log('PASS explicit arena join');
 bot.chat('/server lobby');await until(()=>at(bot,'lobby'),'return lobby');console.log('PASS return lobby');
 bot.quit();await wait(1000);console.log('SMOKE_OK');
}
main().then(()=>process.exit(0)).catch(e=>{console.error(e.stack);process.exit(1);});
