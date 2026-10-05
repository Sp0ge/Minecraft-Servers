package ru.sp0ge;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.*;
import org.bukkit.persistence.PersistentDataType;
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class SurvivalRules implements Listener {
 final Backend plugin;final NamespacedKey settled;final Set<UUID> pending=new HashSet<>();
 record Position(UUID world,double x,double z){}
 volatile Map<UUID,Position> positions=Map.of();
 SurvivalRules(Backend plugin){this.plugin=plugin;settled=new NamespacedKey(plugin,"survival-settled");
  plugin.getServer().getScheduler().runTask(plugin,()->plugin.getServer().getWorlds().forEach(w->w.setGameRule(GameRule.SHOW_DEATH_MESSAGES,true)));
  plugin.getServer().getScheduler().runTaskTimer(plugin,()->{
   Map<UUID,Position> next=new HashMap<>();for(Player p:plugin.getServer().getOnlinePlayers()){
    Location l=p.getLocation();next.put(p.getUniqueId(),new Position(l.getWorld().getUID(),l.getX(),l.getZ()));
   }positions=Map.copyOf(next);
  },1,1);
 }
 @EventHandler(priority=EventPriority.HIGHEST) public void join(PlayerJoinEvent e){
  Player p=e.getPlayer();e.joinMessage(Component.text(p.getName()+" зашёл на Survival.",NamedTextColor.GREEN));
  Byte state=p.getPersistentDataContainer().get(settled,PersistentDataType.BYTE);
  if(state==null){state=(byte)(p.hasPlayedBefore()?1:0);p.getPersistentDataContainer().set(settled,PersistentDataType.BYTE,state);}
  if(state==0)scatter(p);
 }
 @EventHandler(priority=EventPriority.HIGHEST) public void death(PlayerDeathEvent e){
  Component original=e.deathMessage();
  if(original!=null)e.deathMessage(Component.text("☠ ",NamedTextColor.RED).append(original));
 }
 @EventHandler public void respawn(PlayerPostRespawnEvent e){
  if(e.getRespawnReason()==PlayerRespawnEvent.RespawnReason.DEATH&&!e.isBedSpawn()&&!e.isAnchorSpawn())scatter(e.getPlayer());
 }
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void chat(AsyncChatEvent e){
  Map<UUID,Position> snapshot=positions;Position from=snapshot.get(e.getPlayer().getUniqueId());
  e.viewers().removeIf(audience->{
   if(!(audience instanceof Player p))return false;if(p.getUniqueId().equals(e.getPlayer().getUniqueId()))return false;
   Position to=snapshot.get(p.getUniqueId());if(from==null||to==null||!from.world().equals(to.world()))return true;
   double dx=from.x()-to.x(),dz=from.z()-to.z();return dx*dx+dz*dz>128*128;
  });
  e.renderer(io.papermc.paper.chat.ChatRenderer.viewerUnaware((source,name,message)->
    Component.text("[Локальный] ",NamedTextColor.GRAY).append(name).append(Component.text(": ")).append(message)));
 }
 void scatter(Player p){
  if(!pending.add(p.getUniqueId()))return;p.sendMessage("Подготавливаем безопасную точку появления…");
  World world=plugin.getServer().getWorlds().stream().filter(w->w.getEnvironment()==World.Environment.NORMAL).findFirst().orElseThrow();
  find(p,world,0);
 }
 void find(Player p,World world,int attempt){
  if(!p.isOnline()){pending.remove(p.getUniqueId());return;}
  Location origin=world.getSpawnLocation();var random=ThreadLocalRandom.current();
  int cx=(origin.getBlockX()>>4)-32+random.nextInt(64),cz=(origin.getBlockZ()>>4)-32+random.nextInt(64);
  int x=cx*16+random.nextInt(16),z=cz*16+random.nextInt(16);
  world.getChunkAtAsync(cx,cz,true).whenComplete((chunk,error)->{
   if(!plugin.isEnabled())return;
   plugin.getServer().getScheduler().runTask(plugin,()->{
    if(!p.isOnline()){pending.remove(p.getUniqueId());return;}
    if(error!=null){pending.remove(p.getUniqueId());p.sendMessage("Не удалось подготовить точку появления. Обратитесь к администратору.");return;}
    Block ground=world.getHighestBlockAt(x,z);Block feet=ground.getRelative(0,1,0);
    boolean safe=ground.getType().isSolid()&&!ground.getType().name().endsWith("LEAVES")&&!plugin.hazard(ground)
      &&feet.isPassable()&&feet.getRelative(0,1,0).isPassable()&&!plugin.hazard(feet)&&!plugin.hazard(feet.getRelative(0,1,0));
    if(!safe&&attempt<31){find(p,world,attempt+1);return;}
    if(!safe){
      int y=Math.min(world.getMaxHeight()-4,Math.max(world.getSeaLevel()+1,ground.getY()+1));
      world.getBlockAt(x,y,z).setType(Material.COBBLESTONE);world.getBlockAt(x,y+1,z).setType(Material.AIR);world.getBlockAt(x,y+2,z).setType(Material.AIR);
      feet=world.getBlockAt(x,y+1,z);
    }
    p.teleportAsync(feet.getLocation().add(.5,0,.5)).whenComplete((ok,teleportError)->{
     if(!plugin.isEnabled())return;plugin.getServer().getScheduler().runTask(plugin,()->{
      pending.remove(p.getUniqueId());if(Boolean.TRUE.equals(ok))p.getPersistentDataContainer().set(settled,PersistentDataType.BYTE,(byte)1);
     });
    });
   });
  });
 }
 @EventHandler public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player p&&pending.contains(p.getUniqueId()))e.setCancelled(true);}
 @EventHandler public void move(PlayerMoveEvent e){if(pending.contains(e.getPlayer().getUniqueId())&&(e.getFrom().getX()!=e.getTo().getX()||e.getFrom().getZ()!=e.getTo().getZ()))e.setCancelled(true);}
 @EventHandler public void quit(PlayerQuitEvent e){pending.remove(e.getPlayer().getUniqueId());}
}
