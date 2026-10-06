package ru.sp0ge;

import com.google.gson.*;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

final class ModpackGate {
 static final String URL = "https://sp0ge.ru:3000/s/KiwyClubMRPack";
 static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("kiwy", "modcheck");
 final NetworkProxy owner;
 final Map<String,String> required = new LinkedHashMap<>();
 final ConcurrentMap<UUID,Check> sessions = new ConcurrentHashMap<>();
 static final class Check {
  final Player player; final String nonce = UUID.randomUUID().toString();
  final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
  volatile boolean answered, verified;
  Check(Player player) { this.player = player; }
 }
 ModpackGate(NetworkProxy owner) throws Exception {
  this.owner = owner;
  var config = JsonParser.parseString(Files.readString(Path.of("/assets/client-requirements.json"))).getAsJsonObject();
  if (!config.get("minecraft").getAsString().equals("1.20.1")) throw new IllegalStateException("Incompatible modpack manifest");
  for (var entry : config.getAsJsonArray("files")) {
   var file = entry.getAsJsonObject(); String hash = file.get("sha512").getAsString();
   if (!hash.matches("[a-f0-9]{128}")) throw new IllegalStateException("Invalid modpack hash");
   required.put(hash, file.get("name").getAsString());
  }
  if (required.isEmpty()) throw new IllegalStateException("Empty modpack manifest");
  owner.proxy.getChannelRegistrar().register(CHANNEL);
 }
 static void download(Player player, String reason) {
  player.sendMessage(Component.text("KiwyClub • ", ClubStyle.GREEN)
   .append(Component.text(reason, NamedTextColor.YELLOW)));
  player.sendMessage(Component.text("[Скачать сборку KiwyClub]", ClubStyle.GREEN)
   .clickEvent(ClickEvent.openUrl(URL)).hoverEvent(Component.text(URL)));
 }
 boolean verified(Player player) {
  Check check = sessions.get(player.getUniqueId());
  return check != null && check.player == player && check.verified;
 }
 void start(Player player) {
  Check old = sessions.get(player.getUniqueId());
  if (old != null && old.player == player) return;
  Check check = new Check(player); sessions.put(player.getUniqueId(), check);
  player.sendMessage(Component.text("KiwyClub • Проверяем сборку для Survival и PvP…", ClubStyle.GOLD));
  JsonObject request = new JsonObject(); request.addProperty("protocol", 1); request.addProperty("nonce", check.nonce);
  byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
  for (int delay : new int[]{1,3,8}) owner.proxy.getScheduler().buildTask(owner, () -> {
   if (sessions.get(player.getUniqueId()) == check && player.isActive() && !check.answered)
    player.sendPluginMessage(CHANNEL, bytes);
  }).delay(delay, TimeUnit.SECONDS).schedule();
  owner.proxy.getScheduler().buildTask(owner, () -> {
   if (sessions.get(player.getUniqueId()) == check && player.isActive() && !check.answered)
    download(player, "Сборка не подтверждена. Импортируйте актуальный .mrpack в лаунчер и подключитесь снова.");
  }).delay(15, TimeUnit.SECONDS).schedule();
 }
 void receive(PluginMessageEvent event) {
  if (!event.getIdentifier().equals(CHANNEL)) return;
  // Never forward a client report to a backend, or accept a backend as a client.
  event.setResult(PluginMessageEvent.ForwardResult.handled());
  if (!(event.getSource() instanceof Player player)) return;
  Check check = sessions.get(player.getUniqueId());
  if (check == null || check.player != player || check.answered || System.nanoTime() > check.deadline) return;
  byte[] data = event.getData(); if (data.length > 32767) return;
  try {
   JsonObject report = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
   if (report.get("protocol").getAsInt() != 1 || !check.nonce.equals(report.get("nonce").getAsString())) return;
   JsonArray files = report.getAsJsonArray("files"); if (files.size() > 256) return;
   Set<String> loaded = new HashSet<>();
   for (var item : files) {
    String hash = item.getAsString(); if (!hash.matches("[a-f0-9]{128}")) return; loaded.add(hash);
   }
   var missing = required.entrySet().stream().filter(e -> !loaded.contains(e.getKey())).map(Map.Entry::getValue).toList();
   synchronized(check) {
    if (check.answered) return;
    check.verified = report.get("minecraft").getAsString().equals("1.20.1") && missing.isEmpty();
    check.answered = true;
   }
   if (check.verified) player.sendMessage(Component.text("KiwyClub • Сборка проверена. Survival и PvP доступны.", ClubStyle.GREEN));
   else download(player, "Моды отсутствуют или отличаются: " + String.join(", ", missing.stream().limit(5).toList())
    + (missing.size() > 5 ? " и ещё " + (missing.size()-5) : "") + ". Установите актуальную сборку.");
  } catch (RuntimeException invalid) { /* Malformed reports cannot grant admission. */ }
 }
 void quit(Player player) {
  Check check = sessions.get(player.getUniqueId());
  if (check != null && check.player == player) sessions.remove(player.getUniqueId(), check);
 }
}
