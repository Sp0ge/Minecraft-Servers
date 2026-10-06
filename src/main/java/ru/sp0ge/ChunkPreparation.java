package ru.sp0ge;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import com.google.gson.*;
import java.nio.file.*;
import java.util.function.Consumer;

/** Idle-time pre-generation owned by this network, isolated from player commands. */
final class ChunkPreparation implements Listener {
 final Backend p;final Path file=Path.of("/data/plugins/NetworkBackend/chunky-state.json");Object api;Class<?> type;World world;JsonObject state;boolean failed;
 ChunkPreparation(Backend p){this.p=p;p.getServer().getPluginManager().registerEvents(this,p);p.getServer().getScheduler().runTaskTimer(p,this::tick,200,200);}
 Object call(String name)throws Exception{return type.getMethod(name,String.class).invoke(api,world.getName());}
 void initialize()throws Exception{
  var chunky=p.getServer().getPluginManager().getPlugin("Chunky");if(chunky==null||!chunky.isEnabled())throw new IllegalStateException("Chunky is unavailable");
  type=Class.forName("org.popcraft.chunky.api.ChunkyAPI",true,chunky.getClass().getClassLoader());
  api=p.getServer().getServicesManager().load(type);if(api==null||(int)type.getMethod("version").invoke(api)!=0)throw new IllegalStateException("Unsupported Chunky API");
  world=p.getServer().getWorlds().stream().filter(w->w.getEnvironment()==World.Environment.NORMAL).findFirst().orElseThrow();
  state=Files.exists(file)?JsonParser.parseString(Files.readString(file)).getAsJsonObject():new JsonObject();
  if(!state.has("world_uuid")||!state.get("world_uuid").getAsString().equals(world.getUID().toString())){
   // Discard the old season's saved task, never trim or delete generated chunks.
   call("cancelTask");state=new JsonObject();state.addProperty("world_uuid",world.getUID().toString());state.addProperty("started",false);state.addProperty("done",false);save();
  }
  Consumer<Object> complete=event->{try{if(world.getName().equals(event.getClass().getMethod("world").invoke(event)))p.getServer().getScheduler().runTask(p,()->{state.addProperty("done",true);try{save();}catch(Exception e){p.getLogger().warning("Cannot save pre-generation completion");}});}catch(Exception e){p.getLogger().warning("Chunky completion callback failed");}};
  type.getMethod("onGenerationComplete",Consumer.class).invoke(api,complete);
 }
 void save()throws Exception{Files.createDirectories(file.getParent());Path temp=file.resolveSibling("chunky-state.tmp");Files.writeString(temp,p.json.toJson(state));Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
 void tick(){if(failed)return;try{
  if(api==null)initialize();if(state.get("done").getAsBoolean())return;
  boolean running=(boolean)call("isRunning");
  boolean idle=p.getServer().getOnlinePlayers().isEmpty()&&p.accepting&&p.getServer().getTPS()[0]>=18.5&&p.getServer().getAverageTickTime()<40&&file.getParent().toFile().getUsableSpace()>10L*1024*1024*1024;
  if(!idle){if(running)call("pauseTask");return;}if(running)return;
  if(state.get("started").getAsBoolean()&&(boolean)call("continueTask"))return;
  Location spawn=world.getSpawnLocation();double cx=(spawn.getBlockX()>>4)*16,cz=(spawn.getBlockZ()>>4)*16;
  boolean started=(boolean)type.getMethod("startTask",String.class,String.class,double.class,double.class,double.class,double.class,String.class).invoke(api,world.getName(),"square",cx,cz,640d,640d,"concentric");
  if(started){state.addProperty("started",true);save();p.getLogger().info("Chunky: preparing Survival spawn area, radius 640, idle only");}
 }catch(Exception e){failed=true;try{if(api!=null)call("pauseTask");}catch(Exception ignored){}p.getLogger().log(java.util.logging.Level.WARNING,"Automatic chunk preparation disabled",e);}}
 @EventHandler public void join(PlayerJoinEvent e){if(api!=null&&!failed)try{call("pauseTask");}catch(Exception ex){failed=true;p.getLogger().warning("Could not pause Chunky on player join");}}
}
