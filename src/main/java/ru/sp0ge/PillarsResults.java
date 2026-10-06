package ru.sp0ge;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;

final class PillarsResults {
 final Backend plugin; boolean announced;
 PillarsResults(Backend plugin){this.plugin=plugin;
  plugin.getServer().getScheduler().runTaskTimer(plugin,()->{
   var game=plugin.getServer().getScoreboardManager().getMainScoreboard().getObjective("game");
   if(game!=null&&game.getScore("#game_info").getScore()==0)announced=false;
  },1,1);
 }
 void report(String kind){
  if(announced||!Set.of("solo","red","blue","none").contains(kind))return;
  var board=plugin.getServer().getScoreboardManager().getMainScoreboard();var game=board.getObjective("game");
  if(game==null||game.getScore("#game_info").getScore()!=1)return;
  List<String> winners=new ArrayList<>();
  for(Player p:plugin.getServer().getOnlinePlayers()){
   var team=board.getEntryTeam(p.getName());
   if(kind.equals("solo")&&p.getScoreboardTags().contains("playing")
     ||Set.of("red","blue").contains(kind)&&team!=null&&team.getName().equals(kind))winners.add(p.getName());
  }
  if(kind.equals("solo")&&winners.size()!=1)return;
  String outcome=winners.isEmpty()?"none":kind;announced=true;
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
