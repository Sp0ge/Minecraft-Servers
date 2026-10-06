# KiwyClub pvp — Fabric 1.20.1

Запускается из `main` через `bash start.sh` (`--local` на Mac).

- Данные: `ServerData/pvp-fabric-1.20.1`. Старые Paper-миры сохраняются отдельно.
- Полный набор и зависимости: `infra-controller/modpack/mods.lock.json`.
- Клиент: `ServerData/KiwyClub-Survival-1.20.1.mrpack`.
- Серверный мод KiwyClub собирается из `infra/fabric`; сетевой доступ только через Velocity с общим forwarding secret.
- Voice Chat: UDP 24455.

PvP: копия полного player NBT/аксессуаров, поле 48×48, возврат исходного состояния в Survival. Оба backend используют одну сборку модов.
