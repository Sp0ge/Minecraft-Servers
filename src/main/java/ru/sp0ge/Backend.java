package ru.sp0ge;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class Backend extends JavaPlugin implements Listener {
  String role=System.getenv().getOrDefault("ROLE","survival"), token;
  final Gson json=new Gson(); HttpServer http; ExecutorService httpExecutor; boolean visited=false, accepting=true;
  volatile String snapshot="{}"; final Map<UUID,Request> requests=new HashMap<>();SurvivalRules rules;ParkourRules parkour;DuelRules duels;
  static final String MENU_TITLE="§a§lKiwyClub §8• §0Режимы";
  ItemStack guidebook; volatile boolean authReady=false;
  record Request(UUID sender,long expires) {}
  public ChunkGenerator getDefaultWorldGenerator(String name,String id) {
    return new ChunkGenerator() {
      public boolean shouldGenerateNoise(){return false;} public boolean shouldGenerateSurface(){return false;}
      public boolean shouldGenerateCaves(){return false;} public boolean shouldGenerateDecorations(){return false;}
      public boolean shouldGenerateMobs(){return false;} public boolean shouldGenerateStructures(){return false;}
      public Location getFixedSpawnLocation(World world,Random random){return new Location(world,0.5,101,0.5);}
    };
  }
  @EventHandler(priority=EventPriority.LOWEST) public void admission(AsyncPlayerPreLoginEvent e){
    if(role.equals("lobby")&&!authReady)e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,"KiwyClub готовится. Повторите вход через несколько секунд.");
  }
  void warmAuthMeRegistration(){
    // AuthMe 6.0.1 lazily resolves this singleton on async registration threads.
    // Resolve it once before admission to avoid its first-registration race.
    try {
      var auth=getServer().getPluginManager().getPlugin("AuthMe");
      if(auth==null||!auth.isEnabled())throw new IllegalStateException("AuthMe is required in lobby");
      var field=auth.getClass().getDeclaredField("injector");field.setAccessible(true);Object injector=field.get(auth);
      var loader=auth.getClass().getClassLoader();
      Class<?> type=Class.forName("fr.xephi.authme.process.register.executors.PasswordRegisterExecutor",true,loader);
      Class.forName("ch.jalu.injector.Injector",true,loader).getMethod("getSingleton",Class.class).invoke(injector,type);
      getLogger().info("AuthMe registration handler prepared before player admission");
    }catch(Exception e){throw new IllegalStateException("AuthMe registration warmup failed",e);}
  }
  public void onEnable() {
    if(role.equals("lobby"))getServer().getScheduler().runTask(this,()->{
      try{warmAuthMeRegistration();authReady=true;}catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"Registration warmup failed",e);getServer().shutdown();}
    });
    try { token=Files.readString(Path.of("/secrets/api-token")).trim(); } catch(Exception e){throw new RuntimeException(e);}
    getServer().getPluginManager().registerEvents(this,this);
    if(role.equals("survival")){rules=new SurvivalRules(this);getServer().getPluginManager().registerEvents(rules,this);}
    if(role.equals("parkour")){parkour=new ParkourRules(this);getServer().getPluginManager().registerEvents(parkour,this);}
    if(role.equals("survival")||role.equals("pvp")){duels=new DuelRules(this);getServer().getPluginManager().registerEvents(duels,this);}
    getServer().getMessenger().registerOutgoingPluginChannel(this,"BungeeCord");
    if(role.equals("lobby")) getServer().getScheduler().runTask(this,()-> {
      World w=getServer().getWorlds().getFirst();
      w.setSpawnLocation(0,101,0); w.setGameRule(GameRule.DO_MOB_SPAWNING,false);
      w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false); w.setTime(6000);
      for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)w.getBlockAt(x,100,z).setType(Material.BARRIER);
    });
    if(role.equals("lobby"))getServer().getScheduler().runTaskTimer(this,()->getServer().getOnlinePlayers().forEach(this::lobbyKit),40,20);
    getServer().getScheduler().runTaskTimer(this,()-> {
      List<String> ids=getServer().getOnlinePlayers().stream().map(p->p.getUniqueId().toString()).toList();
      boolean open=accepting;
      if(role.equals("pillars")) {
        var objective=getServer().getScoreboardManager().getMainScoreboard().getObjective("game");
        open=open && objective!=null && objective.getScore("#game_info").getScore()==0;
        if(objective!=null) objective.getScore("#team").setScore(1);
      }
      long[] ticks=Arrays.stream(getServer().getTickTimes()).filter(t->t>0).sorted().toArray();
      double p95=ticks.length==0?0:ticks[(int)Math.ceil(ticks.length*.95)-1]/1_000_000.0;
      snapshot=json.toJson(Map.ofEntries(Map.entry("players",ids),Map.entry("count",ids.size()),Map.entry("visited",visited),Map.entry("accepting",open),
       Map.entry("players_names",getServer().getOnlinePlayers().stream().map(Player::getName).toList()),
       Map.entry("seed",getServer().getWorlds().getFirst().getSeed()),Map.entry("role",role),Map.entry("generation",System.getenv().getOrDefault("GENERATION","static")),
       Map.entry("tps_1m",Math.min(20,getServer().getTPS()[0])),Map.entry("mspt_mean",getServer().getAverageTickTime()),Map.entry("mspt_p95",p95)));
      requests.entrySet().removeIf(e->e.getValue().expires()<System.currentTimeMillis());
    },1,10);
    try {
      http=HttpServer.create(new InetSocketAddress("0.0.0.0",8081),0);
      http.createContext("/",exchange-> {
        if(!Objects.equals(exchange.getRequestHeaders().getFirst("Authorization"),"Bearer "+token)){reply(exchange,403,"{}");return;}
        String path=exchange.getRequestURI().getPath();
        if(path.equals("/status")){reply(exchange,200,snapshot);return;}
        CompletableFuture<String> response=new CompletableFuture<>();
        String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
        getServer().getScheduler().runTask(this,()-> {
          try {
            if(path.equals("/duel/create")&&role.equals("pvp")&&exchange.getRequestMethod().equals("POST")) {
              duels.create(JsonParser.parseString(body).getAsJsonObject().get("id").getAsString(),response);
            } else if(path.equals("/duel/allowed")&&role.equals("pvp")) {
              UUID id=UUID.fromString(exchange.getRequestURI().getQuery().replace("uuid=",""));String match=duels.locked.get(id);
              response.complete(json.toJson(Map.of("allowed",match!=null&&duels.matches.containsKey(match)&&duels.matches.get(match).get("state").getAsString().equals("ARENA"))));
            } else if(path.equals("/duel/end")&&role.equals("pvp")&&exchange.getRequestMethod().equals("POST")) {
              if(exchange.getRequestURI().getQuery()!=null)duels.finish(UUID.fromString(exchange.getRequestURI().getQuery().replace("uuid=","")),"Дуэль завершена.");
              else {String id=JsonParser.parseString(body).getAsJsonObject().get("id").getAsString();if(duels.matches.containsKey(id))duels.finishRecord(duels.matches.get(id),"Дуэль отменена.");}
              response.complete("{}");
            } else if(path.equals("/duel/restore")&&role.equals("survival")&&exchange.getRequestMethod().equals("POST")) {
              String id=JsonParser.parseString(body).getAsJsonObject().get("id").getAsString();if(Files.exists(DuelJournal.path(id)))duels.restoreAll(id);response.complete("{}");
            } else if(path.equals("/inventory")&&role.equals("survival")) {
              Player p=getServer().getPlayer(UUID.fromString(exchange.getRequestURI().getQuery().replace("uuid=","")));if(p==null)throw new IllegalStateException("Player offline");response.complete(json.toJson(DuelJournal.snapshot(p)));
            } else if(path.equals("/parkour/status")&&role.equals("parkour")) {
              response.complete(json.toJson(parkour.course));
            } else if(path.equals("/parkour/regenerate")&&role.equals("parkour")&&exchange.getRequestMethod().equals("POST")) {
              parkour.renew();response.complete(json.toJson(parkour.course));
            } else if(path.equals("/admit") && exchange.getRequestMethod().equals("POST") && Set.of("survival","parkour","pvp").contains(role)) {
              String name=java.net.URLDecoder.decode(exchange.getRequestURI().getQuery().replace("name=",""),StandardCharsets.UTF_8);
              if(!name.matches("[a-zA-Z0-9_]{3,16}"))throw new IllegalArgumentException("Invalid player name");
              getServer().getOfflinePlayer(name).setWhitelisted(true);response.complete("{}");
            } else if(path.equals("/player")) {
              UUID id=UUID.fromString(exchange.getRequestURI().getQuery().replace("uuid=",""));Player p=getServer().getPlayer(id);
              if(p==null)throw new IllegalStateException("Player not joined");Location l=p.getLocation();
              response.complete(json.toJson(Map.of("x",l.getX(),"y",l.getY(),"z",l.getZ(),"world",l.getWorld().getName(),"spawning",rules!=null&&rules.pending.contains(id))));
            } else if(path.equals("/spawn-area")&&role.equals("survival")) {
              World w=getServer().getWorlds().stream().filter(v->v.getEnvironment()==World.Environment.NORMAL).findFirst().orElseThrow();Location l=w.getSpawnLocation();
              int x=((l.getBlockX()>>4)-32)*16,z=((l.getBlockZ()>>4)-32)*16;
              response.complete(json.toJson(Map.of("min_x",x,"max_x",x+1023,"min_z",z,"max_z",z+1023)));
            } else if(path.equals("/auth")) {
              UUID id=UUID.fromString(exchange.getRequestURI().getQuery().replace("uuid=",""));
              Player p=getServer().getPlayer(id); boolean authenticated=false;
              if(p!=null && role.equals("lobby") && getServer().getPluginManager().isPluginEnabled("AuthMe")) {
                Class<?> api=Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
                Object instance=api.getMethod("getInstance").invoke(null);
                authenticated=(boolean)api.getMethod("isAuthenticated",Player.class).invoke(instance,p);
              }
              response.complete(json.toJson(Map.of("authenticated",authenticated)));
             } else if(path.equals("/registered") && role.equals("lobby")) {
              String name=java.net.URLDecoder.decode(exchange.getRequestURI().getQuery().replace("name=",""),StandardCharsets.UTF_8);
              if(!name.matches("[a-zA-Z0-9_]{3,16}"))throw new IllegalArgumentException("Invalid player name");
              Class<?> api=Class.forName("fr.xephi.authme.api.v3.AuthMeApi");Object instance=api.getMethod("getInstance").invoke(null);
              response.complete(json.toJson(Map.of("registered",api.getMethod("isRegistered",String.class).invoke(instance,name))));
             } else if(path.equals("/restore") && exchange.getRequestMethod().equals("POST") && role.equals("lobby")) {
              UUID id=UUID.fromString(exchange.getRequestURI().getQuery().replace("uuid=",""));Player p=getServer().getPlayer(id);
              if(p==null)throw new IllegalStateException("Player not joined");
              Class<?> api=Class.forName("fr.xephi.authme.api.v3.AuthMeApi");Object instance=api.getMethod("getInstance").invoke(null);
              api.getMethod("forceLogin",Player.class).invoke(instance,p);response.complete("{}");
            } else if(path.equals("/announce") && exchange.getRequestMethod().equals("POST")) {
              getServer().broadcastMessage(body); response.complete("{}");
            } else if(path.equals("/admission") && exchange.getRequestMethod().equals("POST")) {
              accepting=body.equals("open");response.complete("{}");
            } else response.complete("{}");
          } catch(Exception e){response.completeExceptionally(e);}
        });
        try {reply(exchange,200,response.get(path.equals("/duel/create")?30:5,TimeUnit.SECONDS));}catch(Exception e){reply(exchange,503,"{}");}
      });
      httpExecutor=Executors.newFixedThreadPool(2);http.setExecutor(httpExecutor);http.start();
    }catch(Exception e){throw new RuntimeException(e);}
  }
  void reply(HttpExchange x,int code,String s)throws java.io.IOException{byte[] b=s.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(code,b.length);x.getResponseBody().write(b);x.close();}
  public void onDisable(){if(http!=null)http.stop(0);if(httpExecutor!=null)httpExecutor.shutdownNow();}
  @EventHandler public void join(PlayerJoinEvent e){visited=true;if(role.equals("lobby")){
    Player p=e.getPlayer();p.teleport(getServer().getWorlds().getFirst().getSpawnLocation());p.setGameMode(GameMode.ADVENTURE);
    for(Player other:getServer().getOnlinePlayers())if(other!=p){p.hidePlayer(this,other);other.hidePlayer(this,p);}
    getServer().getScheduler().runTaskLater(this,()->lobbyKit(p),40);
    p.sendMessage("§a§lKiwyClub §8» §fДобро пожаловать в клуб!");
    p.sendMessage("§7Войди через §a/register §7или §a/login§7. Книга и §a/help §7помогут выбрать режим.");
  }}
  ItemStack guide(){if(guidebook==null)guidebook=Guidebook.create();return guidebook.clone();}
  ItemStack item(Material material,String name,NamedTextColor color,String... description){
    ItemStack item=new ItemStack(material);var meta=item.getItemMeta();
    meta.displayName(Component.text(name,color).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC,false));
    meta.lore(Arrays.stream(description).map(line->Component.text(line,NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false)).toList());
    item.setItemMeta(meta);return item;
  }
  void lobbyKit(Player p){p.getInventory().setItem(0,guide());p.getInventory().setItem(4,item(Material.COMPASS,"Выбор режима",NamedTextColor.GREEN,"KiwyClub","Нажми, чтобы выбрать приключение"));}
  @EventHandler public void drop(PlayerDropItemEvent e){if(role.equals("lobby"))e.setCancelled(true);}
  @EventHandler public void drag(org.bukkit.event.inventory.InventoryDragEvent e){if(role.equals("lobby"))e.setCancelled(true);}
  @EventHandler public void damage(EntityDamageEvent e){if(role.equals("lobby"))e.setCancelled(true);}
  Set<String> playerCommands(){
    Set<String> result=new HashSet<>(Set.of("server","help"));
    if(role.equals("lobby"))result.addAll(Set.of("register","login","logout","changepassword"));
    if(role.equals("survival"))result.addAll(Set.of("home","tpa","tpaccept","tpdeny","pvp","pvpaccept","pvpdeny"));
    if(role.equals("parkour"))result.addAll(Set.of("checkpoint","restart"));
    if(role.equals("pvp"))result.add("pvpleave");
    if(role.equals("pillars"))result.add("trigger");return result;
  }
  @EventHandler(priority=EventPriority.HIGHEST) public void commandList(PlayerCommandSendEvent e){if(!e.getPlayer().isOp())e.getCommands().removeIf(c->!playerCommands().contains(c.toLowerCase(Locale.ROOT)));}
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void restrict(PlayerCommandPreprocessEvent e){
    if(e.getPlayer().isOp())return;String root=e.getMessage().substring(1).split("\\s+")[0].toLowerCase(Locale.ROOT);
    if(!playerCommands().contains(root)){e.setCancelled(true);e.getPlayer().sendMessage("Команда недоступна. /server — выбор сервера.");}
  }
  @EventHandler public void place(BlockPlaceEvent e){if(role.equals("lobby"))e.setCancelled(true);}
  @EventHandler public void breakBlock(BlockBreakEvent e){if(role.equals("lobby"))e.setCancelled(true);}
  @EventHandler public void move(PlayerMoveEvent e){if(role.equals("lobby")&&e.getTo().getY()<99)e.getPlayer().teleport(e.getPlayer().getWorld().getSpawnLocation());}
  @EventHandler public void interact(PlayerInteractEvent e){if(!role.equals("lobby"))return;e.setCancelled(true);
    if(e.getItem()!=null&&e.getItem().getType()==Material.WRITTEN_BOOK){e.getPlayer().openBook(guide());return;}
    if(e.getItem()!=null&&e.getItem().getType()==Material.COMPASS){
      var menu=getServer().createInventory(null,9,MENU_TITLE);
      ItemStack glass=item(Material.GREEN_STAINED_GLASS_PANE," ",NamedTextColor.DARK_GREEN);
      for(int slot:new int[]{0,1,3,5,7,8})menu.setItem(slot,glass);
      menu.setItem(2,item(Material.END_STONE,"Столбы · Pillars",NamedTextColor.GOLD,"До 16 игроков на арене","Выберем свободную карту","Нажми, чтобы присоединиться"));
      menu.setItem(4,item(Material.GRASS_BLOCK,"Выживание · Survival",NamedTextColor.GREEN,"Мир, дом и приключения","Друзья и безопасные дуэли","Нажми, чтобы присоединиться"));
      menu.setItem(6,item(Material.FEATHER,"Паркур · Parkour",NamedTextColor.AQUA,"Новая трасса каждый час","Прыжки и контрольные точки","Нажми, чтобы присоединиться"));
      e.getPlayer().openInventory(menu);
    }
  }
  @EventHandler public void click(InventoryClickEvent e){if(!role.equals("lobby"))return;e.setCancelled(true);
    if(!e.getView().getTitle().equals(MENU_TITLE))return;
    String target=e.getRawSlot()==2?"pillars":e.getRawSlot()==4?"survival":e.getRawSlot()==6?"parkour":null;
    if(target!=null&&e.getWhoClicked() instanceof Player p){
      try {var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);out.writeUTF("Connect");out.writeUTF(target);p.sendPluginMessage(this,"BungeeCord",bytes.toByteArray());p.closeInventory();}catch(Exception ex){getLogger().warning(ex.toString());}
    }
  }
  boolean hazard(Block b){return b.isLiquid() || Set.of(Material.FIRE,Material.SOUL_FIRE,Material.MAGMA_BLOCK,Material.CAMPFIRE,Material.SOUL_CAMPFIRE,Material.CACTUS,Material.POWDER_SNOW,Material.SWEET_BERRY_BUSH).contains(b.getType());}
  public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
    if(!(sender instanceof Player p))return false;
    if(parkour!=null&&(cmd.getName().equals("checkpoint")||cmd.getName().equals("restart"))){parkour.back(p,cmd.getName().equals("restart"));return true;}
    if(duels!=null&&duels.command(p,cmd.getName(),args))return true;
    if(cmd.getName().equals("arena")){if(args.length==1){accepting=args[0].equals("open");p.sendMessage("Набор игроков: "+accepting);}return true;}
    if(!role.equals("survival")){p.sendMessage("Команда доступна на survival.");return true;}
    switch(cmd.getName()){
      case "home" -> {
        Location bed=p.getRespawnLocation(false);
        if(bed==null){p.sendMessage("Дом не задан: поспите в кровати.");return true;}
        Block b=bed.getBlock();
        if(!(b.getBlockData() instanceof Bed)){p.sendMessage("Кровать отсутствует.");return true;}
        Location safe=null;
        outer:for(int y=0;y<=1;y++)for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
          Block feet=b.getRelative(x,y,z);
          if(feet.isPassable()&&feet.getRelative(0,1,0).isPassable()&&feet.getRelative(0,-1,0).getType().isSolid()
             &&!hazard(feet)&&!hazard(feet.getRelative(0,1,0))&&!hazard(feet.getRelative(0,-1,0))){
            safe=feet.getLocation().add(.5,0,.5);break outer;
          }
        }
        if(safe==null)p.sendMessage("Возле кровати нет безопасного места.");else p.teleportAsync(safe);return true;
      }
      case "tpa" -> {Player target=args.length==1?getServer().getPlayerExact(args[0]):null;
        if(target==null||target==p){p.sendMessage("Используйте /tpa <игрок на survival>.");return true;}
        requests.put(target.getUniqueId(),new Request(p.getUniqueId(),System.currentTimeMillis()+60000));
        target.sendMessage(p.getName()+" просит телепортироваться: /tpaccept или /tpdeny (60 секунд).");p.sendMessage("Запрос отправлен.");return true;
      }
      case "tpaccept","tpdeny" -> {Request r=requests.remove(p.getUniqueId());
        Player source=r==null?null:getServer().getPlayer(r.sender());
        if(r==null||source==null||r.expires()<System.currentTimeMillis()){p.sendMessage("Нет активного запроса.");return true;}
        if(cmd.getName().equals("tpaccept")){source.teleportAsync(p.getLocation());p.sendMessage("Запрос принят.");}else source.sendMessage("Запрос отклонён.");return true;
      }
    } return false;
  }
}
