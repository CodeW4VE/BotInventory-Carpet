<div align="center">

# BotInventory Carpet

**Fabric Carpet addon that lets players view and manage fake player inventories and ender chests via right‑click or commands.**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.6-62B47D?logo=minecraft&logoColor=white)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric%20Loader-0.16.14%2B-87CEEB?logo=fabric&logoColor=white)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?logo=java&logoColor=white)](https://www.java.com/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

</div>

---

## What is it?

A Carpet extension that allows server operators and players to view and interact with fake player (bot) inventories. Right‑click a fake player to open their inventory, or use `/player <name> view inventory|enderchest`. Everything is gated by three Carpet rules.

## Features

- **Right‑click to open**: Right‑click any fake player (spawned with carpet's `/player`) to instantly open their inventory — works only on fake players, never on real players.
- **Command access**: `/player <name> view inventory` and `/player <name> view enderchest` for on‑demand access.

## Requirements

- [Java](https://www.java.com/) 21 or higher
- [Minecraft](https://www.minecraft.net/) 1.21.6 server with Fabric loader
- [Fabric Loader](https://fabricmc.net/) 0.16.14 or higher
- [Fabric Carpet](https://github.com/gnembon/fabric-carpet) 1.21.6-1.4.176 or compatible

## Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/froyln/BotInventory-carpet.git
   cd BotInventory-carpet
   ```

2. Build the mod:
   ```bash
   ./gradlew build
   ```

3. Copy the generated `.jar` file from `build/libs/` to your server's `mods` folder:
   ```bash
   cp build/libs/botinventory-carpet-*.jar /path/to/server/mods/
   ```

## Configuration

All configuration is done via Carpet rules. Run `/carpet setDefault <rule> <value>` or per‑world with `/carpet <rule> <value>`.

| Rule | Default | Description |
|---|---|---|
| `viewFakePlayerInventoryRightClick` | `false` | Allow right‑click on fake players to open inventory |
| `viewPlayerInventoryCommand` | `false` | Allow `/player <name> view inventory` |
| `viewPlayerEnderchestCommand` | `false` | Allow `/player <name> view enderchest` |

**Permission values**: `true` (everyone), `false` (nobody), `ops` (permission level 2+), or `0`–`4` (numeric threshold).

Example:
```
/carpet viewFakePlayerInventoryRightClick ops
/carpet viewPlayerInventoryCommand true
```

## Dependencies

- [Fabric Loader](https://fabricmc.net/)
- [Fabric Carpet](https://github.com/gnembon/fabric-carpet)
- [sgui](https://github.com/Patbox/sgui) (bundled — no separate download needed)

## Building from Source

Requires:
- [Gradle](https://gradle.org/) 8.x or higher (automatically downloaded via gradlew)
- Java 21 or higher

Build command:
```bash
./gradlew clean build
```

Generated artifact: `build/libs/botinventory-carpet-*.jar`

## License

[MIT](LICENSE) © froyln
