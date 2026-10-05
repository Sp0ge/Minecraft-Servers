# KiwyClub: перенос на Ubuntu VM

## Что подготовлено

Исходники переносим через `repository.bundle`, карту и закреплённые JAR — через
`assets.tar.gz`. Docker-образы собираются на VM x86_64: локальные ARM-образы Mac
не переносятся. `SHA256SUMS` проверяет целостность всего релиза.
Релиз не содержит регистраций, миров survival, API-токенов и тестовых паролей.
При необходимости эти данные экспортируются отдельно, только при остановленной сети.

Целевая среда: Ubuntu Server 24.04 LTS x86_64, cgroup v2, Docker Engine с
драйвером cgroup `systemd` и Compose plugin. VM: 16 vCPU, 64 ГБ RAM, диск 128 ГБ.
Minecraft получает родительский `minecraft.slice`: MemoryMax=32G, CPUQuota=1200%,
MemorySwapMax=0. В этой конфигурации G означает GiB. Сумма обычных лимитов
контейнеров при пяти аренах — 29 GiB и 12 vCPU.
Сборка образов выполняется до запуска игр: процессы BuildKit не входят в этот slice.
Генератор 64 клиентов запускается на другой машине и не расходует бюджет Minecraft.

## Подготовка архива на Mac

Все ветки уже сохранены локально. Упаковщик требует чистое состояние исходников:

```sh
python3 .runtime/source/infra/deploy/pack.py --output .runtime/migration/release-2026-10-06
```

Полученную директорию перенесите по SSH/SCP в домашний каталог администратора VM.
Имя пользователя и IP замените фактическими значениями:

```sh
scp -r .runtime/migration/release-2026-10-06 ADMIN@VM_IP:~/
```

## Установка на VM

Сначала проверьте хэш архива, затем запустите bootstrap с административными правами.
Bootstrap устанавливает Docker из официального репозитория, Git, Python и sysstat;
добавляет ресурсный slice. Firewall и существующие Docker-настройки не заменяются.
Если уже установлен несовместимый Docker/cgroup-драйвер, скрипт остановится.

```sh
cd ~/release-2026-10-06
sha256sum -c SHA256SUMS
sudo bash bootstrap.sh
sudo bash unpack.sh "$PWD" /opt/minecraft-servers
```

`unpack.sh` клонирует main из Git bundle, создаёт семь worktree, импортирует
артефакты, создаёт Python-окружение инструментов и собирает образы. Каталог
назначения должен отсутствовать: скрипт не заменяет существующее развёртывание.
На новой VM карты скачивать повторно не требуется; Maven/Paper runtime всё ещё
могут обращаться к официальным репозиториям при сборке/первом запуске.

## Перенос существующих данных — при необходимости

Для чистой VM этот раздел пропускается. Для сохранения текущего survival и
регистраций сначала корректно остановите исходную сеть. Контроллер остановит
также временные арены. Не удаляйте тома.

```sh
export PROJECT_ROOT="$PWD"
docker compose --env-file .runtime/source/infra/local.env down
python3 .runtime/source/infra/deploy/volume_transfer.py export --directory .runtime/migration/private-volumes
```

Инструмент переносит lobby/AuthMe, survival, расписание, secrets, proxy и
сезонные копии. Временные миры Pillars и сборочный том plugins не переносятся.
Архивы содержат приватные данные; директория закрыта правами 0700, файлы — 0600.
Используйте SCP и храните резервную копию отдельно. На исходном Mac требуется
Python 3.9+; при необходимости используйте доступный Python подходящей версии.
Исходную сеть можно снова запустить после экспорта, но новые изменения уже не
попадут в копию. Для окончательного переключения оставьте исходную сеть остановленной.

На VM после сборки, **до первого запуска**:

```sh
cd /opt/minecraft-servers
sudo .runtime/tools-venv/bin/python .runtime/source/infra/deploy/volume_transfer.py import --directory /ABSOLUTE/PATH/private-volumes
```

