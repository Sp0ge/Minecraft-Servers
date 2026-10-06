package ru.sp0ge;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.block.data.type.Light;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.*;
import org.bukkit.persistence.PersistentDataType;
import java.nio.file.*;
import java.util.*;

public final class ParkourRules implements Listener {
 record Step(int x,int y,int z,int radius){}
 record Course(long hour,long seed,List<Step> steps,int layout){}
 final Backend plugin;final Map<UUID,Integer> checkpoints=new HashMap<>();boolean finishing=false;
 World world;Course course;final Path file=Path.of("/data/plugins/NetworkBackend/parkour.json");
 ParkourRules(Backend plugin){this.plugin=plugin;
  plugin.getServer().getScheduler().runTaskTimer(plugin,()->{if(world==null){world=plugin.getServer().getWorlds().getFirst();world.setGameRule(GameRule.DO_MOB_SPAWNING,false);world.setPVP(false);load();}
   if(course.hour()!=System.currentTimeMillis()/3600000)renew();
   for(Player p:world.getPlayers())contact(p,p.getLocation());
  },1,10);
 }
 void load(){
  try{if(Files.exists(file))course=plugin.json.fromJson(Files.readString(file),Course.class);}catch(Exception e){plugin.getLogger().warning("Parkour metadata unreadable; rebuilding");}
  if(course==null||course.layout()!=2||course.hour()!=System.currentTimeMillis()/3600000)renew();else build();
 }
 void renew(){
  finishing=false;
  if(course!=null){for(Player p:world.getPlayers())p.teleport(new Location(world,.5,101,.5));for(Step s:course.steps()){clearLighting(s);platform(s,Material.AIR);}}
  List<Step> points=new ArrayList<>();points.add(new Step(0,100,0,3));
  long seed=new java.security.SecureRandom().nextLong();Random random=new Random(seed);int x=0,y=100,z=0;
  for(int i=1;i<=80;i++){
   int rise=random.nextInt(5)==0?1:random.nextInt(4)==0?-1:0;
   if(y<=96&&rise<0||y>=112&&rise>0)rise=0;
   // Four-block gaps only on level, straight jumps; climbs use shorter gaps.
   int distance=rise>0?3: i<=20?3+random.nextInt(2):4+random.nextInt(2);
   int lateral=distance==5?0:random.nextInt(i<=20?3:5)-(i<=20?1:2);
   if(rise>0)lateral=random.nextInt(3)-1;
   // A solid approach and landing surround each slippery section.
   if(slippery(i)||slippery(i-1)){rise=0;distance=3;lateral=0;}
   x+=distance;z+=lateral;y+=rise;points.add(new Step(x,y,z,i%10==0?1:0));
  }
  course=new Course(System.currentTimeMillis()/3600000,seed,List.copyOf(points),2);checkpoints.clear();build();
  try{Files.createDirectories(file.getParent());Path temp=file.resolveSibling("parkour.tmp");Files.writeString(temp,plugin.json.toJson(course));Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){throw new RuntimeException(e);}
  for(Player p:world.getPlayers()){p.teleport(new Location(world,.5,101,.5));p.sendMessage("§aKiwyClub §8• §fПаркур обновлён! Новая трасса готова.");}
 }
 static boolean slippery(int i){return i>40&&i<80&&i%10==5;}
 void build(){
  world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);world.setTime(18000);
  world.setGameRule(GameRule.DO_WEATHER_CYCLE,false);world.setStorm(false);world.setThundering(false);
  for(int i=0;i<course.steps().size();i++){Step s=course.steps().get(i);platform(s,i==80?Material.EMERALD_BLOCK:i%10==0?Material.LIME_CONCRETE:slippery(i)?Material.PACKED_ICE:i<=20?Material.SMOOTH_QUARTZ:i<=50?Material.CYAN_CONCRETE:Material.PURPLE_CONCRETE);}
  for(Step s:course.steps())lighting(s);
  world.setSpawnLocation(0,101,0);
 }
 void lighting(Step s){
  Light light=(Light)Material.LIGHT.createBlockData();light.setLevel(15);
  // Light blocks have no collision or visible model, so jumps remain unchanged.
  for(int x=-s.radius();x<=s.radius();x++)for(int z=-s.radius();z<=s.radius();z++){
   var block=world.getBlockAt(s.x()+x,s.y()+1,s.z()+z);
   if(block.getType().isAir()||block.getType()==Material.LIGHT)block.setBlockData(light,false);
  }
 }
 void clearLighting(Step s){
  for(int x=-s.radius();x<=s.radius();x++)for(int z=-s.radius();z<=s.radius();z++){
   var block=world.getBlockAt(s.x()+x,s.y()+1,s.z()+z);
   if(block.getType()==Material.LIGHT)block.setType(Material.AIR,false);
  }
 }
 void platform(Step s,Material material){for(int x=-s.radius();x<=s.radius();x++)for(int z=-s.radius();z<=s.radius();z++)world.getBlockAt(s.x()+x,s.y(),s.z()+z).setType(material,false);}
 void back(Player p,boolean restart){if(course==null)return;if(restart)checkpoints.put(p.getUniqueId(),0);Step s=course.steps().get(checkpoints.getOrDefault(p.getUniqueId(),0));p.teleport(new Location(world,s.x()+.5,s.y()+1,s.z()+.5));}
 @EventHandler public void join(PlayerJoinEvent e){e.getPlayer().setGameMode(GameMode.ADVENTURE);e.getPlayer().setFoodLevel(20);e.getPlayer().setSaturation(20);back(e.getPlayer(),true);e.getPlayer().sendMessage("Сложный паркур: длинные и диагональные прыжки, перепады высоты и лёд. Точки каждые 10 прыжков. /checkpoint, /restart. Новая трасса каждый час и после победы.");}
 @EventHandler public void move(PlayerMoveEvent e){
  contact(e.getPlayer(),e.getTo());
 }
 void contact(Player p,Location l){
  if(course==null||finishing)return;if(l.getY()<88){back(p,false);return;}
  for(int i=10;i<course.steps().size();i+=10){Step s=course.steps().get(i);
   if(Math.abs(l.getX()-(s.x()+.5))<=s.radius()+.49&&Math.abs(l.getZ()-(s.z()+.5))<=s.radius()+.49&&Math.abs(l.getY()-(s.y()+1))<.2){
    if(i!=checkpoints.getOrDefault(p.getUniqueId(),0)+10)continue;
    if(i>checkpoints.getOrDefault(p.getUniqueId(),0)){checkpoints.put(p.getUniqueId(),i);p.sendMessage("Контрольная точка "+i/10+" / 8.");}
    if(i==80 && checkpoints.getOrDefault(p.getUniqueId(),0)>=70 && !finishing){
     finishing=true;String winner=p.getName();
     plugin.getServer().getScheduler().runTaskAsynchronously(plugin,()->{
      try{var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://proxy:8080/parkour-winner"))
       .timeout(java.time.Duration.ofSeconds(8)).header("Authorization","Bearer "+plugin.token).POST(java.net.http.HttpRequest.BodyPublishers.ofString(winner)).build();
       java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.discarding());
      }catch(Exception ex){plugin.getLogger().warning("Winner broadcast failed: "+ex.getMessage());}
     });
     plugin.getServer().getScheduler().runTask(plugin,this::renew);
    }break;
   }
  }
 }
 @EventHandler public void hunger(org.bukkit.event.entity.FoodLevelChangeEvent e){if(e.getEntity() instanceof Player p){e.setCancelled(true);p.setFoodLevel(20);p.setSaturation(20);}}
 @EventHandler public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player)e.setCancelled(true);}
 @EventHandler public void place(BlockPlaceEvent e){e.setCancelled(true);}
 @EventHandler public void breakBlock(BlockBreakEvent e){e.setCancelled(true);}
 @EventHandler public void quit(PlayerQuitEvent e){checkpoints.remove(e.getPlayer().getUniqueId());}
}
