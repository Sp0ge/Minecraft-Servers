package ru.sp0ge;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.*;
import java.util.*;

final class ClubStyle {
 static final TextColor GREEN=TextColor.color(0x73DB9A),GOLD=TextColor.color(0xEDD58A),MUTED=TextColor.color(0x94A69B),LINE=TextColor.color(0x365344);
 static final List<String> TOPICS=List.of("all","account","survival","pillars","parkour","pvp");
 static final Component BRAND=brand();
 static Component brand(){
  String name="KiwyClub";int[] colors={0x6BDA90,0x7EDF90,0x91E390,0xA4E890,0xB7EC90,0xCAEF92,0xDBEF98,0xEDEF9E};Component result=Component.empty();
  for(int i=0;i<name.length();i++)result=result.append(Component.text(name.substring(i,i+1),TextColor.color(colors[i])).decorate(TextDecoration.BOLD));return result;
 }
 static String label(String server){return switch(server){case "lobby"->"Лобби";case "survival"->"Survival";case "parkour"->"Parkour";case "pvp"->"PvP";default->server.startsWith("pillars_")?"Pillars №"+server.substring(8):server;};}
 static TextColor color(String server){return server.startsWith("pillars")?GOLD:server.equals("pvp")?TextColor.color(0xE79595):server.equals("parkour")?TextColor.color(0x8FD9E4):GREEN;}
 static Component button(String name,String topic){return Component.text("["+name+"]",GREEN).clickEvent(ClickEvent.runCommand("/help "+topic)).hoverEvent(HoverEvent.showText(Component.text("Открыть справку: "+name)));}
 static void text(Player p,String s){p.sendMessage(Component.text("  "+s,MUTED));}
 static void command(Player p,String cmd,String description){String suggestion=cmd.contains(" <")?cmd.substring(0,cmd.indexOf(" <"))+" ":cmd;
  p.sendMessage(Component.text("  › ",GREEN).append(Component.text(cmd,GREEN).clickEvent(ClickEvent.suggestCommand(suggestion)).hoverEvent(HoverEvent.showText(Component.text("Подставить команду"))))
   .append(Component.text(" — "+description,NamedTextColor.WHITE)));
 }
 static void section(Player p,String title){p.sendMessage(Component.text("\n  "+title,GOLD).decorate(TextDecoration.BOLD));}
 static void help(Player p,String raw){
  String topic=raw.toLowerCase(Locale.ROOT);if(!TOPICS.contains(topic)&&!topic.equals("main"))topic="main";
  p.sendMessage(Component.text("\n━━━━━━━━━━━━━━━━━━━━━━━━━━",LINE));
  p.sendMessage(Component.text("  ").append(BRAND).append(Component.text("  •  Путеводитель",NamedTextColor.WHITE)));
  p.sendMessage(Component.text("  ").append(button("Вход","account")).append(Component.text(" ")).append(button("Survival","survival"))
   .append(Component.text(" ")).append(button("Pillars","pillars")).append(Component.text(" ")).append(button("Parkour","parkour"))
   .append(Component.text(" ")).append(button("PvP","pvp")));
  if(topic.equals("main")){
   p.sendMessage(Component.text("  [Скачать сборку KiwyClub]",GREEN).clickEvent(ClickEvent.openUrl(ModpackGate.URL)));
   text(p,"Выбери раздел выше — или нажми команду ниже.");section(p,"Твой режим");
   command(p,"/server survival","свой мир, дом, друзья и дуэли");command(p,"/server pillars","битвы на столбах, до 16 игроков");
   command(p,"/server parkour","новая трасса каждый час и после победы");command(p,"/server lobby","лобби, компас и книга со справкой");
   text(p,"PvP начинается из Survival: /pvp <игрок>.");command(p,"/voicechat","голосовой чат во всех режимах; меню также по V");text(p,"Для голоса нужен мод Simple Voice Chat на клиенте.");command(p,"/help all","все команды и правила");text(p,"В сети до 64 игроков. Книга доступна в лобби.");
  }
  if(topic.equals("account")||topic.equals("all")){
   command(p,"/voicechat","голосовой чат во всех режимах; меню также по V");
   section(p,"Вход и аккаунт · в лобби");text(p,"Вход с лицензионным Java-аккаунтом. Скин берётся из Minecraft-профиля.");command(p,"/register <пароль> <повтор>","создать аккаунт");command(p,"/login <пароль>","войти");
   command(p,"/changepassword <старый> <новый>","сменить пароль");command(p,"/logout","выйти из аккаунта");
   text(p,"После входа выбери режим через /server или компас.");
  }
  if(topic.equals("survival")||topic.equals("all")){
   p.sendMessage(Component.text("  [Скачать сборку KiwyClub]",GREEN).clickEvent(ClickEvent.openUrl(ModpackGate.URL)));
   section(p,"Survival · Fabric 1.20.1");text(p,"Для Survival и PvP нужна клиентская сборка KiwyClub.");command(p,"/opac","личные настройки защиты");command(p,"/oclaims","управление приватами; также через карту Xaero");command(p,"/oparties","создание и управление партией");command(p,"/server survival","перейти в выживание");command(p,"/home","к текущей кровати; дом только один");
   command(p,"/tpa <игрок>","попросить телепортацию на 60 секунд");command(p,"/tpaccept","принять запрос");command(p,"/tpdeny","отклонить запрос");
   text(p,"Первый спаун: квадрат 64×64 чанка. Чат: радиус 128 блоков.");text(p,"Входы и смерти видны всему Survival.");
   text(p,"Перезапуск в 05:00 МСК; предупреждения за 20/10/5 минут.");text(p,"Вайп каждые 3 месяца: новый сид, мир и инвентарь.");
  }
  if(topic.equals("pillars")||topic.equals("all")){
   section(p,"Pillars · битвы на столбах");command(p,"/server pillars","выбрать свободную арену");command(p,"/server pillars_1","выбрать конкретную; также pillars_2 … pillars_5");
   text(p,"16 игроков на арене, до 5 арен. Первая всегда готова.");text(p,"Все вышли — карта сброшена. Готовый резерв получает нужное имя по запросу.");text(p,"В идущий матч можно войти наблюдателем по имени арены.");
   command(p,"/trigger <цель>","действие карты, если она его разрешила");
  }
  if(topic.equals("parkour")||topic.equals("all")){
   section(p,"Parkour · прыгай в своём темпе");command(p,"/server parkour","перейти на общую трассу");command(p,"/checkpoint","к последней контрольной точке");command(p,"/restart","начать сначала");
   text(p,"Длинные и диагональные прыжки, высота и лёд; сложность растёт.");text(p,"Точки по порядку каждые 10 прыжков. Падение — возврат; голод отключён.");text(p,"Новая трасса каждый час и после победы; все начинают её заново.");text(p,"Отдельной квоты нет; действует общий предел сети 64.");
  }
  if(topic.equals("pvp")||topic.equals("all")){
   section(p,"PvP · вызови соперника из Survival");command(p,"/pvp <игрок>","отправить вызов на 60 секунд");command(p,"/pvpaccept","принять вызов");command(p,"/pvpdeny","отклонить вызов");command(p,"/pvpleave","сдаться на арене и вернуться");
   text(p,"Поле 3×3 чанка со стеной, случайным рельефом и укрытиями.");text(p,"Новая карта перед каждой дуэлью; бой до 10 минут с копиями вещей.");text(p,"После смерти, сдачи или выхода оба вернутся в Survival.");text(p,"Исходный инвентарь восстановится целиком: расходники и износ тоже.");text(p,"Победу увидит вся сеть KiwyClub.");
  }
  if(!topic.equals("main"))p.sendMessage(Component.text("\n  ").append(button("Все разделы","main")).append(Component.text("  /help — главное меню",MUTED)));
  p.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━\n",LINE));
 }
}
