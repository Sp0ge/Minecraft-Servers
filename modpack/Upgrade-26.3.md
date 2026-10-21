# Survival 26.3: новая сборка и настройки

Minecraft 26.3, Fabric Loader 0.19.5, Java 25. Сервер: 21 внешний мод и KiwyClub Network. Клиент: 8 внешних модов и проверка KiwyClub.

## Геймплей

- Ванильные предметы, мобы и бой. Artifacts, Better Combat, Lootr, Friends & Foes, YUNG’s, OPAC и прежние игровые расширения исключены.
- Генерация Overworld: Terralith + Tectonic; End: Nullscape. Nether ванильный. Существующий рельеф сохраняется, новая генерация действует в новых чанках; на границах возможны переходы.
- Simple Voice Chat работает через Velocity; Sound Physics Remastered устанавливается только на клиент. Controlify и YACL сохранены для геймпадов.
- Уровни чар — до технического предела 255. Сняты конфликты чар/предметов и запрет «Слишком дорого». Оплата опытом сохраняется, торговля жителей не меняется.

## Удобство

- AppleSkin — подсказки о насыщении еды; клиент + сервер.
- Mouse Tweaks — удобное перемещение предметов; только клиент.
- Shulker Box Tooltip — просмотр содержимого шалкеров; клиент.
- Double Doors — парное открытие обычных дверей; сервер. Рекурсивное открытие, люки и калитки выключены. Зависимость: Collective.

## Оптимизация

Lithium, FerriteCore, Krypton, Alternate Current, ServerCore, C2ME, VMP; Chunky для предварительной генерации и spark для диагностики. ModernFix и Noisium не включены: версий для Fabric 26.3 нет.

C2ME использует 2 потока. Chunky включён: радиус 640 блоков, 60 секунд ожидания без игроков, 30 секунд работы / 60 секунд отдыха; при входе игрока — пауза. Пауза пустого Survival отключена. Перезапуск в 05:00 МСК и квартальный вайп с новым сидом сохранены.

## Права и команды

LuckPerms создаёт управляемую группу `kiwy-player`, наследуемую `default`. Разрешены узлы `kiwy.command.<команда>`, `voicechat.listen`, `voicechat.speak`, `voicechat.groups`. Права администратора игрокам не выдаются.

Обычные команды Survival: `/home`, `/tpa`, `/tpaccept`, `/tpdeny`, `/pvp`, `/pvpaccept`, `/pvpdeny`; сетевые `/server`, `/help`, `/voicechat`. В PvP доступна `/pvpleave`. Прокси скрывает лишние корневые команды, сервер блокирует их выполнение.

Административные операции выполняются через консоль/RCON. Для доверенного администратора создайте отдельную группу с `kiwy.admin=true` и конкретными необходимыми правами LuckPerms; не выдавайте `*` группе `default`.

## Новый мир

Сборка создаёт новый Survival при первом запуске. Перенос мира 1.20.1 и конвертация модовых блоков не требуются. Новый мир сразу генерируется с Terralith + Tectonic и Nullscape.

Данные: `ServerData/survival-fabric-26.3`, `ServerData/pvp-fabric-26.3`, `ServerData/duels-fabric-26.3`. Старые каталоги 1.20.1 не используются и не блокируют запуск. В ZIP сборки игровые данные, аккаунты и секреты не входят.

Обычный запуск `bash start.sh` сохраняет уже созданный мир 26.3. Новый сид назначается при создании мира и при плановом вайпе.

## Источники

- [Minecraft Java 26.3](https://feedback.minecraft.net/hc/en-us/articles/48913133328013-Minecraft-Java-Edition-26-3)
- [Fabric 26.3](https://www.fabricmc.net/2026/09/15/263.html)
- [Enchantments Unbound](https://modrinth.com/mod/enchantments-unbound)
