# Changelog

Player-facing notes for each GreenCorps X+ release. Downloads: [Releases](https://github.com/qolarr-dev/GreenCorps-X-plus/releases).

## 0.6.0: first public release

Where X+ stands today: 15 modules in the **GreenCorps** category of the Meteor GUI, for Minecraft **1.21.4** (all modules) and **1.21.11** (finders, Stasis Monitor and Discord Pings so far).

**Finders**
- **Breeze Finder**: trial spawners and ominous vaults in loaded chunks, with one Xaero waypoint per chamber.
- **Outpost Finder**: pillager outposts, scored by how flat the land around them is.
- **Solid Pocket**: dense underground pockets for spawn-proof farms, with history, ESP and alerts.
- **Sign Search (WDL)**: searches sign text and coordinates from the 2b2t.place world download. It needs the separate sign pack, which members can get in our Discord.

**Stasis & safety**
- **Stasis Monitor**: settled pearls per home, quotas, missing-pearl alerts, ESP and a chamber overview (`.stasis`).
- **Discord Pings**: private pings to your own webhook for low rockets or elytra, heavy damage, totem pops and deaths. It never sends coordinates.
- **Safe Shulker** (by fuxkingjosh): use a shulker from your inventory overhead, and it drops right back.

**Base**
- **Time Machine**: geofenced home backups, a timeline, and export to a playable singleplayer world. Item frames, maps, paintings and armour stands are saved too.
- **End Floor**: a void floor and helper grids in the End.
- **Timed Commands**: runs saved commands at a day time, world tick, or sunrise/sunset.

**Maps & items**
- **Map Duper+**: copies maps and bundles, and sorts mapart into reading order. It works in the inventory, crafting table and cartography table, and stays within 2b2t's click limit.
- **Map Slot Preview**: mapart right in your slots.
- **Tooltip+**: shulker and ender chest previews (the ender chest is remembered between sessions), full bundle contents, books, banners, item locations and file size.
- **Book Filler**: book-and-quill templates with title and signer.
- **Gateway ESP**: End gateway ESP, direction vectors, tracers and Xaero pins.

**Works with**: Xaero's Minimap / World Map (waypoints, optional), Baritone (`#` commands in Timed Commands, optional), and God's Eye Companion (a separate jar for the God's Eye map).
