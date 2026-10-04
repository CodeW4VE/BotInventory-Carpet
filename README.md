# BotInventory Carpet

View and edit Carpet bot inventories and ender chests by command or right click, with optional access to real and offline players.

## Installation

Download the jar matching your Minecraft version from [GitHub Releases](https://github.com/CodeW4VE/BotInventory-Carpet/releases) or [Modrinth](https://modrinth.com/mod/botinventory-carpet-w4ve).

| Minecraft | Java | Fabric Loader | Carpet |
| --- | --- | --- | --- |
| 26.2 | 25 | 0.19.5 or newer | 26.2 |
| 26.3 | 25 | 0.19.5 or newer | 26.3 |

Install the jar and [Fabric Carpet](https://modrinth.com/mod/carpet) on the server. Clients need no extra mod. [sgui](https://github.com/Patbox/sgui) is bundled.

## Features

- Right click a fake player to manage its inventory.
- `/player <name> view inventory` and `/player <name> view enderchest`.
- Inventory access includes the 36 main slots, four armor slots and offhand.
- Optionally edit real players and saved inventories while players are offline.
- Offline edits save as they change and preserve position, dimension, health and other player data.
- A second viewer is refused while an offline inventory is open. Login invalidates an offline session; logout closes views of the online inventory.
- Changed data on disk invalidates a stale session instead of overwriting it.

Version 1.0.1 fixes offline serialization: player data is saved with Minecraft's player-specific serialization method, preserving edited items across repeated saves.

## Configuration

Use `/carpet <rule> <value>` for the current session or `/carpet setDefault <rule> <value>` to retain the rule across restarts.

| Rule | Default | Access controlled |
| --- | --- | --- |
| `viewFakePlayerInventoryRightClick` | `false` | Right click on fake players |
| `viewPlayerInventoryCommand` | `false` | Inventory command |
| `viewPlayerEnderchestCommand` | `false` | Ender chest command |
| `viewOfflinePlayerInventory` | `false` | Saved inventories of offline players |
| `viewRealPlayerInventory` | `true` | Real players instead of only Carpet bots |

Values: `true` for everyone, `false` to disable, `ops` for operator level 2 or higher, or a level from `0` to `4`. Offline and real player access also require the relevant inventory or ender chest command rule.

```
/carpet setDefault viewFakePlayerInventoryRightClick ops
/carpet setDefault viewPlayerInventoryCommand ops
/carpet setDefault viewPlayerEnderchestCommand ops
/carpet setDefault viewOfflinePlayerInventory ops
```

When updating, stop the server, replace the matching jar and keep the same world, player data and Carpet configuration.

## Building

Requires JDK 25. The wrapper downloads Gradle automatically.

```
python3 tools/multiversion.py
```

Both supported jars are written to `build/multiversion/`. Tagged releases publish the jars to GitHub and Modrinth.

## License

[MIT](LICENSE) © froyln.
