package ru.sp0ge.kiwy;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.math.*;
import net.minecraft.world.*;
import net.minecraft.block.*;
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
   s.getOverworld().getGameRules().get(GameRules.SHOW_DEATH_MESSAGES).set(false,s);
   if(arena){s.getOverworld().getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false,s);s.getOverworld().getGameRules().get(GameRules.KEEP_INVENTORY).set(true,s);s.getOverworld().getGameRules().get(GameRules.DO_FIRE_TICK).set(false,s);s.getOverworld().getGameRules().get(GameRules.DO_MOB_GRIEFING).set(false,s);}
   duels=new Duel(this);if(!arena&&Boolean.parseBoolean(System.getenv().getOrDefault("CHUNK_PREGEN_ENABLED","false")))chunks=new ChunkPreparation(this);openHttp();
  }catch(Exception e){throw new IllegalStateException("KiwyClub startup failed",e);}});
  ServerLifecycleEvents.SERVER_STOPPING.register(s->{if(http!=null)http.stop(0);if(executor!=null)executor.shutdownNow();saveSettled();});
  ServerTickEvents.START_SERVER_TICK.register(s->tickStart=System.nanoTime());
  ServerTickEvents.END_SERVER_TICK.register(s->{ticks.add((System.nanoTime()-tickStart)/1000000.0);if(ticks.size()>1200)ticks.remove();tickEnds.add(System.nanoTime());if(tickEnds.size()>1200)tickEnds.remove();if(duels==null)return;
   for(UUID id:new ArrayList<>(pending)){ServerPlayerEntity p=s.getPlayerManager().getPlayer(id);if(p==null){pending.remove(id);continue;}scatter(p);break;}
   duels.tick();if(counter%200==199&&chunks!=null)chunks.tick();if(++counter%10==0)status();if(counter%200==0)teleports.entrySet().removeIf(e->e.getValue().expires<System.currentTimeMillis());
  });
  ServerPlayConnectionEvents.JOIN.register((h,sender,s)->{ServerPlayerEntity p=h.player;visited=true;if(chunks!=null)chunks.pause();
   if(!admitted.remove(p.getUuid())||!accepting){h.disconnect(Text.literal("Вход доступен через KiwyClub Velocity после авторизации."));return;}
   try{duels.join(p);}catch(Exception e){h.disconnect(Text.literal("Ошибка восстановления вещей. Исходный инвентарь сохранён."));return;}
   if(!arena){
    if(!settled.contains(p.getUuid())){pending.add(p.getUuid());p.sendMessage(Text.literal("§aПодготавливаем безопасную точку появления…"));}
   }
  });
  ServerPlayConnectionEvents.DISCONNECT.register((h,s)->{pending.remove(h.player.getUuid());scatterAttempts.remove(h.player.getUuid());if(duels!=null)duels.leave(h.player);});
  ServerPlayerEvents.AFTER_RESPAWN.register((old,p,alive)->{if(!arena&&!alive&&(p.getSpawnPointPosition()==null||p.getSpawnPointDimension()==null||safelyMissingBed(p)))pending.add(p.getUuid());});
  ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity,source,amount)->!(source.getAttacker() instanceof ServerPlayerEntity attacker&&frozen(attacker)));
  ServerLivingEntityEvents.ALLOW_DEATH.register((entity,source,amount)->{if(arena&&entity instanceof ServerPlayerEntity p&&duels!=null){duels.finish(p.getUuid(),"Дуэль завершена.");p.setHealth(p.getMaxHealth());return false;}return true;});
  ServerLivingEntityEvents.AFTER_DEATH.register((entity,source)->{if(!arena&&entity instanceof ServerPlayerEntity p)server.getPlayerManager().broadcast(Text.literal("§c☠ ").append(source.getDeathMessage(p)),false);});
  ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message,sender,params)->{
   Text formatted=Text.literal(arena?"§7[Дуэль] ":"§7[Локальный] ").append(Text.literal(sender.getEntityName()+": §f")).append(message.getContent());
   for(ServerPlayerEntity recipient:server.getPlayerManager().getPlayerList()){
    boolean visible=arena?duels.sameMatch(sender,recipient):sender.getWorld()==recipient.getWorld()&&Math.pow(sender.getX()-recipient.getX(),2)+Math.pow(sender.getZ()-recipient.getZ(),2)<=128*128;
    if(visible)recipient.sendMessage(formatted,false);
   }server.sendMessage(formatted);return false;
  });
  CommandRegistrationCallback.EVENT.register((dispatcher,access,env)->{
   for(String name:List.of("home","tpa","tpaccept","tpdeny","pvp","pvpaccept","pvpdeny","pvpleave")){
    var node=CommandManager.literal(name).requires(source->source.getEntity() instanceof ServerPlayerEntity).executes(c->{command(c.getSource().getPlayerOrThrow(),name,null);return 1;});
    if(name.equals("tpa")||name.equals("pvp"))node.then(CommandManager.argument("player",StringArgumentType.word()).suggests((ctx,b)->{for(ServerPlayerEntity p:ctx.getSource().getServer().getPlayerManager().getPlayerList())b.suggest(p.getEntityName());return b.buildFuture();}).executes(c->{command(c.getSource().getPlayerOrThrow(),name,StringArgumentType.getString(c,"player"));return 1;}));dispatcher.register(node);
   }
  });
 }
 boolean safelyMissingBed(ServerPlayerEntity p){ServerWorld w=server.getWorld(p.getSpawnPointDimension());return w==null||!(w.getBlockState(p.getSpawnPointPosition()).getBlock() instanceof BedBlock)&&!w.getBlockState(p.getSpawnPointPosition()).isOf(Blocks.RESPAWN_ANCHOR);}
 public boolean frozen(ServerPlayerEntity p){return pending.contains(p.getUuid())||(!arena&&duels!=null&&duels.locked.containsKey(p.getUuid()));}
 public Set<String> commands(){Set<String> roots=new HashSet<>(Set.of("help","server"));if(arena)roots.add("pvpleave");else roots.addAll(Set.of("home","tpa","tpaccept","tpdeny","pvp","pvpaccept","pvpdeny","opac","oclaims","oparties"));return roots;}
 void command(ServerPlayerEntity p,String name,String otherName){
  if(duels.command(p,name,otherName))return;if(arena)return;if(frozen(p)){say(p,"Подождите завершения подготовки.");return;}
  switch(name){
   case "home"->{ServerWorld w=server.getWorld(p.getSpawnPointDimension());BlockPos bed=p.getSpawnPointPosition();if(w==null||bed==null||!(w.getBlockState(bed).getBlock() instanceof BedBlock)){say(p,"Дом не задан или кровать уничтожена: поспите в кровати.");return;}
    var pos=ServerPlayerEntity.findRespawnPosition(w,bed,p.getSpawnAngle(),false,true);if(pos.isEmpty()){say(p,"Возле кровати нет безопасного места.");return;}Vec3d v=pos.get();p.teleport(w,v.x,v.y,v.z,p.getYaw(),p.getPitch());}
   case "tpa"->{ServerPlayerEntity other=server.getPlayerManager().getPlayer(otherName==null?"":otherName);if(other==null||other==p||frozen(other)){say(p,"/tpa <игрок на survival>");return;}teleports.put(other.getUuid(),new Request(p.getUuid(),System.currentTimeMillis()+60000));say(other,p.getEntityName()+" просит телепортироваться: /tpaccept или /tpdeny (60 секунд).");say(p,"Запрос отправлен.");}
   case "tpaccept","tpdeny"->{Request r=teleports.remove(p.getUuid());ServerPlayerEntity source=r==null?null:server.getPlayerManager().getPlayer(r.sender);if(r==null||source==null||r.expires<System.currentTimeMillis()||frozen(source)){say(p,"Нет активного запроса.");return;}if(name.equals("tpaccept"))source.teleport(p.getServerWorld(),p.getX(),p.getY(),p.getZ(),p.getYaw(),p.getPitch());else say(source,"Запрос отклонён.");}
  }
 }
 void scatter(ServerPlayerEntity p){ServerWorld w=server.getOverworld();BlockPos origin=w.getSpawnPos();int x=((origin.getX()>>4)-32+random.nextInt(64))*16+random.nextInt(16),z=((origin.getZ()>>4)-32+random.nextInt(64))*16+random.nextInt(16);
  w.getChunk(x>>4,z>>4);int y=w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,x,z);BlockPos feet=new BlockPos(x,y,z);BlockState ground=w.getBlockState(feet.down());
  boolean safe=ground.isSolidBlock(w,feet.down())&&ground.getFluidState().isEmpty()&&!ground.isOf(Blocks.MAGMA_BLOCK)&&!ground.isOf(Blocks.CACTUS)&&w.getBlockState(feet).isAir()&&w.getBlockState(feet.up()).isAir();
  int attempt=scatterAttempts.merge(p.getUuid(),1,Integer::sum);if(!safe&&attempt<32)return;
  if(!safe){y=Math.min(w.getTopY()-4,Math.max(65,y));feet=new BlockPos(x,y,z);w.setBlockState(feet.down(),Blocks.COBBLESTONE.getDefaultState());w.setBlockState(feet,Blocks.AIR.getDefaultState());w.setBlockState(feet.up(),Blocks.AIR.getDefaultState());}
  p.teleport(w,x+.5,feet.getY(),z+.5,p.getYaw(),p.getPitch());pending.remove(p.getUuid());scatterAttempts.remove(p.getUuid());settled.add(p.getUuid());saveSettled();
 }
 void saveSettled(){try{Path tmp=Path.of("kiwy-settled.json.tmp");Files.writeString(tmp,json.toJson(settled.stream().map(UUID::toString).toList()));Files.move(tmp,Path.of("kiwy-settled.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(Exception e){throw new IllegalStateException(e);}}
 double tps(){return tickEnds.size()<2?20:Math.min(20,(tickEnds.size()-1)*1_000_000_000d/(tickEnds.getLast()-tickEnds.getFirst()));}
 void status(){double[] sorted=ticks.stream().mapToDouble(Double::doubleValue).sorted().toArray();double mean=Arrays.stream(sorted).average().orElse(0);
  var players=server.getPlayerManager().getPlayerList();snapshot=json.toJson(Map.ofEntries(Map.entry("players",players.stream().map(p->p.getUuid().toString()).toList()),Map.entry("players_names",players.stream().map(ServerPlayerEntity::getEntityName).toList()),Map.entry("count",players.size()),Map.entry("visited",visited),Map.entry("accepting",accepting),Map.entry("role",role),Map.entry("seed",server.getOverworld().getSeed()),Map.entry("generation",season),Map.entry("tps_1m",tps()),Map.entry("mspt_mean",mean),Map.entry("mspt_p95",sorted.length==0?0:sorted[(int)Math.ceil(sorted.length*.95)-1])));}
 void openHttp()throws Exception{http=HttpServer.create(new InetSocketAddress("0.0.0.0",8081),0);executor=Executors.newFixedThreadPool(2);http.setExecutor(executor);http.createContext("/",x->{
  if(!Objects.equals(x.getRequestHeaders().getFirst("Authorization"),"Bearer "+token)){reply(x,403,"{}");return;}
  if(x.getRequestURI().getPath().equals("/status")){reply(x,200,snapshot);return;}
  String body=new String(x.getRequestBody().readNBytes(1048576),StandardCharsets.UTF_8);CompletableFuture<String> f=new CompletableFuture<>();server.execute(()->{try{f.complete(api(x,body));}catch(Exception e){f.completeExceptionally(e);}});
  try{reply(x,200,f.get(30,TimeUnit.SECONDS));}catch(Exception e){reply(x,503,"{}");}
 });http.start();}
 String api(HttpExchange x,String body)throws Exception{String path=x.getRequestURI().getPath();String query=x.getRequestURI().getQuery();UUID uuid=query!=null&&query.startsWith("uuid=")?UUID.fromString(query.substring(5)):null;ServerPlayerEntity p=uuid==null?null:server.getPlayerManager().getPlayer(uuid);boolean post=x.getRequestMethod().equals("POST");
  switch(path){
   case "/announce":if(!post)throw new IllegalArgumentException();server.getPlayerManager().broadcast(Text.literal(body),false);return "{}";
   case "/admission":if(!post)throw new IllegalArgumentException();accepting=body.equals("open");return "{}";
   case "/admit":if(!post||uuid==null)throw new IllegalArgumentException();admitted.add(uuid);return "{}";
   case "/player":if(p==null)throw new IllegalArgumentException();return json.toJson(Map.of("x",p.getX(),"y",p.getY(),"z",p.getZ(),"world",p.getServerWorld().getRegistryKey().getValue().toString(),"spawning",pending.contains(uuid)));
   case "/spawn-area":BlockPos a=server.getOverworld().getSpawnPos();int sx=((a.getX()>>4)-32)*16,sz=((a.getZ()>>4)-32)*16;return json.toJson(Map.of("min_x",sx,"max_x",sx+1023,"min_z",sz,"max_z",sz+1023));
   case "/inventory":if(arena||p==null)throw new IllegalArgumentException();return Journal.snapshot(p,season).toString();
   case "/duel/allowed":return json.toJson(Map.of("allowed",arena&&duels.allowed(uuid)));
   case "/duel/create":if(!arena||!post)throw new IllegalArgumentException();duels.create(JsonParser.parseString(body).getAsJsonObject().get("id").getAsString());return "{}";
   case "/duel/end":if(!arena||!post)throw new IllegalArgumentException();if(uuid!=null)duels.finish(uuid,"Игрок сдался.");else duels.finishRecord(Journal.read(JsonParser.parseString(body).getAsJsonObject().get("id").getAsString()),"Дуэль отменена.",null);return "{}";
   case "/duel/restore":if(arena||!post)throw new IllegalArgumentException();for(ServerPlayerEntity player:server.getPlayerManager().getPlayerList())duels.restore(player);return "{}";
   default:throw new IllegalArgumentException("Unknown API path");
  }
 }
 static void reply(HttpExchange x,int code,String body)throws java.io.IOException{byte[] b=body.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(code,b.length);x.getResponseBody().write(b);x.close();}
 public static void say(ServerPlayerEntity p,String text){p.sendMessage(Text.literal("§aKiwyClub §8» §f"+text),false);}
}
