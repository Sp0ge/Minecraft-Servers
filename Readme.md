# KiwyClub pvp — Fabric 26.3

Запускается из `main` через `bash start.sh` (`--local` на Mac).

- Данные: `ServerData/pvp-fabric-26.3`. Старые Paper-миры сохраняются отдельно.
- Полный набор и зависимости: `infra-controller/modpack/mods.lock.json`.
- Клиент: `ServerData/KiwyClub-Survival-26.3.mrpack`.
- Серверный мод KiwyClub собирается из `infra/fabric`; сетевой доступ только через Velocity с общим forwarding secret.
- Voice Chat: UDP 24455.

PvP: копия полного player NBT/аксессуаров, поле 48×48, возврат исходного состояния в Survival. Оба backend используют одну сборку модов.
