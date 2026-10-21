package ru.sp0ge.kiwy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.item.*;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.*;
import net.minecraft.nbt.*;

import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import net.luckperms.api.LuckPermsProvider;
import com.sun.net.httpserver.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;








import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Network implements ModInitializer {
 public static Network INSTANCE; public MinecraftServer server;
 public final String role=System.getenv().getOrDefault("ROLE","survival"); public final boolean arena=role.equals("pvp");
 public String token,season; public final Gson json=new Gson(); final Random random=new Random();
 public final Set<UUID> pending=new HashSet<>(),admitted=new HashSet<>(),settled=new HashSet<>();
 final Map<UUID,Request> teleports=new HashMap<>(); final Map<UUID,Integer> scatterAttempts=new HashMap<>();
 ChunkPreparation chunks; public Duel duels; HttpServer http; ExecutorService executor; public boolean accepting=true,visited=false;
 volatile String snapshot="{}"; long tickStart; final ArrayDeque<Double> ticks=new ArrayDeque<>(); final ArrayDeque<Long> tickEnds=new ArrayDeque<>(); int counter;
 record Request(UUID sender,long expires){}
 public void onInitialize(){INSTANCE=this;
  ServerLifecycleEvents.SERVER_STARTED.register(s->{server=s;try{
   token=Files.readString(Path.of("/secrets/api-token")).trim();season=JsonParser.parseString(Files.readString(Path.of("season.json"))).getAsJsonObject().get("id").getAsString();
   if(Files.exists(Path.of("kiwy-settled.json")))for(JsonElement e:JsonParser.parseString(Files.readString(Path.of("kiwy-settled.json"))).getAsJsonArray())settled.add(UUID.fromString(e.getAsString()));
   s.overworld().getGameRules().set(GameRules.SHOW_DEATH_MESSAGES,false,s);
   if(arena){for(var w:s.getAllLevels()){w.getGameRules().set(GameRules.SPAWN_MOBS,false,s);w.getGameRules().set(GameRules.KEEP_INVENTORY,true,s);w.getGameRules().set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER,0,s);w.getGameRules().set(GameRules.MOB_GRIEFING,false,s);w.getGameRules().set(GameRules.TNT_EXPLODES,false,s);}}
   configurePermissions();
   duels=new Duel(this);if(!arena&&Boolean.parseBoolean(System.getenv().getOrDefault("CHUNK_PREGEN_ENABLED","true")))chunks=new ChunkPreparation(this);openHttp();
  }catch(Exception e){throw new IllegalStateException("KiwyClub startup failed",e);}});
  ServerLifecycleEvents.SERVER_STOPPING.register(s->{if(http!=null)http.stop(0);if(executor!=null)executor.shutdownNow();saveSettled();});
  ServerTickEvents.START_SERVER_TICK.register(s->tickStart=System.nanoTime());
  ServerTickEvents.END_SERVER_TICK.register(s->{ticks.add((System.nanoTime()-tickStart)/1000000.0);if(ticks.size()>1200)ticks.remove();tickEnds.add(System.nanoTime());if(tickEnds.size()>1200)tickEnds.remove();if(duels==null)return;
   for(UUID id:new ArrayList<>(pending)){ServerPlayer p=s.getPlayerList().getPlayer(id);if(p==null){pending.remove(id);continue;}scatter(p);break;}
   duels.tick();if(counter%200==199&&chunks!=null)chunks.tick();if(++counter%10==0)status();if(counter%200==0)teleports.entrySet().removeIf(e->e.getValue().expires<System.currentTimeMillis());
  });
  ServerPlayConnectionEvents.JOIN.register((h,sender,s)->{ServerPlayer p=h.player;visited=true;if(chunks!=null)chunks.pause();
   if(!admitted.remove(p.getUUID())||!accepting){h.disconnect(Component.literal("Вход доступен через KiwyClub Velocity после авторизации."));return;}
   try{duels.join(p);}catch(Exception e){h.disconnect(Component.literal("Ошибка восстановления вещей. Исходный инвентарь сохранён."));return;}
   if(!arena){
    if(!settled.contains(p.getUUID())){pending.add(p.getUUID());p.sendSystemMessage(Component.literal("§aПодготавливаем безопасную точку появления…"));}
   }
  });
  ServerPlayConnectionEvents.DISCONNECT.register((h,s)->{pending.remove(h.player.getUUID());scatterAttempts.remove(h.player.getUUID());if(duels!=null)duels.leave(h.player);});
  ServerPlayerEvents.AFTER_RESPAWN.register((old,p,alive)->{if(!arena&&!alive&&(p.getRespawnConfig()==null||safelyMissingBed(p)))pending.add(p.getUUID());});
  ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity,source,amount)->{
   if(entity instanceof ServerPlayer p&&(frozen(p)||arena&&source.getEntity() instanceof ServerPlayer attacker&&!duels.sameMatch(p,attacker)))return false;
   return !(source.getEntity() instanceof ServerPlayer attacker&&frozen(attacker));
  });
  ServerLivingEntityEvents.ALLOW_DEATH.register((entity,source,amount)->{if(arena&&entity instanceof ServerPlayer p&&duels!=null){duels.finish(p.getUUID(),"Дуэль завершена.");p.setHealth(p.getMaxHealth());return false;}return true;});
  ServerLivingEntityEvents.AFTER_DEATH.register((entity,source)->{if(!arena&&entity instanceof ServerPlayer p)server.getPlayerList().broadcastSystemMessage(Component.literal("§c☠ ").append(source.getLocalizedDeathMessage(p)),false);});
  ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message,sender,params)->{
   Component formatted=Component.literal(arena?"§7[Дуэль] ":"§7[Локальный] ").append(Component.literal(sender.getPlainTextName()+": §f")).append(message.decoratedContent());
   for(ServerPlayer recipient:server.getPlayerList().getPlayers()){
    boolean visible=arena?duels.sameMatch(sender,recipient):sender.level()==recipient.level()&&Math.pow(sender.getX()-recipient.getX(),2)+Math.pow(sender.getZ()-recipient.getZ(),2)<=128*128;
    if(visible)recipient.sendSystemMessage(formatted,false);
   }server.sendSystemMessage(formatted);return false;
  });
  CommandRegistrationCallback.EVENT.register((dispatcher,access,env)->{
   for(String name:List.of("home","tpa","tpaccept","tpdeny","pvp","pvpaccept","pvpdeny","pvpleave")){
    var node=Commands.literal(name).requires(source->source.getEntity() instanceof ServerPlayer p&&(admin(source)||permitted(p,name))).executes(c->{command(c.getSource().getPlayerOrException(),name,null);return 1;});
    if(name.equals("tpa")||name.equals("pvp"))node.then(Commands.argument("player",StringArgumentType.word()).suggests((ctx,b)->{for(ServerPlayer p:ctx.getSource().getServer().getPlayerList().getPlayers())b.suggest(p.getPlainTextName());return b.buildFuture();}).executes(c->{command(c.getSource().getPlayerOrException(),name,StringArgumentType.getString(c,"player"));return 1;}));dispatcher.register(node);
   }
  });
 }
 boolean safelyMissingBed(ServerPlayer p){if(p.getRespawnConfig()==null)return true;var data=p.getRespawnConfig().respawnData();ServerLevel w=server.getLevel(data.dimension());return w==null||!(w.getBlockState(data.pos()).getBlock() instanceof BedBlock)&&!w.getBlockState(data.pos()).is(Blocks.RESPAWN_ANCHOR);}
 public boolean frozen(ServerPlayer p){return pending.contains(p.getUUID())||(!arena&&duels!=null&&duels.locked.containsKey(p.getUUID()));}
 public Set<String> commands(){Set<String> roots=new HashSet<>(Set.of("help","server","voicechat"));if(arena)roots.add("pvpleave");else roots.addAll(Set.of("home","tpa","tpaccept","tpdeny","pvp","pvpaccept","pvpdeny"));return roots;}
 void command(ServerPlayer p,String name,String otherName){
  if(duels.command(p,name,otherName))return;if(arena)return;if(frozen(p)){say(p,"Подождите завершения подготовки.");return;}
  switch(name){
   case "home"->{if(p.getRespawnConfig()==null||safelyMissingBed(p)){say(p,"Дом не задан или кровать уничтожена: поспите в кровати.");return;}
    var data=p.getRespawnConfig().respawnData();ServerLevel w=server.getLevel(data.dimension());if(!(w.getBlockState(data.pos()).getBlock() instanceof BedBlock)){say(p,"Дом доступен только у кровати.");return;}
    var transition=p.findRespawnPositionAndUseSpawnBlock(false,net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING);if(transition==null){say(p,"Возле кровати нет безопасного места.");return;}p.teleport(transition);}

   case "tpa"->{ServerPlayer other=server.getPlayerList().getPlayerByName(otherName==null?"":otherName);if(other==null||other==p||frozen(other)){say(p,"/tpa <игрок на survival>");return;}teleports.put(other.getUUID(),new Request(p.getUUID(),System.currentTimeMillis()+60000));say(other,p.getPlainTextName()+" просит телепортироваться: /tpaccept или /tpdeny (60 секунд).");say(p,"Запрос отправлен.");}
   case "tpaccept","tpdeny"->{Request r=teleports.remove(p.getUUID());ServerPlayer source=r==null?null:server.getPlayerList().getPlayer(r.sender);if(r==null||source==null||r.expires<System.currentTimeMillis()||frozen(source)){say(p,"Нет активного запроса.");return;}if(name.equals("tpaccept"))Network.teleport(source,p.level(),p.getX(),p.getY(),p.getZ(),p.getYRot(),p.getXRot());else say(source,"Запрос отклонён.");}
  }
 }
 void scatter(ServerPlayer p){ServerLevel w=server.overworld();BlockPos origin=w.getRespawnData().pos();int x=((origin.getX()>>4)-32+random.nextInt(64))*16+random.nextInt(16),z=((origin.getZ()>>4)-32+random.nextInt(64))*16+random.nextInt(16);
  w.getChunk(x>>4,z>>4);int y=w.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);BlockPos feet=new BlockPos(x,y,z);BlockState ground=w.getBlockState(feet.below());
  boolean safe=ground.isSolidRender()&&ground.getFluidState().isEmpty()&&!ground.is(Blocks.MAGMA_BLOCK)&&!ground.is(Blocks.CACTUS)&&w.getBlockState(feet).isAir()&&w.getBlockState(feet.above()).isAir();
  int attempt=scatterAttempts.merge(p.getUUID(),1,Integer::sum);if(!safe&&attempt<32)return;
  if(!safe){y=Math.min(w.getMaxY()-4,Math.max(65,y));feet=new BlockPos(x,y,z);w.setBlock(feet.below(),Blocks.COBBLESTONE.defaultBlockState(),3);w.setBlock(feet,Blocks.AIR.defaultBlockState(),3);w.setBlock(feet.above(),Blocks.AIR.defaultBlockState(),3);}
  Network.teleport(p,w,x+.5,feet.getY(),z+.5,p.getYRot(),p.getXRot());pending.remove(p.getUUID());scatterAttempts.remove(p.getUUID());settled.add(p.getUUID());saveSettled();
 }
 void saveSettled(){try{Path tmp=Path.of("kiwy-settled.json.tmp");Files.writeString(tmp,json.toJson(settled.stream().map(UUID::toString).toList()));Files.move(tmp,Path.of("kiwy-settled.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(Exception e){throw new IllegalStateException(e);}}
 double tps(){return tickEnds.size()<2?20:Math.min(20,(tickEnds.size()-1)*1_000_000_000d/(tickEnds.getLast()-tickEnds.getFirst()));}
 void status(){double[] sorted=ticks.stream().mapToDouble(Double::doubleValue).sorted().toArray();double mean=Arrays.stream(sorted).average().orElse(0);
  var players=server.getPlayerList().getPlayers();snapshot=json.toJson(Map.ofEntries(Map.entry("players",players.stream().map(p->p.getUUID().toString()).toList()),Map.entry("players_names",players.stream().map(ServerPlayer::getPlainTextName).toList()),Map.entry("count",players.size()),Map.entry("visited",visited),Map.entry("accepting",accepting),Map.entry("role",role),Map.entry("seed",server.overworld().getSeed()),Map.entry("generation",season),Map.entry("tps_1m",tps()),Map.entry("mspt_mean",mean),Map.entry("mspt_p95",sorted.length==0?0:sorted[(int)Math.ceil(sorted.length*.95)-1])));}
 void openHttp()throws Exception{http=HttpServer.create(new InetSocketAddress("0.0.0.0",8081),0);executor=Executors.newFixedThreadPool(2);http.setExecutor(executor);http.createContext("/",x->{
  if(!Objects.equals(x.getRequestHeaders().getFirst("Authorization"),"Bearer "+token)){reply(x,403,"{}");return;}
  if(x.getRequestURI().getPath().equals("/status")){reply(x,200,snapshot);return;}
  String body=new String(x.getRequestBody().readNBytes(1048576),StandardCharsets.UTF_8);CompletableFuture<String> f=new CompletableFuture<>();server.execute(()->{try{f.complete(api(x,body));}catch(Exception e){f.completeExceptionally(e);}});
  try{reply(x,200,f.get(30,TimeUnit.SECONDS));}catch(Exception e){reply(x,503,"{}");}
 });http.start();}
 String api(HttpExchange x,String body)throws Exception{String path=x.getRequestURI().getPath();String query=x.getRequestURI().getQuery();UUID uuid=query!=null&&query.startsWith("uuid=")?UUID.fromString(query.substring(5)):null;ServerPlayer p=uuid==null?null:server.getPlayerList().getPlayer(uuid);boolean post=x.getRequestMethod().equals("POST");
  switch(path){
   case "/announce":if(!post)throw new IllegalArgumentException();server.getPlayerList().broadcastSystemMessage(Component.literal(body),false);return "{}";
   case "/admission":if(!post)throw new IllegalArgumentException();accepting=body.equals("open");return "{}";
   case "/admit":if(!post||uuid==null)throw new IllegalArgumentException();admitted.add(uuid);return "{}";
   case "/player":if(p==null)throw new IllegalArgumentException();return json.toJson(Map.of("x",p.getX(),"y",p.getY(),"z",p.getZ(),"world",p.level().dimension().identifier().toString(),"spawning",pending.contains(uuid)));
   case "/spawn-area":BlockPos a=server.overworld().getRespawnData().pos();int sx=((a.getX()>>4)-32)*16,sz=((a.getZ()>>4)-32)*16;return json.toJson(Map.of("min_x",sx,"max_x",sx+1023,"min_z",sz,"max_z",sz+1023));
   case "/inventory":if(arena||p==null)throw new IllegalArgumentException();return Journal.snapshot(p,season).toString();
   case "/duel/allowed":return json.toJson(Map.of("allowed",arena&&duels.allowed(uuid)));
   case "/duel/create":if(!arena||!post)throw new IllegalArgumentException();duels.create(JsonParser.parseString(body).getAsJsonObject().get("id").getAsString());return "{}";
   case "/duel/end":if(!arena||!post)throw new IllegalArgumentException();if(uuid!=null)duels.finish(uuid,"Игрок сдался.");else duels.finishRecord(Journal.read(JsonParser.parseString(body).getAsJsonObject().get("id").getAsString()),"Дуэль отменена.",null);return "{}";
   case "/duel/restore":if(arena||!post)throw new IllegalArgumentException();for(ServerPlayer player:server.getPlayerList().getPlayers())duels.restore(player);return "{}";
   default:throw new IllegalArgumentException("Unknown API path");
  }
 }
 public static void teleport(ServerPlayer p,ServerLevel w,double x,double y,double z,float yaw,float pitch){p.teleportTo(w,x,y,z,Set.of(),yaw,pitch,true);}
 public boolean admin(CommandSourceStack source){
  if(!(source.getEntity() instanceof ServerPlayer p))return true;
  if(Commands.LEVEL_GAMEMASTERS.check(source.permissions()))return true;
  try{var user=LuckPermsProvider.get().getUserManager().getUser(p.getUUID());return user!=null&&user.getCachedData().getPermissionData().checkPermission("kiwy.admin").asBoolean();}catch(IllegalStateException e){return false;}
 }
 public boolean permitted(ServerPlayer p,String name){try{var user=LuckPermsProvider.get().getUserManager().getUser(p.getUUID());return user!=null&&user.getCachedData().getPermissionData().checkPermission("kiwy.command."+name).asBoolean();}catch(IllegalStateException e){return false;}}
 void configurePermissions(){
  // Set only the managed group, preserving all pre-existing administrator groups.
  var lp=LuckPermsProvider.get();var manager=lp.getGroupManager();
  var group=manager.createAndLoadGroup("kiwy-player").join();
  for(String command:commands())group.data().add(net.luckperms.api.node.Node.builder("kiwy.command."+command).value(true).build());
  for(String node:List.of("voicechat.listen","voicechat.speak","voicechat.groups"))group.data().add(net.luckperms.api.node.Node.builder(node).value(true).build());
  for(String node:List.of("luckperms.*","minecraft.command.*","chunky.*","spark.*","kiwy.admin"))group.data().add(net.luckperms.api.node.Node.builder(node).value(false).build());
  manager.saveGroup(group).join();var defaults=manager.getGroup("default");if(defaults!=null){defaults.data().add(net.luckperms.api.node.types.InheritanceNode.builder("kiwy-player").build());manager.saveGroup(defaults).join();}
 }

 static void reply(HttpExchange x,int code,String body)throws java.io.IOException{byte[] b=body.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(code,b.length);x.getResponseBody().write(b);x.close();}
 public static void say(ServerPlayer p,String text){p.sendSystemMessage(Component.literal("§aKiwyClub §8» §f"+text),false);}
}
