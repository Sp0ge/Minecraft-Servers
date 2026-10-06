package ru.sp0ge.kiwy;
import com.google.gson.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.nbt.*;
import net.minecraft.network.packet.s2c.play.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
public final class Journal {
 static final Path ROOT=Path.of("/duels");static final Gson JSON=new Gson();
 static Path path(String id){return ROOT.resolve(UUID.fromString(id)+".json");}
 static JsonObject read(String id)throws Exception{JsonObject r=JsonParser.parseString(Files.readString(path(id))).getAsJsonObject();if(!r.has("format")||!r.get("format").getAsString().equals("fabric-1.20.1"))throw new IllegalStateException("Incompatible duel journal");return r;}
 static void write(JsonObject record)throws Exception{Files.createDirectories(ROOT);Path target=path(record.get("id").getAsString()),temp=target.resolveSibling(target.getFileName()+".tmp");Files.writeString(temp,JSON.toJson(record));Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
 static JsonObject snapshot(ServerPlayerEntity p,String season)throws Exception{
  NbtCompound nbt=p.writeNbt(new NbtCompound());ByteArrayOutputStream bytes=new ByteArrayOutputStream();NbtIo.writeCompressed(nbt,bytes);
  JsonObject o=new JsonObject();o.addProperty("uuid",p.getUuidAsString());o.addProperty("name",p.getEntityName());o.addProperty("nbt",Base64.getEncoder().encodeToString(bytes.toByteArray()));o.addProperty("season",season);o.addProperty("world",p.getServerWorld().getRegistryKey().getValue().toString());o.addProperty("x",p.getX());o.addProperty("y",p.getY());o.addProperty("z",p.getZ());o.addProperty("yaw",p.getYaw());o.addProperty("pitch",p.getPitch());o.addProperty("restored",false);return o;
 }
 static void inventory(ServerPlayerEntity p,JsonObject original)throws Exception{
  NbtCompound nbt=NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(original.get("nbt").getAsString())));
  // Full player NBT includes vanilla inventory and Cardinal Components (Trinkets/Artifacts).
  // Keep the current entity position/world until the caller performs an explicit safe teleport.
  nbt.remove("Pos");nbt.remove("Motion");nbt.remove("Rotation");nbt.remove("Dimension");
  NbtCompound current=p.writeNbt(new NbtCompound());for(String key:List.of("Pos","Motion","Rotation","Dimension"))if(current.contains(key))nbt.put(key,current.get(key));
  p.readNbt(nbt);p.getInventory().markDirty();p.playerScreenHandler.sendContentUpdates();
  p.networkHandler.sendPacket(new ExperienceBarUpdateS2CPacket(p.experienceProgress,p.totalExperience,p.experienceLevel));
  p.networkHandler.sendPacket(new HealthUpdateS2CPacket(p.getHealth(),p.getHungerManager().getFoodLevel(),p.getHungerManager().getSaturationLevel()));
 }
 static JsonObject member(JsonObject record,UUID id){for(JsonElement e:record.getAsJsonArray("players")){JsonObject p=e.getAsJsonObject();if(p.get("uuid").getAsString().equals(id.toString()))return p;}return null;}
 static List<JsonObject> records(){List<JsonObject> out=new ArrayList<>();if(!Files.isDirectory(ROOT))return out;try(var stream=Files.list(ROOT)){for(Path p:stream.filter(p->p.toString().endsWith(".json")).toList())out.add(read(p.getFileName().toString().replace(".json","")));}catch(Exception e){throw new IllegalStateException("Cannot read journals",e);}return out;}
}
