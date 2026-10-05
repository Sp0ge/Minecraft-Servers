const net=require('net');
module.exports=(host,password,command)=>new Promise((resolve,reject)=>{
 const socket=net.createConnection(25575,host);let buffer=Buffer.alloc(0),authenticated=false;
 const send=(id,type,text)=>{const b=Buffer.alloc(Buffer.byteLength(text)+14);b.writeInt32LE(b.length-4);b.writeInt32LE(id,4);b.writeInt32LE(type,8);b.write(text,12);socket.write(b);};
 socket.setTimeout(10000,()=>socket.destroy(Error('RCON timeout')));
 socket.on('error',reject);socket.on('connect',()=>send(1,3,password));
 socket.on('data',chunk=>{buffer=Buffer.concat([buffer,chunk]);while(buffer.length>=4&&buffer.length>=buffer.readInt32LE(0)+4){const size=buffer.readInt32LE(0)+4,b=buffer.subarray(0,size);buffer=buffer.subarray(size);const id=b.readInt32LE(4),type=b.readInt32LE(8);if(id===-1){socket.destroy();reject(Error('RCON authentication'));return;}if(!authenticated&&type===2){authenticated=true;send(2,2,command);}else if(authenticated&&id===2){socket.end();resolve(b.subarray(12,-2).toString());}}});
});
