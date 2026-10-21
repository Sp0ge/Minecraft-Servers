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



import java.nio.file.*;
import java.io.*;
import java.util.*;
public final class Journal {
 static final Path ROOT=Path.of("/duels");static final Gson JSON=new Gson();
 static Path path(String id){return ROOT.resolve(UUID.fromString(id)+".json");}
 static JsonObject read(String id)throws Exception{JsonObject r=JsonParser.parseString(Files.readString(path(id))).getAsJsonObject();if(!r.has("format")||!r.get("format").getAsString().equals("fabric-26.3"))throw new IllegalStateException("Incompatible duel journal");return r;}
 static void write(JsonObject record)throws Exception{Files.createDirectories(ROOT);Path target=path(record.get("id").getAsString()),temp=target.resolveSibling(target.getFileName()+".tmp");Files.writeString(temp,JSON.toJson(record));Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
 static JsonObject snapshot(ServerPlayer p,String season)throws Exception{
  CompoundTag nbt=save(p);ByteArrayOutputStream bytes=new ByteArrayOutputStream();NbtIo.writeCompressed(nbt,bytes);
  JsonObject o=new JsonObject();o.addProperty("uuid",p.getStringUUID());o.addProperty("name",p.getPlainTextName());o.addProperty("nbt",Base64.getEncoder().encodeToString(bytes.toByteArray()));o.addProperty("season",season);o.addProperty("world",p.level().dimension().identifier().toString());o.addProperty("x",p.getX());o.addProperty("y",p.getY());o.addProperty("z",p.getZ());o.addProperty("yaw",p.getYRot());o.addProperty("pitch",p.getXRot());o.addProperty("restored",false);return o;
 }
 static void inventory(ServerPlayer p,JsonObject original)throws Exception{
  CompoundTag nbt=NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(original.get("nbt").getAsString())),NbtAccounter.unlimitedHeap());
  // Full registry-aware player NBT preserves vanilla items, enchantments and XP.
  // Keep the current entity position/world until the caller performs an explicit safe teleport.
  nbt.remove("Pos");nbt.remove("Motion");nbt.remove("Rotation");nbt.remove("Dimension");
  CompoundTag current=save(p);for(String key:List.of("Pos","Motion","Rotation","Dimension","pos","motion","rotation","dimension"))if(current.contains(key))nbt.put(key,current.get(key));
  p.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,p.registryAccess(),nbt));p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();
  p.connection.send(new ClientboundSetExperiencePacket(p.experienceProgress,p.totalExperience,p.experienceLevel));
  p.connection.send(new ClientboundSetHealthPacket(p.getHealth(),p.getFoodData().getFoodLevel(),p.getFoodData().getSaturationLevel()));
 }
 static CompoundTag save(ServerPlayer p){var out=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,p.registryAccess());p.saveWithoutId(out);return out.buildResult();}
 static JsonObject member(JsonObject record,UUID id){for(JsonElement e:record.getAsJsonArray("players")){JsonObject p=e.getAsJsonObject();if(p.get("uuid").getAsString().equals(id.toString()))return p;}return null;}
 static List<JsonObject> records(){List<JsonObject> out=new ArrayList<>();if(!Files.isDirectory(ROOT))return out;try(var stream=Files.list(ROOT)){for(Path p:stream.filter(p->p.toString().endsWith(".json")).toList())out.add(read(p.getFileName().toString().replace(".json","")));}catch(Exception e){throw new IllegalStateException("Cannot read journals",e);}return out;}
}
