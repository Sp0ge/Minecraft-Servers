package ru.sp0ge.kiwy;
import com.google.gson.*;
import java.nio.file.*;
import java.util.function.Consumer;
final class ChunkPreparation {
 final Network n;final Path file=Path.of("kiwy-chunky.json");Object api;Class<?> type;JsonObject state;boolean failed;
 static final String WORLD="minecraft:overworld";
 ChunkPreparation(Network n){this.n=n;}
 Object call(String name)throws Exception{return type.getMethod(name,String.class).invoke(api,WORLD);}
 void initialize()throws Exception{Object chunky=Class.forName("org.popcraft.chunky.ChunkyProvider").getMethod("get").invoke(null);api=chunky.getClass().getMethod("getApi").invoke(chunky);type=Class.forName("org.popcraft.chunky.api.ChunkyAPI");if((int)type.getMethod("version").invoke(api)!=0)throw new IllegalStateException("Unsupported Chunky API");
  state=Files.exists(file)?JsonParser.parseString(Files.readString(file)).getAsJsonObject():new JsonObject();
  if(!state.has("season")||!state.get("season").getAsString().equals(n.season)){call("cancelTask");state=new JsonObject();state.addProperty("season",n.season);state.addProperty("started",false);state.addProperty("done",false);save();}
  Consumer<Object> complete=event->{try{if(WORLD.equals(event.getClass().getMethod("world").invoke(event)))n.server.execute(()->{state.addProperty("done",true);try{save();}catch(Exception e){System.err.println("Cannot save Chunky marker");}});}catch(Exception e){System.err.println("Chunky completion callback failed");}};type.getMethod("onGenerationComplete",Consumer.class).invoke(api,complete);
 }
 void save()throws Exception{Path temp=file.resolveSibling("kiwy-chunky.tmp");Files.writeString(temp,n.json.toJson(state));Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
 void pause(){if(api!=null&&!failed)try{call("pauseTask");}catch(Exception e){failed=true;System.err.println("Could not pause Chunky");}}
 void tick(){if(failed)return;try{if(api==null)initialize();if(state.get("done").getAsBoolean())return;boolean running=(boolean)call("isRunning");double mean=n.ticks.stream().mapToDouble(Double::doubleValue).average().orElse(0);boolean idle=n.server.getPlayerManager().getCurrentPlayerCount()==0&&n.accepting&&n.tps()>=18.5&&mean<40&&file.toAbsolutePath().getParent().toFile().getUsableSpace()>10L*1024*1024*1024;
  if(!idle){if(running)call("pauseTask");return;}if(running)return;if(state.get("started").getAsBoolean()&&(boolean)call("continueTask"))return;
  var pos=n.server.getOverworld().getSpawnPos();boolean started=(boolean)type.getMethod("startTask",String.class,String.class,double.class,double.class,double.class,double.class,String.class).invoke(api,WORLD,"square",(double)((pos.getX()>>4)*16),(double)((pos.getZ()>>4)*16),640d,640d,"concentric");if(started){state.addProperty("started",true);save();}
 }catch(Exception e){failed=true;try{if(api!=null)call("pauseTask");}catch(Exception ignored){}System.err.println("Automatic Chunky preparation disabled: "+e);}}
}
