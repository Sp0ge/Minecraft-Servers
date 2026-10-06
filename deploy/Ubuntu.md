# KiwyClub: запуск на Ubuntu

Скачайте ZIP ветки main, распакуйте в удобную папку, затем:

```bash
bash start.sh
```

Python 3 и Docker при отсутствии устанавливаются скриптом через sudo.
Скрипт запускает сеть с профилем вашей VM: до 29 ГБ / 12 CPU квоты,
включая максимум пять арен вместе с готовым резервом. Порт TCP 25565
слушает все интерфейсы. Откройте его в сетевых настройках VM для игроков.

Данные по умолчанию: `./ServerData`. Настройки: `ServerData/settings.env`.
Не удаляйте и не публикуйте `ServerData/secrets`; аккаунты находятся в lobby.
Можно задать другую папку: `DATA_ROOT=/opt/kiwy-data bash start.sh`.

На локальном Mac с Docker Desktop: `bash start.sh --local`.
Minecraft: `localhost:25565`. Локальное обслуживание Survival отключено.
Повторный запуск сохраняет данные; источники обновляются из проверенного архива.

Для переноса готовых сохранений сначала полностью остановите сеть:

```bash
DATA_ROOT="$PWD/ServerData" docker compose --env-file ServerData/settings.env stop
```

Затем перенесите папку ServerData на VM и запустите `bash start.sh` в новом main.
В ServerData/settings.env замените локальные лимиты на значения профиля Ubuntu из
`ServerData/source/infra/deploy/ubuntu.env`, не добавляя PROJECT_ROOT.
MC_CGROUP_PARENT=minecraft.slice добавляйте только после установки соответствующего
systemd slice через bootstrap.sh; обычный start.sh использует лимиты контейнеров.
Установите BIND_ADDRESS=0.0.0.0 и MAINTENANCE_ENABLED=true.
Не переносите старый абсолютный путь DATA_ROOT в окружении новой машины.

Вместо копирования можно пользоваться offline export/import:

```bash
python3 ServerData/source/infra/deploy/volume_transfer.py export --directory ./private-backup
python3 ServerData/source/infra/deploy/volume_transfer.py import --directory ./private-backup
```

Импорт требует пустых папок назначения, проверяет SHA-256 и пути в архивах.
Экспорт содержит приватные данные. Старые Docker volumes поддерживаются отдельным
флагом --legacy-volumes. Экспорт включает также Fabric-миры и журнал дуэлей.
Нагрузочные проверки по текущему запросу не проводятся.

Полная справка по режимам, структуре данных и источникам находится в Readme.md main.
[Docker bind mounts](https://docs.docker.com/engine/storage/bind-mounts/).
