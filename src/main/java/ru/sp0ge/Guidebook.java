package ru.sp0ge;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
public final class Guidebook {
 public static ItemStack create(){
  ItemStack book=new ItemStack(Material.WRITTEN_BOOK);BookMeta meta=(BookMeta)book.getItemMeta();
  meta.setTitle("Команды и режимы");meta.setAuthor("Minecraft Servers");
  meta.setPages(paginate(
   "§6Minecraft Servers\n\n§0В сети до 64 игроков.\n\nSurvival — выживание.\nPillars — столбы.\nParkour — паркур.\nPvP — дуэли.\n\n/help — справка.\nВ lobby есть компас.",
   "§6Вход\n\n§0/register пароль повтор\nСоздать аккаунт.\n\n/login пароль\nВойти.\n\nДля выбора режима сначала войдите. Ник и аккаунт сохраняются между режимами.",
   "§6Аккаунт и lobby\n\n§0/logout — выйти.\n\n/changepassword старый новый\nСменить пароль.\n\nНе делитесь паролем.\n/server lobby\nВернуться в lobby.",
   "§6Выбор режима\n\n§0/server survival\nВыживание.\n\n/server pillars\nСвободная арена.\n\n/server parkour\nОбщий паркур.\n\nКомпас открывает меню.",
   "§6Survival: дом\n\n§0/home — к кровати.\nДом один: текущая кровать. Поспите в ней для сохранения.\n\nЕсли кровать сломана или рядом небезопасно, телепортации не будет.",
   "§6Survival: друзья\n\n§0/tpa имя\nЗапрос телепортации.\n\n/tpaccept — принять.\n/tpdeny — отклонить.\n\nЗапрос действует 60 секунд. Оба игрока должны быть в Survival.",
   "§6Survival: спаун\n\n§0Первый вход в сезон: случайная безопасная точка в квадрате 64 × 64 чанка.\n\nПосле смерти без кровати — новая точка. При обычном входе место сохраняется.",
   "§6Survival: чат\n\n§0Чат слышен в радиусе 8 чанков (128 блоков) в том же мире.\n\nСообщения о входе и смерти видны всему Survival.",
   "§6Survival: расписание\n\n§0Перезапуск ежедневно в 05:00 по Москве.\n\nПредупреждения за 20, 10 и 5 минут.\n\nВайп раз в 3 месяца: новый мир и сид. Вещи сбрасываются, аккаунт сохраняется.",
   "§6Pillars\n\n§0До 16 игроков на арене. До 5 арен.\n\n/server pillars\nВыбрать свободную.\n\n/server pillars_1\nВыбрать конкретную. Аналогично _2 … _5.",
   "§6Pillars: арены\n\n§0После выхода всех игроков карта сбрасывается.\n\nПервая арена всегда готова. Остальные запускаются по запросу.\n\nВ идущий матч можно зайти наблюдателем по имени арены.",
   "§6Parkour\n\n§0Общая случайная трасса. Обновление каждый час: все начинают новую трассу.\n\n/checkpoint\nК контрольной точке.\n\n/restart\nНачать сначала.",
   "§6Parkour: правила\n\n§0Контрольные точки каждые 10 прыжков. При падении вы вернётесь к последней.\n\nУ режима нет отдельной квоты игроков: действует общий предел сети 64.",
   "§6PvP: вызов\n\n§0/pvp имя\nВызвать из Survival.\n\n/pvpaccept — принять.\n/pvpdeny — отклонить.\n\nВызов действует 60 секунд. Бой на отдельной арене с копией ваших вещей.",
   "§6PvP: возврат\n\n§0После смерти оба возвращаются в Survival. Исходный инвентарь восстанавливается целиком, включая расходники и износ.\n\n/pvpleave — сдаться.\nЛимит боя: 10 минут.",
   "§6PvP: результат\n\n§0После победы вся сеть увидит:\n\nИгрок <имя> выиграл <имя> в PVP\n\nПри отключении бой заканчивается, вещи сохраняются.\n\n/help — все команды."
  ));book.setItemMeta(meta);return book;
 }
 static String[] paginate(String... sections){
  java.util.List<String> pages=new java.util.ArrayList<>();
  for(String section:sections){java.util.List<String> lines=new java.util.ArrayList<>();
   for(String raw:section.split("\\n",-1)){
    String line="";for(String word:raw.split(" ",-1)){
     String candidate=line.isEmpty()?word:line+" "+word;
     if(!line.isEmpty()&&candidate.replaceAll("§.","").length()>18){lines.add(line);line=word;}else line=candidate;
    }lines.add(line);
   }
   for(int start=0;start<lines.size();start+=13)pages.add((start==0?"":"§0")+String.join("\n",lines.subList(start,Math.min(start+13,lines.size()))));
  }return pages.toArray(String[]::new);
 }
}
