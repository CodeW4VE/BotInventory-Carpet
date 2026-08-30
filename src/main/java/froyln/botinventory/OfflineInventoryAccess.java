package froyln.botinventory;

import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerConfigHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Uuids;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes a player's saved data on disk directly, so their
 * inventory can be viewed and edited while they are offline, without
 * spawning them. See PLAN.md for the write-back design rationale (merge
 * only the Inventory/EnderItems keys — never a full entity round trip,
 * since PlayerEntity#writeCustomDataToNbt writes several fields, such as
 * Dimension, from the entity's live state rather than from what was read).
 *
 * 1.21.4 predates PlayerConfigEntry / the storage-view system (ReadView,
 * WriteView, ErrorReporter.Logging) entirely, so this reads and writes the
 * `<uuid>.dat` file directly instead of going through PlayerManager, and
 * uses Entity#readNbt(NbtCompound) / #writeNbt(NbtCompound) instead of
 * #readData(ReadView) / #writeData(WriteView). Armor/offhand also predate
 * the separate "equipment" NBT key (added ~1.21.5) - here they round-trip
 * as part of "Inventory" itself, so EDITABLE_KEYS is just the two list keys.
 */
public final class OfflineInventoryAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger("botinventory-carpet");

    private OfflineInventoryAccess() {
    }

    public record Target(GameProfile entry, NbtCompound data, boolean isBot) {
    }

    /**
     * Resolves an offline name to its saved data. Empty if the name never
     * saved any data (no .dat file) — the caller treats that as an error,
     * not an empty inventory.
     */
    public static Optional<Target> resolve(MinecraftServer server, String name) {
        UUID mojangUuid = ServerConfigHandler.getPlayerUuidByName(server, name);
        UUID uuid;
        boolean isBot;
        if (mojangUuid != null) {
            uuid = mojangUuid;
            isBot = false;
        } else if (server.isOnlineMode()) {
            // Online-mode server verifies real accounts; no such account means this is a fake player.
            uuid = Uuids.getOfflinePlayerUuid(name);
            isBot = true;
        } else {
            // Offline-mode server: every player has an offline UUID, so a bot and
            // a real account are indistinguishable. Fail safe to "real".
            uuid = Uuids.getOfflinePlayerUuid(name);
            isBot = false;
        }

        GameProfile entry = new GameProfile(uuid, name);
        return readPlayerDat(server, uuid).map(nbt -> new Target(entry, nbt, isBot));
    }

    private static Optional<NbtCompound> readPlayerDat(MinecraftServer server, UUID uuid) {
        Path path = server.getSavePath(WorldSavePath.PLAYERDATA).resolve(uuid + ".dat");
        if (!Files.exists(path)) return Optional.empty();
        try {
            return Optional.of(NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes()));
        } catch (IOException e) {
            LOGGER.warn("Failed to read offline player data for {}", uuid, e);
            return Optional.empty();
        }
    }

    /**
     * Builds a detached ServerPlayerEntity that is never added to the world
     * or the player list - a deserialization target only - with the given
     * saved data applied.
     */
    public static ServerPlayerEntity createGhost(MinecraftServer server, GameProfile entry, NbtCompound data) {
        ServerPlayerEntity ghost = new ServerPlayerEntity(server, server.getOverworld(), entry, SyncedClientOptions.createDefault());
        ghost.readNbt(data);
        return ghost;
    }

    /**
     * The NBT keys write-back is allowed to touch. "Inventory" is the 36
     * main+hotbar slots plus armor and offhand (pre-1.21.5, they are encoded
     * as extra slot indices inside the same list, not a separate key).
     * "EnderItems" is the ender chest.
     */
    private static final String[] EDITABLE_KEYS = {"Inventory", "EnderItems"};

    /**
     * A cheap, disk-free snapshot of just the ghost's editable NBT (see
     * EDITABLE_KEYS), for detecting whether anything has changed since the
     * last write.
     *
     * Needed because vanilla's Slot#markDirty() is not a reliable change
     * signal: Slot#tryTakeStackRange only calls it when a take empties the
     * slot - a single-item drop (Q) from a stack of more than one bypasses
     * Inventory#removeStack directly and never marks anything dirty. A
     * per-slot dirty hook built on markDirty() would miss that edit's
     * write-back entirely while the *drop itself* (a plain
     * PlayerEntity#dropItem call, unrelated to markDirty) still happens -
     * duplicating the item once the target logs back in and loads the
     * still-unedited save. So this is compared every tick instead of relying
     * on any mutation hook; see PLAN.md's follow-up race note.
     */
    public static NbtCompound currentEditedSnapshot(MinecraftServer server, ServerPlayerEntity ghost) {
        return extractEditableKeys(ghost);
    }

    private static NbtCompound extractEditableKeys(ServerPlayerEntity ghost) {
        NbtCompound edited = ghost.writeNbt(new NbtCompound());
        NbtCompound snapshot = new NbtCompound();
        for (String key : EDITABLE_KEYS) {
            if (edited.get(key) != null) snapshot.put(key, edited.get(key));
        }
        return snapshot;
    }

    /**
     * Writes the ghost's current inventory and ender chest back to disk,
     * merged onto a freshly re-read copy of the target's data so nothing
     * else the player saved since opening is touched or lost.
     *
     * Returns false without writing if the on-disk data has changed since we
     * last knew about it (the target logged in, or anything else saved to
     * it) - see PLAN.md's race-safety notes for why this check exists and
     * why it is "did the file change", not "is the target online now".
     *
     * The comparison baseline is `session.lastKnownDiskState`, which this
     * method advances to what it just wrote on every success - NOT a fixed
     * snapshot from when the GUI was opened. A session is a sequence of
     * writes, not one transaction: comparing every write against the
     * open-time snapshot meant the *second* distinct edit of any session
     * always saw the disk (correctly changed by the *first* edit) as
     * "different from expected" and permanently marked the session stale,
     * silently dropping every edit after the first while the corresponding
     * drop/etc. side effects (which don't go through this method) still
     * happened - see PLAN.md's dedicated note on this bug for the full
     * walkthrough.
     */
    public static boolean writeBack(MinecraftServer server, ViewSessions.OfflineSession session, ServerPlayerEntity ghost) {
        if (session.stale) return false;

        Optional<NbtCompound> fresh = readPlayerDat(server, session.entry.getId());
        if (fresh.isEmpty() || !fresh.get().equals(session.lastKnownDiskState)) {
            session.stale = true;
            return false;
        }

        NbtCompound merged = fresh.get().copy();
        NbtCompound edited = extractEditableKeys(ghost);
        for (String key : EDITABLE_KEYS) {
            var value = edited.get(key);
            if (value != null) {
                merged.put(key, value);
            } else {
                merged.remove(key);
            }
        }

        if (!writeToDisk(server, session.entry.getId(), merged)) return false;
        session.lastKnownDiskState = merged;
        return true;
    }

    private static boolean writeToDisk(MinecraftServer server, UUID uuid, NbtCompound compound) {
        Path dir = server.getSavePath(WorldSavePath.PLAYERDATA);
        Path finalPath = dir.resolve(uuid + ".dat");
        try {
            Path tempPath = Files.createTempFile(dir, uuid + "-", ".dat");
            NbtIo.writeCompressed(compound, tempPath);
            Files.move(tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Failed to save offline player data for {}", uuid, e);
            return false;
        }
    }
}
