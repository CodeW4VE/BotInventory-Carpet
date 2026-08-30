# BotInventory Carpet

A Minecraft Fabric mod — Carpet addon that lets server players view fake player (bot) and real player inventories and ender chests via `/player <name> view inventory|enderchest`, gated by Carpet rules. Targets can be online or offline; offline viewing reads and writes the target's saved `.dat` file directly.

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

- Java 21, Fabric Loom 1.10, Yarn mappings
- fabric-carpet (rules + command integration)
- sgui (SimpleGui for inventory display)

## Structure

```
src/main/java/froyln/botinventory/
├── BotInventory.java             — Mod entrypoint, CarpetExtension hooks
├── BotInventoryRules.java        — Carpet rule definitions (5 rules)
├── ViewCommand.java              — /player <name> view inventory|enderchest
├── OfflineInventoryAccess.java   — Offline .dat read/merge-write, bot heuristic, ghost entity
├── ViewSessions.java             — Open-GUI bookkeeping; login/logout race guards
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

Five rules, all accepting `true`, `false`, `ops`, or numeric permission levels (0-4):

| Rule | Default | Used by |
|---|---|---|
| `viewFakePlayerInventoryRightClick` | `false` | Right-click fake player → open inventory (`PlayerEntityInteractMixin`) |
| `viewPlayerInventoryCommand` | `false` | `/player <name> view inventory` |
| `viewPlayerEnderchestCommand` | `false` | `/player <name> view enderchest` |
| `viewOfflinePlayerInventory` | `false` | Allows the command to target an offline player (reads/writes their `.dat`) |
| `viewRealPlayerInventory` | `true` | Allows the command to target a non-bot player, online or offline. Defaults `true` because the command never distinguished bots from real players before this rule existed — `false` would silently change behavior on upgrade |

Resulting matrix (`ViewCommand.resolveTarget`):

| Target | Rules required |
|---|---|
| Online bot | the relevant `viewPlayer*Command` rule |
| Online real player | + `viewRealPlayerInventory` |
| Offline bot | + `viewOfflinePlayerInventory` |
| Offline real player | + `viewOfflinePlayerInventory` + `viewRealPlayerInventory` |

The right-click mixin is unaffected: it only ever sees an `EntityPlayerMPFake`, so it is bot-only by construction.

### Command (`ViewCommand.java`)

Attaches `view inventory` and `view enderchest` as child nodes of the existing `/player <name>` command node from carpet. If the `/player` command isn't present the registration silently does nothing.

`requires()` on each subcommand gates the base `viewPlayer*Command` rule via `isViewAllowed()` (boolean / `ops` / numeric threshold). The target-dependent rules (`viewRealPlayerInventory`, `viewOfflinePlayerInventory`) can't live in `requires()` — the target isn't known until execution — so `resolveTarget()` checks them at command-execution time instead, before anything is opened.

`resolveTarget()` is the single place both subcommands go through:
- **Online**: `server.getPlayerManager().getPlayer(name)`. Bot check is `instanceof EntityPlayerMPFake`.
- **Offline**: only if `viewOfflinePlayerInventory` allows it. Delegates to `OfflineInventoryAccess.resolve()` for name→UUID→saved-data lookup and the bot heuristic, then registers a `ViewSessions.OfflineSession` (refuses a second viewer on the same target).
- Either way, a non-bot target needs `viewRealPlayerInventory`.
- No match (online or offline) → the original "Player not found or not online" message, unchanged.

Both `viewInventory` and `viewEnderchest` then open an sgui `SimpleGui` — the target's inventory (fixed 9x5) or ender chest (adaptive 9x1–9x6) — via one of four small paths depending on online/offline: `openInventory`/`openEnderchestOnline` (live redirect onto the real player) or `openOfflineInventory`/`openEnderchestOffline` (redirect onto a detached ghost entity's inventory, with write-back wired in). `openInventory`'s signature is unchanged from before this feature, so the mixin didn't need to change.

### Offline inventory access (`OfflineInventoryAccess.java`)

Lets the command target a player who isn't online, without spawning them.

**Resolving a name**: `ServerConfigHandler.getPlayerUuidByName` (Mojang/UserCache lookup — can block on network I/O the same way Carpet's own fake-player spawning already does) with `Uuids.getOfflinePlayerUuid` as the fallback for names with no Mojang account. Bot detection is a heuristic, not a fact: no Mojang account + online-mode server ⇒ bot; anything else (including every player on an offline-mode server, where real accounts and bots are indistinguishable) fails safe to "real", requiring `viewRealPlayerInventory`. No `.dat` on disk (`PlayerManager.loadPlayerData` empty) ⇒ resolution fails, surfaced as "Player not found or not online" — never an empty inventory the viewer could populate into a fresh file.

**Ghost entity**: `createGhost()` builds a `ServerPlayerEntity` via its public constructor, passed `server.getOverworld()` purely as constructor plumbing — never added to the world or the player list, never ticked. `ghost.readData(...)` on the loaded NBT populates it, and from there `ghost.getInventory()` / `ghost.getEnderChestInventory()` work exactly like a real online player's for GUI purposes.

**Write-back is a merge, not a full save** — deliberately, not for simplicity. A full `ghost.writeData(...)` round-trip was considered and rejected: `ServerPlayerEntity.writeCustomData` writes fields like `"Dimension"` from the entity's *live* world reference, not from what was read, so a full save would silently corrupt fields the ghost was never meaningfully holding. `writeBack()` instead re-reads the target's current `.dat`, and copies over only the `Inventory` and `EnderItems` keys pulled from the ghost's `writeData()` output — nothing else in the file can ever be touched. Written atomically (temp file + `Files.move` with `ATOMIC_MOVE`).

**Race guard**: `writeBack()` refuses (returns `false`, no write) if the on-disk data has changed since it was opened, compared against the snapshot cached in `ViewSessions.OfflineSession`. This is a "did the file change", not "is the target online now" — a target who logs in *and back out* while the GUI is open still trips it, which an online-check would miss. See `PLAN.md` for the full walkthrough of why.

### View sessions (`ViewSessions.java`)

Tracks every open GUI so a target logging in or out can react:

- **Online session** (`registerOnline`/`unregisterOnline`): a set of GUIs per target UUID. On `onPlayerLoggedOut`, every GUI still redirecting into that now-departed player's (now-orphaned) `PlayerInventory` is force-closed. Fixes a pre-existing item duplication bug: without this, taking an item from an online GUI after the target disconnects duplicates it when they reconnect, because vanilla saves their data on disconnect *before* the GUI's edit is ever persisted anywhere.
- **Offline session** (`tryRegisterOffline`/`unregisterOffline`): one slot per target UUID — a second viewer is refused outright rather than silently losing whichever edit closes last. On `onPlayerLoggedIn`, any open offline session for that UUID is marked `stale` and force-closed (no final write), since the target's live inventory already reflects every edit written before the login — see `OfflineInventoryAccess`'s race guard above.

Both hooks are wired from `BotInventory.onPlayerLoggedIn`/`onPlayerLoggedOut` (`CarpetExtension` defaults, already available — no new dependency).

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

## Maintenance rules for AI agents

- Important change (new rule, new command, new mixin, behavior change, new file, dependency change) → update this doc same turn, section that describe it.
- Each important change → propose local commit after edit. **Ask user first**, never commit without confirm.
- Trivial change (typo, formatting, comment) → no doc update, no commit needed unless user ask.
