package ru.sp0ge;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Chunk-aligned 48 x 48 arena; both starting sides have mirrored terrain. */
final class DuelArena {
 static final int SIZE=48, FLOOR=100, TOP=120;
 static int budgetTick=-1,usedColumns=0;
 static int x(int room){return (room%8)*256;}
 static int z(int room){return (room/8)*256;}
 static Location spawn(World w,int room,boolean first){return new Location(w,x(room)+(first?7.5:40.5),FLOOR+1,z(room)+23.5,first?-90:90,0);}
 static boolean contains(Location l,World w,int room){return l!=null&&l.getWorld()==w&&l.getX()>=x(room)+1&&l.getX()<x(room)+47&&l.getZ()>=z(room)+1&&l.getZ()<z(room)+47&&l.getY()>=FLOOR+1&&l.getY()<TOP-1;}
 static void release(Backend p,int room){World w=p.getServer().getWorlds().getFirst();for(int dx=0;dx<3;dx++)for(int dz=0;dz<3;dz++)w.removePluginChunkTicket((x(room)>>4)+dx,(z(room)>>4)+dz,p);}
 static void generate(DuelRules rules,JsonObject record,CompletableFuture<String> response){
  Backend p=rules.plugin;World w=p.getServer().getWorlds().getFirst();int room=record.get("room").getAsInt();String id=record.get("id").getAsString();
  // UUID is freshly generated for every duel, so reused room slots get a new seed.
  UUID match=UUID.fromString(id);long seed=match.getMostSignificantBits()^match.getLeastSignificantBits();Random random=new Random(seed);
  record.addProperty("arena_seed",seed);record.addProperty("arena_size",SIZE);record.addProperty("arena_layout",2);
  Material[][] palette={{Material.GRASS_BLOCK,Material.MOSSY_COBBLESTONE,Material.STONE_BRICKS},{Material.SANDSTONE,Material.CUT_SANDSTONE,Material.SMOOTH_SANDSTONE},{Material.MOSS_BLOCK,Material.COBBLESTONE,Material.MOSSY_STONE_BRICKS},{Material.SMOOTH_STONE,Material.ANDESITE,Material.DEEPSLATE_BRICKS}};
  Material[] theme=palette[random.nextInt(palette.length)];int[][] heights=new int[SIZE][SIZE],cover=new int[SIZE][SIZE];
  double phase=random.nextDouble()*Math.PI*2;
  for(int a=1;a<24;a++)for(int b=1;b<47;b++){
   int h=(int)Math.round(1+Math.sin(a*.18+phase)*Math.cos(b*.16+phase));
   if(b>=20&&b<=27||a<=10)h=0;heights[a][b]=h;heights[47-a][47-b]=h;
  }
  for(int n=0;n<18;n++){
   int a=12+random.nextInt(11),b=3+random.nextInt(42),width=2+random.nextInt(3),depth=2+random.nextInt(3),height=2+random.nextInt(3);
   for(int dx=0;dx<width;dx++)for(int dz=0;dz<depth;dz++){
    int aa=a+dx,bb=b+dz;if(aa>=24||bb>=45||bb>=19&&bb<=28)continue;
    cover[aa][bb]=height;cover[47-aa][47-bb]=height;
   }
  }
  List<CompletableFuture<Chunk>> chunks=new ArrayList<>();for(int dx=0;dx<3;dx++)for(int dz=0;dz<3;dz++)chunks.add(w.getChunkAtAsync((x(room)>>4)+dx,(z(room)>>4)+dz,true));
  CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new)).whenComplete((unused,error)->p.getServer().getScheduler().runTask(p,()->{
   if(rules.matches.get(id)!=record){response.completeExceptionally(new IllegalStateException("Duel cancelled"));return;}
   if(error!=null){response.completeExceptionally(error);rules.finishRecord(record,"Не удалось загрузить поле боя.");return;}
   for(var future:chunks)future.join().addPluginChunkTicket(p);
   for(Entity entity:w.getNearbyEntities(new Location(w,x(room)+24,FLOOR+10,z(room)+24),24,12,24))if(!(entity instanceof Player))entity.remove();
   new BukkitRunnable(){int column=0;
    public void run(){
     if(rules.matches.get(id)!=record){cancel();response.completeExceptionally(new IllegalStateException("Duel cancelled"));return;}
     try{
      // Shared budget limits generation across all simultaneous matches.
      int tick=Bukkit.getCurrentTick();if(tick!=budgetTick){budgetTick=tick;usedColumns=0;}
      int amount=Math.min(64,256-usedColumns);usedColumns+=amount;
      for(int end=Math.min(column+amount,SIZE*SIZE);column<end;column++){
       int a=column/SIZE,b=column%SIZE,wx=x(room)+a,wz=z(room)+b;boolean wall=a==0||a==47||b==0||b==47;
       for(int y=FLOOR;y<=TOP;y++){
        Material material;
        if(y==FLOOR)material=Material.BEDROCK;
        else if(y==TOP||wall&&y>FLOOR+8)material=Material.BARRIER;
        else if(wall)material=theme[2];
        else if(y<=FLOOR+heights[a][b])material=theme[0];
        else if(y<=FLOOR+heights[a][b]+cover[a][b])material=theme[1];
        else material=Material.AIR;
        // Surface is one block above the bedrock base only for raised terrain.
        if(y==FLOOR&&!wall)material=heights[a][b]==0?theme[0]:Material.BEDROCK;
        w.getBlockAt(wx,y,wz).setType(material,false);
       }
      }
      if(column==SIZE*SIZE){cancel();if(!DuelJournal.read(id).get("state").getAsString().equals("PREPARED"))throw new IllegalStateException("Duel cancelled");record.addProperty("state","ARENA");DuelJournal.write(record);response.complete("{}");}
     }catch(Exception e){cancel();response.completeExceptionally(e);rules.finishRecord(record,"Не удалось подготовить поле боя.");}
    }
   }.runTaskTimer(p,1,1);
  }));
 }
}
