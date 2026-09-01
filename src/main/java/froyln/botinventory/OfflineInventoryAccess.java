package froyln.botinventory;

import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerConfigHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.storage.ReadView;
import net.minecraft.util.ErrorReporter;
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
 * spawning them. Never a full PlayerEntity#writeData round trip — that
 * writes live-state fields (e.g. Dimension) the ghost never meaningfully held.
 *
 * 1.21.6 has no PlayerConfigEntry / PlayerManager#loadPlayerData(entry) yet
 * (needs an already-constructed ServerPlayerEntity), so this reads/writes
 * the `<uuid>.dat` file directly instead of going through PlayerManager.
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
        try (ErrorReporter.Logging reporter = new ErrorReporter.Logging(LOGGER)) {
            ReadView view = NbtReadView.create(reporter, server.getRegistryManager(), data);
            ghost.readData(view);
        }
        return ghost;
    }

    /**
     * NBT keys write-back may touch. Armor/offhand live in LivingEntity's
     * own "equipment" key (EQUIPMENT_KEY), not "Inventory" — missing that
     * key here doesn't fail loudly, edits just silently never reach disk.
     */
    private static final String[] EDITABLE_KEYS = {"Inventory", "EnderItems", "equipment"};

    /**
     * Disk-free snapshot of the ghost's editable NBT, for change detection.
     * Polled every tick rather than hooked to Slot#markDirty() — not every
     * mutation calls it (e.g. a partial-stack drop bypasses it entirely),
     * which used to under-report changes and duplicate items on login.
     */
    public static NbtCompound currentEditedSnapshot(MinecraftServer server, ServerPlayerEntity ghost) {
        try (ErrorReporter.Logging reporter = new ErrorReporter.Logging(LOGGER)) {
            return extractEditableKeys(server, ghost, reporter);
        }
    }

    private static NbtCompound extractEditableKeys(MinecraftServer server, ServerPlayerEntity ghost, ErrorReporter.Logging reporter) {
        NbtWriteView editedView = NbtWriteView.create(reporter, server.getRegistryManager());
        ghost.writeData(editedView);
        NbtCompound edited = editedView.getNbt();
        NbtCompound snapshot = new NbtCompound();
        for (String key : EDITABLE_KEYS) {
            if (edited.get(key) != null) snapshot.put(key, edited.get(key));
        }
        return snapshot;
    }

    /**
     * Merges the ghost's edits onto a freshly re-read copy of the target's
     * `.dat`, so nothing saved since opening is touched or lost. Refuses
     * (returns false) if disk has changed since `session.lastKnownDiskState`
     * — a rolling baseline advanced on every successful write, not a fixed
     * open-time snapshot, so a later edit in the same session isn't wrongly
     * seen as a conflict against the disk state the first edit already wrote.
     */
    public static boolean writeBack(MinecraftServer server, ViewSessions.OfflineSession session, ServerPlayerEntity ghost) {
        if (session.stale) return false;

        Optional<NbtCompound> fresh = readPlayerDat(server, session.entry.getId());
        if (fresh.isEmpty() || !fresh.get().equals(session.lastKnownDiskState)) {
            session.stale = true;
            return false;
        }

        NbtCompound merged = fresh.get().copy();
        try (ErrorReporter.Logging reporter = new ErrorReporter.Logging(LOGGER)) {
            NbtCompound edited = extractEditableKeys(server, ghost, reporter);
            for (String key : EDITABLE_KEYS) {
                // "equipment" is omitted (not present) when empty, unlike Inventory/EnderItems.
                var value = edited.get(key);
                if (value != null) {
                    merged.put(key, value);
                } else {
                    merged.remove(key);
                }
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
