package ru.sp0ge;
import com.google.gson.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.nio.file.*;
import java.util.*;
public final class DuelJournal {
 public static final Path ROOT=Path.of("/duels");static final Gson JSON=new Gson();
 static Path path(String id){return ROOT.resolve(UUID.fromString(id)+".json");}
 static JsonObject read(String id)throws Exception{return JsonParser.parseString(Files.readString(path(id))).getAsJsonObject();}
 static void write(JsonObject record)throws Exception{
  Files.createDirectories(ROOT);Path target=path(record.get("id").getAsString()),temp=target.resolveSibling(target.getFileName()+".tmp");
  Files.writeString(temp,JSON.toJson(record));Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
 }
 static JsonArray items(ItemStack[] items){JsonArray a=new JsonArray();for(ItemStack item:items)a.add(item==null?"":Base64.getEncoder().encodeToString(item.serializeAsBytes()));return a;}
 static ItemStack[] items(JsonArray array){ItemStack[] out=new ItemStack[array.size()];for(int i=0;i<out.length;i++){String value=array.get(i).getAsString();out[i]=value.isEmpty()?null:ItemStack.deserializeBytes(Base64.getDecoder().decode(value));}return out;}
 static JsonObject snapshot(Player p){
  JsonObject o=new JsonObject();o.addProperty("uuid",p.getUniqueId().toString());o.addProperty("name",p.getName());o.add("storage",items(p.getInventory().getStorageContents()));o.add("armor",items(p.getInventory().getArmorContents()));o.add("extra",items(p.getInventory().getExtraContents()));
  Location l=p.getLocation();o.addProperty("world",l.getWorld().getUID().toString());o.addProperty("x",l.getX());o.addProperty("y",l.getY());o.addProperty("z",l.getZ());o.addProperty("yaw",l.getYaw());o.addProperty("pitch",l.getPitch());
  o.addProperty("health",p.getHealth());o.addProperty("food",p.getFoodLevel());o.addProperty("saturation",p.getSaturation());o.addProperty("level",p.getLevel());o.addProperty("exp",p.getExp());o.addProperty("total_exp",p.getTotalExperience());o.addProperty("restored",false);return o;
 }
 static void inventory(Player p,JsonObject o){p.getInventory().setStorageContents(items(o.getAsJsonArray("storage")));p.getInventory().setArmorContents(items(o.getAsJsonArray("armor")));p.getInventory().setExtraContents(items(o.getAsJsonArray("extra")));p.setLevel(o.get("level").getAsInt());p.setExp(o.get("exp").getAsFloat());p.setTotalExperience(o.get("total_exp").getAsInt());}
 static JsonObject member(JsonObject record,UUID id){for(JsonElement e:record.getAsJsonArray("players")){JsonObject p=e.getAsJsonObject();if(p.get("uuid").getAsString().equals(id.toString()))return p;}return null;}
 static List<JsonObject> records(){
  List<JsonObject> result=new ArrayList<>();if(!Files.isDirectory(ROOT))return result;
  try(var stream=Files.list(ROOT)){for(Path path:stream.filter(p->p.toString().endsWith(".json")).toList())result.add(JsonParser.parseString(Files.readString(path)).getAsJsonObject());}catch(Exception e){throw new IllegalStateException("Duel journal cannot be read",e);}return result;
 }
}
