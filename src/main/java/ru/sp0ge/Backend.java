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
import com.google.gson.Gson;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class Backend extends JavaPlugin implements Listener {
  String role=System.getenv().getOrDefault("ROLE","survival"), token;
  final Gson json=new Gson(); HttpServer http; ExecutorService httpExecutor; boolean visited=false, accepting=true;
  volatile String snapshot="{}"; final Map<UUID,Request> requests=new HashMap<>();
  record Request(UUID sender,long expires) {}
  public ChunkGenerator getDefaultWorldGenerator(String name,String id) {
    return new ChunkGenerator() {
      public boolean shouldGenerateNoise(){return false;} public boolean shouldGenerateSurface(){return false;}
      public boolean shouldGenerateCaves(){return false;} public boolean shouldGenerateDecorations(){return false;}
      public boolean shouldGenerateMobs(){return false;} public boolean shouldGenerateStructures(){return false;}
      public Location getFixedSpawnLocation(World world,Random random){return new Location(world,0.5,101,0.5);}
    };
  }
  public void onEnable() {
    try { token=Files.readString(Path.of("/secrets/api-token")).trim(); } catch(Exception e){throw new RuntimeException(e);}
    getServer().getPluginManager().registerEvents(this,this);
    getServer().getMessenger().registerOutgoingPluginChannel(this,"BungeeCord");
    if(role.equals("lobby")) getServer().getScheduler().runTask(this,()-> {
      World w=getServer().getWorlds().getFirst();
      w.setSpawnLocation(0,101,0); w.setGameRule(GameRule.DO_MOB_SPAWNING,false);
      w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false); w.setTime(6000);
      for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)w.getBlockAt(x,100,z).setType(Material.BARRIER);
    });
    getServer().getScheduler().runTaskTimer(this,()-> {
      List<String> ids=getServer().getOnlinePlayers().stream().map(p->p.getUniqueId().toString()).toList();
      boolean open=accepting;
      if(role.equals("pillars")) {
        var objective=getServer().getScoreboardManager().getMainScoreboard().getObjective("game");
        open=open && objective!=null && objective.getScore("#game_info").getScore()==0;
        if(objective!=null) objective.getScore("#team").setScore(1);
      }
      snapshot=json.toJson(Map.of("players",ids,"count",ids.size(),"visited",visited,"accepting",open,
       "seed",getServer().getWorlds().getFirst().getSeed(),"role",role,"generation",System.getenv().getOrDefault("GENERATION","static")));
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
            if(path.equals("/admit") && exchange.getRequestMethod().equals("POST") && role.equals("survival")) {
              String name=java.net.URLDecoder.decode(exchange.getRequestURI().getQuery().replace("name=",""),StandardCharsets.UTF_8);
              if(!name.matches("[a-zA-Z0-9_]{3,16}"))throw new IllegalArgumentException("Invalid player name");
              getServer().getOfflinePlayer(name).setWhitelisted(true);response.complete("{}");
            } else if(path.equals("/player")) {
              UUID id=UUID.fromString(exchange.getRequestURI().getQuery().replace("uuid=",""));Player p=getServer().getPlayer(id);
              if(p==null)throw new IllegalStateException("Player not joined");Location l=p.getLocation();
              response.complete(json.toJson(Map.of("x",l.getX(),"y",l.getY(),"z",l.getZ(),"world",l.getWorld().getName())));
            } else if(path.equals("/auth")) {
              UUID id=UUID.fromString(exchange.getRequestURI().getQuery().replace("uuid=",""));
              Player p=getServer().getPlayer(id); boolean authenticated=false;
              if(p!=null && role.equals("lobby") && getServer().getPluginManager().isPluginEnabled("AuthMe")) {
                Class<?> api=Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
                Object instance=api.getMethod("getInstance").invoke(null);
                authenticated=(boolean)api.getMethod("isAuthenticated",Player.class).invoke(instance,p);
              }
              response.complete(json.toJson(Map.of("authenticated",authenticated)));
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
        try {reply(exchange,200,response.get(5,TimeUnit.SECONDS));}catch(Exception e){reply(exchange,503,"{}");}
      });
      httpExecutor=Executors.newFixedThreadPool(2);http.setExecutor(httpExecutor);http.start();
    }catch(Exception e){throw new RuntimeException(e);}
  }
  void reply(HttpExchange x,int code,String s)throws java.io.IOException{byte[] b=s.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(code,b.length);x.getResponseBody().write(b);x.close();}
  public void onDisable(){if(http!=null)http.stop(0);if(httpExecutor!=null)httpExecutor.shutdownNow();}
  @EventHandler public void join(PlayerJoinEvent e){visited=true;if(role.equals("lobby")){
    Player p=e.getPlayer();p.teleport(getServer().getWorlds().getFirst().getSpawnLocation());p.setGameMode(GameMode.ADVENTURE);
    for(Player other:getServer().getOnlinePlayers())if(other!=p){p.hidePlayer(this,other);other.hidePlayer(this,p);}
    getServer().getScheduler().runTaskLater(this,()->p.getInventory().setItem(4,new ItemStack(Material.COMPASS)),40);
    p.sendMessage("После /register или /login: /server pillars, /server survival. Компас открывает выбор.");
  }}
  @EventHandler public void damage(EntityDamageEvent e){if(role.equals("lobby"))e.setCancelled(true);}
  @EventHandler public void place(BlockPlaceEvent e){if(role.equals("lobby"))e.setCancelled(true);}
  @EventHandler public void breakBlock(BlockBreakEvent e){if(role.equals("lobby"))e.setCancelled(true);}
  @EventHandler public void move(PlayerMoveEvent e){if(role.equals("lobby")&&e.getTo().getY()<99)e.getPlayer().teleport(e.getPlayer().getWorld().getSpawnLocation());}
  @EventHandler public void interact(PlayerInteractEvent e){if(!role.equals("lobby"))return;e.setCancelled(true);
    if(e.getItem()!=null&&e.getItem().getType()==Material.COMPASS){
      var menu=getServer().createInventory(null,9,"Выбор сервера");
      ItemStack pillars=new ItemStack(Material.END_STONE);var m=pillars.getItemMeta();m.setDisplayName("Pillars");pillars.setItemMeta(m);
      ItemStack survival=new ItemStack(Material.GRASS_BLOCK);m=survival.getItemMeta();m.setDisplayName("Survival");survival.setItemMeta(m);
      menu.setItem(3,pillars);menu.setItem(5,survival);e.getPlayer().openInventory(menu);
    }
  }
  @EventHandler public void click(InventoryClickEvent e){if(!role.equals("lobby"))return;e.setCancelled(true);
    if(!e.getView().getTitle().equals("Выбор сервера"))return;
    String target=e.getRawSlot()==3?"pillars":e.getRawSlot()==5?"survival":null;
    if(target!=null&&e.getWhoClicked() instanceof Player p){
      try {var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);out.writeUTF("Connect");out.writeUTF(target);p.sendPluginMessage(this,"BungeeCord",bytes.toByteArray());p.closeInventory();}catch(Exception ex){getLogger().warning(ex.toString());}
    }
  }
  boolean hazard(Block b){return b.isLiquid() || Set.of(Material.FIRE,Material.SOUL_FIRE,Material.MAGMA_BLOCK,Material.CAMPFIRE,Material.SOUL_CAMPFIRE,Material.CACTUS,Material.POWDER_SNOW,Material.SWEET_BERRY_BUSH).contains(b.getType());}
  public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
    if(!(sender instanceof Player p))return false;
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
