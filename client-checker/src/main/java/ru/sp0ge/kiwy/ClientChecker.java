package ru.sp0ge.kiwy;
import com.google.gson.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.util.Identifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class ClientChecker implements ClientModInitializer {
 private static final Identifier CHANNEL = new Identifier("kiwy", "modcheck");
 @Override public void onInitializeClient() {
  // Hash only origins of mods actually loaded by Fabric, never arbitrary local files.
  var inventory = CompletableFuture.supplyAsync(() -> {
   Set<Path> origins = new HashSet<>();
   for (var mod : FabricLoader.getInstance().getAllMods())
    if (mod.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH)
     origins.addAll(mod.getOrigin().getPaths());
   JsonArray files = new JsonArray();
   for (Path path : origins) {
    if (!Files.isRegularFile(path) || !path.toString().endsWith(".jar")) continue;
    try (var input = Files.newInputStream(path)) {
     MessageDigest digest = MessageDigest.getInstance("SHA-512");
     byte[] buffer = new byte[65536]; int read;
     while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
     files.add(HexFormat.of().formatHex(digest.digest()));
    } catch (Exception ignored) { /* An unreadable origin cannot satisfy the check. */ }
   }
   return files;
  });
  ClientPlayNetworking.registerGlobalReceiver(CHANNEL, (client, handler, buffer, sender) -> {
   if (buffer.readableBytes() > 1024) return;
   byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes);
   final String nonce;
   try {
    var request = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    nonce = request.get("nonce").getAsString();
    if (request.get("protocol").getAsInt() != 1 || !nonce.matches("[a-f0-9-]{36}")) return;
   } catch (RuntimeException invalid) { return; }
   inventory.thenAccept(files -> client.execute(() -> {
    if (client.getNetworkHandler() != handler) return;
    JsonObject report = new JsonObject(); report.addProperty("protocol", 1); report.addProperty("nonce", nonce);
    report.addProperty("minecraft", "1.20.1"); report.add("files", files);
    byte[] data = report.toString().getBytes(StandardCharsets.UTF_8);
    if (data.length <= 32767) {
     var packet = PacketByteBufs.create(); packet.writeBytes(data);
     ClientPlayNetworking.send(CHANNEL, packet);
    }
   }));
  });
 }
}
