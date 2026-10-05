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
  final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  final Set<UUID> authenticated=ConcurrentHashMap.newKeySet(), routing=ConcurrentHashMap.newKeySet(), restoring=ConcurrentHashMap.newKeySet();
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
        if(invocation.arguments().length!=1){message(p,"/server lobby | survival | pillars | pillars_1 … pillars_5");return;}
        route(p,invocation.arguments()[0]);
      }
      public List<String> suggest(Invocation invocation){return List.of("lobby","survival","pillars","pillars_1","pillars_2","pillars_3","pillars_4","pillars_5");}
    });
    http=HttpServer.create(new InetSocketAddress("0.0.0.0",8080),0);
    http.createContext("/",x->{
      if(!Objects.equals(x.getRequestHeaders().getFirst("Authorization"),"Bearer "+token)){reply(x,403,"{}");return;}
      String name=x.getRequestURI().getQuery();String path=x.getRequestURI().getPath();
      if(path.equals("/drain")&&x.getRequestMethod().equals("POST")&&"survival".equals(name)){
        drained.add(name);var lobby=proxy.getServer("lobby").orElseThrow();
        List<CompletableFuture<?>> futures=new ArrayList<>();
        for(Player p:proxy.getAllPlayers())if(p.getCurrentServer().map(s->s.getServerInfo().getName().equals(name)).orElse(false))
          futures.add(p.createConnectionRequest(lobby).connect().thenAccept(r->{if(!r.isSuccessful())p.disconnect(Component.text("Survival перезапускается. Подключитесь снова."));}));
        try{CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(20,TimeUnit.SECONDS);reply(x,200,"{}");}catch(Exception e){reply(x,503,"{}");}
      }else if(path.equals("/undrain")&&x.getRequestMethod().equals("POST")&&"survival".equals(name)){drained.remove(name);reply(x,200,"{}");}
      else if(path.equals("/status"))reply(x,200,gson.toJson(Map.of("players",proxy.getPlayerCount(),"drained",drained)));
      else reply(x,404,"{}");
    });httpExecutor=Executors.newFixedThreadPool(2);http.setExecutor(httpExecutor);http.start();
  }
  void route(Player p,String target){
    if(!Set.of("lobby","survival","pillars","pillars_1","pillars_2","pillars_3","pillars_4","pillars_5").contains(target)){message(p,"Неизвестный сервер.");return;}
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
    if(name.equals("lobby"))return null;
    return EventTask.async(()->{
      if(!auth(p)||drained.contains(name)){event.setResult(ServerPreConnectEvent.ServerResult.denied());message(p,"Вход закрыт: нужна авторизация или сервер на обслуживании.");return;}
      if(name.equals("pillars")){event.setResult(ServerPreConnectEvent.ServerResult.denied());route(p,"pillars");return;}
      if(name.equals("survival"))try{
        var req=HttpRequest.newBuilder(URI.create("http://survival:8081/admit?name="+p.getUsername())).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.noBody()).build();
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
    if(parts[0].equalsIgnoreCase("logout"))authenticated.remove(p.getUniqueId());
    if(parts[0].equalsIgnoreCase("server")){
      event.setResult(CommandExecuteEvent.CommandResult.denied());
      if(parts.length==2)route(p,parts[1]);else message(p,"/server lobby | survival | pillars | pillars_1 … pillars_5");
    }
  }
  @Subscribe public void connected(ServerPostConnectEvent event){
    Player p=event.getPlayer();
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
  @Subscribe public void quit(DisconnectEvent event){authenticated.remove(event.getPlayer().getUniqueId());}
  static void reply(HttpExchange x,int code,String s)throws java.io.IOException{byte[]b=s.getBytes(java.nio.charset.StandardCharsets.UTF_8);x.sendResponseHeaders(code,b.length);x.getResponseBody().write(b);x.close();}
}
