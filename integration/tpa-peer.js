const mineflayer = require('mineflayer');
const fs = require('fs'); const crypto = require('crypto');
const token = fs.readFileSync('/secrets/api-token','utf8').trim();
const wait = ms => new Promise(r=>setTimeout(r,ms));
async function api(host,path){const r=await fetch('http://'+host+path,{headers:{Authorization:'Bearer '+token},signal:AbortSignal.timeout(5000)});if(!r.ok)throw Error('API '+r.status);return r.json();}
async function until(check,label,timeout=120000){const start=Date.now();while(Date.now()-start<timeout){if(await check())return;await wait(1000);}throw Error('Timeout '+label);}
async function at(bot,host){try{return (await api(host+':8081','/status')).players.includes(bot._client.uuid);}catch{return false;}}
async function main(){
 for(const f of ['/tests/tpa-ready','/tests/tpa-done'])if(fs.existsSync(f))fs.unlinkSync(f);
 await until(async()=>{try{return (await api('lobby:8081','/status')).role==='lobby'}catch{return false}},'lobby readiness');
 const bot=mineflayer.createBot({host:'proxy',port:25565,username:'MCNetTestB',auth:'offline',version:'1.21.10',physicsEnabled:false});
 const write=bot._client.write.bind(bot._client);
 bot._client.write=(name,data)=>{if(['position','position_look','look'].includes(name)&&Object.entries(data).some(([k,v])=>['x','y','z','yaw','pitch'].includes(k)&&!Number.isFinite(v))){console.log('TEST_CLIENT_INVALID_MOVEMENT_SKIPPED');return;}const result=write(name,data);if(['position','position_look','look','flying'].includes(name))write('tick_end',{});return result;};
 bot.on('error',e=>console.log('BOT_ERROR',e.message));bot.on('kicked',r=>console.log('KICKED',JSON.stringify(r)));
 bot.on('messagestr',s=>{console.log('CHAT',s.replace(/\/[a-z]+ [0-9a-f]{24}/g,'[redacted]'));});
 await new Promise((resolve,reject)=>{bot.once('login',resolve);setTimeout(()=>reject(Error('No client login')),60000)});console.log('PASS lobby client login',bot._client.uuid);
 bot.chat('/server survival');await wait(3000);if(await at(bot,'survival'))throw Error('Unauthorized player escaped lobby');console.log('PASS auth gate');
 let password; if(fs.existsSync('/tests/password-b'))password=fs.readFileSync('/tests/password-b','utf8');else{password=crypto.randomBytes(12).toString('hex');fs.writeFileSync('/tests/password-b',password,{mode:0o600});}
 bot.chat('/register '+password+' '+password);await wait(2000);bot.chat('/login '+password);
 await until(async()=> (await api('lobby:8081','/auth?uuid='+bot._client.uuid)).authenticated,'AuthMe registration');console.log('PASS AuthMe');
 bot.chat('/server survival');await until(()=>at(bot,'survival'),'survival');await wait(5000);
 fs.writeFileSync('/tests/tpa-ready',bot._client.uuid);let requests=0;
 bot.on('messagestr',s=>{if(s.includes('MCNetTestA просит телепортироваться')){requests++;bot.chat(requests===1?'/tpdeny':'/tpaccept');}});
 await until(()=>fs.existsSync('/tests/tpa-done'),'TPA completion',180000);bot.quit();console.log('TPA_RESPONDER_OK');

}
main().then(()=>process.exit(0)).catch(e=>{console.error(e.stack);process.exit(1);});
