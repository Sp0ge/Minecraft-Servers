package ru.sp0ge;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import java.util.*;

public final class Guidebook {
 static final Set<String> RUN=Set.of("/server lobby","/server survival","/server pillars","/server pillars_1","/server parkour","/home","/checkpoint","/restart","/pvpleave","/help");
 public static ItemStack create(){
  ItemStack book=new ItemStack(Material.WRITTEN_BOOK);BookMeta meta=(BookMeta)book.getItemMeta();
  meta.setTitle("Команды и режимы");meta.setAuthor("KiwyClub");
  meta.displayName(Component.text("Команды и режимы",NamedTextColor.GREEN).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC,false));
  meta.lore(List.of(Component.text("KiwyClub · путеводитель",NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false),Component.text("Режимы, команды и правила",NamedTextColor.DARK_GREEN).decoration(TextDecoration.ITALIC,false)));
  List<Component> pages=new ArrayList<>();
  pages.add(page("Игровой клуб","Добро пожаловать!","","Survival • Pillars","Parkour • PvP","","До 64 игроков","в одной сети.","","/help"));
  Component contents=header("Оглавление");
  String[] names={"Вход и аккаунт","Выбор режима","Survival","Pillars","Parkour","Дуэли PvP","Победы и справка"};int[] targets={3,5,6,10,12,14,16};
  for(int i=0;i<names.length;i++)contents=contents.append(Component.text("› "+names[i],NamedTextColor.DARK_GREEN).clickEvent(ClickEvent.changePage(targets[i])).hoverEvent(HoverEvent.showText(Component.text("Открыть раздел")))).append(Component.newline());
  pages.add(contents.append(Component.text("Нажми на раздел.",NamedTextColor.GRAY)));
  pages.add(page("Вход в клуб","/register","пароль повтор","Создать аккаунт.","","/login пароль","Войти в аккаунт.","","Сначала войди,","выбери режим."));
  pages.add(page("Твой аккаунт","/changepassword","старый новый","Сменить пароль.","","/logout","Выйти из аккаунта.","","Береги пароль.","Ник и вход общие."));
  pages.add(page("Выбор режима","/server survival","Выживание.","/server pillars","Свободная арена.","/server parkour","Общий паркур.","/server lobby","Вернуться в лобби.","Компас — меню."));
  pages.add(page("Survival · дом","/home","Вернуться к дому.","","Дом — твоя текущая","кровать. Поспи","в ней для дома.","","Сломанная кровать","не работает."));
  pages.add(page("Survival · друзья","/tpa имя","Запросить переход.","/tpaccept","Принять запрос.","/tpdeny","Отклонить запрос.","","Запрос: 60 секунд.","Оба — в Survival."));
  pages.add(page("Survival · мир","Первый спаун:","64×64 чанка.","Без кровати после","смерти — заново.","Обычный вход:","место сохраняется.","Чат: 128 блоков.","Входы и смерти","видны в Survival."));
  pages.add(page("Карта и сезоны","Перезапуск: 05:00","ежедневно (МСК).","Предупреждения:","20/10/5 минут.","","Вайп: 3 месяца.","Новый мир и сид.","Вещи сбрасываются.","Аккаунт сохранён."));
  pages.add(page("Pillars · столбы","До 16 игроков","на каждой арене.","До 5 арен в сети.","","/server pillars","Свободная арена.","/server pillars_1","Конкретная арена.","Также _2 … _5."));
  pages.add(page("Свободные арены","Все вышли — мир","сбрасывается.","Первая арена","всегда готова.","Другие запускаются","по запросу.","В идущий матч","по имени —","наблюдателем."));
  pages.add(page("Parkour · прыжки","/server parkour","Одна общая трасса.","","Новая трасса","каждый час.","При обновлении","стартуют заново.","","Общий предел — 64."));
  pages.add(page("Точки трассы","/checkpoint","К последней точке.","/restart","К началу трассы.","","Точка через каждые","10 прыжков.","При падении —","возврат к точке."));
  pages.add(page("PvP · вызов","/pvp имя","Игрок из Survival.","/pvpaccept","Принять вызов.","/pvpdeny","Отклонить вызов.","Вызов: 60 секунд.","Отдельная арена.","Вещи — копии."));
  pages.add(page("PvP · возврат","После смерти оба","назад в Survival.","Исходные вещи","вернутся целиком.","Расходники и износ","не теряются.","/pvpleave","Завершить бой.","Бой: до 10 минут."));
  pages.add(page("Победы и справка","Победу видит","вся сеть KiwyClub.","Уход соперника","бой заканчивается.","Вещи сохраняются.","","/help","Справка в чате.","Нажми команду."));
  meta.pages(pages);book.setItemMeta(meta);return book;
 }
 static Component header(String title){return Component.empty().append(Component.text("KiwyClub",NamedTextColor.DARK_GREEN).decorate(TextDecoration.BOLD))
  .append(Component.newline()).append(Component.text(title,NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
  .append(Component.text("\n──────────────\n",NamedTextColor.GRAY));}
 static Component page(String title,String... lines){
  Component result=header(title);
  for(String line:lines){Component row=Component.text(line,line.startsWith("/")?NamedTextColor.DARK_GREEN:NamedTextColor.BLACK);
   if(RUN.contains(line))row=row.clickEvent(ClickEvent.runCommand(line)).hoverEvent(HoverEvent.showText(Component.text("Выполнить "+line)));
   result=result.append(row).append(Component.newline());
  }
  return result.append(Component.text("← Оглавление",NamedTextColor.DARK_AQUA).clickEvent(ClickEvent.changePage(2)));
 }
}
