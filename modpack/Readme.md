# KiwyClub Survival 26.3

Minecraft 26.3 / Fabric Loader 0.19.5 / Java 25

| Mod | Version | Installation | Release type |
|---|---|---|---|
| [Alternate Current](https://modrinth.com/mod/alternate-current) | mc26.3-1.9.0 | server | release |
| [AppleSkin](https://modrinth.com/mod/appleskin) | 3.0.10+mc26.3 | client, server | release |
| [Concurrent Chunk Management Engine (Fabric)](https://modrinth.com/mod/c2me-fabric) | 0.4.2-alpha.0.106+26.3 | server | alpha |
| [Chunky](https://modrinth.com/mod/chunky) | 1.5.3 | server | release |
| [Collective](https://modrinth.com/mod/collective) | 26.3.0-8.60-fabric+forge+neo | server | release |
| [Controlify (Controller support)](https://modrinth.com/mod/controlify) | 3.5.3+mc26.3 | client | release |
| [Double Doors](https://modrinth.com/mod/double-doors) | 26.3.0-7.5-fabric+forge+neo | server | release |
| [Enchantments Unbound](https://modrinth.com/mod/enchantments-unbound) | 4.3.3+mc26.3 | server | release |
| [Fabric API](https://modrinth.com/mod/fabric-api) | 0.164.0+26.3 | client, server | release |
| [FabricProxy-Lite](https://modrinth.com/mod/fabricproxy-lite) | v2.12.0 | server | release |
| [FerriteCore](https://modrinth.com/mod/ferrite-core) | 9.0.0-fabric | server | release |
| [Krypton](https://modrinth.com/mod/krypton) | 0.3.2 | server | release |
| [Lithium](https://modrinth.com/mod/lithium) | mc26.3-0.26.2-fabric | server | release |
| [Lithostitched](https://modrinth.com/mod/lithostitched) | 2.0.6-fabric-26.3 | server | release |
| [LuckPerms](https://modrinth.com/mod/luckperms) | v5.5.85-fabric | server | release |
| [Mouse Tweaks](https://modrinth.com/mod/mouse-tweaks) | 26.3-2.31-fabric | client | release |
| [Nullscape](https://modrinth.com/mod/nullscape) | 2.0.1+26.3 | server | release |
| [ServerCore](https://modrinth.com/mod/servercore) | 1.5.20+26.3 | server | release |
| [Shulker Box Tooltip](https://modrinth.com/mod/shulkerboxtooltip) | 5.4.2+26.3-fabric | client | release |
| [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat) | fabric-2.6.24+26.3 | client, server | beta |
| [Sound Physics Remastered](https://modrinth.com/mod/sound-physics-remastered) | fabric-1.5.1+26.3 | client | beta |
| [spark](https://modrinth.com/mod/spark) | 1.10.187-fabric | server | release |
| [Tectonic](https://modrinth.com/mod/tectonic) | 3.0.31-fabric-26.3 | server | release |
| [Terralith](https://modrinth.com/mod/terralith) | 2.6.5+26.3 | server | release |
| [Very Many Players (Fabric)](https://modrinth.com/mod/vmp-fabric) | 0.2.0+beta.7.238+26.3 | server | alpha |
| [YetAnotherConfigLib (YACL)](https://modrinth.com/mod/yacl) | 3.9.7+26.3-fabric | client | release |

Server optimizers: Lithium, FerriteCore, Krypton, Alternate Current, ServerCore, C2ME, VMP. Chunky: pre-generation. spark: diagnostics.
ModernFix and Noisium have no Fabric 26.3 versions.
World generation: Terralith + Tectonic (Overworld), Nullscape (End), vanilla Nether.
Client: Fabric API, Simple Voice Chat, Sound Physics Remastered, Controlify, YACL, AppleSkin, Mouse Tweaks, Shulker Box Tooltip, KiwyClub checker.
Enchantments: level cap 255 (storage limit), all conflicts removed, anvil XP remains required, vanilla villager trades.
Player commands: /home /tpa /tpaccept /tpdeny /pvp /pvpaccept /pvpdeny /server /help /voicechat. PvP: /pvpleave.
LuckPerms: default inherits kiwy-player. Only kiwy.command.<name> and voicechat.listen/speak/groups are granted.
Survival never pauses when empty. Daily restart 05:00 Europe/Moscow and quarterly wipe with a new seed remain.
C2ME global executor: 2. Other C2ME options use the current mod defaults. Chunky enabled by default: 640-block square radius, 60s idle grace, 30s batches / 60s rest, TPS >=19.5, MSPT <35, free disk >20 GiB. Joins pause generation.

## Fresh world

First launch generates a new world with the current terrain mods. No world migration is required. Old 1.20.1 data is not used or imported. Repeated launches preserve the current 26.3 world. A fresh seed is assigned on initial generation and on scheduled wipes.

Data: survival-fabric-26.3, pvp-fabric-26.3, duels-fabric-26.3. Release ZIPs contain no saved worlds, player data or secrets.

## References

- https://www.fabricmc.net/2026/09/15/263.html
- https://modrinth.com/mod/enchantments-unbound

Double Doors: only ordinary paired doors; recursive opening, trapdoors and gates disabled. Collective is the required server library.