Импорт сверяет SHA-256, проверяет имена архивов и безопасность содержимого,
отказывается писать в непустые тома. Он не перезаписывает существующие данные.
При сбое сохраните исходные архивы и изучите созданные тома перед повтором.
Расписание и сид сохраняются; новый сервер не начинает сезон заново.

## Первый запуск и автозапуск

Параметры находятся в `/opt/minecraft-servers/.runtime/ubuntu.env`.
Изначально вход доступен только локально на VM. Для проверки с Mac оставьте
loopback и откройте SSH-туннель `ssh -L 25565:127.0.0.1:25565 ADMIN@VM_IP`.
Для генератора нагрузки установите `BIND_ADDRESS` на приватный IP VM и разрешите
доступ только из тестовой сети. Backend, RCON и API остаются без публикации портов.
При публикации Docker-порта нельзя полагаться только на UFW: используйте правила
гипервизора/маршрутизатора или поддерживаемую Docker-схему фильтрации.

```sh
sudo bash .runtime/source/infra/deploy/start.sh
sudo docker exec mcservers-controller python /app/status.py
sudo docker compose --env-file .runtime/ubuntu.env logs --tail 50 lobby survival proxy
```

Проверьте `PROJECT_ROOT=/opt/minecraft-servers` в окружении при ручном использовании
Compose. `start.sh` выставляет его сам. Проверьте вход, AuthMe и обе игровые команды.
Затем установите unit (путь /opt/minecraft-servers уже закреплён в шаблоне):

```sh
sudo install -m 0644 .runtime/source/infra/deploy/mcservers.service /etc/systemd/system/mcservers.service
sudo systemctl daemon-reload
sudo systemctl enable mcservers.service
sudo systemctl start mcservers.service
systemctl status mcservers.service
systemctl show minecraft.slice -p MemoryMax -p CPUQuotaPerSecUSec
```

Unit управляет всей сетью, включая корректное завершение контроллера и арен.
Не запускайте параллельно вторую копию с теми же именами контейнеров/томов.
Для отката остановите unit, сохраните текущие тома, вернитесь к исходной машине
с её сохранёнными данными. Не запускайте две доступные игрокам копии survival.

## Необязательный план нагрузки (сейчас не выполняется)

Нагрузочные проверки отключены по текущему запросу пользователя. Скрипты ниже
сохранены для возможного отдельного запроса; запуск сети их не выполняет.
Проверка проводится на выделенной тестовой VM/копии данных без обычных игроков.
На время теста задайте `MAINTENANCE_ENABLED=false` в ubuntu.env и обновите
контроллер через Compose; после проверки верните true. Начинайте вне окна 05:00
Europe/Moscow. Проверьте запас диска и отсутствие сторонних нагрузок.

| Этап | Нагрузка | Проверяем |
|---|---|---|
| Разминка | 1 арена, 2 клиента, 2 минуты | Вход, инструменты, метрики |
| Заполнение | 5 арен: 13+13+13+13+12 клиентов; выбор по имени | Создание всех пяти, предел мест, распределение |
| Ожидание | 13+13+13+13+12, 5 минут разминки + 15 минут измерений | TPS, MSPT, память, стабильность соединений |
| Раунды | 13+13+13+13+12, реальные функции карты, 15 минут измерений | Очистка/заполнение карты, датапак, пакеты движения |
| Сброс | Выход всех 64 клиентов | Первая арена сброшена, остальные удалены |
| Восстановление | Повторный вход; остановка/запуск сети | Доступность, отсутствие потери survival/AuthMe |

Боты с `workload=active` поворачиваются, выполняют взмахи рукой и обрабатывают
физику. Они не воспроизводят полноценное PvP, сложные постройки или исследование
survival. Полные игровые матчи с людьми и отдельная нагрузка survival обязательны
перед обещанием вместимости сети. Средний CPU всего сервера не доказывает,
что главный поток каждой арены успевает завершать тик.

### Создание тестовых аккаунтов на VM

Команды ниже выполняются из /opt/minecraft-servers. Данные одного прогона
сохраняются в томе state. Тестовые пароли не попадают в Git и API-токен VM
не передаётся генератору. AuthMe не отключается; ограничения регистрации с
одного IP обходятся штатной административной регистрацией собственных аккаунтов.

