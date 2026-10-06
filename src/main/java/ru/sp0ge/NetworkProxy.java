package ru.sp0ge;
import com.google.inject.Inject;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.*;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.Component;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;

@Plugin(id="networkproxy",name="NetworkProxy",version="1.0.0")
public class NetworkProxy {
  final ProxyServer proxy; final Logger log; final Gson gson=new Gson();
  final SessionSlots slots=new SessionSlots(64);
  final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  final Set<UUID> authenticated=ConcurrentHashMap.newKeySet(), routing=ConcurrentHashMap.newKeySet(), restoring=ConcurrentHashMap.newKeySet();
  final Set<String> announcedDuels=ConcurrentHashMap.newKeySet();
  final Set<UUID> returning=ConcurrentHashMap.newKeySet();
  final Set<String> drained=ConcurrentHashMap.newKeySet(); String token; HttpServer http; ExecutorService httpExecutor;
  @Inject public NetworkProxy(ProxyServer proxy,Logger log){this.proxy=proxy;this.log=log;}
  JsonObject call(String host,String path)throws Exception{
    var req=HttpRequest.newBuilder(URI.create("http://"+host+path)).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+token).build();
    var res=client.send(req,HttpResponse.BodyHandlers.ofString());
    if(res.statusCode()!=200)throw new IllegalStateException("Service returned "+res.statusCode());
    return JsonParser.parseString(res.body()).getAsJsonObject();
  }
  boolean auth(Player p){
    if(restoring.contains(p.getUniqueId()) && authenticated.contains(p.getUniqueId()))return true;
    if(!p.getCurrentServer().map(s->s.getServerInfo().getName().equals("lobby")).orElse(true) && authenticated.contains(p.getUniqueId()))return true;
    authenticated.remove(p.getUniqueId());
    try {if(call("lobby:8081","/auth?uuid="+p.getUniqueId()).get("authenticated").getAsBoolean()){
      authenticated.add(p.getUniqueId());return true;}}catch(Exception e){log.warn("Auth service unavailable: {}",e.toString());}
    return false;
  }
  void message(Player p,String s){p.sendMessage(Component.text(s));}
  @Subscribe public void init(ProxyInitializeEvent event)throws Exception{
    token=Files.readString(Path.of("/secrets/api-token")).trim();
    for(int i=1;i<=5;i++)proxy.registerServer(new ServerInfo("pillars_"+i,InetSocketAddress.createUnresolved("pillars_"+i,25565)));
    proxy.registerServer(new ServerInfo("pillars",InetSocketAddress.createUnresolved("lobby",25565)));
    proxy.getCommandManager().unregister("server");
    proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("server").plugin(this).build(),new SimpleCommand(){
      public void execute(Invocation invocation){if(!(invocation.source() instanceof Player p))return;
        if(invocation.arguments().length!=1){message(p,"/server lobby | survival | parkour | pillars | pillars_1 … pillars_5");return;}
        route(p,invocation.arguments()[0]);
      }
      public List<String> suggest(Invocation invocation){return List.of("lobby","survival","parkour","pillars","pillars_1","pillars_2","pillars_3","pillars_4","pillars_5");}
    });
    proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("help").plugin(this).build(),new SimpleCommand(){public void execute(Invocation i){if(i.source() instanceof Player p)ClubStyle.help(p,i.arguments().length>0?i.arguments()[0]:"main");} public List<String> suggest(Invocation i){return ClubStyle.TOPICS;}});
    http=HttpServer.create(new InetSocketAddress("0.0.0.0",8080),0);
    http.createContext("/",x->{
      if(!Objects.equals(x.getRequestHeaders().getFirst("Authorization"),"Bearer "+token)){reply(x,403,"{}");return;}
      String name=x.getRequestURI().getQuery();String path=x.getRequestURI().getPath();
      if((path.equals("/duel-start")||path.equals("/duel-return"))&&x.getRequestMethod().equals("POST")){
        try{String id=JsonParser.parseString(new String(x.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsString();duel(id,path.equals("/duel-start"));reply(x,200,"{}");}catch(Exception e){log.warn("Duel routing failed: {}",e.toString());reply(x,503,"{}");}
      }else if(path.equals("/drain")&&x.getRequestMethod().equals("POST")&&"survival".equals(name)){
        drained.add(name);var lobby=proxy.getServer("lobby").orElseThrow();
        List<CompletableFuture<?>> futures=new ArrayList<>();
        for(Player p:proxy.getAllPlayers())if(p.getCurrentServer().map(s->s.getServerInfo().getName().equals(name)).orElse(false))
          futures.add(p.createConnectionRequest(lobby).connect().thenAccept(r->{if(!r.isSuccessful())p.disconnect(Component.text("Survival перезапускается. Подключитесь снова."));}));
        try{CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(20,TimeUnit.SECONDS);reply(x,200,"{}");}catch(Exception e){reply(x,503,"{}");}
      }else if(path.equals("/undrain")&&x.getRequestMethod().equals("POST")&&"survival".equals(name)){drained.remove(name);reply(x,200,"{}");}
      else if(path.equals("/parkour-winner")&&x.getRequestMethod().equals("POST")){
        String winner=new String(x.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        if(!winner.matches("[A-Za-z0-9_]{1,16}")){reply(x,400,"{}");return;}
        proxy.sendMessage(Component.text("KiwyClub • ",ClubStyle.GREEN).append(Component.text("Игрок "+winner+" прошёл паркур! Новая трасса готова.",ClubStyle.GOLD)));reply(x,200,"{}");
      }else if(path.equals("/status"))reply(x,200,gson.toJson(Map.of("players",proxy.getPlayerCount(),"drained",drained)));
      else reply(x,404,"{}");
    });httpExecutor=Executors.newFixedThreadPool(2);http.setExecutor(httpExecutor);http.start();
    proxy.getScheduler().buildTask(this,()->proxy.getAllPlayers().forEach(this::tab)).repeat(2,TimeUnit.SECONDS).schedule();
  }
  JsonObject post(String host,String path,JsonObject body)throws Exception{
    var req=HttpRequest.newBuilder(URI.create("http://"+host+path)).timeout(Duration.ofSeconds(40)).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    var res=client.send(req,HttpResponse.BodyHandlers.ofString());if(res.statusCode()!=200)throw new IllegalStateException("Service returned "+res.statusCode());return JsonParser.parseString(res.body()).getAsJsonObject();
  }
  void duel(String id,boolean start)throws Exception{
    UUID.fromString(id);Path path=Path.of("/duels",id+".json");if(!Files.exists(path)){if(!start)return;throw new IllegalStateException("Missing duel");}
    JsonObject record=JsonParser.parseString(Files.readString(path)).getAsJsonObject(),body=new JsonObject();body.addProperty("id",id);
    List<Player> players=new ArrayList<>();for(JsonElement e:record.getAsJsonArray("players"))proxy.getPlayer(UUID.fromString(e.getAsJsonObject().get("uuid").getAsString())).ifPresent(players::add);
    if(start){
      if(players.size()!=2||players.stream().anyMatch(p->!current(p).equals("survival")||!auth(p))||drained.contains("survival"))throw new IllegalStateException("Players unavailable");
      try{post("pvp:8081","/duel/create",body);for(Player p:players)if(!p.createConnectionRequest(proxy.getServer("pvp").orElseThrow()).connect().get(20,TimeUnit.SECONDS).isSuccessful())throw new IllegalStateException("Connection rejected");}
      catch(Exception e){try{post("pvp:8081","/duel/end",body);}catch(Exception ignored){}throw e;}
    }else{
      if(!record.get("state").getAsString().equals("DONE"))throw new IllegalStateException("Duel unfinished");
      if(record.has("winner")&&record.has("loser")&&announcedDuels.add(id))proxy.sendMessage(Component.text("Игрок "+record.get("winner").getAsString()+" выиграл "+record.get("loser").getAsString()+" в PVP",NamedTextColor.GOLD));
      for(Player p:players){if(current(p).equals("survival"))continue;returning.add(p.getUniqueId());try{
        var target=proxy.getServer(drained.contains("survival")?"lobby":"survival").orElseThrow();
        if(!p.createConnectionRequest(target).connect().get(20,TimeUnit.SECONDS).isSuccessful())throw new IllegalStateException("Return rejected");
      }finally{returning.remove(p.getUniqueId());}}
      if(!drained.contains("survival"))post("survival:8081","/duel/restore",body);
    }
  }
  void help(Player p){ClubStyle.help(p,"main");}
  String current(Player p){return p.getCurrentServer().map(s->s.getServerInfo().getName()).orElse("lobby");}
  Set<String> commands(Player p){
    String server=current(p);Set<String> result=new HashSet<>(Set.of("server","help"));
    if(server.equals("lobby"))result.addAll(Set.of("register","login","logout","changepassword"));
    if(server.equals("survival"))result.addAll(Set.of("home","tpa","tpaccept","tpdeny","pvp","pvpaccept","pvpdeny"));
    if(server.equals("parkour"))result.addAll(Set.of("checkpoint","restart"));
    if(server.equals("pvp"))result.add("pvpleave");
    if(server.startsWith("pillars_"))result.add("trigger");
    return result;
  }
  void tab(Player p){
    if(!p.isActive())return;String name=current(p);int local=p.getCurrentServer().map(s->s.getServer().getPlayersConnected().size()).orElse(0);
    p.sendPlayerListHeaderAndFooter(Component.text("\n  ").append(ClubStyle.BRAND).append(Component.text("  \n",NamedTextColor.WHITE))
      .append(Component.text("Survival  •  Pillars  •  Parkour  •  PvP\n",ClubStyle.MUTED))
      .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━\n",ClubStyle.LINE))
      .append(Component.text("Сейчас: ",ClubStyle.MUTED)).append(Component.text(ClubStyle.label(name),ClubStyle.color(name)))
      .append(Component.text("  ·  "+name+"\n",NamedTextColor.DARK_GRAY)),
      Component.text("\nВ сети: ",ClubStyle.MUTED).append(Component.text(proxy.getPlayerCount()+" / 64",ClubStyle.GREEN))
      .append(Component.text("    Здесь: ",ClubStyle.MUTED)).append(Component.text(local,NamedTextColor.WHITE))
      .append(Component.text("\nПинг: ",ClubStyle.MUTED)).append(Component.text(Math.max(0,p.getPing())+" мс",NamedTextColor.WHITE))
      .append(Component.text("\n/server",ClubStyle.GREEN)).append(Component.text(" — режимы   •   ",ClubStyle.MUTED))
      .append(Component.text("/help",ClubStyle.GREEN)).append(Component.text(" — справка\n",ClubStyle.MUTED))
      .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━\n",ClubStyle.LINE)));
    for(var entry:p.getTabList().getEntries())proxy.getPlayer(entry.getProfile().getId()).ifPresent(other->
      entry.setDisplayName(Component.text(other.getUsername(),NamedTextColor.WHITE)
        .append(Component.text("  •  ",ClubStyle.MUTED)).append(Component.text(ClubStyle.label(current(other)),ClubStyle.color(current(other))))));
  }
  @Subscribe(order=PostOrder.LAST) public void login(LoginEvent event){
    if(event.getResult().isAllowed()&&!slots.acquire(event.getPlayer()))event.setResult(ResultedEvent.ComponentResult.denied(Component.text("Сеть заполнена: максимум 64 игрока. Повторите вход позже.")));
  }
  @Subscribe public void ping(ProxyPingEvent event){event.setPing(event.getPing().asBuilder().maximumPlayers(64).onlinePlayers(proxy.getPlayerCount()).build());}
  @Subscribe(order=PostOrder.LAST) public void available(PlayerAvailableCommandsEvent event){
    if(!event.getPlayer().hasPermission("network.admin"))event.getRootNode().getChildren().removeIf(node->!commands(event.getPlayer()).contains(node.getName().toLowerCase(Locale.ROOT)));
  }
  void route(Player p,String target){
    if(!Set.of("lobby","survival","parkour","pillars","pillars_1","pillars_2","pillars_3","pillars_4","pillars_5").contains(target)){message(p,"Неизвестный сервер.");return;}
    if(current(p).equals("pvp")){CompletableFuture.runAsync(()->{try{post("pvp:8081","/duel/end?uuid="+p.getUniqueId(),new JsonObject());}catch(Exception e){message(p,"Не удалось закончить дуэль; повторите /pvpleave.");}});return;}
    if(!routing.add(p.getUniqueId())){message(p,"Подключение уже выполняется.");return;}
    CompletableFuture.runAsync(()->{
      try{
        if(!target.equals("lobby")&&!auth(p)){message(p,"Сначала выполните /register или /login в lobby.");return;}
        if(target.startsWith("pillars")) {
          message(p,"Подбираем арену; запуск новой карты может занять время.");
          for(int i=0;i<90&&p.isActive();i++){
            var r=call("controller:8080","/select?target="+target+"&uuid="+p.getUniqueId());
            String status=r.get("status").getAsString();
            if(status.equals("ready")){String name=r.get("server").getAsString();
              var result=p.createConnectionRequest(proxy.getServer(name).orElseThrow()).connect().get(20,TimeUnit.SECONDS);
              if(!result.isSuccessful()){log.warn("Arena connection rejected: {} {}",result.getStatus(),result.getReasonComponent());message(p,"Не удалось подключиться к арене. Повторите команду.");}return;}
            if(status.equals("full")||status.equals("failed")){message(p,r.get("message").getAsString());return;}
            Thread.sleep(2000);
          }message(p,"Ожидание завершилось. Повторите команду.");
        }else{
          if(drained.contains(target)){message(p,"Сервер на обслуживании.");return;}
          var result=p.createConnectionRequest(proxy.getServer(target).orElseThrow()).connect().get(20,TimeUnit.SECONDS);
          if(!result.isSuccessful()){log.warn("Backend connection rejected: {} {}",result.getStatus(),result.getReasonComponent());message(p,"Сервер пока недоступен.");result.getReasonComponent().ifPresent(p::sendMessage);}
        }
      }catch(Exception e){log.warn("Routing failed: {}",e.toString());message(p,"Сервис недоступен; повторите команду позже.");}
      finally{routing.remove(p.getUniqueId());}
    });
  }
  @Subscribe public EventTask before(ServerPreConnectEvent event){
    Player p=event.getPlayer();String name=event.getOriginalServer().getServerInfo().getName();
    if(current(p).equals("pvp")&&!returning.contains(p.getUniqueId())){event.setResult(ServerPreConnectEvent.ServerResult.denied());return null;}
    if(name.equals("lobby"))return null;
    return EventTask.async(()->{
      if(!auth(p)||drained.contains(name)){event.setResult(ServerPreConnectEvent.ServerResult.denied());message(p,"Вход закрыт: нужна авторизация или сервер на обслуживании.");return;}
      if(name.equals("pvp"))try{if(!call("pvp:8081","/duel/allowed?uuid="+p.getUniqueId()).get("allowed").getAsBoolean())event.setResult(ServerPreConnectEvent.ServerResult.denied());}catch(Exception e){event.setResult(ServerPreConnectEvent.ServerResult.denied());}
      if(name.equals("pillars")){event.setResult(ServerPreConnectEvent.ServerResult.denied());route(p,"pillars");return;}
      if(Set.of("survival","parkour","pvp").contains(name))try{
        var req=HttpRequest.newBuilder(URI.create("http://"+name+":8081/admit?uuid="+p.getUniqueId())).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.noBody()).build();
        if(client.send(req,HttpResponse.BodyHandlers.ofString()).statusCode()!=200){event.setResult(ServerPreConnectEvent.ServerResult.denied());message(p,"Не удалось подтвердить допуск survival.");}
      }catch(Exception e){event.setResult(ServerPreConnectEvent.ServerResult.denied());}
      if(name.startsWith("pillars_"))try{
        var r=call("controller:8080","/select?target="+name+"&uuid="+p.getUniqueId());
        if(!r.get("status").getAsString().equals("ready")){event.setResult(ServerPreConnectEvent.ServerResult.denied());message(p,"Арена пока недоступна.");}
      }catch(Exception e){event.setResult(ServerPreConnectEvent.ServerResult.denied());}
    });
  }
  @Subscribe public void command(CommandExecuteEvent event){
    if(!(event.getCommandSource() instanceof Player p))return;
    String[] parts=event.getCommand().trim().split("\\s+");
    String root=parts[0].toLowerCase(Locale.ROOT);
    if(!p.hasPermission("network.admin")&&!commands(p).contains(root)&&(root.contains(":")||proxy.getCommandManager().hasCommand(root))){
      event.setResult(CommandExecuteEvent.CommandResult.denied());message(p,"Команда недоступна. /server — выбор сервера.");return;
    }
    if(root.equals("help")){event.setResult(CommandExecuteEvent.CommandResult.denied());ClubStyle.help(p,parts.length>1?parts[1]:"main");return;}
    if(parts[0].equalsIgnoreCase("logout"))authenticated.remove(p.getUniqueId());
    if(parts[0].equalsIgnoreCase("server")){
      event.setResult(CommandExecuteEvent.CommandResult.denied());
      if(parts.length==2)route(p,parts[1]);else message(p,"/server lobby | survival | parkour | pillars | pillars_1 … pillars_5");
    }
  }
  @Subscribe public void connected(ServerPostConnectEvent event){
    Player p=event.getPlayer();
    tab(p);
    if(event.getPreviousServer()==null || !p.getCurrentServer().map(s->s.getServerInfo().getName().equals("lobby")).orElse(false) || !authenticated.contains(p.getUniqueId()))return;
    restoring.add(p.getUniqueId());
    CompletableFuture.runAsync(()->{
      try{
        for(int i=0;i<10&&p.isActive();i++)try{
          var req=HttpRequest.newBuilder(URI.create("http://lobby:8081/restore?uuid="+p.getUniqueId())).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.noBody()).build();
          if(client.send(req,HttpResponse.BodyHandlers.ofString()).statusCode()==200)return;
          Thread.sleep(500);
        }catch(Exception ignored){}
      }finally{restoring.remove(p.getUniqueId());}
    });
  }
  @Subscribe public void stop(ProxyShutdownEvent event){if(http!=null)http.stop(0);if(httpExecutor!=null)httpExecutor.shutdownNow();}
  @Subscribe public void quit(DisconnectEvent event){slots.release(event.getPlayer());
    if(proxy.getPlayer(event.getPlayer().getUniqueId()).filter(p->p!=event.getPlayer()).isEmpty())authenticated.remove(event.getPlayer().getUniqueId());
  }
  static void reply(HttpExchange x,int code,String s)throws java.io.IOException{byte[]b=s.getBytes(java.nio.charset.StandardCharsets.UTF_8);x.sendResponseHeaders(code,b.length);x.getResponseBody().write(b);x.close();}
}
