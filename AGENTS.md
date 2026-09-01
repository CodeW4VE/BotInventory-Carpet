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

- Java 25, Fabric Loom 1.17 (`net.fabricmc.fabric-loom` plugin id), Mojang official mappings — Minecraft 26.1+ (this branch: 26.2) ships unobfuscated, so there is no mapping file at all; class/method names in this codebase are Mojang's own ("Mojmap": `ServerPlayer`, `CompoundTag`, `Component`, etc.), not Yarn's
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

On 26.1+, vanilla replaced the old numeric op-level check (`CommandSourceStack#hasPermissionLevel(int)`, gone entirely) with a capability-based model: a `CommandSourceStack` exposes `permissions()` → `PermissionSet`, and named `PermissionCheck` constants (`Commands.LEVEL_ALL/MODERATORS/GAMEMASTERS/ADMINS/OWNERS`, one per legacy op level 0–4) answer `.check(permissionSet)`. `checkPermissionLevel()` maps our `0`–`4` rule values onto those five constants directly — same mapping Carpet's own (internal, not `carpet.api`) `CommandHelper.canUseCommand` uses, verified by reading Carpet's 26.1 source rather than assumed, but not called into directly since it isn't part of Carpet's stable public API.

`resolveTarget()` is the single place both subcommands go through:
- **Online**: `server.getPlayerList().getPlayerByName(name)`. Bot check is `instanceof EntityPlayerMPFake`.
- **Offline**: only if `viewOfflinePlayerInventory` allows it. Delegates to `OfflineInventoryAccess.resolve()` for name→UUID→saved-data lookup and the bot heuristic, then registers a `ViewSessions.OfflineSession` (refuses a second viewer on the same target).
- Either way, a non-bot target needs `viewRealPlayerInventory`.
- No match (online or offline) → the original "Player not found or not online" message, unchanged.

Both `viewInventory` and `viewEnderchest` then open an sgui `SimpleGui` — the target's inventory (fixed 9x5) or ender chest (adaptive 9x1–9x6) — via one of four small paths depending on online/offline: `openInventory`/`openEnderchestOnline` (live redirect onto the real player) or `openOfflineInventory`/`openEnderchestOffline` (redirect onto a detached ghost entity's inventory, with write-back wired in). `openInventory`'s signature is unchanged from before this feature, so the mixin didn't need to change.

Inventory redirects only cover indices `0..DISPLAYED_INVENTORY_SIZE-1` (41: 36 main+hotbar, 4 armor, 1 offhand), never `Inventory#getContainerSize()` (43 on 1.21.11) directly — `getContainerSize()` also counts the `BODY`/`SADDLE` equipment slots added for other entity types, which exist in every player's slot map but aren't meaningful for a player and shouldn't be shown as editable. The `41` value carried over unchanged from 1.21.11 on the assumption the layout is structurally identical on 26.2 (same `EQUIPMENT_SLOT_MAPPING` shape found via `javap`) — not independently re-confirmed by a live `getContainerSize()` call; worth checking in-game if slot 41/42 behave oddly.

### Offline inventory access (`OfflineInventoryAccess.java`)

Lets the command target a player who isn't online, without spawning them.