```sh
sudo docker exec mcservers-controller python /app/loadtest/provision.py create --count 64 --file /state/loadtests/run1/accounts.json
mkdir -p .runtime/loadtest/run1
sudo docker cp mcservers-controller:/state/loadtests/run1/accounts.json .runtime/loadtest/run1/accounts.json
sudo chmod 600 .runtime/loadtest/run1/accounts.json
```

Скопируйте accounts.json на отдельную машину генератора по SCP.
Для разминки создайте отдельный файл на 2 аккаунта и используйте arenas=1/per-arena=2.

### Генератор на отдельной машине

Нужен Node 24 с зависимостями из `infra-controller:loadtest/package-lock.json`.
На машине генератора можно использовать Docker с Node 24 вместо установки Node.
Оставьте минимум 4 ГБ памяти генератору; наблюдайте его CPU, память и задержку
event loop. Перегруженный генератор делает измерения недостоверными.

```sh
cd .runtime/source/infra/loadtest
npm ci
node client.js --host=VM_PRIVATE_IP --port=25565 --accounts=/ABSOLUTE/PATH/accounts.json --arenas=5 --distribution=13,13,13,13,12 --route=named --duration=1800 --workload=idle > client-idle.jsonl
```

Заполнение идёт постепенно с паузой 3.5 секунды между клиентами;
лимит частоты подключения Velocity отключён. время первого холодного запуска включено в `join_ms`.
Имена назначенных серверов и реальное число игроков проверяет VM, а не только
клиент. При маршруте `auto` все клиенты используют `/server pillars`.
Для отдельного измерения пяти конкретных арен используйте `--route=named`.
Отсутствие присоединения или непредвиденный разрыв дают ненулевой код завершения.

### Метрики на VM

Перед запуском генератора откройте два терминала VM. Хостовый сборщик пишет
CPU steal, очередь диска/задержку HDD, cgroup throttling/OOM и свободное место:

```sh
sudo bash .runtime/source/infra/loadtest/host-metrics.sh .runtime/loadtest/run1/host
```

Во втором терминале:

```sh
sudo docker exec mcservers-controller python /app/loadtest/monitor.py --output /state/loadtests/run1/idle --arenas 5 --distribution 13,13,13,13,12 --warmup 300 --seconds 900
sudo docker cp mcservers-controller:/state/loadtests/run1/idle .runtime/loadtest/run1/
```

Пятиминутная разминка начинается после подтверждения всех 64 игроков на пяти
backend-серверах. Сборщик проверяет число игроков на каждой арене, пишет
`samples.jsonl` и итоговый `report.json`. Потеря игроков после заполнения — отказ.
Остановите хостовый сборщик Ctrl+C после измерения.

Для этапа матчей перезапустите генератор с `--workload=active`, а сборщик —
с `--start-matches` и другим output. Он запускает настоящую функцию карты
`spark:start_game with storage barrier:` только если все игроки имеют префикс
MLT. Новые раунды запускаются при возвращении арены в ожидание, не чаще одного
раза в три минуты. Датапак при старте выполняет большие операции fill;
этот этап особенно важен на HDD.

Для профиля одной проблемной арены:

```sh
sudo docker exec mcservers-controller python /app/rcon.py pillars_1 'spark profiler start --timeout 120'
```

### Сброс пяти арен

Пока все клиенты ещё подключены, запустите сборщик сброса, дождитесь
`RESET_READY`, затем завершите генератор Ctrl+C. Проверка сравнит поколения:

```sh
sudo docker exec mcservers-controller python /app/loadtest/monitor.py --phase reset --output /state/loadtests/run1/reset --arenas 5 --reset-timeout 300
sudo docker cp mcservers-controller:/state/loadtests/run1/reset .runtime/loadtest/run1/
```

