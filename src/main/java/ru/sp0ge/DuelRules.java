package ru.sp0ge;
import com.google.gson.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class DuelRules implements Listener {
 record Request(UUID sender,long expires){}
 final Backend plugin;final boolean arena;final Map<UUID,Request> requests=new HashMap<>();final Map<UUID,String> locked=new ConcurrentHashMap<>();
 final Set<String> engaged=new HashSet<>();final Map<String,JsonObject> matches=new HashMap<>();final Set<Integer> rooms=new HashSet<>();
 DuelRules(Backend plugin){this.plugin=plugin;arena=plugin.role.equals("pvp");
  if(arena)plugin.getServer().getScheduler().runTask(plugin,()->{
   for(World w:plugin.getServer().getWorlds()){w.setGameRule(GameRule.KEEP_INVENTORY,true);w.setGameRule(GameRule.DO_MOB_SPAWNING,false);}
   for(JsonObject record:DuelJournal.records())if(!record.get("state").getAsString().equals("DONE"))finishRecord(record,"Арена перезапущена; вещи сохранены.");
  });
  plugin.getServer().getScheduler().runTaskTimer(plugin,()->{
   long now=System.currentTimeMillis();requests.entrySet().removeIf(e->e.getValue().expires()<now);
   if(arena)for(JsonObject record:new ArrayList<>(matches.values()))if(now-record.get("created").getAsLong()>600000)finishRecord(record,"Время дуэли истекло.");
  },20,20);
 }
 void proxy(String path,JsonObject body)throws Exception{
  var req=HttpRequest.newBuilder(URI.create("http://proxy:8080"+path)).timeout(Duration.ofSeconds(60)).header("Authorization","Bearer "+plugin.token).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
  if(HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.discarding()).statusCode()!=200)throw new IllegalStateException("Duel routing unavailable");
 }
 public boolean command(Player p,String name,String[] args){
  if(arena){if(name.equals("pvpleave")){finish(p.getUniqueId(),"Игрок сдался.");return true;}return false;}
  if(locked.containsKey(p.getUniqueId())){p.sendMessage("Дуэль уже подготавливается.");return true;}
  if(name.equals("pvp")){
   Player other=args.length==1?plugin.getServer().getPlayerExact(args[0]):null;
   if(other==null||other==p||locked.containsKey(other.getUniqueId())||plugin.rules.pending.contains(p.getUniqueId())||plugin.rules.pending.contains(other.getUniqueId())){p.sendMessage("/pvp <игрок на survival>. Игроки должны быть готовы к бою.");return true;}
   requests.put(other.getUniqueId(),new Request(p.getUniqueId(),System.currentTimeMillis()+60000));other.sendMessage(p.getName()+" вызывает вас на PvP: /pvpaccept или /pvpdeny (60 секунд).");p.sendMessage("Вызов отправлен.");return true;
  }
  if(name.equals("pvpaccept")||name.equals("pvpdeny")){
   Request request=requests.remove(p.getUniqueId());Player other=request==null?null:plugin.getServer().getPlayer(request.sender());
   if(request==null||request.expires()<System.currentTimeMillis()||other==null||locked.containsKey(other.getUniqueId())){p.sendMessage("Нет действующего вызова.");return true;}
   if(name.equals("pvpdeny")){other.sendMessage("Вызов PvP отклонён.");return true;}
   try{
    p.closeInventory();other.closeInventory();String id=UUID.randomUUID().toString();locked.put(p.getUniqueId(),id);locked.put(other.getUniqueId(),id);
    JsonObject record=new JsonObject();record.addProperty("id",id);record.addProperty("state","PREPARED");record.addProperty("created",System.currentTimeMillis());
    JsonArray players=new JsonArray();players.add(DuelJournal.snapshot(other));players.add(DuelJournal.snapshot(p));record.add("players",players);DuelJournal.write(record);
    CompletableFuture.runAsync(()->{try{JsonObject body=new JsonObject();body.addProperty("id",id);proxy("/duel-start",body);}catch(Exception e){
     plugin.getServer().getScheduler().runTask(plugin,()->{try{if(java.nio.file.Files.exists(DuelJournal.path(id))){JsonObject current=DuelJournal.read(id);current.addProperty("state","DONE");DuelJournal.write(current);restoreAll(id);}else{locked.remove(p.getUniqueId());locked.remove(other.getUniqueId());}}catch(Exception failure){plugin.getLogger().severe("Duel recovery failed: "+id);}p.sendMessage("Не удалось начать дуэль; исходные вещи сохранены.");other.sendMessage("Не удалось начать дуэль.");});
    }});
   }catch(Exception e){locked.remove(p.getUniqueId());locked.remove(other.getUniqueId());p.sendMessage("Дуэль не создана. Вещи не изменены.");}return true;
  }return false;
 }
 void create(String id,CompletableFuture<String> response)throws Exception{
  if(!arena)throw new IllegalStateException("Not a PvP backend");JsonObject record=DuelJournal.read(id);
  if(!record.get("state").getAsString().equals("PREPARED"))throw new IllegalStateException("Duel state invalid");
  for(JsonElement e:record.getAsJsonArray("players"))if(locked.containsKey(UUID.fromString(e.getAsJsonObject().get("uuid").getAsString())))throw new IllegalStateException("Player already matched");
  int room=0;while(rooms.contains(room))room++;if(room>=32)throw new IllegalStateException("No duel room");rooms.add(room);record.addProperty("room",room);
  matches.put(id,record);for(JsonElement e:record.getAsJsonArray("players"))locked.put(UUID.fromString(e.getAsJsonObject().get("uuid").getAsString()),id);
  World world=plugin.getServer().getWorlds().getFirst();int cx=(room%8)*256,cz=(room/8)*256;
  List<CompletableFuture<Chunk>> loading=new ArrayList<>();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)loading.add(world.getChunkAtAsync((cx>>4)+x,(cz>>4)+z,true));
  CompletableFuture.allOf(loading.toArray(CompletableFuture[]::new)).whenComplete((unused,error)->plugin.getServer().getScheduler().runTask(plugin,()->{
   try{
    if(error!=null)throw new IllegalStateException(error);if(!DuelJournal.read(id).get("state").getAsString().equals("PREPARED"))throw new IllegalStateException("Duel cancelled");
    for(int x=-16;x<=16;x++)for(int z=-16;z<=16;z++){world.getBlockAt(cx+x,100,cz+z).setType(Material.SMOOTH_STONE,false);if(Math.abs(x)==16||Math.abs(z)==16)for(int y=101;y<=105;y++)world.getBlockAt(cx+x,y,cz+z).setType(Material.BARRIER,false);}
    record.addProperty("state","ARENA");DuelJournal.write(record);response.complete("{}");
   }catch(Exception e){response.completeExceptionally(e);finishRecord(record,"Не удалось подготовить арену.");}
  }));
 }
 void restore(Player p)throws Exception{
  for(JsonObject record:DuelJournal.records()){
   JsonObject original=DuelJournal.member(record,p.getUniqueId());if(original==null||original.get("restored").getAsBoolean())continue;
   locked.put(p.getUniqueId(),record.get("id").getAsString());
   World world=plugin.getServer().getWorld(UUID.fromString(original.get("world").getAsString()));
   if(world==null){original.addProperty("restored",true);DuelJournal.write(record);locked.remove(p.getUniqueId());p.sendMessage("Начался новый сезон Survival; вещи предыдущего сезона не возвращаются.");continue;}
   DuelJournal.inventory(p,original);
   p.setHealth(Math.min(p.getMaxHealth(),original.get("health").getAsDouble()));p.setFoodLevel(original.get("food").getAsInt());p.setSaturation(original.get("saturation").getAsFloat());
   p.teleport(new Location(world,original.get("x").getAsDouble(),original.get("y").getAsDouble(),original.get("z").getAsDouble(),original.get("yaw").getAsFloat(),original.get("pitch").getAsFloat()));
   p.saveData();original.addProperty("restored",true);DuelJournal.write(record);locked.remove(p.getUniqueId());
   if(record.get("state").getAsString().equals("DONE")&&java.util.stream.StreamSupport.stream(record.getAsJsonArray("players").spliterator(),false).allMatch(e->e.getAsJsonObject().get("restored").getAsBoolean()))java.nio.file.Files.deleteIfExists(DuelJournal.path(record.get("id").getAsString()));
  }
 }
 void restoreAll(String id)throws Exception{JsonObject record=DuelJournal.read(id);for(JsonElement e:record.getAsJsonArray("players")){Player p=plugin.getServer().getPlayer(UUID.fromString(e.getAsJsonObject().get("uuid").getAsString()));if(p!=null)restore(p);}}
 void finish(UUID player,String reason){String id=locked.get(player);if(id==null||!matches.containsKey(id))return;
  try{JsonObject record=DuelJournal.read(id);if(engaged.contains(id)&&!record.has("winner")){
   for(JsonElement e:record.getAsJsonArray("players")){JsonObject member=e.getAsJsonObject();record.addProperty(member.get("uuid").getAsString().equals(player.toString())?"loser":"winner",member.get("name").getAsString());}DuelJournal.write(record);
  }finishRecord(record,reason);}catch(Exception e){throw new IllegalStateException("Cannot finish duel",e);}
 }
 void finishRecord(JsonObject record,String reason){
  String id=record.get("id").getAsString();
  try{if(java.nio.file.Files.exists(DuelJournal.path(id)))record=DuelJournal.read(id);else{matches.remove(id);engaged.remove(id);if(record.has("room"))rooms.remove(record.get("room").getAsInt());for(JsonElement e:record.getAsJsonArray("players"))locked.remove(UUID.fromString(e.getAsJsonObject().get("uuid").getAsString()));return;}}catch(Exception e){throw new IllegalStateException(e);}
  try{record.addProperty("state","DONE");DuelJournal.write(record);}catch(Exception e){throw new IllegalStateException("Duel journal write failed",e);}
  matches.remove(id);engaged.remove(id);if(record.has("room"))rooms.remove(record.get("room").getAsInt());
  for(JsonElement e:record.getAsJsonArray("players")){UUID uuid=UUID.fromString(e.getAsJsonObject().get("uuid").getAsString());locked.remove(uuid);Player p=plugin.getServer().getPlayer(uuid);if(p!=null){p.sendMessage(reason+" Возвращаемся в Survival; исходные вещи сохранены.");p.getInventory().clear();}}
  CompletableFuture.runAsync(()->{for(int i=0;i<10;i++)try{JsonObject body=new JsonObject();body.addProperty("id",id);proxy("/duel-return",body);return;}catch(Exception e){try{Thread.sleep(1000);}catch(InterruptedException ignored){return;}}plugin.getLogger().severe("Duel return delayed; journal preserved: "+id);});
 }
 @EventHandler(priority=EventPriority.LOWEST) public void join(PlayerJoinEvent e){Player p=e.getPlayer();try{
  if(!arena){restore(p);return;}String id=locked.get(p.getUniqueId());if(id==null){p.kickPlayer("Нет активной дуэли.");return;}JsonObject record=matches.get(id);if(java.util.stream.StreamSupport.stream(record.getAsJsonArray("players").spliterator(),false).allMatch(v->plugin.getServer().getPlayer(UUID.fromString(v.getAsJsonObject().get("uuid").getAsString()))!=null))engaged.add(id);JsonObject original=DuelJournal.member(record,p.getUniqueId());DuelJournal.inventory(p,original);p.setGameMode(GameMode.SURVIVAL);p.setHealth(p.getMaxHealth());p.setFoodLevel(20);
  int room=record.get("room").getAsInt(),side=record.getAsJsonArray("players").get(0).getAsJsonObject().get("uuid").getAsString().equals(p.getUniqueId().toString())?-8:8;
  p.teleport(new Location(p.getWorld(),(room%8)*256+side+.5,101,(room/8)*256+.5,side<0?-90:90,0));
  for(Player other:plugin.getServer().getOnlinePlayers())if(other!=p&&!Objects.equals(locked.get(other.getUniqueId()),id)){p.hidePlayer(plugin,other);other.hidePlayer(plugin,p);}
 }catch(Exception error){p.kickPlayer("Ошибка восстановления дуэли. Исходные вещи сохранены; обратитесь к администратору.");}}
 @EventHandler public void quit(PlayerQuitEvent e){requests.remove(e.getPlayer().getUniqueId());if(arena)finish(e.getPlayer().getUniqueId(),"Соперник отключился.");}
 @EventHandler(priority=EventPriority.HIGHEST) public void death(PlayerDeathEvent e){if(!arena)return;e.setKeepInventory(true);e.setKeepLevel(true);e.getDrops().clear();e.setDroppedExp(0);UUID id=e.getEntity().getUniqueId();String matchId=locked.get(id);
  if(matchId!=null&&matches.containsKey(matchId))try{JsonObject record=DuelJournal.read(matchId);if(!record.has("winner")){for(JsonElement member:record.getAsJsonArray("players"))if(!member.getAsJsonObject().get("uuid").getAsString().equals(id.toString()))record.addProperty("winner",member.getAsJsonObject().get("name").getAsString());record.addProperty("loser",e.getEntity().getName());DuelJournal.write(record);}}catch(Exception failure){plugin.getLogger().severe("Cannot record PvP result: "+matchId);}
  plugin.getServer().getScheduler().runTaskLater(plugin,()->{if(e.getEntity().isOnline())e.getEntity().spigot().respawn();finish(id,e.getEntity().getName()+" проиграл дуэль.");},2);}
 @EventHandler public void damage(EntityDamageEvent e){if(!arena&&e.getEntity() instanceof Player p&&locked.containsKey(p.getUniqueId()))e.setCancelled(true);}
 @EventHandler public void combat(EntityDamageByEntityEvent e){if(!arena||!(e.getEntity() instanceof Player target))return;Entity attacker=e.getDamager();if(attacker instanceof Projectile projectile&&projectile.getShooter() instanceof Entity shooter)attacker=shooter;
  if(!(attacker instanceof Player source)||!Objects.equals(locked.get(source.getUniqueId()),locked.get(target.getUniqueId())))e.setCancelled(true);
 }
 @EventHandler public void drop(PlayerDropItemEvent e){if(arena||locked.containsKey(e.getPlayer().getUniqueId()))e.setCancelled(true);}
 @EventHandler public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p&&(arena||locked.containsKey(p.getUniqueId())))e.setCancelled(true);}
 @EventHandler public void place(BlockPlaceEvent e){if(arena||locked.containsKey(e.getPlayer().getUniqueId()))e.setCancelled(true);}
 @EventHandler public void breakBlock(BlockBreakEvent e){if(arena||locked.containsKey(e.getPlayer().getUniqueId()))e.setCancelled(true);}
 @EventHandler public void inventory(InventoryClickEvent e){if(!arena&&locked.containsKey(e.getWhoClicked().getUniqueId()))e.setCancelled(true);}
 @EventHandler public void drag(org.bukkit.event.inventory.InventoryDragEvent e){if(!arena&&locked.containsKey(e.getWhoClicked().getUniqueId()))e.setCancelled(true);}
 @EventHandler public void interact(PlayerInteractEvent e){if(!arena&&locked.containsKey(e.getPlayer().getUniqueId()))e.setCancelled(true);}
 @EventHandler public void itemDamage(PlayerItemDamageEvent e){if(!arena&&locked.containsKey(e.getPlayer().getUniqueId()))e.setCancelled(true);}
 @EventHandler public void chat(io.papermc.paper.event.player.AsyncChatEvent e){if(arena)e.viewers().removeIf(v->v instanceof Player p&&!Objects.equals(locked.get(p.getUniqueId()),locked.get(e.getPlayer().getUniqueId())));}
 @EventHandler public void consume(PlayerItemConsumeEvent e){if(!arena&&locked.containsKey(e.getPlayer().getUniqueId()))e.setCancelled(true);}
 @EventHandler public void move(PlayerMoveEvent e){if(!arena&&locked.containsKey(e.getPlayer().getUniqueId()))e.setCancelled(true);}
}
