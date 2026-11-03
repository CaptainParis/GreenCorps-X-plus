<p align="center">
  <img src="src/main/resources/assets/greencorps/icon.png" width="96" alt="GreenCorps X+ icon">
</p>

<h1 align="center">GreenCorps X+</h1>

<p align="center">
  <b>A Meteor Client addon for anarchy servers: find structures, guard your stasis pearls, back up your base and handle maps faster.</b><br>
  Built by <b>QOLARR</b> for the 2b2t community.
</p>

<p align="center">
  <a href="https://github.com/qolarr-dev/GreenCorps-X-plus/releases/latest"><img src="https://img.shields.io/github/v/release/qolarr-dev/GreenCorps-X-plus?label=release&color=3ba55c" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Minecraft-1.21.4%20%7C%201.21.11-3ba55c" alt="Minecraft 1.21.4 | 1.21.11">
  <img src="https://img.shields.io/badge/Meteor%20Client-addon-7d3cff" alt="Meteor Client addon">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/qolarr-dev/GreenCorps-X-plus" alt="MIT license"></a>
</p>

---

## Install

1. Install [Fabric](https://fabricmc.net/use/) and [Meteor Client](https://meteorclient.com/) for your Minecraft version.
2. Download the jar for your version from **[Releases](https://github.com/qolarr-dev/GreenCorps-X-plus/releases/latest)**:
   - `greencorps-<version>.jar` for **1.21.4** (all modules)
   - `greencorps-<version>+1.21.11.jar` for **1.21.11** (all modules)
3. Put it in your `mods` folder next to Meteor, delete any older `greencorps-*.jar` and start the game.
4. Open the Meteor GUI (**Right Shift**). Everything is in the **GreenCorps** category.

Optional: [Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) and [World Map](https://modrinth.com/mod/xaeros-world-map) turn finder results, stasis chambers and gateways into waypoints. [Baritone](https://github.com/cabaletta/baritone) lets Timed Commands run `#` commands.

## What you get

### 🔎 Finders
| Module | What it does for you |
|---|---|
| **Breeze Finder** | Spots breeze trial spawners and ominous vaults in loaded chunks, so you find trial chambers while you fly. One Xaero waypoint per chamber. |
| **Outpost Finder** | Finds pillager outposts and scores how flat the land around them is, which is handy for picking farm or base sites. |
| **Solid Pocket** | Finds dense underground pockets for spawn-proof farm sites, with history, ESP, chat and sound alerts. |
| **Sign Search (WDL)** | Searches sign text and coordinates from the 2b2t.place world download (April–June 2026). Needs the separate **sign pack** (~40 MB), which members can get in our Discord. |

### 🛡️ Stasis & safety
| Module | What it does for you |
|---|---|
| **Stasis Monitor** | Tracks settled stasis pearls per home, with quotas, missing-pearl alerts, ESP (optionally seat-only) and a chamber overview. `.stasis` command. |
| **Discord Pings** | Private pings to **your own** Discord webhook: low rockets, low elytra, heavy damage, totem pops and deaths. It never sends coordinates. |
| **Safe Shulker** | Middle-click a shulker in your inventory (or while holding one) to use it overhead; it's broken right back into your inventory. *(by fuxkingjosh)* |
| **Anti-Client** | Early preview: reads incoming mace dives and crystal bursts and shows a survive-or-leave verdict on its HUD element. It only watches and logs; it never acts on its own. |

### 🏠 Base
| Module | What it does for you |
|---|---|
| **Time Machine** | Geofenced home backups on a timer, with a timeline, export to a **playable singleplayer world** and a forcefield render. Item frames, maps, paintings and armour stands are saved too. `/home`-style command. |
| **End Floor** | A void floor and helper plane in the End, with chunk and block grids. |
| **Timed Commands** | Runs saved commands at a day time, a world tick, or sunrise/sunset with an offset. |

### 🗺️ Maps & items
| Module | What it does for you |
|---|---|
| **Map Duper+** | Copies maps and map bundles from a side panel in the inventory, crafting table and cartography table. Sorts mapart into reading order and stays under 2b2t's click limit. |
| **Map Slot Preview** | Shows mapart right in inventory and container slots, and loads the maps inside bundles on its own. |
| **Item Frame Control** | Mapart walls without frames: filled frames lose their border, empty ones get a small ESP box, and clicks can pass through. |
| **Tooltip+** | Better tooltips: shulker and ender chest previews (the ender chest is remembered between sessions), full bundle contents with a map preview beside them, books, banners, maps, item locations and item file size. |
| **Book Filler** | Fills a book and quill from a template, with its own title and signer. |
| **Auto Loom** | Paste a banner `/give` command (Tooltip+ copies one from any banner) and a panel beside the loom walks you through every layer. |
| **Gateways Esp** | End gateway ESP with direction vectors, tracers and optional Xaero pins. |

HUD elements for Breezes, Outposts, Solid Pockets and Time Machine are under **GreenCorps** in the Meteor HUD editor.

## Your first five minutes
1. Turn on **Breeze Finder** and **Outpost Finder**, then fly around. Hits show in the HUD and, with Xaero, as waypoints.
2. Stand in your base, turn on **Time Machine** and add a home. It now backs up on its own.
3. Turn on **Stasis Monitor** near your chambers to see pearl counts, and get warned when one goes missing.
4. Hover a shulker or ender chest with **Tooltip+** on to see its contents without opening it.

## Feedback & updates
Bug reports and ideas are welcome in [Issues](https://github.com/qolarr-dev/GreenCorps-X-plus/issues). Please include your X+ version, Minecraft version and `latest.log`. Release notes are in [CHANGELOG.md](CHANGELOG.md).

## Build from source
Requirements: Java 21. The main target is Minecraft 1.21.4.

```bash
./gradlew build
```
The jar is `build/libs/greencorps-<version>.jar`; ignore `*-sources.jar` and `*-dev.jar`. Xaero is pulled from Modrinth at compile time only and never bundled. For `scripts/deploy-instance.sh`, copy `local.properties.example` to `local.properties` and set `deploy.mods` to your instance's `mods` folder.

## Contributing
Pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Related
- **[MODUS](https://github.com/qolarr-dev)**: elytra autopilot and travel HUD by QOLARR.

## License
[MIT](LICENSE). Not affiliated with Mojang, Microsoft, MeteorDevelopment or 2b2t.
