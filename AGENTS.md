# BotInventory Carpet

A Minecraft Fabric mod — Carpet addon that lets server players view fake player (bot) inventories and ender chests via `/player <name> view inventory|enderchest`, gated by Carpet rules.

## Build

```bash
./gradlew build
```

## Lint / typecheck

```bash
./gradlew build
```

No separate lint or checkstyle config. Compilation is the only check.

## Test

```bash
./gradlew test
```

Currently no tests exist.

## Stack

- Java 21, Fabric Loom 1.17, Yarn mappings
- fabric-carpet (rules + command integration)
- sgui (SimpleGui for inventory display)

## Structure

```
src/main/java/froyln/botinventory/
├── BotInventory.java          — Mod entrypoint, CarpetExtension hooks
├── BotInventoryRules.java     — Carpet rule definitions (3 rules)
├── ViewCommand.java           — /player <name> view inventory|enderchest
└── mixin/
    └── PlayerEntityInteractMixin.java  — Right-click fake player → open inventory

src/main/resources/
├── botinventory-carpet.mixins.json  — Mixin config
└── fabric.mod.json                  — Fabric mod metadata
```

## Architecture

### Entrypoint (`BotInventory.java`)

Implements both `CarpetExtension` and `ModInitializer`. Registers itself as a Carpet extension in a static initializer. Lifecycle:

| Hook | What happens |
|---|---|
| `static {}` | Registers extension into `CarpetServer` |
| `onInitialize()` | No-op (Fabric init) |
| `onGameStarted()` | Parses rules from `BotInventoryRules.class` |
| `registerCommands()` | Attaches `ViewCommand` under `/player` |
| `canHasTranslations()` | Provides English descriptions for each rule |
| `onTick()` / `onServerClosed()` | No-op stubs |

### Carpet rules (`BotInventoryRules.java`)

Three rules, all defaulting to `"false"`, all accepting `true`, `false`, `ops`, or numeric permission levels (0-4):

| Rule | Used by |
|---|---|
| `viewFakePlayerInventoryRightClick` | Right-click fake player → open inventory (`PlayerEntityInteractMixin`) |
| `viewPlayerInventoryCommand` | `/player <name> view inventory` |
| `viewPlayerEnderchestCommand` | `/player <name> view enderchest` |

### Command (`ViewCommand.java`)

Attaches `view inventory` and `view enderchest` as child nodes of the existing `/player <name>` command node from carpet. If the `/player` command isn't present the registration silently does nothing.

Permission check via `isViewAllowed()`: interprets the rule value as a boolean (`true`/`false`), `ops` (permission level 2), or numeric threshold, and gates the `requires()` on each subcommand.

Both `viewInventory` and `viewEnderchest` resolve the target player, throw if offline, then open an sgui `SimpleGui` with the target's inventory (fixed 9x5) or ender chest (adaptive 9x1–9x6).

### Mixin (`PlayerEntityInteractMixin.java`)

Intercepts `PlayerEntity.interact(Entity target, Hand)` — called on the **clicker** when any player right-clicks an entity. Only triggers for `INTERACT`/`INTERACT_AT` packet types, never for `ATTACK` (left-click), so attacking fake players works normally.

Flow:
1. Player right-clicks a fake player → `PlayerEntity.interact(target, hand)` fires
2. Mixin checks `target instanceof EntityPlayerMPFake`
3. Checks `viewFakePlayerInventoryRightClick` rule via `ViewCommand.isPlayerAllowed()`
4. Opens inventory via `ViewCommand.openInventory()` (same GUI as `/player view inventory`)
5. Returns `ActionResult.SUCCESS` to cancel normal right-click behavior (armor swap, etc.)

### Slots are not copy-protected

Inventory and ender chest slots use vanilla `Slot` with redirect, meaning the viewer **can** take and modify items in the viewed inventory. This is the current behavior — not a bug in scope.
