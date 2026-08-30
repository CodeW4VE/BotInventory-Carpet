package froyln.botinventory;

import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
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
 * spawning them. See PLAN.md for the write-back design rationale (merge
 * only the Inventory/EnderItems keys — never a full entity round trip,
 * since PlayerEntity#writeData writes several fields, such as Dimension,
 * from the entity's live state rather than from what was read).
 */
public final class OfflineInventoryAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger("botinventory-carpet");

    private OfflineInventoryAccess() {
    }

    public record Target(PlayerConfigEntry entry, NbtCompound data, boolean isBot) {
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
            // Resolves to a real Mojang account, whether or not it has ever joined.
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

        PlayerConfigEntry entry = new PlayerConfigEntry(uuid, name);
        return server.getPlayerManager().loadPlayerData(entry).map(nbt -> new Target(entry, nbt, isBot));
    }

    /**
     * Builds a detached ServerPlayerEntity that is never added to the world
     * or the player list - a deserialization target only - with the given
     * saved data applied.
     */
    public static ServerPlayerEntity createGhost(MinecraftServer server, PlayerConfigEntry entry, NbtCompound data) {
        GameProfile profile = new GameProfile(entry.id(), entry.name());
        ServerPlayerEntity ghost = new ServerPlayerEntity(server, server.getOverworld(), profile, SyncedClientOptions.createDefault());
        try (ErrorReporter.Logging reporter = new ErrorReporter.Logging(LOGGER)) {
            ReadView view = NbtReadView.create(reporter, server.getRegistryManager(), data);
            ghost.readData(view);
        }
        return ghost;
    }

    /**
     * Writes the ghost's current inventory and ender chest back to disk,
     * merged onto a freshly re-read copy of the target's data so nothing
     * else the player saved since opening is touched or lost.
     *
     * Returns false without writing if the on-disk data changed since it was
     * opened (the target logged in, or anything else saved to it) - see
     * PLAN.md's race-safety notes for why this check exists and why it is
     * "did the file change", not "is the target online now".
     */
    public static boolean writeBack(MinecraftServer server, ViewSessions.OfflineSession session, ServerPlayerEntity ghost) {
        if (session.stale) return false;

        Optional<NbtCompound> fresh = server.getPlayerManager().loadPlayerData(session.entry);
        if (fresh.isEmpty() || !fresh.get().equals(session.openedSnapshot)) {
            session.stale = true;
            return false;
        }

        NbtCompound merged = fresh.get().copy();
        try (ErrorReporter.Logging reporter = new ErrorReporter.Logging(LOGGER)) {
            NbtWriteView editedView = NbtWriteView.create(reporter, server.getRegistryManager());
            ghost.writeData(editedView);
            NbtCompound edited = editedView.getNbt();
            if (edited.get("Inventory") != null) merged.put("Inventory", edited.get("Inventory"));
            if (edited.get("EnderItems") != null) merged.put("EnderItems", edited.get("EnderItems"));
        }

        return writeToDisk(server, session.entry.id(), merged);
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