pillars_1 должен перейти в WAITING с новым поколением и visited=false.
Арены 2–5 должны перейти в STOPPED, их контейнеры и временные тома удалены.
После этого повторно подключите клиентов и проверьте выбор всех арен.
Убедитесь, что пустые новые арены не пересоздаются повторно без посещения.

### Критерии приёмки

Это целевые значения проекта, а не обещание производительности Xeon X5650:

- На пяти аренах 13+13+13+13+12 игроков, всего 64. 65-й вход отклоняется.
  Общий выбор отдельно проверяется на четырёх аренах по 16 игроков;
  арена не принимает более 16, шестая арена не создаётся.
- После разминки не менее 95% выборок каждой арены имеют TPS за минуту ≥19.5
  и p95 последнего окна тиков ≤50 мс. p95 окна не является p95 всех тиков прогона.
- Нет потери участников, OOM, сбоев сервера; родительский лимит 32 GiB/12 CPU действует.
- Первая чистая арена доступна в пределах 300 секунд после выхода всех клиентов;
  остальные четыре удалены и запускаются только по запросу.
- API/RCON/backend порты не доступны с внешней сети; авторизация сохранена.
- Диск не приближается к заполнению; задержки HDD, CPU steal и throttling
  сопоставлены по UTC со всплесками MSPT. Для VM отдельно проверена точность часов.

Проверьте коды завершения генератора и сборщика вместе: один успешный отчёт
не компенсирует ошибку второго. В отчёт приложите client JSONL, samples/report,
iostat/vmstat/cgroup, логи контейнеров и spark-ссылки. Если критерии не выполнены,
снизьте вместимость/число арен, повторите тот же сценарий. На этом HDD особенно
важно измерить одновременные старты раундов и пересоздание карт.

### Завершение теста

После отключения ботов удалите только созданные тестовые аккаунты:

```sh
sudo docker exec mcservers-controller python /app/loadtest/provision.py remove --file /state/loadtests/run1/accounts.json
```

Удалите тестовые credential-файлы с VM и генератора, сохраните обезличенные
отчёты. Верните MAINTENANCE_ENABLED=true, обновите контроллер, проверьте расписание.
Для переноса в production используйте сохранённые исходные игровые данные,
а не изменённую нагрузочными прогонами тестовую копию.

## Источники

- [Docker Engine на Ubuntu](https://docs.docker.com/engine/install/ubuntu/)
- [Compose plugin](https://docs.docker.com/compose/install/linux/)
- [Родительская cgroup в Compose](https://docs.docker.com/reference/compose-file/services/#cgroup_parent)
- [Ресурсные ограничения systemd](https://www.freedesktop.org/software/systemd/man/latest/systemd.resource-control.html)
- [Метрики Paper Server](https://jd.papermc.io/paper/1.21.10/org/bukkit/Server.html)
- [Профилирование Paper через spark](https://docs.papermc.io/paper/profiling/)

## Новые режимы и общий предел

Общий предел сети — 64 игрока. Для пяти арен используйте маршрут named
и распределение 13,13,13,13,12. Для общего выбора auto проведите отдельный
прогон: --arenas=4 --per-arena=16 --route=auto. Полностью заполнить пять
арен одновременно нельзя при общем лимите 64.

Parkour меняет общую трассу каждый час. PvP копирует вещи survival
и возвращает исходный набор после смерти, сдачи, отключения или 10 минут.
Переносите постоянные тома parkour, pvp и duels вместе с остальной сетью.
В lobby выдаётся книга «Команды и режимы», /help работает во всей сети.

Лимиты: survival 8 GiB/3 CPU; пять арен по 2.5 GiB/1 CPU; lobby 1.5 GiB/0.5 CPU;
proxy и controller по 1 GiB/0.5 CPU; parkour 2 GiB/1 CPU; pvp 3 GiB/1.5 CPU.
Итого 29 GiB и 12 CPU.

## Обновление оформления

В сети используется имя KiwyClub. Книга в лобби содержит кликабельное
оглавление; /help имеет разделы и кнопки. TAB показывает режим, общий/местный
онлайн и пинг. Ограничения AuthMe по IP и login-ratelimit Velocity отключены.
