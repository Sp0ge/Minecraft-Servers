package ru.sp0ge.kiwy;

import com.google.gson.*;
import java.nio.file.*;
import java.util.function.Consumer;

/** Finite spawn-area preparation with idle grace and rest periods for shared HDDs. */
final class ChunkPreparation {
 private static final String WORLD="minecraft:overworld";
 private static final long GRACE_MS=60_000, RUN_MS=30_000, REST_MS=60_000;
 private static final long MIN_FREE_BYTES=20L*1024*1024*1024;
 private static final double RADIUS=640;
 private final Network n;
 private final Path file=Path.of("kiwy-chunky.json");
 private Object api;
 private Class<?> type;
 private JsonObject state;
 private boolean failed;
 private long nextRunAt=System.currentTimeMillis()+GRACE_MS, runningSince;

 ChunkPreparation(Network n){this.n=n;}
 private Object call(String name)throws Exception{return type.getMethod(name,String.class).invoke(api,WORLD);}
 private void initialize()throws Exception{
  Object chunky=Class.forName("org.popcraft.chunky.ChunkyProvider").getMethod("get").invoke(null);
  api=chunky.getClass().getMethod("getApi").invoke(chunky);
  type=Class.forName("org.popcraft.chunky.api.ChunkyAPI");
  if((int)type.getMethod("version").invoke(api)!=0)throw new IllegalStateException("Unsupported Chunky API");
  state=Files.exists(file)?JsonParser.parseString(Files.readString(file)).getAsJsonObject():new JsonObject();
  if(!state.has("season")||!state.get("season").getAsString().equals(n.season)){
   call("cancelTask");state=new JsonObject();state.addProperty("season",n.season);
   state.addProperty("started",false);state.addProperty("done",false);state.addProperty("radius",RADIUS);save();
  }
  // Older markers treated a paused worker as completed. Keep its saved task, resume it.
  if(!state.has("policy")||state.get("policy").getAsInt()!=2){state.addProperty("policy",2);state.addProperty("done",false);save();}
  // Never auto-resume a persisted task before the idle grace period.
  if((boolean)call("isRunning"))call("pauseTask");
  Consumer<Object> complete=event->{try{
   if(WORLD.equals(event.getClass().getMethod("world").invoke(event))&&(boolean)event.getClass().getMethod("complete").invoke(event)&&((Number)event.getClass().getMethod("progress").invoke(event)).doubleValue()>=100.0)n.server.execute(()->{
    state.addProperty("done",true);try{save();}catch(Exception e){System.err.println("Cannot save Chunky marker: "+e);}
   });
  }catch(Exception e){System.err.println("Chunky completion callback failed: "+e);}};
  type.getMethod("onGenerationProgress",Consumer.class).invoke(api,complete);
 }
 private void save()throws Exception{
  Path temp=file.resolveSibling("kiwy-chunky.tmp");Files.writeString(temp,n.json.toJson(state));
  Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
 }
 void pause(){
  nextRunAt=System.currentTimeMillis()+GRACE_MS;runningSince=0;
  if(api!=null&&!failed)try{call("pauseTask");}catch(Exception e){failed=true;System.err.println("Could not pause Chunky: "+e);}
 }
 void tick(){
  if(failed)return;
  try{
   if(api==null)initialize();
   if(state.get("done").getAsBoolean())return;
   long now=System.currentTimeMillis();boolean running=(boolean)call("isRunning");
   double mean=n.ticks.stream().mapToDouble(Double::doubleValue).average().orElse(0);
   boolean idle=n.server.getPlayerList().getPlayerCount()==0&&n.accepting&&n.tps()>=19.5&&mean<35
    &&file.toAbsolutePath().getParent().toFile().getUsableSpace()>MIN_FREE_BYTES;
   if(!idle){pause();return;}
   if(running){
    if(runningSince==0)runningSince=now;
    if(now-runningSince>=RUN_MS){call("pauseTask");runningSince=0;nextRunAt=now+REST_MS;}
    return;
   }
   if(now<nextRunAt)return;
   if(state.get("started").getAsBoolean()&&(boolean)call("continueTask")){runningSince=now;return;}
   var pos=n.server.overworld().getRespawnData().pos();
   boolean started=(boolean)type.getMethod("startTask",String.class,String.class,double.class,double.class,double.class,double.class,String.class)
    .invoke(api,WORLD,"square",(double)((pos.getX()>>4)*16),(double)((pos.getZ()>>4)*16),RADIUS,RADIUS,"concentric");
   if(started){state.addProperty("started",true);runningSince=now;save();}
  }catch(Exception e){
   failed=true;try{if(api!=null)call("pauseTask");}catch(Exception ignored){}
   System.err.println("Automatic Chunky preparation disabled: "+e);
  }
 }
}
