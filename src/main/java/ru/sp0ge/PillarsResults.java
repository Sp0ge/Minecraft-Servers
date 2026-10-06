package ru.sp0ge;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import org.bukkit.scoreboard.DisplaySlot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

final class PillarsResults {
 final Backend plugin; int ticks; String panelState="";
 PillarsResults(Backend plugin){this.plugin=plugin;
  plugin.getServer().getScheduler().runTaskTimer(plugin,()->{
   var board=plugin.getServer().getScoreboardManager().getMainScoreboard();
   var signal=board.getObjective("kiwy_result");
   if(signal==null)signal=board.registerNewObjective("kiwy_result","dummy",Component.empty());
   int result=signal.getScore("#result").getScore();
   if(result>=1&&result<=4){
    signal.getScore("#result").setScore(0);
    report(switch(result){case 1->"solo";case 2->"red";case 3->"blue";default->"none";});
    plugin.getServer().getOnlinePlayers().forEach(p->p.removeScoreboardTag("kiwy_winner"));
   }
   if(++ticks%20==0)panel();
  },1,1);
 }
 void panel(){
  var board=plugin.getServer().getScoreboardManager().getMainScoreboard();
  // The map's per-score display names cannot be translated to Minecraft 1.20.1.
  // Keep its objectives and teams for game logic, replacing only the presentation.
  for(DisplaySlot slot:DisplaySlot.values())if(slot.name().startsWith("SIDEBAR")){
   var shown=board.getObjective(slot);
   if(shown!=null&&!shown.getName().equals("kiwy_panel"))board.clearSlot(slot);
  }
  var game=board.getObjective("game");var round=board.getObjective("round");
  int count=plugin.getServer().getOnlinePlayers().size();
  boolean playing=game!=null&&game.getScore("#game_info").getScore()==1;
  int number=round==null?0:round.getScore("#round").getScore();
  String state=count+":"+playing+":"+number;
  var objective=board.getObjective("kiwy_panel");
  if(!state.equals(panelState)||objective==null){
   if(objective!=null)objective.unregister();
   objective=board.registerNewObjective("kiwy_panel","dummy",Component.text("KiwyClub",NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
    .append(Component.text(" • ",NamedTextColor.DARK_GRAY)).append(Component.text("Pillars",NamedTextColor.GOLD)));
   objective.getScore("§7Игроков: §f"+count+"§7 / 16").setScore(4);
   objective.getScore(playing?"§6Матч идёт":"§aОжидание игроков").setScore(3);
   objective.getScore("§7Раунд: §f"+number).setScore(2);
   objective.getScore("§7Режимы: §a/server").setScore(1);
   panelState=state;
  }
  if(objective.getDisplaySlot()!=DisplaySlot.SIDEBAR)objective.setDisplaySlot(DisplaySlot.SIDEBAR);
 }
 void report(String kind){
  if(!Set.of("solo","red","blue","none").contains(kind))return;
  List<String> winners=new ArrayList<>();
  for(Player p:plugin.getServer().getOnlinePlayers()){
   if(!kind.equals("none")&&p.getScoreboardTags().contains("kiwy_winner"))winners.add(p.getName());
  }
  if(kind.equals("solo")&&winners.size()!=1)return;
  String outcome=winners.isEmpty()?"none":kind;
  var body=new com.google.gson.JsonObject();body.addProperty("id",UUID.randomUUID().toString());body.addProperty("kind",outcome);body.add("winners",plugin.json.toJsonTree(winners));
  String payload=body.toString();
  plugin.getServer().getScheduler().runTaskAsynchronously(plugin,()->{
   for(int attempt=0;attempt<3;attempt++)try{
    var request=HttpRequest.newBuilder(URI.create("http://proxy:8080/pillars-winner"))
     .timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+plugin.token)
     .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
    if(HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==200)return;
   }catch(Exception e){if(attempt==2)plugin.getLogger().warning("Pillars result announcement failed: "+e);}
  });
 }
}