**Resolving a name**: `server.services().nameToIdCache().get(name)` (Mojang lookup — can block on network I/O the same way Carpet's own fake-player spawning already does; this is 26.1+'s replacement for the older `ServerConfigHandler.getPlayerUuidByName`, discovered via `javap` since 26.1+'s `MinecraftServer` has no username-cache accessor at all under the old name) with `UUIDUtil.createOfflinePlayerUUID` as the fallback for names with no Mojang account. Bot detection is a heuristic, not a fact: no Mojang account + online-mode server ⇒ bot; anything else (including every player on an offline-mode server, where real accounts and bots are indistinguishable) fails safe to "real", requiring `viewRealPlayerInventory`. No `.dat` on disk (`PlayerList.loadPlayerData` empty) ⇒ resolution fails — surfaced as a distinct "has no saved data" error, separate from "offline viewing is disabled" (the `viewOfflinePlayerInventory` gate) and from the online "not found" case, so the three aren't indistinguishable from the command's output.

**Ghost entity**: `createGhost()` builds a `ServerPlayer` via its public constructor, passed `server.overworld()` purely as constructor plumbing — never added to the world or the player list, never ticked. `ghost.load(...)` on the loaded NBT populates it, and from there `ghost.getInventory()` / `ghost.getEnderChestInventory()` work exactly like a real online player's for GUI purposes.

**Write-back is a merge, not a full save** — deliberately, not for simplicity. A full `ghost.save(...)` round-trip was considered and rejected: `Entity#addAdditionalSaveData` writes fields like `"Dimension"` from the entity's *live* world reference, not from what was read, so a full save would silently corrupt fields the ghost was never meaningfully holding. `writeBack()` instead re-reads the target's current `.dat`, and copies over only the keys in `EDITABLE_KEYS` — `"Inventory"`, `"EnderItems"`, `"equipment"` — pulled from the ghost's `save()` output; nothing else in the file can ever be touched. Written atomically (temp file + `Files.move` with `ATOMIC_MOVE`).

Armor and offhand are **not** part of `"Inventory"` — found the hard way, after the fix below still duped items dropped from the offhand slot specifically. `Inventory#load` only accepts slots below the main list's size (36); armor/offhand/body/saddle round-trip through `LivingEntity`'s own `"equipment"` key instead (`LivingEntity.TAG_EQUIPMENT`), backed by a separate field. Missing a key here doesn't fail loudly — the edit applies fine to the in-memory ghost, GUI-side, and just silently never reaches disk. `"equipment"` is also only written when non-empty (unlike Inventory/EnderItems, always present), so the merge loop removes the key from the merged compound when the edited version lacks it, not just skips it — otherwise unequipping the last piece of gear would leave the old equipment stuck on disk.

**Change detection is polled, not hooked** — also deliberately, after a real bug. The first version triggered `writeBack()` from a `Slot` subclass overriding `markDirty()` (now `setChanged()`). That's unreliable: a take only calls it when it empties the slot, so a single-item drop (Q) from a stack of more than one bypasses `Container#removeItem` directly and never marks anything dirty — while the drop itself (a plain, unconditional item-drop call) still happens, duplicating the item once the target's stale save reloaded on login. `OfflineViewGui#onTick()` now compares `OfflineInventoryAccess.currentEditedSnapshot()` (a cheap, disk-free NBT snapshot of the ghost's `EDITABLE_KEYS`) against what was last written, every tick and once more on close, and only calls `writeBack()` when they actually differ. Catches every mutation path, not just the ones that happen to call `setChanged()`.

**Race guard**: `writeBack()` refuses (returns `false`, no write) if the on-disk data has changed since we last knew about it, compared against `ViewSessions.OfflineSession.lastKnownDiskState`. This is a "did the file change", not "is the target online now" — a target who logs in *and back out* while the GUI is open still trips it, which an online-check would miss. See `PLAN.md` for the full walkthrough of why.

`lastKnownDiskState` is **our own rolling baseline, not a fixed open-time snapshot** — `writeBack()` advances it to the exact compound just written after every success. It used to be `final`, fixed at GUI-open, and it was a real bug: comparing every write against the state from when the GUI opened means the *second* distinct edit of any session sees the disk — correctly changed by the first edit — as unexpectedly different, permanently marks the session stale, and silently drops every edit after the first (while the drop/etc. side effects that aren't gated by write success still happen — a duplication bug, reproducing on any session with 2+ edits, unrelated to any login timing). Found because isolated single-edit manual tests of the earlier write-back fixes couldn't have caught it.

### View sessions (`ViewSessions.java`)

Tracks every open GUI so a target logging in or out can react:

- **Online session** (`registerOnline`/`unregisterOnline`): a set of GUIs (typed `GuiLike` — sgui 2.x's rename of `GuiInterface`) per target UUID. On `onPlayerLoggedOut`, every GUI still redirecting into that now-departed player's (now-orphaned) inventory is force-closed. Fixes a pre-existing item duplication bug: without this, taking an item from an online GUI after the target disconnects duplicates it when they reconnect, because vanilla saves their data on disconnect *before* the GUI's edit is ever persisted anywhere.
- **Offline session** (`tryRegisterOffline`/`unregisterOffline`): one slot per target UUID — a second viewer is refused outright rather than silently losing whichever edit closes last. On `onPlayerLoggedIn`, any open offline session for that UUID is marked `stale` and force-closed (no final write), since the target's live inventory already reflects every edit written before the login — see `OfflineInventoryAccess`'s race guard above.

Both hooks are wired from `BotInventory.onPlayerLoggedIn`/`onPlayerLoggedOut` (`CarpetExtension` defaults, already available — no new dependency).

### Mixin (`PlayerEntityInteractMixin.java`)

Intercepts `Player.interactOn(Entity target, InteractionHand, Vec3 hitPos)` — called on the **clicker** when any player right-clicks an entity. On 26.1 this method gained a third `Vec3` parameter (the exact hit position) versus the older two-arg `interact(Entity, Hand)`; the mixin's injected method signature had to grow the extra parameter to match, even though it's unused. Only triggers for `INTERACT`/`INTERACT_AT` packet types, never for `ATTACK` (left-click), so attacking fake players works normally.

Flow:
1. Player right-clicks a fake player → `Player.interactOn(target, hand, hitPos)` fires
2. Mixin checks `target instanceof EntityPlayerMPFake`
3. Checks `viewFakePlayerInventoryRightClick` rule via `ViewCommand.isViewAllowed(viewer.createCommandSourceStack(), rule)`
4. Opens inventory via `ViewCommand.openInventory()` (same GUI as `/player view inventory`)
5. Returns `InteractionResult.SUCCESS` to cancel normal right-click behavior (armor swap, etc.)

### Slots are not copy-protected

Inventory and ender chest slots use vanilla `Slot` with redirect, meaning the viewer **can** take and modify items in the viewed inventory. This is the current behavior — not a bug in scope.

## Maintenance rules for AI agents

- Important change (new rule, new command, new mixin, behavior change, new file, dependency change) → update this doc same turn, section that describe it.
- Each important change → propose local commit after edit. **Ask user first**, never commit without confirm.
- Trivial change (typo, formatting, comment) → no doc update, no commit needed unless user ask.
- Do not add unnecessary comments to the code. Each comment costs tokens with no return; only write a comment when it explains something non-obvious that the code itself can't convey.
