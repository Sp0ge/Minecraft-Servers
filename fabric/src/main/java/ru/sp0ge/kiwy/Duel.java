package ru.sp0ge.kiwy;
import com.google.gson.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.*;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.minecraft.block.*;
import net.minecraft.world.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
public final class Duel {
 final Network n; final Map<UUID,Request> requests=new HashMap<>();public final Map<UUID,String> locked=new HashMap<>();
 final Map<String,JsonObject> matches=new HashMap<>(); final Set<Integer> rooms=new HashSet<>(); final Set<String> engaged=new HashSet<>();
 record Request(UUID sender,long expires){}
 Duel(Network n){this.n=n;if(n.arena)for(JsonObject r:Journal.records())if(!r.get("state").getAsString().equals("DONE"))finishRecord(r,"Арена перезапущена; вещи сохранены.",null);}
 boolean allowed(UUID uuid){String id=locked.get(uuid);return id!=null&&matches.containsKey(id)&&matches.get(id).get("state").getAsString().equals("ARENA");}
 public boolean sameMatch(ServerPlayerEntity a,ServerPlayerEntity b){return locked.containsKey(a.getUuid())&&Objects.equals(locked.get(a.getUuid()),locked.get(b.getUuid()));}
 void proxy(String path,String id)throws Exception{JsonObject body=new JsonObject();body.addProperty("id",id);var req=HttpRequest.newBuilder(URI.create("http://proxy:8080"+path)).timeout(Duration.ofSeconds(60)).header("Authorization","Bearer "+n.token).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();if(HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.discarding()).statusCode()!=200)throw new IllegalStateException("Proxy routing failed");}
 boolean command(ServerPlayerEntity p,String name,String target){
  if(n.arena){if(name.equals("pvpleave")){finish(p.getUuid(),"Игрок сдался.");return true;}return false;}
  if(!Set.of("pvp","pvpaccept","pvpdeny").contains(name))return false;
  if(n.frozen(p)){Network.say(p,"Дождитесь завершения подготовки.");return true;}
  if(name.equals("pvp")){ServerPlayerEntity other=n.server.getPlayerManager().getPlayer(target==null?"":target);if(other==null||other==p||n.frozen(other)){Network.say(p,"/pvp <игрок на survival>");return true;}requests.put(other.getUuid(),new Request(p.getUuid(),System.currentTimeMillis()+60000));Network.say(other,p.getEntityName()+" вызывает вас на PvP: /pvpaccept или /pvpdeny (60 секунд).");Network.say(p,"Вызов отправлен.");return true;}
  Request r=requests.remove(p.getUuid());ServerPlayerEntity other=r==null?null:n.server.getPlayerManager().getPlayer(r.sender());if(r==null||r.expires()<System.currentTimeMillis()||other==null||n.frozen(other)){Network.say(p,"Нет действующего вызова.");return true;}
  if(name.equals("pvpdeny")){Network.say(other,"Вызов PvP отклонён.");return true;}
  String id=UUID.randomUUID().toString();try{
   p.closeHandledScreen();other.closeHandledScreen();locked.put(p.getUuid(),id);locked.put(other.getUuid(),id);
   JsonObject record=new JsonObject();record.addProperty("id",id);record.addProperty("format","fabric-1.20.1");record.addProperty("state","PREPARED");record.addProperty("created",System.currentTimeMillis());JsonArray players=new JsonArray();players.add(Journal.snapshot(other,n.season));players.add(Journal.snapshot(p,n.season));record.add("players",players);Journal.write(record);
   CompletableFuture.runAsync(()->{try{proxy("/duel-start",id);}catch(Exception e){n.server.execute(()->{try{JsonObject current=Journal.read(id);if(!current.get("state").getAsString().equals("ARENA")){current.addProperty("state","DONE");Journal.write(current);restore(p);restore(other);}}catch(Exception failure){System.err.println("Duel recovery delayed: "+id);}Network.say(p,"Дуэль не началась. Исходные вещи сохранены.");});}});
  }catch(Exception e){locked.remove(p.getUuid());locked.remove(other.getUuid());Network.say(p,"Не удалось сохранить инвентарь; дуэль отменена.");}return true;
 }
 void create(String id)throws Exception{JsonObject record=Journal.read(id);if(!record.get("state").getAsString().equals("PREPARED"))throw new IllegalStateException("Invalid state");int room=0;while(rooms.contains(room))room++;if(room>=32)throw new IllegalStateException("All rooms occupied");for(JsonElement e:record.getAsJsonArray("players"))if(locked.containsKey(UUID.fromString(e.getAsJsonObject().get("uuid").getAsString())))throw new IllegalStateException("Already matched");
  rooms.add(room);record.addProperty("room",room);generate(room,id);record.addProperty("state","ARENA");Journal.write(record);matches.put(id,record);for(JsonElement e:record.getAsJsonArray("players"))locked.put(UUID.fromString(e.getAsJsonObject().get("uuid").getAsString()),id);
 }
 void generate(int room,String id){ServerWorld w=n.server.getOverworld();int ox=(room%8)*256,oz=(room/8)*256;Random random=new Random(UUID.fromString(id).getMostSignificantBits()^UUID.fromString(id).getLeastSignificantBits());BlockState[][] themes={{Blocks.STONE_BRICKS.getDefaultState(),Blocks.MOSSY_STONE_BRICKS.getDefaultState()},{Blocks.SANDSTONE.getDefaultState(),Blocks.CUT_SANDSTONE.getDefaultState()},{Blocks.BLACKSTONE.getDefaultState(),Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState()},{Blocks.PACKED_MUD.getDefaultState(),Blocks.MUD_BRICKS.getDefaultState()}};BlockState[] theme=themes[random.nextInt(themes.length)];int[][] heights=new int[48][48];for(int x=1;x<24;x++)for(int z=1;z<47;z++){int h=random.nextInt(10)==0?2+random.nextInt(3):random.nextInt(3);if(Math.abs(z-23)<=2||Math.abs(x-7)<=2)h=0;heights[x][z]=heights[47-x][z]=h;}
  for(int cx=ox>>4;cx<(ox+48)>>4;cx++)for(int cz=oz>>4;cz<(oz+48)>>4;cz++)w.setChunkForced(cx,cz,true);
  for(int x=0;x<48;x++)for(int z=0;z<48;z++){boolean wall=x==0||x==47||z==0||z==47;for(int y=100;y<=121;y++){BlockState block=y==100?theme[0]:wall?(y<=108?theme[1]:Blocks.BARRIER.getDefaultState()):y==121?Blocks.BARRIER.getDefaultState():y<=100+heights[x][z]?theme[1]:Blocks.AIR.getDefaultState();w.setBlockState(new BlockPos(ox+x,y,oz+z),block,2);}}
 }
 void join(ServerPlayerEntity p)throws Exception{if(!n.arena){restore(p);return;}String id=locked.get(p.getUuid());JsonObject r=id==null?null:matches.get(id);if(r==null)throw new IllegalStateException("No match");Journal.inventory(p,Journal.member(r,p.getUuid()));p.changeGameMode(GameMode.SURVIVAL);p.setHealth(p.getMaxHealth());p.getHungerManager().setFoodLevel(20);p.getHungerManager().setSaturationLevel(20);spawn(p,r);boolean both=true;for(JsonElement e:r.getAsJsonArray("players"))both&=n.server.getPlayerManager().getPlayer(UUID.fromString(e.getAsJsonObject().get("uuid").getAsString()))!=null;if(both)engaged.add(id);Network.say(p,"Поле боя: 3×3 чанка. Вещи после дуэли полностью восстановятся.");}
 void spawn(ServerPlayerEntity p,JsonObject r){int room=r.get("room").getAsInt();boolean first=r.getAsJsonArray("players").get(0).getAsJsonObject().get("uuid").getAsString().equals(p.getUuidAsString());p.teleport(n.server.getOverworld(),(room%8)*256+(first?7.5:40.5),101,(room/8)*256+23.5,first?-90:90,0);}
 void restore(ServerPlayerEntity p)throws Exception{for(JsonObject r:Journal.records()){JsonObject original=Journal.member(r,p.getUuid());if(original==null||original.get("restored").getAsBoolean())continue;String id=r.get("id").getAsString();locked.put(p.getUuid(),id);
   if(!original.get("season").getAsString().equals(n.season)){original.addProperty("restored",true);Journal.write(r);locked.remove(p.getUuid());Network.say(p,"Новый сезон: вещи предыдущего сезона не возвращаются.");continue;}
   ServerWorld world=n.server.getWorld(RegistryKey.of(RegistryKeys.WORLD,new Identifier(original.get("world").getAsString())));if(world==null)throw new IllegalStateException("Missing original dimension");Journal.inventory(p,original);p.teleport(world,original.get("x").getAsDouble(),original.get("y").getAsDouble(),original.get("z").getAsDouble(),original.get("yaw").getAsFloat(),original.get("pitch").getAsFloat());n.server.getPlayerManager().saveAllPlayerData();original.addProperty("restored",true);Journal.write(r);locked.remove(p.getUuid());
   boolean all=true;for(JsonElement e:r.getAsJsonArray("players"))all&=e.getAsJsonObject().get("restored").getAsBoolean();if(all&&r.get("state").getAsString().equals("DONE"))Files.deleteIfExists(Journal.path(id));
  }}
 void finish(UUID loser,String reason){String id=locked.get(loser);if(id==null||!matches.containsKey(id))return;try{finishRecord(Journal.read(id),reason,engaged.contains(id)?loser:null);}catch(Exception e){throw new IllegalStateException(e);}}
 void finishRecord(JsonObject r,String reason,UUID loser){String id=r.get("id").getAsString();try{if(Files.exists(Journal.path(id)))r=Journal.read(id);if(loser!=null&&!r.has("winner"))for(JsonElement e:r.getAsJsonArray("players")){JsonObject member=e.getAsJsonObject();r.addProperty(member.get("uuid").getAsString().equals(loser.toString())?"loser":"winner",member.get("name").getAsString());}r.addProperty("state","DONE");Journal.write(r);}catch(Exception e){throw new IllegalStateException(e);}matches.remove(id);engaged.remove(id);
  if(r.has("room")){int room=r.get("room").getAsInt();rooms.remove(room);for(int cx=(room%8)*16;cx<(room%8)*16+3;cx++)for(int cz=(room/8)*16;cz<(room/8)*16+3;cz++)n.server.getOverworld().setChunkForced(cx,cz,false);}
  String result=r.has("winner")&&r.has("loser")
   ?"§6Победитель PvP: §a"+r.get("winner").getAsString()+"§f. Проигравший: §c"+r.get("loser").getAsString()+"§f. "
   :"PvP завершён без победителя. ";
  for(JsonElement e:r.getAsJsonArray("players")){UUID uuid=UUID.fromString(e.getAsJsonObject().get("uuid").getAsString());locked.remove(uuid);ServerPlayerEntity p=n.server.getPlayerManager().getPlayer(uuid);if(p!=null){p.getInventory().clear();Network.say(p,result+reason+" Возвращаемся в Survival.");}}
  CompletableFuture.runAsync(()->{for(int i=0;i<10;i++)try{proxy("/duel-return",id);return;}catch(Exception e){try{Thread.sleep(1000);}catch(InterruptedException stopped){return;}}System.err.println("Duel return delayed, journal preserved: "+id);});
 }
 void leave(ServerPlayerEntity p){requests.remove(p.getUuid());if(n.arena)finish(p.getUuid(),"Соперник отключился.");}
 void tick(){if(!n.arena)return;for(JsonObject r:new ArrayList<>(matches.values())){if(System.currentTimeMillis()-r.get("created").getAsLong()>600000){finishRecord(r,"Время дуэли истекло.",null);continue;}for(JsonElement e:r.getAsJsonArray("players")){ServerPlayerEntity p=n.server.getPlayerManager().getPlayer(UUID.fromString(e.getAsJsonObject().get("uuid").getAsString()));if(p==null)continue;int room=r.get("room").getAsInt(),ox=(room%8)*256,oz=(room/8)*256;if(p.getWorld()!=n.server.getOverworld()||p.getX()<ox+1||p.getX()>=ox+47||p.getZ()<oz+1||p.getZ()>=oz+47||p.getY()<101||p.getY()>121)spawn(p,r);}}}
}
